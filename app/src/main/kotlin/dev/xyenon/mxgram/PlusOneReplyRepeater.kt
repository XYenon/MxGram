package dev.xyenon.mxgram

import android.os.SystemClock
import java.util.concurrent.atomic.AtomicReference

private const val PLUS_ONE_REPLY_TTL_MS = 60_000L

internal class PlusOneReplyRepeater(
    private val logError: (String, Throwable) -> Unit,
) {
    private val pendingPlusOneReply = AtomicReference<PendingPlusOneReply?>(null)

    fun prepareReply(chatActivity: Any) {
        pendingPlusOneReply.set(buildPendingPlusOneReply(chatActivity))
    }

    fun tryRepeatPendingReply(
        chatActivity: Any,
        selectedObject: Any,
    ): Boolean {
        // Reply case: Telegram doesn't support replying with a forwarded (quoted) message.
        // When user long-presses +1 on a reply, we re-send the content as a normal message and
        // keep the reply target.
        val pending = pendingPlusOneReply.getAndSet(null)
        if (pending == null || SystemClock.uptimeMillis() - pending.createdAtUptimeMs > PLUS_ONE_REPLY_TTL_MS) {
            return false
        }
        return try {
            val selectedId = selectedObject.javaClass.getMethod("getId").invoke(selectedObject) as Int
            selectedId == pending.selectedMsgId &&
                pending.replyToMsg != null &&
                repeatSelectedMessageAsReply(chatActivity, selectedObject, pending.replyToMsg)
        } catch (_: Throwable) {
            false
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
}

private data class PendingPlusOneReply(
    val replyToMsg: Any?,
    val selectedMsgId: Int,
    val createdAtUptimeMs: Long = SystemClock.uptimeMillis(),
)
