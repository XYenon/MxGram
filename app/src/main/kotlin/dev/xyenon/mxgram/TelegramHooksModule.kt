package dev.xyenon.mxgram

import android.content.Context
import android.os.SystemClock
import android.view.View
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface
import io.github.libxposed.api.annotations.AfterInvocation
import io.github.libxposed.api.annotations.BeforeInvocation
import io.github.libxposed.api.annotations.XposedHooker
import java.lang.reflect.Field
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.min

class TelegramHooksModule(
    base: XposedInterface,
    param: XposedModuleInterface.ModuleLoadedParam,
) : XposedModule(base, param) {
    private val hooksInstalled = AtomicBoolean(false)
    private val processName = param.processName
    private val plusOneMenuIndex = WeakHashMap<Any, Int>()

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

    private fun disableDoubleTapReaction(
        chatActivity: Any,
        classLoader: ClassLoader?,
    ) {
        try {
            val chatListView = findField(chatActivity.javaClass, "chatListView").get(chatActivity) ?: return

            val recyclerListViewClass =
                Class.forName("org.telegram.ui.Components.RecyclerListView", false, classLoader)
            val listenerField = findField(recyclerListViewClass, "onItemClickListenerExtended")
            val originalListener = listenerField.get(chatListView) ?: return
            if (Proxy.isProxyClass(originalListener.javaClass)) {
                val handler = Proxy.getInvocationHandler(originalListener)
                if (handler is DoubleTapDisablingHandler) {
                    return
                }
            }

            val listenerInterface =
                Class.forName(
                    "org.telegram.ui.Components.RecyclerListView\$OnItemClickListenerExtended",
                    false,
                    classLoader,
                )
            val proxy =
                Proxy.newProxyInstance(
                    classLoader,
                    arrayOf<Class<*>>(listenerInterface),
                    DoubleTapDisablingHandler(originalListener),
                )

            val setter = recyclerListViewClass.getDeclaredMethod("setOnItemClickListener", listenerInterface)
            setter.isAccessible = true
            setter.invoke(chatListView, proxy)
        } catch (t: Throwable) {
            logError("Failed to replace Telegram double-tap listener", t)
        }
    }

    @Throws(Exception::class)
    private fun neutralizePullingDownTarget(pullingDownDrawable: Any) {
        findField(pullingDownDrawable.javaClass, "emptyStub").setBoolean(pullingDownDrawable, true)
        findField(pullingDownDrawable.javaClass, "nextChat").set(pullingDownDrawable, null)
        findField(pullingDownDrawable.javaClass, "nextTopic").set(pullingDownDrawable, null)
        findField(pullingDownDrawable.javaClass, "nextDialogId").setLong(pullingDownDrawable, 0L)
    }

    @Throws(Exception::class)
    private fun clearGreetingStickerListener(chatGreetingsView: Any) {
        findField(chatGreetingsView.javaClass, "listener").set(chatGreetingsView, null)
    }

    private fun canSendToCurrentConversation(chatActivity: Any): Boolean {
        try {
            // If Telegram is showing the bottom overlay instead of the input field, we definitely can't
            // send right now.
            val bottomChannelButtonsLayout =
                findField(chatActivity.javaClass, "bottomChannelButtonsLayout").get(chatActivity)
            if (bottomChannelButtonsLayout is View) {
                // Telegram uses this overlay when the input field is not available.
                // NOTE: the normal state is usually INVISIBLE, not GONE.
                if (bottomChannelButtonsLayout.visibility == View.VISIBLE) {
                    return false
                }
            }

            // For user dialogs, blocked state is the most common reason why sending is disabled.
            try {
                if (findField(chatActivity.javaClass, "userBlocked").getBoolean(chatActivity)) {
                    return false
                }
            } catch (_: NoSuchFieldException) {
                // Ignore.
            }

            val currentChat =
                try {
                    findField(chatActivity.javaClass, "currentChat").get(chatActivity)
                } catch (_: NoSuchFieldException) {
                    null
                }
            if (currentChat == null) {
                // Private chat / other modes: rely on the overlay checks above.
                return true
            }

            val classLoader = chatActivity.javaClass.classLoader ?: return true
            val chatObjectClass = Class.forName("org.telegram.messenger.ChatObject", false, classLoader)

            // Not a member / left / kicked.
            if (invokeStaticBoolean(chatObjectClass, "isNotInChat", arrayOf(currentChat))) {
                return false
            }

            // Channels where we can't post.
            if (!invokeStaticBoolean(chatObjectClass, "canWriteToChat", arrayOf(currentChat))) {
                return false
            }

            // Muted by permissions / bans.
            if (!invokeStaticBoolean(chatObjectClass, "canSendMessages", arrayOf(currentChat))) {
                return false
            }

            // Closed forum topic (unless we can manage it).
            val forumTopic =
                try {
                    findField(chatActivity.javaClass, "forumTopic").get(chatActivity)
                } catch (_: NoSuchFieldException) {
                    null
                }
            if (forumTopic != null) {
                val closed =
                    try {
                        findField(forumTopic.javaClass, "closed").getBoolean(forumTopic)
                    } catch (_: NoSuchFieldException) {
                        false
                    }
                if (closed) {
                    val currentAccount =
                        try {
                            findField(chatActivity.javaClass, "currentAccount").getInt(chatActivity)
                        } catch (_: NoSuchFieldException) {
                            0
                        }

                    val canManageTopic =
                        invokeStaticBooleanOrNull(
                            chatObjectClass,
                            "canManageTopic",
                            arrayOf(currentAccount, currentChat, forumTopic),
                        )
                    if (canManageTopic != null && !canManageTopic) {
                        return false
                    }
                }
            }

            return true
        } catch (_: Throwable) {
            // Fail open: keep the option available if Telegram internals change.
            return true
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun addPlusOneToMessageMenu(
        chatActivity: Any,
        args: Array<Any?>?,
    ) {
        if (!canSendToCurrentConversation(chatActivity)) {
            return
        }
        if (args == null || args.size < 4) {
            return
        }
        val iconsRaw = args[1]
        val itemsRaw = args[2]
        val optionsRaw = args[3]
        if (iconsRaw !is ArrayList<*> || itemsRaw !is ArrayList<*> || optionsRaw !is ArrayList<*>) {
            return
        }
        val icons = iconsRaw as ArrayList<Int>
        val items = itemsRaw as ArrayList<CharSequence>
        val options = optionsRaw as ArrayList<Int>

        if (options.contains(OPTION_PLUS_ONE)) {
            plusOneMenuIndex[chatActivity] = options.indexOf(OPTION_PLUS_ONE)
            return
        }

        val optionForward = getStaticIntFieldValue(chatActivity.javaClass, "OPTION_FORWARD", 2)
        val forwardIndex = options.indexOf(optionForward)
        if (forwardIndex < 0) {
            return
        }

        val insertIndex = min(forwardIndex + 1, options.size)
        val forwardIcon = if (forwardIndex < icons.size) icons[forwardIndex] else 0
        val plusIcon =
            resolveTelegramDrawable(
                chatActivity.javaClass.classLoader,
                "msg_filled_plus",
                forwardIcon,
            )

        options.add(insertIndex, OPTION_PLUS_ONE)
        items.add(insertIndex, "+1")
        icons.add(min(insertIndex, icons.size), plusIcon)

        plusOneMenuIndex[chatActivity] = insertIndex
    }

    private fun attachLongPressToPlusOneMenuItem(chatActivity: Any) {
        val index = plusOneMenuIndex.remove(chatActivity) ?: return
        try {
            val itemsRaw = findField(chatActivity.javaClass, "scrimPopupWindowItems").get(chatActivity)
            if (itemsRaw !is Array<*>) {
                return
            }
            if (index < 0 || index >= itemsRaw.size) {
                return
            }
            val item = itemsRaw[index]
            if (item !is View) {
                return
            }
            item.setOnLongClickListener { view -> module().onPlusOneLongPressed(chatActivity, view) }
        } catch (t: Throwable) {
            logError("Failed to attach +1 long-press listener", t)
        }
    }

    private fun onPlusOneLongPressed(
        chatActivity: Any,
        menuItemView: View,
    ): Boolean {
        try {
            val pending = buildPendingPlusOneReply(chatActivity)
            pendingPlusOneReply.set(pending)
            // Reuse Telegram's normal click flow (it will close the menu and clear selection state).
            menuItemView.performClick()
            return true
        } catch (t: Throwable) {
            logError("Failed to handle +1 long-press", t)
            return false
        }
    }

    private fun buildPendingPlusOneReply(chatActivity: Any): PendingPlusOneReply? {
        try {
            val selectedObject = findField(chatActivity.javaClass, "selectedObject").get(chatActivity) ?: return null

            // Only support single-message replies for now.
            val selectedObjectGroup =
                findField(chatActivity.javaClass, "selectedObjectGroup").get(chatActivity)
            if (selectedObjectGroup != null) {
                return null
            }

            val replyMsgId = selectedObject.javaClass.getMethod("getReplyMsgId").invoke(selectedObject) as Int
            if (replyMsgId <= 0) {
                return null
            }

            // Only handle replies within the same dialog (reply_to_peer_id should be null).
            val messageOwner = findField(selectedObject.javaClass, "messageOwner").get(selectedObject)
            if (messageOwner != null) {
                val replyHeader =
                    try {
                        findField(messageOwner.javaClass, "reply_to").get(messageOwner)
                    } catch (_: NoSuchFieldException) {
                        null
                    }
                if (replyHeader != null) {
                    val replyToPeerId =
                        try {
                            findField(replyHeader.javaClass, "reply_to_peer_id").get(replyHeader)
                        } catch (_: NoSuchFieldException) {
                            null
                        }
                    if (replyToPeerId != null) {
                        return null
                    }
                }
            }

            val replyToMsg = findField(selectedObject.javaClass, "replyMessageObject").get(selectedObject) ?: return null
            val selectedMsgId = selectedObject.javaClass.getMethod("getId").invoke(selectedObject) as Int
            return PendingPlusOneReply(replyToMsg, selectedMsgId)
        } catch (_: Throwable) {
            return null
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun forwardSelectedMessageToCurrentChat(chatActivity: Any) {
        try {
            val selectedObject = findField(chatActivity.javaClass, "selectedObject").get(chatActivity) ?: return

            // Reply case: Telegram doesn't support replying with a forwarded (quoted) message.
            // When user long-presses +1 on a reply, we re-send the content as a normal message and
            // keep the reply target.
            val pending = pendingPlusOneReply.getAndSet(null)
            if (pending != null && SystemClock.uptimeMillis() - pending.createdAtUptimeMs <= PLUS_ONE_REPLY_TTL_MS) {
                try {
                    val selectedId = selectedObject.javaClass.getMethod("getId").invoke(selectedObject) as Int
                    if (selectedId == pending.selectedMsgId && pending.replyToMsg != null) {
                        if (repeatSelectedMessageAsReply(chatActivity, selectedObject, pending.replyToMsg)) {
                            return
                        }
                    }
                } catch (_: Throwable) {
                    // Ignore.
                }
            }

            val selectedObjectGroup =
                findField(chatActivity.javaClass, "selectedObjectGroup").get(chatActivity)

            val messages = ArrayList<Any>()
            if (selectedObjectGroup != null) {
                val groupMessages = findField(selectedObjectGroup.javaClass, "messages").get(selectedObjectGroup)
                if (groupMessages is ArrayList<*>) {
                    messages.addAll(groupMessages as ArrayList<Any>)
                }
            } else {
                messages.add(selectedObject)
            }
            if (messages.isEmpty()) {
                return
            }

            // Prefer Telegram's internal sending path for forwarding inside the current chat.
            val forwardMessages =
                chatActivity.javaClass.declaredMethods.firstOrNull { method ->
                    method.name == "forwardMessages" && method.parameterCount == 6
                }
            if (forwardMessages != null) {
                forwardMessages.isAccessible = true
                forwardMessages.invoke(chatActivity, messages, false, false, true, 0, 0L)
                return
            }

            // Fallback: show the forward panel (user still needs to tap send).
            val showFieldPanelForForward =
                chatActivity.javaClass.getMethod(
                    "showFieldPanelForForward",
                    java.lang.Boolean.TYPE,
                    ArrayList::class.java,
                )
            showFieldPanelForForward.invoke(chatActivity, true, messages)
        } catch (t: Throwable) {
            logError("Failed to +1 forward message", t)
        }
    }

    private fun repeatSelectedMessageAsReply(
        chatActivity: Any,
        selectedObject: Any?,
        replyToMsg: Any?,
    ): Boolean {
        if (replyToMsg == null || selectedObject == null) {
            return false
        }
        try {
            val dialogId = chatActivity.javaClass.getMethod("getDialogId").invoke(chatActivity) as Long
            val replyToTopMsg = chatActivity.javaClass.getMethod("getThreadMessage").invoke(chatActivity)
            val sendMessagesHelper =
                chatActivity.javaClass.getMethod("getSendMessagesHelper").invoke(chatActivity) ?: return false

            val isSticker =
                java.lang.Boolean.TRUE == selectedObject.javaClass.getMethod("isAnyKindOfSticker").invoke(selectedObject)
            if (isSticker) {
                val document = selectedObject.javaClass.getMethod("getDocument").invoke(selectedObject) ?: return false
                val sendSticker =
                    sendMessagesHelper.javaClass.declaredMethods.firstOrNull { method ->
                        method.name == "sendSticker" && method.parameterCount == 18
                    } ?: return false

                val monoForumPeer =
                    try {
                        chatActivity.javaClass.getMethod("getSendMonoForumPeerId").invoke(chatActivity) as Long
                    } catch (_: NoSuchMethodException) {
                        0L
                    }
                val suggestionParams =
                    try {
                        chatActivity.javaClass.getMethod("getSendMessageSuggestionParams").invoke(chatActivity)
                    } catch (_: NoSuchMethodException) {
                        null
                    }

                sendSticker.isAccessible = true
                sendSticker.invoke(
                    sendMessagesHelper,
                    document,
                    null,
                    dialogId,
                    replyToMsg,
                    replyToTopMsg,
                    null,
                    null,
                    null,
                    true,
                    0,
                    0,
                    false,
                    null,
                    null,
                    0,
                    0L,
                    monoForumPeer,
                    suggestionParams,
                )
                return true
            }

            val messageOwner = findField(selectedObject.javaClass, "messageOwner").get(selectedObject) ?: return false
            val messageRaw = findField(messageOwner.javaClass, "message").get(messageOwner)
            if (messageRaw !is String || messageRaw.isEmpty()) {
                return false
            }

            val classLoader = chatActivity.javaClass.classLoader ?: return false
            val sendMessageParamsClass =
                Class.forName(
                    "org.telegram.messenger.SendMessagesHelper\$SendMessageParams",
                    false,
                    classLoader,
                )
            val of = sendMessageParamsClass.getDeclaredMethod("of", String::class.java, java.lang.Long.TYPE)
            of.isAccessible = true
            val params = of.invoke(null, messageRaw, dialogId)
            findField(params.javaClass, "replyToMsg").set(params, replyToMsg)
            findField(params.javaClass, "replyToTopMsg").set(params, replyToTopMsg)
            findField(params.javaClass, "notify").setBoolean(params, true)

            val sendMessage =
                sendMessagesHelper.javaClass.declaredMethods.firstOrNull { method ->
                    method.name == "sendMessage" &&
                        method.parameterCount == 1 &&
                        sendMessageParamsClass == method.parameterTypes[0]
                } ?: return false
            sendMessage.isAccessible = true
            sendMessage.invoke(sendMessagesHelper, params)
            return true
        } catch (t: Throwable) {
            logError("Failed to +1 repeat-reply message", t)
            return false
        }
    }

    private fun logInfo(message: String) {
        log("$TAG: $message")
    }

    private fun logError(
        message: String,
        throwable: Throwable,
    ) {
        log("$TAG: $message", throwable)
    }

    @XposedHooker
    class BlockAnimateToNextChatHooker : XposedInterface.Hooker {
        companion object {
            @JvmStatic
            @BeforeInvocation
            fun before(callback: XposedInterface.BeforeHookCallback) {
                callback.returnAndSkip(null)
            }
        }
    }

    @XposedHooker
    class CreateViewHooker : XposedInterface.Hooker {
        companion object {
            @JvmStatic
            @AfterInvocation
            fun after(callback: XposedInterface.AfterHookCallback) {
                val module = module()
                val chatActivity = callback.thisObject ?: return
                module.disableDoubleTapReaction(chatActivity, chatActivity.javaClass.classLoader)
            }
        }
    }

    @XposedHooker
    class SelectReactionHooker : XposedInterface.Hooker {
        companion object {
            @JvmStatic
            @BeforeInvocation
            fun before(callback: XposedInterface.BeforeHookCallback) {
                val args = callback.args
                if (args.size > 7 && java.lang.Boolean.TRUE == args[7]) {
                    callback.returnAndSkip(null)
                }
            }
        }
    }

    @XposedHooker
    class DisableGreetingStickerHooker : XposedInterface.Hooker {
        companion object {
            @JvmStatic
            @BeforeInvocation
            @Throws(Throwable::class)
            fun before(callback: XposedInterface.BeforeHookCallback) {
                val chatGreetingsView = callback.thisObject ?: return
                module().clearGreetingStickerListener(chatGreetingsView)
                callback.returnAndSkip(null)
            }
        }
    }

    @XposedHooker
    class PullingDownTargetHooker : XposedInterface.Hooker {
        companion object {
            @JvmStatic
            @AfterInvocation
            @Throws(Throwable::class)
            fun after(callback: XposedInterface.AfterHookCallback) {
                val pullingDownDrawable = callback.thisObject ?: return
                module().neutralizePullingDownTarget(pullingDownDrawable)
            }
        }
    }

    @XposedHooker
    class FillMessageMenuHooker : XposedInterface.Hooker {
        companion object {
            @JvmStatic
            @AfterInvocation
            fun after(callback: XposedInterface.AfterHookCallback) {
                val module = module()
                val chatActivity = callback.thisObject ?: return
                module.addPlusOneToMessageMenu(chatActivity, callback.args)
            }
        }
    }

    @XposedHooker
    class CreateMenuHooker : XposedInterface.Hooker {
        companion object {
            @JvmStatic
            @AfterInvocation
            fun after(callback: XposedInterface.AfterHookCallback) {
                val result = callback.result
                if (result !is Boolean || result != true) {
                    return
                }
                val chatActivity = callback.thisObject ?: return
                module().attachLongPressToPlusOneMenuItem(chatActivity)
            }
        }
    }

    @XposedHooker
    class ProcessSelectedOptionHooker : XposedInterface.Hooker {
        companion object {
            @JvmStatic
            @BeforeInvocation
            fun before(callback: XposedInterface.BeforeHookCallback) {
                val args = callback.args
                if (args.isEmpty() || args[0] !is Int) {
                    return
                }
                val option = args[0] as Int
                if (option != OPTION_PLUS_ONE) {
                    return
                }
                val chatActivity = callback.thisObject ?: return
                module().forwardSelectedMessageToCurrentChat(chatActivity)
                // Do not skip: let Telegram close the menu & clear internal selection state.
            }
        }
    }

    private data class PendingPlusOneReply(
        val replyToMsg: Any?,
        val selectedMsgId: Int,
        val createdAtUptimeMs: Long = SystemClock.uptimeMillis(),
    )

    private class DoubleTapDisablingHandler(
        private val original: Any,
    ) : InvocationHandler {
        @Throws(Throwable::class)
        override fun invoke(
            proxy: Any,
            method: Method,
            args: Array<Any?>?,
        ): Any? {
            val name = method.name
            if (name == "hasDoubleTap") {
                return false
            }
            if (name == "onDoubleTap") {
                return null
            }
            if (method.declaringClass == Any::class.java) {
                if (name == "toString") {
                    return "$original[doubleTapDisabled]"
                }
                if (name == "hashCode") {
                    return original.hashCode()
                }
                if (name == "equals") {
                    return proxy === args?.get(0)
                }
            }
            return method.invoke(original, *(args ?: emptyArray<Any?>()))
        }

        override fun equals(other: Any?): Boolean = this === other

        override fun hashCode(): Int = original.hashCode()
    }

    private companion object {
        private const val TAG = "MxGram"
        private const val TARGET_PACKAGE = "org.telegram.messenger"
        private const val OPTION_PLUS_ONE = 0x4D584701 // "MXG\u0001"
        private const val PLUS_ONE_REPLY_TTL_MS = 60_000L

        private val pendingPlusOneReply = AtomicReference<PendingPlusOneReply?>(null)

        @Volatile
        private var instance: TelegramHooksModule? = null

        private fun getStaticIntFieldValue(
            type: Class<*>,
            name: String,
            fallback: Int,
        ): Int =
            try {
                val field = type.getDeclaredField(name)
                field.isAccessible = true
                field.getInt(null)
            } catch (_: Throwable) {
                fallback
            }

        private fun resolveTelegramDrawable(
            classLoader: ClassLoader?,
            drawableName: String,
            fallback: Int,
        ): Int {
            if (classLoader == null) {
                return fallback
            }
            return try {
                val drawableClass = Class.forName("org.telegram.messenger.R\$drawable", false, classLoader)
                val field = drawableClass.getDeclaredField(drawableName)
                field.isAccessible = true
                field.getInt(null)
            } catch (_: Throwable) {
                fallback
            }
        }

        @Throws(Exception::class)
        private fun invokeStaticBoolean(
            type: Class<*>,
            name: String,
            args: Array<Any?>?,
        ): Boolean {
            val result = invokeStaticBooleanOrNull(type, name, args)
            return result == true
        }

        @Throws(Exception::class)
        private fun invokeStaticBooleanOrNull(
            type: Class<*>,
            name: String,
            args: Array<Any?>?,
        ): Boolean? {
            val paramCount = args?.size ?: 0
            for (method in type.declaredMethods) {
                if (method.name != name || method.parameterCount != paramCount) {
                    continue
                }
                // Disambiguate ChatObject.canManageTopic overloads: we want the one that takes a topic
                // object (not a long topicId).
                if (name == "canManageTopic" && paramCount == 3 && method.parameterTypes[2] == Long::class.javaPrimitiveType) {
                    continue
                }
                method.isAccessible = true
                val result = method.invoke(null, *(args ?: emptyArray<Any?>()))
                if (result is Boolean) {
                    return result
                }
            }
            return null
        }

        @Throws(NoSuchFieldException::class)
        private fun findField(
            type: Class<*>,
            name: String,
        ): Field {
            var current: Class<*>? = type
            while (current != null) {
                try {
                    val field = current.getDeclaredField(name)
                    field.isAccessible = true
                    return field
                } catch (_: NoSuchFieldException) {
                    current = current.superclass
                }
            }
            throw NoSuchFieldException(type.name + '#' + name)
        }

        private fun module(): TelegramHooksModule {
            val current = instance
            checkNotNull(current) { "Module instance is not ready" }
            return current
        }
    }
}
