package dev.xyenon.mxgram

import android.content.Context
import android.util.Log
import android.view.View
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicBoolean

class TelegramHooksModule : XposedModule() {
    private val hooksInstalled = AtomicBoolean(false)
    private val previewRunnableHookInstalled = AtomicBoolean(false)
    private val secretReadActions = Collections.synchronizedMap(WeakHashMap<Runnable, Runnable>())
    private val secretReadRunnableClasses = HashSet<Class<*>>()
    private var processName: String = ""
    private val plusOneForwarder = PlusOneForwarder { message, throwable -> logError(message, throwable) }
    private val stickerDownloadMenu =
        StickerDownloadMenu(
            StickerSaver { message, throwable -> logError(message, throwable) },
        ) { message, throwable -> logError(message, throwable) }
    private val profileIdDisplay = ProfileIdDisplay { message, throwable -> logError(message, throwable) }

    override fun onModuleLoaded(param: XposedModuleInterface.ModuleLoadedParam) {
        instance = this
        processName = param.processName
        logInfo("Module loaded in $processName")
    }

    override fun onPackageReady(param: XposedModuleInterface.PackageReadyParam) {
        logInfo("Package ready: ${param.packageName}")
        if (param.packageName != TARGET_PACKAGE) {
            return
        }
        if (!hooksInstalled.compareAndSet(false, true)) {
            return
        }

        TelegramObfuscationResolver.initialize(
            classLoader = param.classLoader,
            hostSourceDir = param.applicationInfo.sourceDir,
            moduleSourceDir = moduleApplicationInfo.sourceDir,
            moduleNativeLibDir = moduleApplicationInfo.nativeLibraryDir,
            log = { msg -> logInfo(msg) },
            logErr = { msg, err -> logError(msg, err) },
        )

        installHooks(param.classLoader)
        logInfo("Telegram hook installation completed in $processName")
    }

    private fun installHooks(classLoader: ClassLoader) {
        val chatActivityClass =
            try {
                TelegramObfuscationResolver.resolveChatActivity(classLoader)
            } catch (t: Throwable) {
                logError("Failed to resolve ChatActivity class", t)
                null
            }

        installHookGroup("noforwards and message flag hooks") {
            val messagesControllerClass =
                Class.forName("org.telegram.messenger.MessagesController", false, classLoader)
            val messageObjectClass = Class.forName("org.telegram.messenger.MessageObject", false, classLoader)
            val tlrpcMessageClass = Class.forName("org.telegram.tgnet.TLRPC\$Message", false, classLoader)
            hookNoForwardsRestrictions(messagesControllerClass)
            hookMessageNoForwardsFlag(messageObjectClass, tlrpcMessageClass)
        }
        if (chatActivityClass != null) {
            installHookGroup("self-destruct media hooks") {
                hookSelfDestructMediaProtection(
                    chatActivityClass,
                    Class.forName("org.telegram.messenger.MessagesController", false, classLoader),
                )
            }
            installHookGroup("pull-down navigation hook") {
                hookAnimateToNextChat(chatActivityClass)
            }
            installHookGroup("double-tap listener hook") {
                hookCreateView(chatActivityClass)
            }
            installHookGroup("double-tap reaction hook") {
                hookSelectReaction(chatActivityClass)
            }
            installHookGroup("+1 message hooks") {
                hookPlusOneForward(chatActivityClass)
            }
        }
        installHookGroup("greeting sticker hook") {
            hookGreetingStickerSend(
                TelegramObfuscationResolver.resolveChatGreetingsView(classLoader),
                classLoader,
            )
        }
        installHookGroup("pull-down target hooks") {
            hookPullingDownTargets(
                TelegramObfuscationResolver.resolveChatPullingDownDrawable(classLoader),
            )
        }
        installHookGroup("sticker download hooks") {
            hookStickerDownload(classLoader)
        }
        installHookGroup("profile ID hooks") {
            hookProfileIdDisplay(TelegramObfuscationResolver.resolveProfileActivity(classLoader))
        }
        installHookGroup("reply forward author hooks") {
            hookReplyForwardAuthor(
                TelegramObfuscationResolver.resolveChatMessageCell(classLoader),
                Class.forName("org.telegram.messenger.MessageObject", false, classLoader),
            )
        }
    }

