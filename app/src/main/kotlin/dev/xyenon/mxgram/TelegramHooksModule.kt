package dev.xyenon.mxgram

import android.content.Context
import android.util.Log
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface
import java.util.concurrent.atomic.AtomicBoolean

class TelegramHooksModule : XposedModule() {
    private val hooksInstalled = AtomicBoolean(false)
    private var processName: String = ""
    private val plusOneForwarder = PlusOneForwarder { message, throwable -> logError(message, throwable) }
    private val profileIdDisplay = ProfileIdDisplay { message, throwable -> logError(message, throwable) }

    override fun onModuleLoaded(param: XposedModuleInterface.ModuleLoadedParam) {
        instance = this
        processName = param.processName
    }

    override fun onPackageReady(param: XposedModuleInterface.PackageReadyParam) {
        if (param.packageName != TARGET_PACKAGE) {
            return
        }
        if (!hooksInstalled.compareAndSet(false, true)) {
            return
        }

        try {
            installHooks(param.classLoader)
            logInfo("Telegram hooks installed in $processName")
        } catch (t: Throwable) {
            hooksInstalled.set(false)
            logError("Failed to install Telegram hooks", t)
        }
    }

    @Throws(Exception::class)
    private fun installHooks(classLoader: ClassLoader) {
        val chatActivityClass = Class.forName("org.telegram.ui.ChatActivity", false, classLoader)
        val messagesControllerClass =
            Class.forName("org.telegram.messenger.MessagesController", false, classLoader)
        val messageObjectClass = Class.forName("org.telegram.messenger.MessageObject", false, classLoader)
        val tlrpcMessageClass = Class.forName("org.telegram.tgnet.TLRPC\$Message", false, classLoader)
        val profileActivityClass = Class.forName("org.telegram.ui.ProfileActivity", false, classLoader)
        val chatGreetingsViewClass =
            Class.forName("org.telegram.ui.Components.ChatGreetingsView", false, classLoader)
        val pullingDownDrawableClass =
            Class.forName("org.telegram.ui.ChatPullingDownDrawable", false, classLoader)

        hookNoForwardsRestrictions(messagesControllerClass)
        hookMessageNoForwardsFlag(messageObjectClass, tlrpcMessageClass)
        hookSelfDestructMediaProtection(chatActivityClass, messagesControllerClass)
        hookAnimateToNextChat(chatActivityClass)
        hookCreateView(chatActivityClass)
        hookGreetingStickerSend(chatGreetingsViewClass)
        hookSelectReaction(chatActivityClass)
        hookPullingDownTargets(pullingDownDrawableClass)
        hookPlusOneForward(chatActivityClass)
        hookProfileIdDisplay(profileActivityClass)
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
        try {
            val sendSecretMessageRead =
                chatActivityClass.declaredMethods.firstOrNull { method ->
                    method.name == "sendSecretMessageRead" && method.parameterCount == 2
                } ?: throw IllegalStateException("ChatActivity.sendSecretMessageRead(...) not found")
            sendSecretMessageRead.isAccessible = true
            hook(sendSecretMessageRead).intercept(SecretMessageReadHooker())

            val sendSecretMediaDelete =
                chatActivityClass.declaredMethods.firstOrNull { method ->
                    method.name == "sendSecretMediaDelete" && method.parameterCount == 1
                } ?: throw IllegalStateException("ChatActivity.sendSecretMediaDelete(...) not found")
            sendSecretMediaDelete.isAccessible = true
            hook(sendSecretMediaDelete).intercept(SecretMediaDeleteHooker())

            val markMessageAsRead2 =
                messagesControllerClass.declaredMethods.firstOrNull { method ->
                    method.name == "markMessageAsRead2" && method.parameterCount == 6
                } ?: throw IllegalStateException("MessagesController.markMessageAsRead2(...) not found")
            markMessageAsRead2.isAccessible = true
            hook(markMessageAsRead2).intercept(PreventDeleteTaskOnContentReadHooker())

            val markMessageAsRead =
                messagesControllerClass.declaredMethods.firstOrNull { method ->
                    method.name == "markMessageAsRead" && method.parameterCount == 3
                } ?: throw IllegalStateException("MessagesController.markMessageAsRead(...) not found")
            markMessageAsRead.isAccessible = true
            hook(markMessageAsRead).intercept(PreventDeleteTaskOnSecretChatReadHooker())

            val createDeleteShowOnceTask =
                messagesControllerClass.declaredMethods.firstOrNull { method ->
                    method.name == "createDeleteShowOnceTask" && method.parameterCount == 2
                } ?: throw IllegalStateException("MessagesController.createDeleteShowOnceTask(...) not found")
            createDeleteShowOnceTask.isAccessible = true
            hook(createDeleteShowOnceTask).intercept(BlockCreateDeleteShowOnceTaskHooker())

            val doDeleteShowOnceTask =
                messagesControllerClass.declaredMethods.firstOrNull { method ->
                    method.name == "doDeleteShowOnceTask" && method.parameterCount == 3
                } ?: throw IllegalStateException("MessagesController.doDeleteShowOnceTask(...) not found")
            doDeleteShowOnceTask.isAccessible = true
            hook(doDeleteShowOnceTask).intercept(BlockDoDeleteShowOnceTaskHooker())
        } catch (t: Throwable) {
            logError("Failed to install self-destruct media protection hook", t)
        }
    }

