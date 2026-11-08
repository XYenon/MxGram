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
        val chatObjectClass = findClassOrNull("org.telegram.messenger.ChatObject", classLoader)
        val userObjectClass = findClassOrNull("org.telegram.messenger.UserObject", classLoader)

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
        if (currentUser != null) {
            if (isCurrentUserBlocked(chatActivity, currentUser) != false) {
                return false
            }
            val userId = findFieldOrNull(currentUser.javaClass, "id")?.getLong(currentUser)
            // 708513L is Telegram's Replies account; 1271266957L is UserObject.REPLY_BOT
            if (userId == 708513L || userId == 1271266957L ||
                (userObjectClass != null && invokeStaticBooleanOrNull(userObjectClass, "isReplyUser", arrayOf(currentUser)) == true)
            ) {
                return false
            }
        }

        val currentChatField = TelegramObfuscationResolver.findChatCurrentChatFieldOrNull(chatActivityClass)
        val currentChatMethod = TelegramObfuscationResolver.resolveChatGetCurrentChatMethodOrNull(chatActivityClass)
        if (currentChatField == null && currentChatMethod == null) return false
        val currentChat = currentChatField?.get(chatActivity) ?: currentChatMethod?.invoke(chatActivity)
        if (currentChat == null) {
            return true
        }

        if (chatObjectClass != null) {
            if (invokeStaticBooleanOrNull(chatObjectClass, "isNotInChat", arrayOf(currentChat)) == true) {
                val monoForum = invokeStaticBooleanOrNull(chatObjectClass, "isMonoForum", arrayOf(currentChat)) == true
                val threadChat = invokeInstanceBooleanOrNull(chatActivityClass, chatActivity, "isThreadChat") == true
                if (!monoForum && !threadChat) {
                    return false
                }
            }

            val isChannel = invokeStaticBooleanOrNull(chatObjectClass, "isChannel", arrayOf(currentChat)) == true
            val megagroup =
                try {
                    findField(currentChat.javaClass, "megagroup").getBoolean(currentChat)
                } catch (_: NoSuchFieldException) {
                    false
                }
            if (isChannel && invokeStaticBooleanOrNull(chatObjectClass, "canPost", arrayOf(currentChat)) == false && !megagroup) {
                return false
            }

            if (invokeStaticBooleanOrNull(chatObjectClass, "canSendMessages", arrayOf(currentChat)) == false) {
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
        } else {
            // Obfuscated Telegram fallback: check raw TLRPC.Chat fields
            val left = findFieldOrNull(currentChat.javaClass, "left")?.getBoolean(currentChat) ?: false
            val kicked = findFieldOrNull(currentChat.javaClass, "kicked")?.getBoolean(currentChat) ?: false
            val deactivated = findFieldOrNull(currentChat.javaClass, "deactivated")?.getBoolean(currentChat) ?: false
            if (left || kicked || deactivated) {
                val monoforum = findFieldOrNull(currentChat.javaClass, "monoforum")?.getBoolean(currentChat) ?: false
                val threadChat = invokeInstanceBooleanOrNull(chatActivityClass, chatActivity, "isThreadChat") == true
                if (!monoforum && !threadChat) {
                    return false
                }
            }

            val isChannel =
                currentChat.javaClass.simpleName.contains("Channel", ignoreCase = true) ||
                    findFieldOrNull(currentChat.javaClass, "broadcast")?.getBoolean(currentChat) == true
            val megagroup = findFieldOrNull(currentChat.javaClass, "megagroup")?.getBoolean(currentChat) ?: false
            val creator = findFieldOrNull(currentChat.javaClass, "creator")?.getBoolean(currentChat) ?: false

            if (isChannel && !megagroup && !creator) {
                val adminRights = findFieldOrNull(currentChat.javaClass, "admin_rights")?.get(currentChat)
                val canPost =
                    adminRights?.let {
                        findFieldOrNull(it.javaClass, "post_messages")?.getBoolean(it)
                    } ?: false
                if (!canPost) {
                    return false
                }
            }

            val bannedRights =
                findFieldOrNull(currentChat.javaClass, "banned_rights")?.get(currentChat)
                    ?: findFieldOrNull(currentChat.javaClass, "default_banned_rights")?.get(currentChat)
            if (bannedRights != null && !creator) {
                val sendMessagesBanned = findFieldOrNull(bannedRights.javaClass, "send_messages")?.getBoolean(bannedRights) ?: false
                if (sendMessagesBanned) {
                    return false
                }
            }

            val forumTopic =
                TelegramObfuscationResolver.findChatForumTopicFieldOrNull(chatActivityClass)?.get(chatActivity)
            if (forumTopic != null) {
                val closed = findFieldOrNull(forumTopic.javaClass, "closed")?.getBoolean(forumTopic) ?: false
                if (closed && !creator) {
                    return false
                }
            }
        }

        return true
    } catch (t: Throwable) {
        runCatching { android.util.Log.w("MxGram", "canSendToCurrentConversation failed with exception", t) }
        // Do not expose a send action when Telegram's current capability cannot be established.
        return false
    }
}

internal fun isCurrentUserBlocked(
    chatActivity: Any,
    currentUser: Any,
): Boolean? {
    return try {
        val controller =
            findMethodOrNull(chatActivity.javaClass, "getMessagesController")?.invoke(chatActivity)
                ?: return null
        val blockedPeers =
            TelegramObfuscationResolver
                .findMessagesControllerBlockedPeersFieldOrNull(controller.javaClass)
                ?.get(controller) ?: return null
        val userId = findFieldOrNull(currentUser.javaClass, "id")?.getLong(currentUser) ?: return null
        val indexOfKey =
            findMethodOrNull(blockedPeers.javaClass, "indexOfKey", java.lang.Long.TYPE)
                ?: return null
        (indexOfKey.invoke(blockedPeers, userId) as? Int ?: return null) >= 0
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
