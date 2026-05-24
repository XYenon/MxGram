package dev.xyenon.mxgram

import android.view.View
import java.util.WeakHashMap
import kotlin.math.min

internal const val OPTION_PLUS_ONE = 0x4D584701 // "MXG\u0001"

internal class PlusOneForwarder(
    private val logError: (String, Throwable) -> Unit,
) {
    private val plusOneMenuIndex = WeakHashMap<Any, Int>()
    private val noForwardsMessages = WeakHashMap<Any, Boolean>()
    private val replyRepeater = PlusOneReplyRepeater(logError)

    fun markNoForwardsMessage(messageObject: Any) {
        noForwardsMessages[messageObject] = true
    }

    @Suppress("UNCHECKED_CAST")
    fun addToMessageMenu(
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

    fun attachLongPressToMenuItem(chatActivity: Any) {
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
            item.setOnLongClickListener { view -> onPlusOneLongPressed(chatActivity, view) }
        } catch (t: Throwable) {
            logError("Failed to attach +1 long-press listener", t)
        }
    }

    @Suppress("UNCHECKED_CAST")
    fun forwardSelectedMessageToCurrentChat(chatActivity: Any) {
        try {
            val selectedObject = findField(chatActivity.javaClass, "selectedObject").get(chatActivity) ?: return
            val repeatNoForwardsMessage = noForwardsMessages.containsKey(selectedObject)
            val repeatNoForwardsPeer = isCurrentPeerNoForwards(chatActivity)
            val shouldRepeatWithoutForwarding = repeatNoForwardsMessage || repeatNoForwardsPeer
            if (replyRepeater.tryRepeatPendingOrForced(
                    chatActivity,
                    selectedObject,
                    shouldRepeatWithoutForwarding,
                )
            ) {
                return
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

            if (shouldRepeatWithoutForwarding) {
                repeatMessagesFromMyName(chatActivity, messages)
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

    private fun isCurrentPeerNoForwards(chatActivity: Any): Boolean {
        return try {
            chatActivity.javaClass.getMethod("isPeerNoForwards").invoke(chatActivity) == true
        } catch (_: Throwable) {
            false
        }
    }

    private fun repeatMessagesFromMyName(
        chatActivity: Any,
        messages: ArrayList<Any>,
    ) {
        val dialogId = chatActivity.javaClass.getMethod("getDialogId").invoke(chatActivity) as Long
        val sendMessagesHelper =
            chatActivity.javaClass.getMethod("getSendMessagesHelper").invoke(chatActivity) ?: return
        val processForwardFromMyName =
            sendMessagesHelper.javaClass.declaredMethods.firstOrNull { method ->
                method.name == "processForwardFromMyName" && method.parameterCount == 5
            } ?: return
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

        processForwardFromMyName.isAccessible = true
        for (message in messages) {
            processForwardFromMyName.invoke(
                sendMessagesHelper,
                message,
                dialogId,
                0L,
                monoForumPeer,
                suggestionParams,
            )
        }
    }

    private fun onPlusOneLongPressed(
        chatActivity: Any,
        menuItemView: View,
    ): Boolean {
        try {
            replyRepeater.prepareReply(chatActivity)
            // Reuse Telegram's normal click flow (it will close the menu and clear selection state).
            menuItemView.performClick()
            return true
        } catch (t: Throwable) {
            logError("Failed to handle +1 long-press", t)
            return false
        }
    }
}
