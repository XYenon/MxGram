package dev.xyenon.mxgram

import android.content.Context
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface
import java.util.concurrent.atomic.AtomicBoolean

class TelegramHooksModule(
    base: XposedInterface,
    param: XposedModuleInterface.ModuleLoadedParam,
) : XposedModule(base, param) {
    private val hooksInstalled = AtomicBoolean(false)
    private val processName = param.processName
    private val plusOneForwarder = PlusOneForwarder { message, throwable -> logError(message, throwable) }

    init {
        instance = this
    }

    override fun onPackageLoaded(param: XposedModuleInterface.PackageLoadedParam) {
        if (param.packageName != TARGET_PACKAGE) {
            return
        }
        if (!hooksInstalled.compareAndSet(false, true)) {
            return
        }

        try {
            val classLoader = param.classLoader ?: param.defaultClassLoader
            checkNotNull(classLoader) { "Telegram classloader is null" }
            installHooks(classLoader)
            logInfo("Telegram hooks installed in $processName")
        } catch (t: Throwable) {
            hooksInstalled.set(false)
            logError("Failed to install Telegram hooks", t)
        }
    }

    @Throws(Exception::class)
    private fun installHooks(classLoader: ClassLoader) {
        val chatActivityClass = Class.forName("org.telegram.ui.ChatActivity", false, classLoader)
        val chatGreetingsViewClass =
            Class.forName("org.telegram.ui.Components.ChatGreetingsView", false, classLoader)
        val pullingDownDrawableClass =
            Class.forName("org.telegram.ui.ChatPullingDownDrawable", false, classLoader)

        hookAnimateToNextChat(chatActivityClass)
        hookCreateView(chatActivityClass)
        hookGreetingStickerSend(chatGreetingsViewClass)
        hookSelectReaction(chatActivityClass)
        hookPullingDownTargets(pullingDownDrawableClass)
        hookPlusOneForward(chatActivityClass)
    }

    @Throws(Exception::class)
    private fun hookPlusOneForward(chatActivityClass: Class<*>) {
        val fillMessageMenu =
            chatActivityClass.declaredMethods.firstOrNull { method ->
                method.name == "fillMessageMenu" && method.parameterCount == 4
            } ?: throw IllegalStateException("ChatActivity.fillMessageMenu(...) not found")
        fillMessageMenu.isAccessible = true
        hook(fillMessageMenu, FillMessageMenuHooker::class.java)

        val processSelectedOption =
            chatActivityClass.getDeclaredMethod("processSelectedOption", java.lang.Integer.TYPE)
        processSelectedOption.isAccessible = true
        hook(processSelectedOption, ProcessSelectedOptionHooker::class.java)

        val createMenu =
            chatActivityClass.declaredMethods.firstOrNull { method ->
                method.name == "createMenu" && method.parameterCount == 8
            } ?: throw IllegalStateException("ChatActivity.createMenu(...) not found")
        createMenu.isAccessible = true
        hook(createMenu, CreateMenuHooker::class.java)
    }

    @Throws(NoSuchMethodException::class)
    private fun hookAnimateToNextChat(chatActivityClass: Class<*>) {
        val method = chatActivityClass.getDeclaredMethod("animateToNextChat")
        method.isAccessible = true
        hook(method, BlockAnimateToNextChatHooker::class.java)
    }

    @Throws(NoSuchMethodException::class)
    private fun hookCreateView(chatActivityClass: Class<*>) {
        val method = chatActivityClass.getDeclaredMethod("createView", Context::class.java)
        method.isAccessible = true
        hook(method, CreateViewHooker::class.java)
    }

    @Throws(NoSuchMethodException::class)
    private fun hookGreetingStickerSend(chatGreetingsViewClass: Class<*>) {
        val listenerInterface =
            chatGreetingsViewClass.declaredClasses.firstOrNull { innerClass ->
                innerClass.simpleName == "Listener"
            } ?: throw IllegalStateException("ChatGreetingsView.Listener not found")

        val method = chatGreetingsViewClass.getDeclaredMethod("setListener", listenerInterface)
        method.isAccessible = true
        hook(method, DisableGreetingStickerHooker::class.java)
    }

    private fun hookSelectReaction(chatActivityClass: Class<*>) {
        for (method in chatActivityClass.declaredMethods) {
            if (method.name != "selectReaction" || method.parameterCount != 11) {
                continue
            }
            method.isAccessible = true
            hook(method, SelectReactionHooker::class.java)
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
            hook(method, PullingDownTargetHooker::class.java)
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
    internal fun clearGreetingStickerListener(chatGreetingsView: Any) {
        findField(chatGreetingsView.javaClass, "listener").set(chatGreetingsView, null)
    }

    internal fun addPlusOneToMessageMenu(
        chatActivity: Any,
        args: Array<Any?>?,
    ) {
        plusOneForwarder.addToMessageMenu(chatActivity, args)
    }

    internal fun attachLongPressToPlusOneMenuItem(chatActivity: Any) {
        plusOneForwarder.attachLongPressToMenuItem(chatActivity)
    }

    internal fun forwardSelectedMessageToCurrentChat(chatActivity: Any) {
        plusOneForwarder.forwardSelectedMessageToCurrentChat(chatActivity)
    }

    private fun logInfo(message: String) {
        log("$TAG: $message")
    }

    internal fun logError(
        message: String,
        throwable: Throwable,
    ) {
        log("$TAG: $message", throwable)
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
