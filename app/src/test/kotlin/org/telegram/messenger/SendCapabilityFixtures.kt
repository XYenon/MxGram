package org.telegram.messenger

// Minimal stable messenger API for ordinary-user capability tests; no group methods are called.
internal object ChatObject

internal object UserObject {
    @JvmStatic
    fun isReplyUser(
        @Suppress("UNUSED_PARAMETER") user: Any,
    ): Boolean = false
}