    private fun hookProfileIdDisplay(profileActivityClass: Class<*>) {
        try {
            val createView = profileActivityClass.getDeclaredMethod("createView", Context::class.java)
            createView.isAccessible = true
            hook(createView).intercept(ProfileCreateViewHooker())

            val updateProfileData =
                profileActivityClass.getDeclaredMethod("updateProfileData", java.lang.Boolean.TYPE)
            updateProfileData.isAccessible = true
            hook(updateProfileData).intercept(ProfileUpdateDataHooker())

            val needLayout = profileActivityClass.getDeclaredMethod("needLayout", java.lang.Boolean.TYPE)
            needLayout.isAccessible = true
            hook(needLayout).intercept(ProfileLayoutHooker())

            val setAvatarExpandProgress =
                profileActivityClass.getDeclaredMethod("setAvatarExpandProgress", java.lang.Float.TYPE)
            setAvatarExpandProgress.isAccessible = true
            hook(setAvatarExpandProgress).intercept(ProfileLayoutHooker())
        } catch (t: Throwable) {
            logError("Failed to install profile ID display hook", t)
        }
    }

    @Throws(Exception::class)
    private fun hookPlusOneForward(chatActivityClass: Class<*>) {
        // fillMessageMenu arity 4: icons, items, options (before 12.8.1 / 6916).
        // fillMessageMenu arity 5: primaryMessage, icons, items, options (12.8.1 / 6916).
        val fillMessageMenu =
            findDeclaredMethodByNameAndArity(chatActivityClass, "fillMessageMenu", 4, 5)
                ?: throw IllegalStateException("ChatActivity.fillMessageMenu(...) not found")
        fillMessageMenu.isAccessible = true
        hook(fillMessageMenu).intercept(FillMessageMenuHooker())

        val processSelectedOption =
            chatActivityClass.getDeclaredMethod("processSelectedOption", java.lang.Integer.TYPE)
        processSelectedOption.isAccessible = true
        hook(processSelectedOption).intercept(ProcessSelectedOptionHooker())

        val createMenu =
            chatActivityClass.declaredMethods.firstOrNull { method ->
                method.name == "createMenu" && method.parameterCount == 8
            } ?: throw IllegalStateException("ChatActivity.createMenu(...) not found")
        createMenu.isAccessible = true
        hook(createMenu).intercept(CreateMenuHooker())
    }

    @Throws(NoSuchMethodException::class)
    private fun hookAnimateToNextChat(chatActivityClass: Class<*>) {
        val method = chatActivityClass.getDeclaredMethod("animateToNextChat")
        method.isAccessible = true
        hook(method).intercept(BlockAnimateToNextChatHooker())
    }

    @Throws(NoSuchMethodException::class)
    private fun hookCreateView(chatActivityClass: Class<*>) {
        val method = chatActivityClass.getDeclaredMethod("createView", Context::class.java)
        method.isAccessible = true
        hook(method).intercept(CreateViewHooker())
    }

    @Throws(NoSuchMethodException::class)
    private fun hookGreetingStickerSend(chatGreetingsViewClass: Class<*>) {
        val listenerInterface =
            chatGreetingsViewClass.declaredClasses.firstOrNull { innerClass ->
                innerClass.simpleName == "Listener"
            } ?: throw IllegalStateException("ChatGreetingsView.Listener not found")

        val method = chatGreetingsViewClass.getDeclaredMethod("setListener", listenerInterface)
        method.isAccessible = true
        hook(method).intercept(DisableGreetingStickerHooker())
    }

    private fun hookSelectReaction(chatActivityClass: Class<*>) {
        for (method in chatActivityClass.declaredMethods) {
            if (method.name != "selectReaction" || method.parameterCount != 11) {
                continue
            }
            method.isAccessible = true
            hook(method).intercept(SelectReactionHooker())
            return
        }
        throw IllegalStateException("ChatActivity.selectReaction(...) not found")
    }

    private fun hookPullingDownTargets(pullingDownDrawableClass: Class<*>) {
        for (method in pullingDownDrawableClass.declaredMethods) {
            val isUpdateDialog =
                method.name == "updateDialog" && (method.parameterCount == 0 || method.parameterCount == 1)
            val isUpdateTopic = method.name == "updateTopic" && method.parameterCount == 0
            if (!isUpdateDialog && !isUpdateTopic) {
                continue
            }
            method.isAccessible = true
            hook(method).intercept(PullingDownTargetHooker())
        }
    }

    internal fun disableDoubleTapReaction(
        chatActivity: Any,
        classLoader: ClassLoader?,
    ) {
        DoubleTapReactionBlocker.disable(chatActivity, classLoader, ::logError)
    }

    @Throws(Exception::class)
    internal fun neutralizePullingDownTarget(pullingDownDrawable: Any) {
        findField(pullingDownDrawable.javaClass, "emptyStub").setBoolean(pullingDownDrawable, true)
        findField(pullingDownDrawable.javaClass, "nextChat").set(pullingDownDrawable, null)
        findField(pullingDownDrawable.javaClass, "nextTopic").set(pullingDownDrawable, null)
        findField(pullingDownDrawable.javaClass, "nextDialogId").setLong(pullingDownDrawable, 0L)
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

    @Throws(Exception::class)
    internal fun clearGreetingStickerListener(chatGreetingsView: Any) {
        findField(chatGreetingsView.javaClass, "listener").set(chatGreetingsView, null)
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
        val dialogId = findField(chatActivity.javaClass, "dialog_id").getLong(chatActivity)
        val currentEncryptedChat = findField(chatActivity.javaClass, "currentEncryptedChat").get(chatActivity)
        val messagesController =
            findMethod(chatActivity.javaClass, "getMessagesController").invoke(chatActivity)
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
    }

    internal fun logError(
        message: String,
        throwable: Throwable,
    ) {
        log(Log.ERROR, TAG, message, throwable)
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