    private fun hookReplyForwardAuthor(
        chatMessageCellClass: Class<*>,
        messageObjectClass: Class<*>,
    ) {
        val setMessageObjectInternal =
            chatMessageCellClass.getDeclaredMethod("setMessageObjectInternal", messageObjectClass)
        val getForwardedName = messageObjectClass.getDeclaredMethod("getForwardedName")
        setMessageObjectInternal.isAccessible = true
        getForwardedName.isAccessible = true
        hook(setMessageObjectInternal).intercept(ReplyLayoutHooker())
        hook(getForwardedName).intercept(ReplyForwardedNameHooker())
    }

    private inline fun installHookGroup(
        name: String,
        install: () -> Unit,
    ) {
        try {
            install()
        } catch (t: Throwable) {
            logError("Failed to install $name", t)
        }
    }

    private fun hookNoForwardsRestrictions(messagesControllerClass: Class<*>) {
        try {
            var hooked = 0
            for (method in messagesControllerClass.declaredMethods) {
                if (method.parameterCount != 1 || method.returnType != java.lang.Boolean.TYPE) {
                    continue
                }
                if (
                    method.name != "isChatNoForwards" &&
                    method.name != "isUserNoForwards" &&
                    method.name != "isPeerNoForwards"
                ) {
                    continue
                }
                method.isAccessible = true
                hook(method).intercept(NoForwardsBypassHooker())
                hooked += 1
            }
            check(hooked > 0) { "MessagesController noforwards methods not found" }
        } catch (t: Throwable) {
            logError("Failed to install noforwards bypass hook", t)
        }
    }

    private fun hookMessageNoForwardsFlag(
        messageObjectClass: Class<*>,
        tlrpcMessageClass: Class<*>,
    ) {
        try {
            var hooked = 0
            for (constructor in messageObjectClass.declaredConstructors) {
                if (!constructor.parameterTypes.any { param -> param == tlrpcMessageClass }) {
                    continue
                }
                constructor.isAccessible = true
                hook(constructor).intercept(ClearMessageNoForwardsHooker())
                hooked += 1
            }
            check(hooked > 0) { "MessageObject constructors taking TLRPC.Message not found" }
        } catch (t: Throwable) {
            logError("Failed to install message noforwards cleanup hook", t)
        }
    }

    private fun hookSelfDestructMediaProtection(
        chatActivityClass: Class<*>,
        messagesControllerClass: Class<*>,
    ) {
        installHookGroup("secret media read hook") {
            val sendSecretMessageRead =
                TelegramObfuscationResolver.resolveSendSecretMessageReadMethod(chatActivityClass)
            sendSecretMessageRead.isAccessible = true
            hook(sendSecretMessageRead).intercept(SecretMessageReadHooker())
            logInfo("Hooked secret media read: $sendSecretMessageRead")
        }
        installHookGroup("secret media close hook") {
            val sendSecretMediaDelete =
                TelegramObfuscationResolver.resolveSendSecretMediaDeleteMethod(chatActivityClass)
            if (sendSecretMediaDelete != null) {
                sendSecretMediaDelete.isAccessible = true
                hook(sendSecretMediaDelete).intercept(SecretMediaDeleteHooker())
                logInfo("Hooked secret media delete: $sendSecretMediaDelete")
            } else {
                error("ChatActivity.sendSecretMediaDelete not found")
            }
        }
        installHookGroup("content read delete-task hook") {
            val markMessageAsRead2 =
                messagesControllerClass.declaredMethods.firstOrNull { method ->
                    method.name == "markMessageAsRead2" && method.parameterCount == 6
                } ?: throw IllegalStateException("MessagesController.markMessageAsRead2(...) not found")
            markMessageAsRead2.isAccessible = true
            hook(markMessageAsRead2).intercept(PreventDeleteTaskOnContentReadHooker())
        }
        installHookGroup("secret chat delete-task hook") {
            val markMessageAsRead =
                messagesControllerClass.declaredMethods.firstOrNull { method ->
                    method.name == "markMessageAsRead" && method.parameterCount == 3
                } ?: throw IllegalStateException("MessagesController.markMessageAsRead(...) not found")
            markMessageAsRead.isAccessible = true
            hook(markMessageAsRead).intercept(PreventDeleteTaskOnSecretChatReadHooker())
        }
        installHookGroup("show-once task creation hook") {
            val createDeleteShowOnceTask =
                messagesControllerClass.declaredMethods.firstOrNull { method ->
                    method.name == "createDeleteShowOnceTask" && method.parameterCount == 2
                } ?: throw IllegalStateException("MessagesController.createDeleteShowOnceTask(...) not found")
            createDeleteShowOnceTask.isAccessible = true
            hook(createDeleteShowOnceTask).intercept(BlockCreateDeleteShowOnceTaskHooker())
        }
        installHookGroup("show-once task execution hook") {
            val doDeleteShowOnceTask =
                messagesControllerClass.declaredMethods.firstOrNull { method ->
                    method.name == "doDeleteShowOnceTask" && method.parameterCount == 3
                } ?: throw IllegalStateException("MessagesController.doDeleteShowOnceTask(...) not found")
            doDeleteShowOnceTask.isAccessible = true
            hook(doDeleteShowOnceTask).intercept(BlockDoDeleteShowOnceTaskHooker())
        }
    }

