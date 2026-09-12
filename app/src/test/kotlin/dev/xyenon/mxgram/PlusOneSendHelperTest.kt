package dev.xyenon.mxgram

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PlusOneSendHelperTest {
    @Test
    fun regularTapDoesNotKeepOriginalReply() {
        val originalReply = Any()
        val message = FakeMessage(originalReply)
        val helper = FakeSendMessagesHelper()

        assertTrue(invokeProcessForwardFromMyName(FakeChatActivity(helper), message, null) { _, _ -> })

        assertNull(helper.replySeenDuringSend)
        assertSame(originalReply, message.replyMessageObject)
    }

    @Test
    fun longPressUsesReplyOverride() {
        val originalReply = Any()
        val replyOverride = Any()
        val message = FakeMessage(originalReply)
        val helper = FakeSendMessagesHelper()

        assertTrue(
            invokeProcessForwardFromMyName(
                FakeChatActivity(helper),
                message,
                replyOverride,
            ) { _, _ -> },
        )

        assertSame(replyOverride, helper.replySeenDuringSend)
        assertSame(originalReply, message.replyMessageObject)
    }

    @Test
    fun longPressWithoutOriginalReplyRepeatsWithoutForwarding() {
        val message = FakeMessage(null)
        val helper = FakeSendMessagesHelper()
        val activity = FakeChatActivity(helper, message)
        val repeater = PlusOneReplyRepeater { _, _ -> }

        repeater.prepareReply(activity)

        assertTrue(repeater.tryRepeatPendingOrForced(activity, message, listOf(message), false))
        assertEquals(1, helper.sendCount)
        assertNull(helper.replySeenDuringSend)
    }

    @Test
    fun regularTapInUnprotectedChatContinuesToForwardPath() {
        val message = FakeMessage(null)
        val helper = FakeSendMessagesHelper()
        val activity = FakeChatActivity(helper, message)
        val repeater = PlusOneReplyRepeater { _, _ -> }

        assertFalse(repeater.tryRepeatPendingOrForced(activity, message, listOf(message), false))
        assertEquals(0, helper.sendCount)
    }

    internal class FakeMessage(
        var replyMessageObject: Any?,
    ) {
        fun getDialogId(): Long = 456L

        fun getId(): Int = 789

        fun getReplyMsgId(): Int = if (replyMessageObject == null) 0 else 1
    }

    internal class FakeChatActivity(
        private val sendMessagesHelper: FakeSendMessagesHelper,
        private val selectedObject: FakeMessage? = null,
    ) {
        @Suppress("unused")
        private val selectedObjectGroup: Any? = null

        fun getDialogId(): Long = 123L

        fun getSendMessagesHelper(): FakeSendMessagesHelper = sendMessagesHelper
    }

    internal class FakeSendMessagesHelper {
        var replySeenDuringSend: Any? = null
        var sendCount: Int = 0

        @Suppress("UNUSED_PARAMETER")
        fun processForwardFromMyName(
            message: FakeMessage,
            dialogId: Long,
            payStars: Long,
            monoForumPeerId: Long,
            suggestionParams: Any?,
        ) {
            replySeenDuringSend = message.replyMessageObject
            sendCount += 1
        }
    }
}
