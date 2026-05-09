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
            if (replyRepeater.tryRepeatPendingOrForced(
                    chatActivity,
                    selectedObject,
                    repeatNoForwardsMessage,
                )
            ) {
                return
            }
            if (repeatNoForwardsMessage) {
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