    private fun hookProfileIdDisplay(profileActivityClass: Class<*>) {
        logInfo("Hooking profile activity: $profileActivityClass")
        installHookGroup("profile ID view hook") {
            val createView = profileActivityClass.getDeclaredMethod("createView", Context::class.java)
            createView.isAccessible = true
            hook(createView).intercept(ProfileCreateViewHooker())
            logInfo("Hooked ProfileActivity.createView")
        }
        installHookGroup("profile ID data hook") {
            val updateProfileData =
                TelegramObfuscationResolver.resolveProfileUpdateDataMethod(profileActivityClass)
            if (updateProfileData != null) {
                updateProfileData.isAccessible = true
                hook(updateProfileData).intercept(ProfileUpdateDataHooker())
                logInfo("Hooked ProfileActivity.updateProfileData (${updateProfileData.name})")
            } else {
                logError("ProfileActivity updateProfileData method not found", IllegalStateException("updateProfileData not found"))
            }
        }
        installHookGroup("profile ID layout hook") {
            val needLayout =
                TelegramObfuscationResolver.resolveProfileNeedLayoutMethod(profileActivityClass)
            if (needLayout != null) {
                needLayout.isAccessible = true
                hook(needLayout).intercept(ProfileLayoutHooker())
                logInfo("Hooked ProfileActivity.needLayout (${needLayout.name})")
            } else {
                logError("ProfileActivity needLayout method not found", IllegalStateException("needLayout not found"))
            }
        }
        installHookGroup("profile ID avatar expansion hook") {
            val setAvatarExpandProgress =
                TelegramObfuscationResolver.resolveProfileSetAvatarExpandProgressMethod(profileActivityClass)
            if (setAvatarExpandProgress != null) {
                setAvatarExpandProgress.isAccessible = true
                hook(setAvatarExpandProgress).intercept(ProfileLayoutHooker())
                logInfo("Hooked ProfileActivity.setAvatarExpandProgress (${setAvatarExpandProgress.name})")
            } else {
                logError(
                    "ProfileActivity setAvatarExpandProgress method not found",
                    IllegalStateException("setAvatarExpandProgress not found"),
                )
            }
        }
    }

    private fun hookStickerDownload(classLoader: ClassLoader) {
        try {
            val contentPreviewViewerClass =
                TelegramObfuscationResolver.resolveContentPreviewViewer(classLoader)
            val getInstance =
                TelegramObfuscationResolver.resolveContentPreviewGetInstance(contentPreviewViewerClass)
            getInstance.isAccessible = true
            hook(getInstance).intercept(ContentPreviewGetInstanceHooker())

            val popupWindowClass =
                TelegramObfuscationResolver.resolveActionBarPopupWindow(classLoader)
            val dismiss = popupWindowClass.getDeclaredMethod("dismiss")
            dismiss.isAccessible = true
            hook(dismiss).intercept(PreviewPopupDismissHooker())
        } catch (t: Throwable) {
            logError("Failed to install sticker preview menu hook", t)
        }
    }

