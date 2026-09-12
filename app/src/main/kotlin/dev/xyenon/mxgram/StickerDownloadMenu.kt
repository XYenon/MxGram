package dev.xyenon.mxgram

import android.app.Activity
import android.content.Context
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.PopupWindow
import java.lang.ref.WeakReference
import java.util.WeakHashMap
import kotlin.math.max
import kotlin.math.min

internal const val OPTION_SAVE_STICKER = 0x4D584702 // "MXG\u0002"
private const val CONTENT_TYPE_STICKER = 0

internal class StickerDownloadMenu(
    private val stickerSaver: StickerSaver,
    private val logError: (String, Throwable) -> Unit,
) {
    @Volatile
    private var previewTarget: PreviewTarget? = null
    private val previewMenuItems = WeakHashMap<ViewGroup, WeakReference<View>>()
    private val ownedPreviewPopups = WeakHashMap<Any, WeakReference<Any>>()

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

    fun registerContentPreviewViewer(viewer: Any): Runnable {
        val runnable =
            findField(viewer.javaClass, "showSheetRunnable").get(viewer) as? Runnable
                ?: throw IllegalStateException("ContentPreviewViewer.showSheetRunnable is not a Runnable")
        previewTarget = PreviewTarget(runnable, viewer)
        return runnable
    }

    fun patchContentPreviewStickerMenu(runnable: Any) {
        val target = previewTarget ?: return
        if (runnable !== target.runnable) {
            return
        }
        patchStickerPreviewMenu(target.viewer)
    }

    private fun patchStickerPreviewMenu(viewer: Any) {
        try {
            val contentType = findField(viewer.javaClass, "currentContentType").getInt(viewer)
            if (contentType != CONTENT_TYPE_STICKER) {
                return
            }
            if (findField(viewer.javaClass, "isPhotoEditor").getBoolean(viewer)) {
                return
            }
            val currentDocument = findField(viewer.javaClass, "currentDocument").get(viewer) ?: return
            val classLoader = viewer.javaClass.classLoader ?: return
            val messageObjectClass = classLoader.loadClass("org.telegram.messenger.MessageObject")
            if (invokeStaticBoolean(messageObjectClass, "isMaskDocument", arrayOf(currentDocument))) {
                return
            }
            val activity = findField(viewer.javaClass, "parentActivity").get(viewer) as? Activity ?: return
            val account = findField(viewer.javaClass, "currentAccount").getInt(viewer)
            val containerView = findField(viewer.javaClass, "containerView").get(viewer) as? FrameLayout ?: return
            val resourcesProvider = findField(viewer.javaClass, "resourcesProvider").get(viewer)
            val popupWindow = findField(viewer.javaClass, "popupWindow").get(viewer)
            if (popupWindow != null) {
                val previewMenu =
                    findMethod(popupWindow.javaClass, "getContentView").invoke(popupWindow) as? ViewGroup
                        ?: return
                addPreviewSaveItem(
                    previewMenu,
                    activity,
                    currentDocument,
                    classLoader,
                    account,
                    containerView,
                    resourcesProvider,
                ) { dismissPreviewPopup(viewer) }
                return
            }

            val menuVisible = findField(viewer.javaClass, "menuVisible").getBoolean(viewer)
            if (menuVisible &&
                addPremiumPreviewSaveItem(
                    viewer,
                    activity,
                    currentDocument,
                    classLoader,
                    account,
                    containerView,
                    resourcesProvider,
                )
            ) {
                return
            }
            if (!menuVisible) {
                createSaveOnlyPreviewPopup(
                    viewer,
                    activity,
                    currentDocument,
                    classLoader,
                    account,
                    containerView,
                    resourcesProvider,
                )
            }
        } catch (t: Throwable) {
            logError("Failed to patch sticker preview menu", t)
        }
    }

    private fun addPremiumPreviewSaveItem(
        viewer: Any,
        activity: Activity,
        document: Any,
        classLoader: ClassLoader,
        account: Int,
        containerView: FrameLayout,
        resourcesProvider: Any?,
    ): Boolean {
        val unlockView = findField(viewer.javaClass, "unlockPremiumView").get(viewer) ?: return false
        val premiumButton = findField(unlockView.javaClass, "premiumButtonView").get(unlockView) as? View ?: return false
        val host = premiumButton.parent as? ViewGroup ?: return false
        val item =
            addPreviewSaveItem(
                host,
                activity,
                document,
                classLoader,
                account,
                containerView,
                resourcesProvider,
            ) { findMethod(viewer.javaClass, "closeWithMenu").invoke(viewer) } ?: return false
        if (host.indexOfChild(item) != 0) {
            val layoutParams = item.layoutParams
            host.removeView(item)
            host.addView(item, 0, layoutParams)
        }
        return true
    }

    private fun addPreviewSaveItem(
        host: ViewGroup,
        activity: Activity,
        document: Any,
        classLoader: ClassLoader,
        account: Int,
        containerView: FrameLayout,
        resourcesProvider: Any?,
        closeMenu: () -> Unit,
    ): View? {
        val existing = previewMenuItems[host]?.get()?.takeIf { it.parent != null }
        val item =
            existing ?: run {
                val actionBarMenuItemClass =
                    Class.forName("org.telegram.ui.ActionBar.ActionBarMenuItem", false, classLoader)
                val addItem =
                    actionBarMenuItemClass.declaredMethods.firstOrNull { method ->
                        method.name == "addItem" &&
                            method.parameterCount == 5 &&
                            ViewGroup::class.java.isAssignableFrom(method.parameterTypes[0])
                    } ?: return null
                addItem.isAccessible = true
                val created =
                    addItem.invoke(
                        null,
                        host,
                        resolveTelegramDrawable(classLoader, "msg_gallery", 0),
                        resolveSaveToGalleryLabel(classLoader),
                        false,
                        resourcesProvider,
                    ) as? View ?: return null
                previewMenuItems[host] = WeakReference(created)
                created
            }
        item.setOnClickListener {
            if (!hasGalleryWritePermission(activity)) {
                requestGalleryWritePermission(activity)
                return@setOnClickListener
            }
            stickerSaver.saveDocumentSticker(activity, document, classLoader, account) {
                showDownloadBulletin(containerView, resourcesProvider)
            }
            closeMenu()
        }
        return item
    }

    private fun createSaveOnlyPreviewPopup(
        viewer: Any,
        activity: Activity,
        document: Any,
        classLoader: ClassLoader,
        account: Int,
        containerView: FrameLayout,
        resourcesProvider: Any?,
    ) {
        if (findField(viewer.javaClass, "isVisible").getBoolean(viewer).not()) {
            return
        }
        val resourcesProviderClass =
            Class.forName("org.telegram.ui.ActionBar.Theme\$ResourcesProvider", false, classLoader)
        val layoutClass =
            Class.forName(
                "org.telegram.ui.ActionBar.ActionBarPopupWindow\$ActionBarPopupWindowLayout",
                false,
                classLoader,
            )
        val previewMenu =
            layoutClass
                .getConstructor(
                    Context::class.java,
                    java.lang.Integer.TYPE,
                    resourcesProviderClass,
                    java.lang.Integer.TYPE,
                ).newInstance(
                    containerView.context,
                    resolveTelegramDrawable(classLoader, "popup_fixed_alert4", 0),
                    resourcesProvider,
                    0,
                ) as? ViewGroup ?: return
        addPreviewSaveItem(
            previewMenu,
            activity,
            document,
            classLoader,
            account,
            containerView,
            resourcesProvider,
        ) { dismissPreviewPopup(viewer) } ?: return

        val popupClass = Class.forName("org.telegram.ui.ActionBar.ActionBarPopupWindow", false, classLoader)
        val popup =
            popupClass
                .getConstructor(View::class.java, java.lang.Integer.TYPE, java.lang.Integer.TYPE)
                .newInstance(previewMenu, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        findField(viewer.javaClass, "popupWindow").set(viewer, popup)
        findField(viewer.javaClass, "menuVisible").setBoolean(viewer, true)
        ownedPreviewPopups[popup] = WeakReference(viewer)
        try {
            findMethod(popupClass, "setPauseNotifications", java.lang.Boolean.TYPE).invoke(popup, true)
            findMethod(popupClass, "setDismissAnimationDuration", java.lang.Integer.TYPE).invoke(popup, 100)
            findMethod(popupClass, "setScaleOut", java.lang.Boolean.TYPE).invoke(popup, true)
            findMethod(popupClass, "setOutsideTouchable", java.lang.Boolean.TYPE).invoke(popup, true)
            findMethod(popupClass, "setClippingEnabled", java.lang.Boolean.TYPE).invoke(popup, true)
            findMethod(popupClass, "setAnimationStyle", java.lang.Integer.TYPE)
                .invoke(popup, resolveTelegramStyle(classLoader, "PopupContextAnimation"))
            findMethod(popupClass, "setFocusable", java.lang.Boolean.TYPE).invoke(popup, true)
            previewMenu.measure(
                View.MeasureSpec.makeMeasureSpec(dp(classLoader, 1000f), View.MeasureSpec.AT_MOST),
                View.MeasureSpec.makeMeasureSpec(dp(classLoader, 1000f), View.MeasureSpec.AT_MOST),
            )
            findMethod(popupClass, "setInputMethodMode", java.lang.Integer.TYPE)
                .invoke(popup, PopupWindow.INPUT_METHOD_NOT_NEEDED)
            previewMenu.isFocusableInTouchMode = true
            val y = previewPopupY(viewer, containerView, classLoader)
            val x = (containerView.measuredWidth - previewMenu.measuredWidth) / 2
            findMethod(
                popupClass,
                "showAtLocation",
                View::class.java,
                java.lang.Integer.TYPE,
                java.lang.Integer.TYPE,
                java.lang.Integer.TYPE,
            ).invoke(popup, containerView, 0, x, y)
            runCatching { containerView.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS) }
            containerView.invalidate()
        } catch (t: Throwable) {
            ownedPreviewPopups.remove(popup)
            findField(viewer.javaClass, "popupWindow").set(viewer, null)
            findField(viewer.javaClass, "menuVisible").setBoolean(viewer, false)
            runCatching { findMethod(popupClass, "dismiss").invoke(popup) }
            throw t
        }
    }

    private fun previewPopupY(
        viewer: Any,
        containerView: FrameLayout,
        classLoader: ClassLoader,
    ): Int {
        val insets = findField(viewer.javaClass, "lastInsets").get(viewer)
        val insetTop = findField(insets.javaClass, "top").getInt(insets)
        val insetBottom = findField(insets.javaClass, "bottom").getInt(insets)
        val moveY = findField(viewer.javaClass, "moveY").getFloat(viewer)
        val keyboardHeight = findField(viewer.javaClass, "keyboardHeight").getInt(viewer)
        val drawEffect = findField(viewer.javaClass, "drawEffect").getBoolean(viewer)
        val size =
            if (drawEffect) {
                min(containerView.width, containerView.height - insetTop - insetBottom) - dp(classLoader, 40f)
            } else {
                (min(containerView.width, containerView.height - insetTop - insetBottom) / 1.8f).toInt()
            }
        val emojiOffset =
            if (findField(viewer.javaClass, "stickerEmojiLayout").get(viewer) != null) dp(classLoader, 40f) else 0
        var y =
            (
                moveY +
                    max(
                        size / 2 + insetTop + emojiOffset,
                        (containerView.height - insetTop - insetBottom - keyboardHeight) / 2,
                    ) + size / 2
            ).toInt()
        y += dp(classLoader, 24f)
        if (drawEffect) {
            y += dp(classLoader, 24f)
        }
        return y
    }

    fun handlePreviewPopupDismissed(popupWindow: Any) {
        val viewer = ownedPreviewPopups.remove(popupWindow)?.get() ?: return
        try {
            val popupField = findField(viewer.javaClass, "popupWindow")
            if (popupField.get(viewer) !== popupWindow) {
                return
            }
            popupField.set(viewer, null)
            findField(viewer.javaClass, "menuVisible").setBoolean(viewer, false)
            if (!findField(viewer.javaClass, "closeOnDismiss").getBoolean(viewer)) {
                return
            }
            val currentPreviewCellField = findField(viewer.javaClass, "currentPreviewCell")
            val currentPreviewCell = currentPreviewCellField.get(viewer)
            if (currentPreviewCell != null) {
                runCatching {
                    findMethod(currentPreviewCell.javaClass, "setScaled", java.lang.Boolean.TYPE)
                        .invoke(currentPreviewCell, false)
                }
                currentPreviewCellField.set(viewer, null)
            }
            findMethod(viewer.javaClass, "close").invoke(viewer)
        } catch (t: Throwable) {
            logError("Failed to clean up sticker preview popup", t)
        }
    }

    private fun dp(
        classLoader: ClassLoader,
        value: Float,
    ): Int {
        val androidUtilities = Class.forName("org.telegram.messenger.AndroidUtilities", false, classLoader)
        return androidUtilities.getMethod("dp", java.lang.Float.TYPE).invoke(null, value) as Int
    }

    private fun resolveTelegramStyle(
        classLoader: ClassLoader,
        name: String,
    ): Int =
        try {
            Class
                .forName("org.telegram.messenger.R\$style", false, classLoader)
                .getDeclaredField(name)
                .getInt(null)
        } catch (_: Throwable) {
            0
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

    /** The final three arguments are the parallel icon, label, and option lists. */
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

    private data class PreviewTarget(
        val runnable: Runnable,
        val viewer: Any,
    )
}
