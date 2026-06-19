package dev.xyenon.mxgram

import android.view.View

/**
 * Mirrors [ChatActivity.fillMessageMenu] `allowChatActions` gates (Telegram 12.8.1 / 6916) so +1
 * only appears when the official message menu would allow chat actions.
 */
internal fun canSendToCurrentConversation(chatActivity: Any): Boolean {
    try {
        val classLoader = chatActivity.javaClass.classLoader ?: return true
        val chatActivityClass = chatActivity.javaClass
        val chatObjectClass = Class.forName("org.telegram.messenger.ChatObject", false, classLoader)
        val userObjectClass = Class.forName("org.telegram.messenger.UserObject", false, classLoader)

        val modeScheduled =
            getStaticIntFieldValue(chatActivityClass, "MODE_SCHEDULED", 1)
        val chatMode =
            try {
                findField(chatActivityClass, "chatMode").getInt(chatActivity)
            } catch (_: NoSuchFieldException) {
                0
            }
        if (chatMode == modeScheduled) {
            return false
        }

        if (invokeInstanceBooleanOrNull(chatActivityClass, chatActivity, "isReport") == true) {
            return false
        }

        val currentEncryptedChat =
            try {
                findField(chatActivityClass, "currentEncryptedChat").get(chatActivity)
            } catch (_: NoSuchFieldException) {
                null
            }
        if (currentEncryptedChat != null) {
            return false
        }

        val bottomChannelButtonsLayout =
            try {
                findField(chatActivityClass, "bottomChannelButtonsLayout").get(chatActivity)
            } catch (_: NoSuchFieldException) {
                null
            }
        if (bottomChannelButtonsLayout is View && bottomChannelButtonsLayout.visibility == View.VISIBLE) {
            return false
        }

        if (invokeInstanceBooleanOrNull(chatActivityClass, chatActivity, "canSendMessage") == false) {
            return false
        }

        try {
            if (findField(chatActivityClass, "userBlocked").getBoolean(chatActivity)) {
                return false
            }
        } catch (_: NoSuchFieldException) {
            // Ignore.
        }

        val currentUser =
            try {
                findField(chatActivityClass, "currentUser").get(chatActivity)
            } catch (_: NoSuchFieldException) {
                null
            }
        if (currentUser != null && invokeStaticBoolean(userObjectClass, "isReplyUser", arrayOf(currentUser))) {
            return false
        }

        val currentChat =
            try {
                findField(chatActivityClass, "currentChat").get(chatActivity)
            } catch (_: NoSuchFieldException) {
                null
            }
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
            try {
                findField(chatActivityClass, "forumTopic").get(chatActivity)
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

private fun invokeInstanceBooleanOrNull(
    type: Class<*>,
    instance: Any,
    name: String,
): Boolean? =
    try {
        val method = type.getMethod(name)
        val result = method.invoke(instance)
        if (result is Boolean) result else null
    } catch (_: Throwable) {
        null
    }