    @Throws(Exception::class)
    private fun hookPlusOneForward(chatActivityClass: Class<*>) {
        val fillMessageMenu = TelegramObfuscationResolver.resolveFillMessageMenuMethod(chatActivityClass)
        fillMessageMenu.isAccessible = true
        hook(fillMessageMenu).intercept(FillMessageMenuHooker())

        val processSelectedOption =
            TelegramObfuscationResolver.resolveProcessSelectedOptionMethod(chatActivityClass)
        processSelectedOption.isAccessible = true
        hook(processSelectedOption).intercept(ProcessSelectedOptionHooker())

        val createMenu = TelegramObfuscationResolver.resolveCreateMenuMethod(chatActivityClass)
        createMenu.isAccessible = true
        hook(createMenu).intercept(CreateMenuHooker())
    }

    private fun hookAnimateToNextChat(chatActivityClass: Class<*>) {
        val method = TelegramObfuscationResolver.resolveAnimateToNextChatMethod(chatActivityClass)
        if (method != null) {
            method.isAccessible = true
            hook(method).intercept(BlockAnimateToNextChatHooker())
        }
    }

    @Throws(NoSuchMethodException::class)
    private fun hookCreateView(chatActivityClass: Class<*>) {
        val method = chatActivityClass.getDeclaredMethod("createView", Context::class.java)
        method.isAccessible = true
        hook(method).intercept(CreateViewHooker())
    }

    @Throws(NoSuchMethodException::class)
    private fun hookGreetingStickerSend(
        chatGreetingsViewClass: Class<*>,
        classLoader: ClassLoader,
    ) {
        val listenerInterface =
            TelegramObfuscationResolver.resolveChatGreetingsListener(classLoader, chatGreetingsViewClass)

        val method = chatGreetingsViewClass.getDeclaredMethod("setListener", listenerInterface)
        method.isAccessible = true
        hook(method).intercept(DisableGreetingStickerHooker())
    }

    private fun hookSelectReaction(chatActivityClass: Class<*>) {
        val method = TelegramObfuscationResolver.resolveSelectReactionMethod(chatActivityClass)
        method.isAccessible = true
        hook(method).intercept(SelectReactionHooker())
    }

    private fun hookPullingDownTargets(pullingDownDrawableClass: Class<*>) {
        var hooked = 0
        val updateMethods = TelegramObfuscationResolver.resolvePullingDownUpdateMethods(pullingDownDrawableClass)
        for (method in updateMethods) {
            method.isAccessible = true
            hook(method).intercept(PullingDownTargetHooker())
            hooked += 1
        }
        check(hooked > 0) { "ChatPullingDownDrawable update methods not found" }
        logInfo("Hooked $hooked pulling down update target methods in ${pullingDownDrawableClass.name}")
    }

    internal fun disableDoubleTapReaction(
        chatActivity: Any,
        classLoader: ClassLoader?,
    ) {
        DoubleTapReactionBlocker.disable(chatActivity, classLoader, ::logError)
    }

    @Throws(Exception::class)
    internal fun neutralizePullingDownTarget(pullingDownDrawable: Any) {
        val cls = pullingDownDrawable.javaClass
        TelegramObfuscationResolver.findPullingDownEmptyStubField(cls).setBoolean(pullingDownDrawable, true)
        TelegramObfuscationResolver.findPullingDownNextChatField(cls).set(pullingDownDrawable, null)
        TelegramObfuscationResolver.findPullingDownNextTopicField(cls).set(pullingDownDrawable, null)
        TelegramObfuscationResolver.findPullingDownNextDialogIdField(cls).setLong(pullingDownDrawable, 0L)
        TelegramObfuscationResolver.findPullingDownImageReceiverFieldOrNull(cls)?.get(pullingDownDrawable)?.let { ir ->
            try {
                findMethodOrNull(ir.javaClass, "clearImage")?.invoke(ir)
            } catch (_: Throwable) {
            }
        }
        android.util.Log.i("MxGram", "neutralizePullingDownTarget: set emptyStub=true, nextChat=null")
    }

