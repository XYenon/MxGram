package dev.xyenon.mxgram

import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/** Stable key for a message; survives new [MessageObject] instances for the same server message. */
internal fun messageIdentity(messageObject: Any): String? =
    try {
        val dialogId = messageObject.javaClass.getMethod("getDialogId").invoke(messageObject) as Long
        val id = messageObject.javaClass.getMethod("getId").invoke(messageObject) as Int
        "$dialogId:$id"
    } catch (_: Throwable) {
        null
    }

/**
 * Mirrors [ChatActivity.isPeerNoForwards] / [MessagesController.isChatNoForwards] using raw
 * [TLRPC.Chat] / [TLRPC.UserFull] fields. Does not call hooked [MessagesController] helpers.
 * Verified against Telegram Android 12.8.1 (6916).
 */
internal fun isPeerNoForwardsFromRawPeerState(chatActivity: Any): Boolean =
    try {
        val chatActivityClass = chatActivity.javaClass
        val currentChat =
            try {
                findField(chatActivityClass, "currentChat").get(chatActivity)
            } catch (_: NoSuchFieldException) {
                null
            }
        if (currentChat != null) {
            isChatNoForwardsFromRawChat(chatActivity, currentChat)
        } else {
            val userInfo =
                try {
                    findField(chatActivityClass, "userInfo").get(chatActivity)
                } catch (_: NoSuchFieldException) {
                    null
                }
            isUserNoForwardsFromRawUserFull(userInfo)
        }
    } catch (_: Throwable) {
        false
    }

private fun isChatNoForwardsFromRawChat(
    chatActivity: Any,
    chat: Any,
): Boolean {
    val migratedToRef =
        try {
            findField(chat.javaClass, "migrated_to").get(chat)
        } catch (_: NoSuchFieldException) {
            null
        }
    if (migratedToRef != null) {
        val channelId =
            try {
                findField(migratedToRef.javaClass, "channel_id").getLong(migratedToRef)
            } catch (_: NoSuchFieldException) {
                0L
            }
        if (channelId != 0L) {
            val messagesController =
                chatActivity.javaClass.getMethod("getMessagesController").invoke(chatActivity) ?: return false
            val migratedChat =
                messagesController.javaClass.getMethod("getChat", java.lang.Long.TYPE).invoke(messagesController, channelId)
            if (migratedChat != null) {
                return readBooleanField(migratedChat, "noforwards")
            }
        }
    }
    return readBooleanField(chat, "noforwards")
}

private fun isUserNoForwardsFromRawUserFull(userFull: Any?): Boolean {
    if (userFull == null) {
        return false
    }
    val peerEnabled = readBooleanField(userFull, "noforwards_peer_enabled")
    val myEnabled = readBooleanField(userFull, "noforwards_my_enabled")
    return peerEnabled || myEnabled
}

private fun readBooleanField(
    instance: Any,
    name: String,
): Boolean =
    try {
        findField(instance.javaClass, name).getBoolean(instance)
    } catch (_: Throwable) {
        false
    }

/** Matches fillMessageMenu: peer restriction or per-message [TLRPC.Message.noforwards]. */
internal fun shouldRepeatPlusOneWithoutForwardHeader(
    chatActivity: Any,
    messageObject: Any,
): Boolean = isPeerNoForwardsFromRawPeerState(chatActivity) || isMessageNoForwardsForPlusOne(messageObject)

internal fun isMessageNoForwardsForPlusOne(messageObject: Any): Boolean {
    if (PlusOneNoForwardsTracker.contains(messageObject)) {
        return true
    }
    return try {
        val messageOwner = findField(messageObject.javaClass, "messageOwner").get(messageObject) ?: return false
        readBooleanField(messageOwner, "noforwards")
    } catch (_: Throwable) {
        false
    }
}

internal object PlusOneNoForwardsTracker {
    private const val MAX_KEYS = 2048
    private val keys = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())

    fun mark(messageObject: Any) {
        val key = messageIdentity(messageObject) ?: return
        synchronized(keys) {
            if (keys.size >= MAX_KEYS) {
                keys.clear()
            }
            keys.add(key)
        }
    }

    fun contains(messageObject: Any): Boolean {
        val key = messageIdentity(messageObject) ?: return false
        return keys.contains(key)
    }
}

/**
 * Re-sends message content without a forward header via Telegram
 * [SendMessagesHelper.processForwardFromMyName] (12.8.1 / 6916): text entities, web previews,
 * photos, documents, stickers, geo, contacts, etc.
 */
internal fun invokeProcessForwardFromMyName(
    chatActivity: Any,
    messageObject: Any,
    replyToMsgOverride: Any? = null,
    logError: (String, Throwable) -> Unit,
): Boolean {
    return try {
        val dialogId = chatActivity.javaClass.getMethod("getDialogId").invoke(chatActivity) as Long
        val sendMessagesHelper =
            chatActivity.javaClass.getMethod("getSendMessagesHelper").invoke(chatActivity) ?: return false
        val processForwardFromMyName =
            findDeclaredMethodByNameAndArity(
                sendMessagesHelper.javaClass,
                "processForwardFromMyName",
                5,
            ) ?: return false
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

        if (replyToMsgOverride != null) {
            val replyField = findField(messageObject.javaClass, "replyMessageObject")
            val previousReply = replyField.get(messageObject)
            try {
                replyField.set(messageObject, replyToMsgOverride)
                processForwardFromMyName.isAccessible = true
                processForwardFromMyName.invoke(
                    sendMessagesHelper,
                    messageObject,
                    dialogId,
                    0L,
                    monoForumPeer,
                    suggestionParams,
                )
            } finally {
                replyField.set(messageObject, previousReply)
            }
        } else {
            processForwardFromMyName.isAccessible = true
            processForwardFromMyName.invoke(
                sendMessagesHelper,
                messageObject,
                dialogId,
                0L,
                monoForumPeer,
                suggestionParams,
            )
        }
        true
    } catch (t: Throwable) {
        logError("Failed to +1 repeat message via processForwardFromMyName", t)
        false
    }
}

internal fun invokeProcessForwardFromMyNameBatch(
    chatActivity: Any,
    messages: List<Any>,
    logError: (String, Throwable) -> Unit,
) {
    for (message in messages) {
        invokeProcessForwardFromMyName(chatActivity, message, replyToMsgOverride = null, logError)
    }
}
