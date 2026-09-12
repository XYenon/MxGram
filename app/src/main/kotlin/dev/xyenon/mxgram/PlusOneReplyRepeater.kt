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

    fun tryRepeatPendingOrForced(
        chatActivity: Any,
        selectedObject: Any,
        messages: List<Any>,
        forceRepeatWithoutReply: Boolean,
    ): Boolean {
        // Long press always re-sends without a forward header. For a single reply message it also
        // keeps the reply target, which Telegram cannot do with a forwarded (quoted) message.
        val pending = pendingPlusOneReply.getAndSet(null)
        return try {
            if (pending != null && SystemClock.uptimeMillis() - pending.createdAtUptimeMs <= PLUS_ONE_REPLY_TTL_MS) {
                val selectedIdentity = messageIdentity(selectedObject)
                if (selectedIdentity == pending.selectedMessageIdentity) {
                    if (pending.replyToMsg != null && messages.size == 1) {
                        invokeProcessForwardFromMyName(
                            chatActivity,
                            selectedObject,
                            replyToMsgOverride = pending.replyToMsg,
                            logError,
                        )
                    } else {
                        invokeProcessForwardFromMyNameBatch(chatActivity, messages, logError)
                    }
                    return true
                }
            }
            if (forceRepeatWithoutReply) {
                invokeProcessForwardFromMyNameBatch(chatActivity, messages, logError)
                true
            } else {
                false
            }
        } catch (_: Throwable) {
            if (forceRepeatWithoutReply) {
                invokeProcessForwardFromMyNameBatch(chatActivity, messages, logError)
                true
            } else {
                false
            }
        }
    }

    private fun buildPendingPlusOneReply(chatActivity: Any): PendingPlusOneReply? {
        try {
            val selectedObject = findField(chatActivity.javaClass, "selectedObject").get(chatActivity) ?: return null
            val selectedIdentity = messageIdentity(selectedObject) ?: return null

            val selectedObjectGroup =
                findField(chatActivity.javaClass, "selectedObjectGroup").get(chatActivity)
            if (selectedObjectGroup != null) {
                return PendingPlusOneReply(null, selectedIdentity)
            }

            val replyMsgId = selectedObject.javaClass.getMethod("getReplyMsgId").invoke(selectedObject) as Int
            if (replyMsgId <= 0) {
                return PendingPlusOneReply(null, selectedIdentity)
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
                        return PendingPlusOneReply(null, selectedIdentity)
                    }
                }
            }

            val replyToMsg = findField(selectedObject.javaClass, "replyMessageObject").get(selectedObject)
            return PendingPlusOneReply(replyToMsg, selectedIdentity)
        } catch (_: Throwable) {
            return null
        }
    }
}

private data class PendingPlusOneReply(
    val replyToMsg: Any?,
    val selectedMessageIdentity: String,
    val createdAtUptimeMs: Long = SystemClock.uptimeMillis(),
)
