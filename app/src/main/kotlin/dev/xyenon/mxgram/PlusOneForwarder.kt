package dev.xyenon.mxgram

import android.util.Log
import android.view.View
import java.util.WeakHashMap
import kotlin.math.min

internal const val OPTION_PLUS_ONE = 0x4D584701 // "MXG\u0001"

internal class PlusOneForwarder(
    private val logError: (String, Throwable) -> Unit,
) {
    private val plusOneMenuIndex = WeakHashMap<Any, Int>()
    private val replyRepeater = PlusOneReplyRepeater(logError)

    fun markNoForwardsMessage(messageObject: Any) {
        PlusOneNoForwardsTracker.mark(messageObject)
    }

    @Suppress("UNCHECKED_CAST")
    fun addToMessageMenu(
        chatActivity: Any,
        args: Array<Any?>?,
    ) {
        if (!canSendToCurrentConversation(chatActivity)) {
            return
        }
        if (args == null || args.size < 3) {
            return
        }
        // Hook args end with the parallel icons, items, and options lists.
        val (iconsRaw, itemsRaw, optionsRaw) = resolveFillMessageMenuLists(args) ?: return
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
            val itemsRaw = TelegramObfuscationResolver.findChatScrimPopupWindowItemsFieldOrNull(chatActivity.javaClass)?.get(chatActivity)
            if (itemsRaw !is Array<*>) {
                Log.w("MxGram", "attachLongPressToMenuItem: itemsRaw is not Array")
                return
            }
            var targetItem: View? = null
            if (index in itemsRaw.indices && isPlusOneItem(itemsRaw[index])) {
                targetItem = itemsRaw[index] as? View
            }
            if (targetItem == null) {
                targetItem = itemsRaw.filterIsInstance<View>().firstOrNull { isPlusOneItem(it) }
            }
            if (targetItem == null && index in itemsRaw.indices) {
                targetItem = itemsRaw[index] as? View
            }
            if (targetItem == null) {
                Log.w("MxGram", "attachLongPressToMenuItem: targetItem not found (index=$index, size=${itemsRaw.size})")
                return
            }
            Log.i("MxGram", "attachLongPressToMenuItem: attached long-click listener to +1 item")
            targetItem.setOnLongClickListener { view -> onPlusOneLongPressed(chatActivity, view) }
        } catch (t: Throwable) {
            logError("Failed to attach +1 long-press listener", t)
        }
    }

    private fun isPlusOneItem(view: Any?): Boolean {
        if (view !is View) return false
        return try {
            val textView =
                findMethodOrNull(view.javaClass, "getTextView")?.invoke(view)
                    ?: findFieldOrNull(view.javaClass, "textView")?.get(view)
            if (textView is android.widget.TextView && textView.text?.toString() == "+1") {
                true
            } else {
                false
            }
        } catch (_: Throwable) {
            false
        }
    }

    @Suppress("UNCHECKED_CAST")
    fun forwardSelectedMessageToCurrentChat(chatActivity: Any) {
        try {
            if (!canSendToCurrentConversation(chatActivity)) return
            val selectedObject =
                TelegramObfuscationResolver.findChatSelectedObjectField(chatActivity.javaClass).get(chatActivity)
                    ?: return
            val shouldRepeatWithoutForwarding =
                shouldRepeatPlusOneWithoutForwardHeader(chatActivity, selectedObject)
            val selectedObjectGroup =
                TelegramObfuscationResolver.findChatSelectedObjectGroupField(chatActivity.javaClass).get(chatActivity)

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

            Log.i("MxGram", "forwardSelectedMessageToCurrentChat: count=${messages.size}, withoutHeader=$shouldRepeatWithoutForwarding")

            if (replyRepeater.tryRepeatPendingOrForced(
                    chatActivity,
                    selectedObject,
                    messages,
                    shouldRepeatWithoutForwarding,
                )
            ) {
                return
            }

            // Prefer Telegram's internal sending path for forwarding inside the current chat.
            val forwardMessages = TelegramObfuscationResolver.resolveForwardMessagesMethod(chatActivity.javaClass)
            if (forwardMessages != null) {
                forwardMessages.isAccessible = true
                forwardMessages.invoke(chatActivity, messages, false, false, true, 0, 0L)
                return
            }

            // Fallback: show the forward panel (user still needs to tap send).
            val showFieldPanelForForward =
                findMethodOrNull(
                    chatActivity.javaClass,
                    "showFieldPanelForForward",
                    java.lang.Boolean.TYPE,
                    ArrayList::class.java,
                )
            showFieldPanelForForward?.invoke(chatActivity, true, messages)
        } catch (t: Throwable) {
            logError("Failed to +1 forward message", t)
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

    private fun onPlusOneLongPressed(
        chatActivity: Any,
        menuItemView: View,
    ): Boolean {
        try {
            Log.i("MxGram", "onPlusOneLongPressed: preparing reply and clicking")
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
