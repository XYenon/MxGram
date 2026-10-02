package dev.xyenon.mxgram

import android.app.Activity
import android.content.Context
import android.util.Log
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
            (
                try {
                    TelegramObfuscationResolver.findChatSelectedObjectField(chatActivity.javaClass).get(chatActivity)
                } catch (_: Throwable) {
                    null
                }
            ) ?: args.firstOrNull()?.takeIf { it.javaClass.name.endsWith("MessageObject") }
                ?: return
        if (!isSaveableStickerMessage(selectedObject)) {
            Log.d("MxGram", "addToMessageMenu: message is not saveable sticker: $selectedObject")
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
        Log.d("MxGram", "addToMessageMenu: added save sticker option at index $insertIndex")
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
            val selectedObject =
                TelegramObfuscationResolver.findChatSelectedObjectField(chatActivity.javaClass).get(chatActivity)
                    ?: return true
            Log.i("MxGram", "handleSelectedOption: saving message sticker")
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
            TelegramObfuscationResolver.findContentPreviewShowSheetRunnableField(viewer.javaClass).get(viewer) as? Runnable
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
            val contentType = TelegramObfuscationResolver.findContentPreviewCurrentContentTypeField(viewer.javaClass).getInt(viewer)
            Log.d("MxGram", "patchStickerPreviewMenu: contentType=$contentType")
            if (contentType != CONTENT_TYPE_STICKER) {
                return
            }
            if (TelegramObfuscationResolver.findContentPreviewIsPhotoEditorFieldOrNull(viewer.javaClass)?.getBoolean(viewer) == true) {
                return
            }
            val currentDocument = TelegramObfuscationResolver.findContentPreviewCurrentDocumentField(viewer.javaClass).get(viewer) ?: return
            val classLoader = viewer.javaClass.classLoader ?: return
            val messageObjectClass = classLoader.loadClass("org.telegram.messenger.MessageObject")
            if (invokeStaticBoolean(messageObjectClass, "isMaskDocument", arrayOf(currentDocument))) {
                return
            }
            val activity =
                TelegramObfuscationResolver.findContentPreviewParentActivityField(viewer.javaClass).get(viewer) as? Activity ?: return
            val account = TelegramObfuscationResolver.findContentPreviewCurrentAccountField(viewer.javaClass).getInt(viewer)
            val containerView =
                TelegramObfuscationResolver.findContentPreviewContainerViewField(viewer.javaClass).get(viewer) as? FrameLayout ?: return
            val resourcesProvider =
                TelegramObfuscationResolver
                    .findContentPreviewResourcesProviderFieldOrNull(
                        viewer.javaClass,
                    )?.get(viewer)
            val popupWindow =
                TelegramObfuscationResolver.findContentPreviewPopupWindowFieldOrNull(viewer.javaClass)?.get(viewer)
            if (popupWindow != null) {
                val previewMenu =
                    findMethodOrNull(popupWindow.javaClass, "getContentView")?.invoke(popupWindow) as? ViewGroup
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

            val menuVisible =
                TelegramObfuscationResolver.findContentPreviewMenuVisibleFieldOrNull(viewer.javaClass)?.getBoolean(viewer) ?: false
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
        val unlockView =
            TelegramObfuscationResolver.findContentPreviewUnlockPremiumViewFieldOrNull(viewer.javaClass)?.get(viewer) ?: return false
        val premiumButton = findFieldOrNull(unlockView.javaClass, "premiumButtonView")?.get(unlockView) as? View ?: return false
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
            ) {
                val closeWithMenuMethod =
                    TelegramObfuscationResolver.resolveContentPreviewCloseWithMenuMethod(viewer.javaClass)
                        ?: findMethodOrNull(viewer.javaClass, "closeWithMenu")
                closeWithMenuMethod?.invoke(viewer)
            } ?: return false
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
                val created = createMenuSubItem(host, resourcesProvider, classLoader) ?: return null
                previewMenuItems[host] = WeakReference(created)
                created
            }
        item.setOnClickListener {
            if (!hasGalleryWritePermission(activity)) {
                requestGalleryWritePermission(activity)
                return@setOnClickListener
            }
            Log.i("MxGram", "saveDocumentSticker: saving preview sticker")
            stickerSaver.saveDocumentSticker(activity, document, classLoader, account) {
                showDownloadBulletin(containerView, resourcesProvider)
            }
            closeMenu()
        }
        return item
    }

    private fun createMenuSubItem(
        host: ViewGroup,
        resourcesProvider: Any?,
        classLoader: ClassLoader,
    ): View? {
        val actionBarMenuItemClass =
            TelegramObfuscationResolver.resolveClassOrNull(classLoader, "org.telegram.ui.ActionBar.ActionBarMenuItem")
        if (actionBarMenuItemClass != null) {
            val addItem =
                actionBarMenuItemClass.declaredMethods.firstOrNull { method ->
                    method.name == "addItem" &&
                        method.parameterCount == 5 &&
                        ViewGroup::class.java.isAssignableFrom(method.parameterTypes[0])
                }
            if (addItem != null) {
                addItem.isAccessible = true
                val created =
                    addItem.invoke(
                        null,
                        host,
                        resolveTelegramDrawable(classLoader, "msg_gallery", 0),
                        resolveSaveToGalleryLabel(classLoader),
                        false,
                        resourcesProvider,
                    ) as? View
                if (created != null) {
                    return created
                }
            }
        }

        val subItemClass = TelegramObfuscationResolver.resolveActionBarMenuSubItem(classLoader)
        val cell = instantiateMenuSubItem(subItemClass, host.context, resourcesProvider) ?: return null
        val icon = resolveTelegramDrawable(classLoader, "msg_gallery", 0)
        val text = resolveSaveToGalleryLabel(classLoader)

        val setTextAndIcon =
            findMethodOrNull(subItemClass, "setTextAndIcon", CharSequence::class.java, java.lang.Integer.TYPE)
        if (setTextAndIcon != null) {
            setTextAndIcon.invoke(cell, text, icon)
        } else {
            // R8 reorders the two-argument overload; the drawable overload retains this signature.
            val withDrawable =
                subItemClass.declaredMethods.single {
                    it.parameterTypes.contentEquals(
                        arrayOf(CharSequence::class.java, java.lang.Integer.TYPE, android.graphics.drawable.Drawable::class.java),
                    ) && it.returnType == java.lang.Void.TYPE
                }
            withDrawable.isAccessible = true
            withDrawable.invoke(cell, text, icon, null)
        }
        val minWidth = dp(classLoader, 196f)
        findMethodOrNull(subItemClass, "setMinimumWidth", java.lang.Integer.TYPE)?.invoke(cell, minWidth)
        host.addView(cell)
        return cell
    }

    private fun instantiateMenuSubItem(
        subItemClass: Class<*>,
        context: Context,
        resourcesProvider: Any?,
    ): View? {
        for (constructor in subItemClass.constructors) {
            val params = constructor.parameterTypes
            val instance =
                runCatching {
                    when (params.size) {
                        5 -> {
                            if (params[0] == Context::class.java) {
                                constructor.newInstance(context, false, false, false, resourcesProvider)
                            } else if (params[1] == Context::class.java) {
                                constructor.newInstance(0, context, resourcesProvider, false, false)
                            } else {
                                null
                            }
                        }

                        4 -> {
                            if (params[0] == Context::class.java &&
                                (resourcesProvider == null || params[1].isInstance(resourcesProvider))
                            ) {
                                constructor.newInstance(context, resourcesProvider, false, false)
                            } else if (params[0] == Context::class.java) {
                                constructor.newInstance(context, false, false, false)
                            } else {
                                null
                            }
                        }

                        3 -> {
                            if (params[0] == Context::class.java) {
                                constructor.newInstance(context, false, false)
                            } else {
                                null
                            }
                        }

                        2 -> {
                            if (params[0] == Context::class.java) {
                                constructor.newInstance(context, resourcesProvider)
                            } else {
                                null
                            }
                        }

                        1 -> {
                            if (params[0] == Context::class.java) {
                                constructor.newInstance(context)
                            } else {
                                null
                            }
                        }

                        else -> {
                            null
                        }
                    }
                }.getOrNull()
            if (instance is View) {
                return instance
            }
        }
        return null
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
        val isVisible =
            TelegramObfuscationResolver.findContentPreviewIsVisibleFieldOrNull(viewer.javaClass)?.getBoolean(viewer)
                ?: (findMethodOrNull(viewer.javaClass, "isVisible")?.invoke(viewer) as? Boolean)
                ?: true
        if (!isVisible) {
            return
        }
        val layoutClass =
            TelegramObfuscationResolver.resolveClassOrNull(
                classLoader,
                "org.telegram.ui.ActionBar.ActionBarPopupWindow\$ActionBarPopupWindowLayout",
            ) ?: return

        val previewMenu =
            instantiatePopupWindowLayout(layoutClass, containerView.context, resourcesProvider, classLoader)
                ?: return

        addPreviewSaveItem(
            previewMenu,
            activity,
            document,
            classLoader,
            account,
            containerView,
            resourcesProvider,
        ) { dismissPreviewPopup(viewer) } ?: return

        val popupClass = TelegramObfuscationResolver.resolveActionBarPopupWindow(classLoader)
        val popup =
            popupClass
                .getConstructor(View::class.java, java.lang.Integer.TYPE, java.lang.Integer.TYPE)
                .newInstance(previewMenu, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        TelegramObfuscationResolver.findContentPreviewPopupWindowField(viewer.javaClass).set(viewer, popup)
        TelegramObfuscationResolver.findContentPreviewMenuVisibleField(viewer.javaClass).setBoolean(viewer, true)
        ownedPreviewPopups[popup] = WeakReference(viewer)
        try {
            findMethodOrNull(popupClass, "setPauseNotifications", java.lang.Boolean.TYPE)?.invoke(popup, true)
            findMethodOrNull(popupClass, "setDismissAnimationDuration", java.lang.Integer.TYPE)?.invoke(popup, 100)
            findMethodOrNull(popupClass, "setScaleOut", java.lang.Boolean.TYPE)?.invoke(popup, true)
            findMethodOrNull(popupClass, "setOutsideTouchable", java.lang.Boolean.TYPE)?.invoke(popup, true)
            findMethodOrNull(popupClass, "setClippingEnabled", java.lang.Boolean.TYPE)?.invoke(popup, true)
            findMethodOrNull(popupClass, "setAnimationStyle", java.lang.Integer.TYPE)
                ?.invoke(popup, resolveTelegramStyle(classLoader, "PopupContextAnimation"))
            findMethodOrNull(popupClass, "setFocusable", java.lang.Boolean.TYPE)?.invoke(popup, true)
            previewMenu.measure(
                View.MeasureSpec.makeMeasureSpec(dp(classLoader, 1000f), View.MeasureSpec.AT_MOST),
                View.MeasureSpec.makeMeasureSpec(dp(classLoader, 1000f), View.MeasureSpec.AT_MOST),
            )
            findMethodOrNull(popupClass, "setInputMethodMode", java.lang.Integer.TYPE)
                ?.invoke(popup, PopupWindow.INPUT_METHOD_NOT_NEEDED)
            previewMenu.isFocusableInTouchMode = true
            val y = previewPopupY(viewer, containerView, classLoader)
            val x = (containerView.measuredWidth - previewMenu.measuredWidth) / 2
            findMethodOrNull(
                popupClass,
                "showAtLocation",
                View::class.java,
                java.lang.Integer.TYPE,
                java.lang.Integer.TYPE,
                java.lang.Integer.TYPE,
            )?.invoke(popup, containerView, 0, x, y)
            runCatching { containerView.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS) }
            containerView.invalidate()
        } catch (t: Throwable) {
            ownedPreviewPopups.remove(popup)
            TelegramObfuscationResolver.findContentPreviewPopupWindowFieldOrNull(viewer.javaClass)?.set(viewer, null)
            TelegramObfuscationResolver.findContentPreviewMenuVisibleFieldOrNull(viewer.javaClass)?.setBoolean(viewer, false)
            runCatching { findMethodOrNull(popupClass, "dismiss")?.invoke(popup) }
            throw t
        }
    }

    private fun instantiatePopupWindowLayout(
        layoutClass: Class<*>,
        context: Context,
        resourcesProvider: Any?,
        classLoader: ClassLoader,
    ): ViewGroup? {
        val bgDrawableRes = resolveTelegramDrawable(classLoader, "popup_fixed_alert4", 0)
        for (constructor in layoutClass.constructors) {
            val params = constructor.parameterTypes
            val instance =
                runCatching {
                    when (params.size) {
                        4 -> {
                            if (params[0] == Context::class.java) {
                                constructor.newInstance(context, bgDrawableRes, resourcesProvider, 0)
                            } else if (params[2] == Context::class.java) {
                                constructor.newInstance(0, bgDrawableRes, context, resourcesProvider)
                            } else {
                                null
                            }
                        }

                        2 -> {
                            if (params[0] == Context::class.java) {
                                constructor.newInstance(context, resourcesProvider)
                            } else {
                                null
                            }
                        }

                        1 -> {
                            if (params[0] == Context::class.java) {
                                constructor.newInstance(context)
                            } else {
                                null
                            }
                        }

                        else -> {
                            null
                        }
                    }
                }.getOrNull()
            if (instance is ViewGroup) {
                return instance
            }
        }
        return null
    }

    private fun previewPopupY(
        viewer: Any,
        containerView: FrameLayout,
        classLoader: ClassLoader,
    ): Int {
        val insets = findFieldOrNull(viewer.javaClass, "lastInsets")?.get(viewer)
        val insetTop = insets?.let { findFieldOrNull(it.javaClass, "top")?.getInt(it) } ?: 0
        val insetBottom = insets?.let { findFieldOrNull(it.javaClass, "bottom")?.getInt(it) } ?: 0
        val moveY = TelegramObfuscationResolver.findContentPreviewMoveYFieldOrNull(viewer.javaClass)?.getFloat(viewer) ?: 0f
        val keyboardHeight = TelegramObfuscationResolver.findContentPreviewKeyboardHeightFieldOrNull(viewer.javaClass)?.getInt(viewer) ?: 0
        val drawEffect = TelegramObfuscationResolver.findContentPreviewDrawEffectFieldOrNull(viewer.javaClass)?.getBoolean(viewer) ?: false
        val size =
            if (drawEffect) {
                min(containerView.width, containerView.height - insetTop - insetBottom) - dp(classLoader, 40f)
            } else {
                (min(containerView.width, containerView.height - insetTop - insetBottom) / 1.8f).toInt()
            }
        val emojiOffset =
            if (TelegramObfuscationResolver.findContentPreviewStickerEmojiLayoutFieldOrNull(viewer.javaClass)?.get(viewer) !=
                null
            ) {
                dp(classLoader, 40f)
            } else {
                0
            }
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
            val popupField = TelegramObfuscationResolver.findContentPreviewPopupWindowFieldOrNull(viewer.javaClass) ?: return
            if (popupField.get(viewer) !== popupWindow) {
                return
            }
            popupField.set(viewer, null)
            TelegramObfuscationResolver.findContentPreviewMenuVisibleFieldOrNull(viewer.javaClass)?.setBoolean(viewer, false)
            val closeOnDismiss =
                TelegramObfuscationResolver.findContentPreviewCloseOnDismissFieldOrNull(viewer.javaClass)?.getBoolean(viewer) ?: true
            if (!closeOnDismiss) {
                return
            }
            val currentPreviewCellField = TelegramObfuscationResolver.findContentPreviewCurrentPreviewCellFieldOrNull(viewer.javaClass)
            val currentPreviewCell = currentPreviewCellField?.get(viewer)
            if (currentPreviewCell != null) {
                runCatching {
                    findMethodOrNull(currentPreviewCell.javaClass, "setScaled", java.lang.Boolean.TYPE)
                        ?.invoke(currentPreviewCell, false)
                }
                currentPreviewCellField.set(viewer, null)
            }
            val closeMethod =
                TelegramObfuscationResolver.resolveContentPreviewCloseMethod(viewer.javaClass)
                    ?: findMethodOrNull(viewer.javaClass, "close")
            closeMethod?.invoke(viewer)
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
            val dismissMethod =
                TelegramObfuscationResolver.resolveContentPreviewDismissPopupWindowMethod(viewer.javaClass)
                    ?: findMethodOrNull(viewer.javaClass, "dismissPopupWindow")
            dismissMethod?.invoke(viewer)
        } catch (_: Throwable) {
        }
        try {
            val popupField = TelegramObfuscationResolver.findContentPreviewPopupWindowFieldOrNull(viewer.javaClass)
            val popup = popupField?.get(viewer)
            if (popup != null) {
                findMethodOrNull(popup.javaClass, "dismiss")?.invoke(popup)
                popupField.set(viewer, null)
                TelegramObfuscationResolver.findContentPreviewMenuVisibleFieldOrNull(viewer.javaClass)?.setBoolean(viewer, false)
            }
        } catch (_: Throwable) {
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun showDownloadBulletin(host: Any) {
        try {
            val classLoader = host.javaClass.classLoader ?: return
            val bulletinFactoryClass = TelegramObfuscationResolver.resolveBulletinFactory(classLoader)

            val ofMethod =
                bulletinFactoryClass.declaredMethods.firstOrNull { method ->
                    java.lang.reflect.Modifier
                        .isStatic(method.modifiers) &&
                        method.returnType == bulletinFactoryClass &&
                        method.parameterCount == 1 &&
                        method.parameterTypes[0].isAssignableFrom(host.javaClass)
                }
            val factory =
                ofMethod?.let {
                    it.isAccessible = true
                    it.invoke(null, host)
                } ?: runCatching {
                    val ctor =
                        bulletinFactoryClass.constructors.firstOrNull {
                            it.parameterCount == 1 && it.parameterTypes[0].isAssignableFrom(host.javaClass)
                        }
                    ctor?.newInstance(host)
                }.getOrNull()

            if (factory != null) {
                val fileTypeClass =
                    TelegramObfuscationResolver.resolveClassOrNull(
                        classLoader,
                        "org.telegram.ui.Components.BulletinFactory\$FileType",
                    ) ?: bulletinFactoryClass.declaredMethods
                        .flatMap { it.parameterTypes.toList() }
                        .filter { it.isEnum }
                        .distinct()
                        .singleOrNull()
                val fileType =
                    fileTypeClass?.let { ft ->
                        runCatching { java.lang.Enum.valueOf(ft as Class<out Enum<*>>, "MEDIA") }.getOrNull()
                            ?: runCatching { java.lang.Enum.valueOf(ft as Class<out Enum<*>>, "PHOTO") }.getOrNull()
                    }
                val themeDelegate = runCatching { findFieldOrNull(host.javaClass, "themeDelegate")?.get(host) }.getOrNull()
                val createDownloadBulletin =
                    factory.javaClass.declaredMethods.firstOrNull { method ->
                        method.parameterCount in 1..2 &&
                            method.parameterTypes[0] == fileTypeClass
                    }
                if (createDownloadBulletin != null && fileType != null) {
                    createDownloadBulletin.isAccessible = true
                    val bulletin =
                        when (createDownloadBulletin.parameterCount) {
                            1 -> createDownloadBulletin.invoke(factory, fileType)
                            2 -> createDownloadBulletin.invoke(factory, fileType, themeDelegate)
                            else -> null
                        }
                    if (bulletin != null) {
                        showBulletin(bulletin)
                        return
                    }
                }
            }

            val staticSaveMethod =
                bulletinFactoryClass.declaredMethods.firstOrNull { method ->
                    java.lang.reflect.Modifier
                        .isStatic(method.modifiers) &&
                        method.parameterCount == 3 &&
                        method.parameterTypes[0].isAssignableFrom(host.javaClass) &&
                        method.parameterTypes[1] == java.lang.Boolean.TYPE &&
                        method.parameterTypes[2].isInterface
                }
            if (staticSaveMethod != null) {
                staticSaveMethod.isAccessible = true
                val bulletin =
                    if (staticSaveMethod.parameterCount == 2) {
                        staticSaveMethod.invoke(null, host, false)
                    } else {
                        staticSaveMethod.invoke(null, host, false, null)
                    }
                if (bulletin != null) {
                    showBulletin(bulletin)
                    return
                }
            }
        } catch (t: Throwable) {
            logError("Failed to show sticker saved bulletin", t)
        }
    }

    private fun showDownloadBulletin(
        containerLayout: FrameLayout,
        resourcesProvider: Any?,
    ) {
        try {
            val classLoader =
                containerLayout.context.classLoader ?: resourcesProvider?.javaClass?.classLoader ?: return
            val bulletinFactoryClass = TelegramObfuscationResolver.resolveBulletinFactory(classLoader)

            val staticSaveMethod =
                bulletinFactoryClass.declaredMethods.firstOrNull { method ->
                    java.lang.reflect.Modifier
                        .isStatic(method.modifiers) &&
                        method.parameterCount in 2..3 &&
                        method.parameterTypes[0].isAssignableFrom(containerLayout.javaClass) &&
                        method.parameterTypes[1] == java.lang.Boolean.TYPE
                }
            if (staticSaveMethod != null) {
                staticSaveMethod.isAccessible = true
                val bulletin =
                    if (staticSaveMethod.parameterCount == 2) {
                        staticSaveMethod.invoke(null, containerLayout, false)
                    } else {
                        staticSaveMethod.invoke(null, containerLayout, false, resourcesProvider)
                    }
                if (bulletin != null) {
                    showBulletin(bulletin)
                    return
                }
            }

            val ofMethod =
                bulletinFactoryClass.declaredMethods.firstOrNull { method ->
                    java.lang.reflect.Modifier
                        .isStatic(method.modifiers) &&
                        method.returnType == bulletinFactoryClass &&
                        (method.name == "of" || method.parameterCount == 2) &&
                        method.parameterCount == 2 &&
                        method.parameterTypes[0].isAssignableFrom(containerLayout.javaClass)
                }
            val factory =
                ofMethod?.let {
                    it.isAccessible = true
                    it.invoke(null, containerLayout, resourcesProvider)
                } ?: runCatching {
                    val ctor =
                        bulletinFactoryClass.constructors.firstOrNull {
                            it.parameterCount == 2 && it.parameterTypes[0].isAssignableFrom(containerLayout.javaClass)
                        }
                    ctor?.newInstance(containerLayout, resourcesProvider)
                }.getOrNull()

            if (factory != null) {
                showDownloadBulletin(factory, classLoader, resourcesProvider)
            }
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
        val fileTypeClass =
            TelegramObfuscationResolver.resolveClassOrNull(
                classLoader,
                "org.telegram.ui.Components.BulletinFactory\$FileType",
            ) ?: factory.javaClass.declaredMethods
                .flatMap { it.parameterTypes.toList() }
                .filter { it.isEnum }
                .distinct()
                .singleOrNull()
        val fileType =
            fileTypeClass?.let { ft ->
                runCatching { java.lang.Enum.valueOf(ft as Class<out Enum<*>>, "MEDIA") }.getOrNull()
                    ?: runCatching { java.lang.Enum.valueOf(ft as Class<out Enum<*>>, "PHOTO") }.getOrNull()
            }
        val createDownloadBulletin =
            factory.javaClass.declaredMethods.firstOrNull { method ->
                method.parameterCount in 1..2 &&
                    method.parameterTypes[0] == fileTypeClass
            } ?: return
        createDownloadBulletin.isAccessible = true
        val bulletin =
            if (createDownloadBulletin.parameterCount == 1) {
                createDownloadBulletin.invoke(factory, fileType)
            } else {
                createDownloadBulletin.invoke(factory, fileType, resourcesProvider)
            } ?: return
        showBulletin(bulletin)
    }

    private fun showBulletin(bulletin: Any) {
        val showMethod =
            findMethodOrNull(bulletin.javaClass, "show")
                ?: bulletin.javaClass.declaredMethods.singleOrNull {
                    it.parameterCount == 0 &&
                        !java.lang.reflect.Modifier
                            .isStatic(it.modifiers) &&
                        it.returnType == bulletin.javaClass
                }
        showMethod?.invoke(bulletin)
    }

    private fun clearSelection(chatActivity: Any) {
        try {
            TelegramObfuscationResolver.findChatSelectedObjectField(chatActivity.javaClass).set(chatActivity, null)
            TelegramObfuscationResolver.findChatSelectedObjectGroupField(chatActivity.javaClass).set(chatActivity, null)
            TelegramObfuscationResolver.findChatSelectedObjectToEditCaptionFieldOrNull(chatActivity.javaClass)?.set(chatActivity, null)
        } catch (_: Throwable) {
        }
    }

    private fun finishSelectedOption(chatActivity: Any) {
        clearSelection(chatActivity)
        try {
            val closeMenu = findMethodOrNull(chatActivity.javaClass, "closeMenu")
            if (closeMenu != null) {
                closeMenu.invoke(chatActivity)
            } else {
                val popup =
                    TelegramObfuscationResolver.findChatScrimPopupWindowFieldOrNull(chatActivity.javaClass)?.get(chatActivity)
                if (popup != null) {
                    findMethodOrNull(popup.javaClass, "dismiss")?.invoke(popup)
                }
            }
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
