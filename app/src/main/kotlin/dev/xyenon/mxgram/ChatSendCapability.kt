package dev.xyenon.mxgram

import android.os.Bundle

/**
 * Mirrors [ChatActivity.fillMessageMenu] `allowChatActions` gates (Telegram 12.9.2 / 6991) so +1
 * only appears when the official message menu would allow chat actions.
 */
internal fun canSendToCurrentConversation(chatActivity: Any): Boolean {
    try {
        val classLoader = chatActivity.javaClass.classLoader ?: return false
        val chatActivityClass = chatActivity.javaClass
        val chatObjectClass = Class.forName("org.telegram.messenger.ChatObject", false, classLoader)
        val userObjectClass = Class.forName("org.telegram.messenger.UserObject", false, classLoader)

        val modeScheduled =
            getStaticIntFieldValue(chatActivityClass, "MODE_SCHEDULED", 1)
        val arguments = findMethodOrNull(chatActivityClass, "getArguments")?.invoke(chatActivity) as? Bundle
        val chatMode =
            findFieldOrNull(chatActivityClass, "chatMode")?.getInt(chatActivity)
                ?: arguments?.getInt("chatMode", 0) ?: return false
        if (chatMode == modeScheduled) {
            return false
        }

        val isReport =
            invokeInstanceBooleanOrNull(chatActivityClass, chatActivity, "isReport")
                ?: arguments?.let { !it.getString("reportTitle").isNullOrEmpty() } ?: return false
        if (isReport) {
            return false
        }

        val currentEncryptedChat =
            TelegramObfuscationResolver.findChatCurrentEncryptedChatFieldOrNull(chatActivityClass)?.get(chatActivity)
        if (currentEncryptedChat != null) {
            return false
        }

        if (TelegramObfuscationResolver
                .resolveChatCanSendMessageMethodOrNull(chatActivityClass)
                ?.invoke(chatActivity) != true
        ) {
            return false
        }

        val currentUserField = TelegramObfuscationResolver.findChatCurrentUserFieldOrNull(chatActivityClass)
        val currentUserMethod = TelegramObfuscationResolver.resolveChatGetCurrentUserMethodOrNull(chatActivityClass)
        if (currentUserField == null && currentUserMethod == null) return false
        val currentUser = currentUserField?.get(chatActivity) ?: currentUserMethod?.invoke(chatActivity)
        if (currentUser != null && isCurrentUserBlocked(chatActivity, currentUser) != false) {
            return false
        }
        if (currentUser != null && invokeStaticBoolean(userObjectClass, "isReplyUser", arrayOf(currentUser))) {
            return false
        }

        val currentChatField = TelegramObfuscationResolver.findChatCurrentChatFieldOrNull(chatActivityClass)
        val currentChatMethod = TelegramObfuscationResolver.resolveChatGetCurrentChatMethodOrNull(chatActivityClass)
        if (currentChatField == null && currentChatMethod == null) return false
        val currentChat = currentChatField?.get(chatActivity) ?: currentChatMethod?.invoke(chatActivity)
        if (currentChat == null) {
            return true
        }

        if (invokeStaticBoolean(chatObjectClass, "isNotInChat", arrayOf(currentChat))) {
            val monoForum = invokeStaticBoolean(chatObjectClass, "isMonoForum", arrayOf(currentChat))
            val threadChat = invokeInstanceBooleanOrNull(chatActivityClass, chatActivity, "isThreadChat") == true
            if (!monoForum && !threadChat) {
                return false
            }
        }

        val isChannel = invokeStaticBoolean(chatObjectClass, "isChannel", arrayOf(currentChat))
        val megagroup =
            try {
                findField(currentChat.javaClass, "megagroup").getBoolean(currentChat)
            } catch (_: NoSuchFieldException) {
                false
            }
        if (isChannel && !invokeStaticBoolean(chatObjectClass, "canPost", arrayOf(currentChat)) && !megagroup) {
            return false
        }

        if (!invokeStaticBoolean(chatObjectClass, "canSendMessages", arrayOf(currentChat))) {
            return false
        }

        val forumTopic =
            TelegramObfuscationResolver.findChatForumTopicFieldOrNull(chatActivityClass)?.get(chatActivity)
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
                        findField(chatActivityClass, "currentAccount").getInt(chatActivity)
                    } catch (_: NoSuchFieldException) {
                        0
                    }
                val canManageTopic =
                    invokeStaticBooleanOrNull(
                        chatObjectClass,
                        "canManageTopic",
                        arrayOf(currentAccount, currentChat, forumTopic),
                    )
                if (canManageTopic != true) {
                    return false
                }
            }
        }

        return true
    } catch (_: Throwable) {
        // Do not expose a send action when Telegram's current capability cannot be established.
        return false
    }
}

internal fun isCurrentUserBlocked(
    chatActivity: Any,
    currentUser: Any,
): Boolean? {
    return try {
        // Messenger state retains its source names in the shipped APK, unlike ChatActivity.userBlocked.
        val controller =
            findMethod(chatActivity.javaClass, "getMessagesController").invoke(chatActivity)
                ?: return null
        val blockedPeers = findField(controller.javaClass, "blockePeers").get(controller) ?: return null
        val userId = findField(currentUser.javaClass, "id").getLong(currentUser)
        (findMethod(blockedPeers.javaClass, "indexOfKey", java.lang.Long.TYPE).invoke(blockedPeers, userId) as Int) >= 0
    } catch (_: Throwable) {
        null
    }
}

private fun invokeInstanceBooleanOrNull(
    type: Class<*>,
    instance: Any,
    name: String,
): Boolean? =
    try {
        val method = findMethod(type, name)
        val result = method.invoke(instance)
        if (result is Boolean) result else null
    } catch (_: Throwable) {
        null
    }
