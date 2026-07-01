package dev.xyenon.mxgram

import android.app.Activity
import android.view.View
import android.widget.FrameLayout
import java.util.concurrent.atomic.AtomicBoolean

internal const val OPTION_SAVE_STICKER = 0x4D584702 // "MXG\u0002"
private const val CONTENT_TYPE_STICKER = 0

internal class StickerDownloadMenu(
    private val stickerSaver: StickerSaver,
    private val logError: (String, Throwable) -> Unit,
) {
    private val previewRunnableWrapped = AtomicBoolean(false)

    @Suppress("UNCHECKED_CAST")
    fun addToMessageMenu(
        chatActivity: Any,
        args: Array<Any?>?,
    ) {
        if (args == null || args.size < 3) {
            return
        }
        val (iconsRaw, itemsRaw, optionsRaw) = resolveFillMessageMenuLists(args) ?: return
        if (iconsRaw !is ArrayList<*> || itemsRaw !is ArrayList<*> || optionsRaw !is ArrayList<*>) {
            return
        }
        val icons = iconsRaw as ArrayList<Int>
        val items = itemsRaw as ArrayList<CharSequence>
        val options = optionsRaw as ArrayList<Int>
        if (options.contains(OPTION_SAVE_STICKER)) {
            return
        }

        val selectedObject =
            try {
                findField(chatActivity.javaClass, "selectedObject").get(chatActivity)
            } catch (_: Throwable) {
                null
            } ?: return
        if (!isSaveableStickerMessage(selectedObject)) {
            return
        }

        val insertIndex = stickerMenuInsertIndex(options, chatActivity.javaClass)
        val galleryIcon =
            resolveTelegramDrawable(
                chatActivity.javaClass.classLoader,
                "msg_gallery",
                0,
            )
        val label = resolveSaveToGalleryLabel(chatActivity.javaClass.classLoader)

        options.add(insertIndex, OPTION_SAVE_STICKER)
        items.add(insertIndex, label)
        icons.add(minOf(insertIndex, icons.size), galleryIcon)
    }

    fun handleSelectedOption(
        chatActivity: Any,
        option: Int,
    ): Boolean {
        if (option != OPTION_SAVE_STICKER) {
            return false
        }
        try {
            val activity =
                findMethod(chatActivity.javaClass, "getParentActivity").invoke(chatActivity) as? Activity
                    ?: return true
            if (!hasGalleryWritePermission(activity)) {
                requestGalleryWritePermission(activity)
                finishSelectedOption(chatActivity)
                return true
            }
            val selectedObject = findField(chatActivity.javaClass, "selectedObject").get(chatActivity) ?: return true
            stickerSaver.saveMessageSticker(activity, selectedObject) {
                showDownloadBulletin(chatActivity)
            }
            finishSelectedOption(chatActivity)
        } catch (t: Throwable) {
            logError("Failed to handle save sticker menu option", t)
        }
        return true
    }

    fun wrapContentPreviewViewer(viewer: Any) {
        if (!previewRunnableWrapped.compareAndSet(false, true)) {
            return
        }
        try {
            val field = findField(viewer.javaClass, "showSheetRunnable")
            val original = field.get(viewer) as? Runnable ?: return
            field.set(
                viewer,
                Runnable {
                    original.run()
                    patchStickerPreviewMenu(viewer)
                },
            )
        } catch (t: Throwable) {
            previewRunnableWrapped.set(false)
            logError("Failed to wrap ContentPreviewViewer sticker menu", t)
        }
    }

    private fun patchStickerPreviewMenu(viewer: Any) {
        try {
            val contentType = findField(viewer.javaClass, "currentContentType").getInt(viewer)
            if (contentType != CONTENT_TYPE_STICKER) {
                return
            }
            val menuVisible = findField(viewer.javaClass, "menuVisible").getBoolean(viewer)
            if (!menuVisible) {
                return
            }
            val currentDocument = findField(viewer.javaClass, "currentDocument").get(viewer) ?: return
            val classLoader = viewer.javaClass.classLoader ?: return
            val messageObjectClass = classLoader.loadClass("org.telegram.messenger.MessageObject")
            if (invokeStaticBoolean(messageObjectClass, "isMaskDocument", arrayOf(currentDocument))) {
                return
            }
            val popupWindow = findField(viewer.javaClass, "popupWindow").get(viewer) ?: return
            val previewMenu =
                findMethod(popupWindow.javaClass, "getContentView").invoke(popupWindow) as? View
                    ?: findField(viewer.javaClass, "popupLayout").get(viewer) as? View
                    ?: return
            val activity = findField(viewer.javaClass, "parentActivity").get(viewer) as? Activity ?: return
            val account = findField(viewer.javaClass, "currentAccount").getInt(viewer)
            val containerView = findField(viewer.javaClass, "containerView").get(viewer) as? FrameLayout ?: return
            val label = resolveSaveToGalleryLabel(classLoader)
            val galleryIcon = resolveTelegramDrawable(classLoader, "msg_gallery", 0)
            val actionBarMenuItemClass = Class.forName("org.telegram.ui.ActionBar.ActionBarMenuItem", false, classLoader)
            val resourcesProvider = findField(viewer.javaClass, "resourcesProvider").get(viewer)
            val addItem =
                actionBarMenuItemClass.declaredMethods.firstOrNull { method ->
                    method.name == "addItem" &&
                        method.parameterCount == 5 &&
                        View::class.java.isAssignableFrom(method.parameterTypes[0])
                } ?: return
            addItem.isAccessible = true
            val item =
                addItem.invoke(
                    null,
                    previewMenu,
                    galleryIcon,
                    label,
                    false,
                    resourcesProvider,
                ) as? View ?: return
            item.setOnClickListener {
                if (!hasGalleryWritePermission(activity)) {
                    requestGalleryWritePermission(activity)
                    return@setOnClickListener
                }
                stickerSaver.saveDocumentSticker(activity, currentDocument, classLoader, account) {
                    showDownloadBulletin(containerView, resourcesProvider)
                }
                dismissPreviewPopup(viewer)
            }
        } catch (t: Throwable) {
            logError("Failed to patch sticker preview menu", t)
        }
    }

    private fun dismissPreviewPopup(viewer: Any) {
        try {
            findMethod(viewer.javaClass, "dismissPopupWindow").invoke(viewer)
        } catch (_: Throwable) {
        }
    }

    private fun showDownloadBulletin(host: Any) {
        try {
            val classLoader = host.javaClass.classLoader
            val bulletinFactoryClass = Class.forName("org.telegram.ui.Components.BulletinFactory", false, classLoader)
            val ofMethod =
                bulletinFactoryClass.declaredMethods.firstOrNull { method ->
                    method.name == "of" && method.parameterCount == 1 && method.parameterTypes[0].isAssignableFrom(host.javaClass)
                } ?: return
            ofMethod.isAccessible = true
            val factory = ofMethod.invoke(null, host) ?: return
            val fileTypeClass = Class.forName("org.telegram.ui.Components.BulletinFactory\$FileType", false, classLoader)
            val fileType =
                runCatching { java.lang.Enum.valueOf(fileTypeClass as Class<out Enum<*>>, "MEDIA") }.getOrNull()
                    ?: java.lang.Enum.valueOf(fileTypeClass as Class<out Enum<*>>, "PHOTO")
            val themeDelegate = runCatching { findField(host.javaClass, "themeDelegate").get(host) }.getOrNull()
            val createDownloadBulletin =
                factory.javaClass.declaredMethods.firstOrNull { method ->
                    method.name == "createDownloadBulletin" &&
                        method.parameterCount == 2 &&
                        method.parameterTypes[0] == fileTypeClass
                } ?: return
            createDownloadBulletin.isAccessible = true
            val bulletin = createDownloadBulletin.invoke(factory, fileType, themeDelegate) ?: return
            findMethod(bulletin.javaClass, "show").invoke(bulletin)
        } catch (t: Throwable) {
            logError("Failed to show sticker saved bulletin", t)
        }
    }

    private fun showDownloadBulletin(
        containerLayout: FrameLayout,
        resourcesProvider: Any?,
    ) {
        try {
            val classLoader = containerLayout.javaClass.classLoader ?: resourcesProvider?.javaClass?.classLoader ?: return
            val bulletinFactoryClass = Class.forName("org.telegram.ui.Components.BulletinFactory", false, classLoader)
            val ofMethod =
                bulletinFactoryClass.declaredMethods.firstOrNull { method ->
                    method.name == "of" &&
                        method.parameterCount == 2 &&
                        method.parameterTypes[0].isAssignableFrom(containerLayout.javaClass)
                } ?: return
            ofMethod.isAccessible = true
            val factory = ofMethod.invoke(null, containerLayout, resourcesProvider) ?: return
            showDownloadBulletin(factory, classLoader, resourcesProvider)
        } catch (t: Throwable) {
            logError("Failed to show sticker saved bulletin", t)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun showDownloadBulletin(
        factory: Any,
        classLoader: ClassLoader,
        resourcesProvider: Any?,
    ) {
        val fileTypeClass = Class.forName("org.telegram.ui.Components.BulletinFactory\$FileType", false, classLoader)
        val fileType =
            runCatching { java.lang.Enum.valueOf(fileTypeClass as Class<out Enum<*>>, "MEDIA") }.getOrNull()
                ?: java.lang.Enum.valueOf(fileTypeClass as Class<out Enum<*>>, "PHOTO")
        val createDownloadBulletin =
            factory.javaClass.declaredMethods.firstOrNull { method ->
                method.name == "createDownloadBulletin" &&
                    method.parameterCount == 2 &&
                    method.parameterTypes[0] == fileTypeClass
            } ?: return
        createDownloadBulletin.isAccessible = true
        val bulletin = createDownloadBulletin.invoke(factory, fileType, resourcesProvider) ?: return
        findMethod(bulletin.javaClass, "show").invoke(bulletin)
    }

    private fun clearSelection(chatActivity: Any) {
        try {
            findField(chatActivity.javaClass, "selectedObject").set(chatActivity, null)
            findField(chatActivity.javaClass, "selectedObjectGroup").set(chatActivity, null)
            findField(chatActivity.javaClass, "selectedObjectToEditCaption").set(chatActivity, null)
        } catch (_: Throwable) {
        }
    }

    private fun finishSelectedOption(chatActivity: Any) {
        clearSelection(chatActivity)
        try {
            findMethod(chatActivity.javaClass, "closeMenu").invoke(chatActivity)
        } catch (t: Throwable) {
            logError("Failed to close sticker save menu", t)
        }
    }

    private fun isSaveableStickerMessage(messageObject: Any): Boolean {
        if (invokeInstanceBoolean(messageObject, "isAnimatedEmoji")) {
            return false
        }
        if (invokeInstanceBoolean(messageObject, "isMask")) {
            return false
        }
        return invokeInstanceBoolean(messageObject, "isSticker") ||
            invokeInstanceBoolean(messageObject, "isAnimatedSticker")
    }

    private fun stickerMenuInsertIndex(
        options: ArrayList<Int>,
        chatActivityClass: Class<*>,
    ): Int {
        val addToStickers =
            getStaticIntFieldValue(
                chatActivityClass,
                "OPTION_ADD_TO_STICKERS_OR_MASKS",
                9,
            )
        val addToStickersIndex = options.indexOf(addToStickers)
        if (addToStickersIndex >= 0) {
            return addToStickersIndex
        }
        val addToFavorites =
            getStaticIntFieldValue(
                chatActivityClass,
                "OPTION_ADD_STICKER_TO_FAVORITES",
                20,
            )
        val addToFavoritesIndex = options.indexOf(addToFavorites)
        if (addToFavoritesIndex >= 0) {
            return addToFavoritesIndex
        }
        return options.size
    }

    private fun resolveSaveToGalleryLabel(classLoader: ClassLoader?): CharSequence {
        if (classLoader == null) {
            return "Save to gallery"
        }
        return try {
            val localeControllerClass = Class.forName("org.telegram.messenger.LocaleController", false, classLoader)
            val stringClass = Class.forName("org.telegram.messenger.R\$string", false, classLoader)
            val resId = stringClass.getDeclaredField("SaveToGallery").getInt(null)
            val getString = localeControllerClass.getMethod("getString", Int::class.javaPrimitiveType)
            getString.invoke(null, resId) as? CharSequence ?: "Save to gallery"
        } catch (_: Throwable) {
            "Save to gallery"
        }
    }

    private fun invokeInstanceBoolean(
        instance: Any,
        name: String,
    ): Boolean =
        try {
            val method = instance.javaClass.getMethod(name)
            method.invoke(instance) == true
        } catch (_: Throwable) {
            false
        }

    private fun invokeStaticBoolean(
        type: Class<*>,
        name: String,
        args: Array<Any?>,
    ): Boolean {
        return try {
            for (method in type.declaredMethods) {
                if (method.name != name || method.parameterCount != args.size) {
                    continue
                }
                method.isAccessible = true
                if (method.invoke(null, *args) == true) {
                    return true
                }
            }
            false
        } catch (_: Throwable) {
            false
        }
    }

    /** arity 4 before 12.8.1 (6916); arity 5 on 12.8.1 (6916) with leading primaryMessage. */
    private fun resolveFillMessageMenuLists(args: Array<Any?>): Triple<Any?, Any?, Any?>? {
        val lastThreeAreLists =
            args.size >= 3 &&
                args[args.size - 3] is ArrayList<*> &&
                args[args.size - 2] is ArrayList<*> &&
                args[args.size - 1] is ArrayList<*>
        if (!lastThreeAreLists) {
            return null
        }
        return Triple(args[args.size - 3], args[args.size - 2], args[args.size - 1])
    }
}
