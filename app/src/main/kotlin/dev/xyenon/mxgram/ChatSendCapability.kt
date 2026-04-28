package dev.xyenon.mxgram

import android.view.View

internal fun canSendToCurrentConversation(chatActivity: Any): Boolean {
    try {
        // If Telegram is showing the bottom overlay instead of the input field, we definitely can't
        // send right now.
        val bottomChannelButtonsLayout =
            findField(chatActivity.javaClass, "bottomChannelButtonsLayout").get(chatActivity)
        if (bottomChannelButtonsLayout is View) {
            // Telegram uses this overlay when the input field is not available.
            // NOTE: the normal state is usually INVISIBLE, not GONE.
            if (bottomChannelButtonsLayout.visibility == View.VISIBLE) {
                return false
            }
        }

        // For user dialogs, blocked state is the most common reason why sending is disabled.
        try {
            if (findField(chatActivity.javaClass, "userBlocked").getBoolean(chatActivity)) {
                return false
            }
        } catch (_: NoSuchFieldException) {
            // Ignore.
        }

        val currentChat =
            try {
                findField(chatActivity.javaClass, "currentChat").get(chatActivity)
            } catch (_: NoSuchFieldException) {
                null
            }
        if (currentChat == null) {
            // Private chat / other modes: rely on the overlay checks above.
            return true
        }

        val classLoader = chatActivity.javaClass.classLoader ?: return true
        val chatObjectClass = Class.forName("org.telegram.messenger.ChatObject", false, classLoader)

        // Not a member / left / kicked.
        if (invokeStaticBoolean(chatObjectClass, "isNotInChat", arrayOf(currentChat))) {
            return false
        }

        // Channels where we can't post.
        if (!invokeStaticBoolean(chatObjectClass, "canWriteToChat", arrayOf(currentChat))) {
            return false
        }

        // Muted by permissions / bans.
        if (!invokeStaticBoolean(chatObjectClass, "canSendMessages", arrayOf(currentChat))) {
            return false
        }

        // Closed forum topic (unless we can manage it).
        val forumTopic =
            try {
                findField(chatActivity.javaClass, "forumTopic").get(chatActivity)
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
                        findField(chatActivity.javaClass, "currentAccount").getInt(chatActivity)
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