    @Throws(Exception::class)
    internal fun buildSelfDestructMediaReadAction(
        chatActivity: Any,
        messageObject: Any,
        readNow: Boolean,
    ): Runnable? {
        val messageOwner = findField(messageObject.javaClass, "messageOwner").get(messageObject) ?: return null
        val destroyTime = findField(messageOwner.javaClass, "destroyTime").getInt(messageOwner)
        val ttl = findField(messageOwner.javaClass, "ttl").getInt(messageOwner)
        val isOut = findMethod(messageObject.javaClass, "isOut").invoke(messageObject) == true
        val isSecretMedia = findMethod(messageObject.javaClass, "isSecretMedia").invoke(messageObject) == true
        if (isOut || !isSecretMedia || destroyTime != 0 || ttl <= 0) {
            return null
        }

        val action =
            Runnable {
                try {
                    markSelfDestructMediaAsReadWithoutDeleteTask(chatActivity, messageObject)
                } catch (t: Throwable) {
                    logError("Failed to keep self-destruct media after opening", t)
                }
            }
        return if (readNow) {
            action.run()
            null
        } else {
            action
        }
    }

    internal fun replaceDeferredSecretRead(
        original: Runnable,
        action: Runnable,
    ): Runnable {
        synchronized(secretReadRunnableClasses) {
            if (original.javaClass !in secretReadRunnableClasses) {
                val run = original.javaClass.getDeclaredMethod("run").apply { isAccessible = true }
                hook(run).intercept(DeferredSecretReadHooker())
                secretReadRunnableClasses.add(original.javaClass)
            }
        }
        secretReadActions[original] = action
        // R8 may narrow the return type to a concrete Runnable class. Keep that object.
        return original
    }

    internal fun runDeferredSecretRead(runnable: Any?): Boolean {
        val action = secretReadActions[runnable] ?: return false
        action.run()
        return true
    }

    @Throws(Exception::class)
    internal fun disarmSelfDestructDeleteTask(args: Array<Any?>?) {
        if (args == null || args.size < 6) {
            return
        }
        if (args[5] == true) {
            args[5] = false
        }
    }

    @Throws(Exception::class)
    internal fun disarmSecretChatDeleteTask(args: Array<Any?>?) {
        if (args == null || args.size < 3) {
            return
        }
        val ttl = args[2] as? Int ?: return
        if (ttl > 0) {
            args[2] = Int.MIN_VALUE
        }
    }

    internal fun addPlusOneToMessageMenu(
        chatActivity: Any,
        args: Array<Any?>?,
    ) {
        plusOneForwarder.addToMessageMenu(chatActivity, args)
    }

    internal fun markNoForwardsMessage(messageObject: Any) {
        plusOneForwarder.markNoForwardsMessage(messageObject)
    }

    internal fun attachLongPressToPlusOneMenuItem(chatActivity: Any) {
        plusOneForwarder.attachLongPressToMenuItem(chatActivity)
    }

    internal fun forwardSelectedMessageToCurrentChat(chatActivity: Any) {
        plusOneForwarder.forwardSelectedMessageToCurrentChat(chatActivity)
    }

    internal fun addSaveStickerToMessageMenu(
        chatActivity: Any,
        args: Array<Any?>?,
    ) {
        stickerDownloadMenu.addToMessageMenu(chatActivity, args)
    }

    internal fun handleSaveStickerOption(
        chatActivity: Any,
        option: Int,
    ): Boolean = stickerDownloadMenu.handleSelectedOption(chatActivity, option)

