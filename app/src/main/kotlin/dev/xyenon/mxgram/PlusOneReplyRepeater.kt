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
        forceRepeatWithoutReply: Boolean,
    ): Boolean {
        // Reply case: Telegram doesn't support replying with a forwarded (quoted) message.
        // When user long-presses +1 on a reply, we re-send the content as a normal message and
        // keep the reply target.
        val pending = pendingPlusOneReply.getAndSet(null)
        return try {
            if (pending != null && SystemClock.uptimeMillis() - pending.createdAtUptimeMs <= PLUS_ONE_REPLY_TTL_MS) {
                val selectedId = selectedObject.javaClass.getMethod("getId").invoke(selectedObject) as Int
                if (selectedId == pending.selectedMsgId && pending.replyToMsg != null) {
                    return invokeProcessForwardFromMyName(
                        chatActivity,
                        selectedObject,
                        replyToMsgOverride = pending.replyToMsg,
                        logError,
                    )
                }
            }
            if (forceRepeatWithoutReply) {
                invokeProcessForwardFromMyName(
                    chatActivity,
                    selectedObject,
                    replyToMsgOverride = null,
                    logError,
                )
            } else {
                false
            }
        } catch (_: Throwable) {
            if (forceRepeatWithoutReply) {
                invokeProcessForwardFromMyName(
                    chatActivity,
                    selectedObject,
                    replyToMsgOverride = null,
                    logError,
                )
            } else {
                false
            }
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
}

private data class PendingPlusOneReply(
    val replyToMsg: Any?,
    val selectedMsgId: Int,
    val createdAtUptimeMs: Long = SystemClock.uptimeMillis(),
)