    internal fun installContentPreviewStickerMenuHook(contentPreviewViewer: Any) {
        val runnable =
            try {
                stickerDownloadMenu.registerContentPreviewViewer(contentPreviewViewer)
            } catch (t: Throwable) {
                logError("Failed to resolve ContentPreviewViewer sticker menu runnable", t)
                return
            }
        if (!previewRunnableHookInstalled.compareAndSet(false, true)) {
            return
        }
        try {
            val run = runnable.javaClass.getDeclaredMethod("run")
            run.isAccessible = true
            hook(run).intercept(ContentPreviewShowSheetHooker())
            logInfo("Hooked sticker preview menu: $run")
        } catch (t: Throwable) {
            previewRunnableHookInstalled.set(false)
            logError("Failed to hook ContentPreviewViewer sticker menu runnable", t)
        }
    }

    internal fun patchContentPreviewStickerMenu(runnable: Any) {
        stickerDownloadMenu.patchContentPreviewStickerMenu(runnable)
    }

    internal fun handlePreviewPopupDismissed(popupWindow: Any) {
        stickerDownloadMenu.handlePreviewPopupDismissed(popupWindow)
    }

    internal fun installProfileIdDisplay(profileActivity: Any) {
        profileIdDisplay.install(profileActivity)
    }

    internal fun updateProfileIdDisplay(profileActivity: Any) {
        profileIdDisplay.update(profileActivity)
    }

    internal fun syncProfileIdDisplay(profileActivity: Any) {
        profileIdDisplay.sync(profileActivity)
    }

    @Throws(Exception::class)
    private fun markSelfDestructMediaAsReadWithoutDeleteTask(
        chatActivity: Any,
        messageObject: Any,
    ) {
        val dialogId =
            try {
                TelegramObfuscationResolver.getChatDialogId(chatActivity)
            } catch (_: Throwable) {
                0L
            }
        val currentEncryptedChat =
            TelegramObfuscationResolver.findChatCurrentEncryptedChatFieldOrNull(chatActivity.javaClass)?.get(chatActivity)
        val messagesController =
            findMethodOrNull(chatActivity.javaClass, "getMessagesController")?.invoke(chatActivity)
                ?: return
        val messageOwner = findField(messageObject.javaClass, "messageOwner").get(messageObject) ?: return
        val ttl = findField(messageOwner.javaClass, "ttl").getInt(messageOwner)
        val normalizedTtl = if (ttl == Int.MAX_VALUE) 0 else ttl

        if (currentEncryptedChat != null) {
            val randomId = findField(messageOwner.javaClass, "random_id").getLong(messageOwner)
            val markMessageAsRead =
                messagesController.javaClass.declaredMethods.firstOrNull { method ->
                    method.name == "markMessageAsRead" && method.parameterCount == 3
                } ?: throw IllegalStateException("MessagesController.markMessageAsRead(...) not found")
            markMessageAsRead.isAccessible = true
            val readReceiptTtl = if (normalizedTtl > 0) Int.MIN_VALUE else normalizedTtl
            markMessageAsRead.invoke(messagesController, dialogId, randomId, readReceiptTtl)
            return
        }

        val messageId = (findMethod(messageObject.javaClass, "getId").invoke(messageObject) as Number).toInt()
        val markMessageAsRead2 =
            messagesController.javaClass.declaredMethods.firstOrNull { method ->
                method.name == "markMessageAsRead2" && method.parameterCount == 6
            } ?: throw IllegalStateException("MessagesController.markMessageAsRead2(...) not found")
        markMessageAsRead2.isAccessible = true
        markMessageAsRead2.invoke(messagesController, dialogId, messageId, null, normalizedTtl, 0L, false)
    }

    private fun logInfo(message: String) {
        log(Log.INFO, TAG, message)
        android.util.Log.i(TAG, message)
    }

    internal fun logError(
        message: String,
        throwable: Throwable,
    ) {
        log(Log.ERROR, TAG, message, throwable)
        android.util.Log.e(TAG, message, throwable)
    }

    companion object {
        private const val TAG = "MxGram"
        private const val TARGET_PACKAGE = "org.telegram.messenger"

        @Volatile
        private var instance: TelegramHooksModule? = null

        internal fun currentModule(): TelegramHooksModule {
            val current = instance
            checkNotNull(current) { "Module instance is not ready" }
            return current
        }
    }
}
