package dev.xyenon.mxgram

import android.content.Context
import android.graphics.Canvas
import android.view.View
import android.widget.FrameLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy

class ObfuscatedTelegramTest {
    @Test
    fun doubleTapProxyRecognizesSignaturesAndPreservesSingleClicks() {
        val original = ObfuscatedClicks()
        val proxy =
            Proxy.newProxyInstance(
                javaClass.classLoader,
                arrayOf(ObfuscatedClickListener::class.java),
                DoubleTapReactionBlocker.DoubleTapDisablingHandler(original),
            ) as ObfuscatedClickListener

        assertFalse(proxy.a(null))
        proxy.b(null, 7f, 13f)
        proxy.c(7f, 13f, 29, null)
        assertFalse(original.doubleTapped)
        assertEquals(listOf(7f, 13f, 29), original.click)
        assertTrue(proxy == proxy)
        assertFalse(proxy == original)
    }

    @Test
    fun extendedListenerFieldIgnoresOtherInterfaces() {
        assertEquals(
            "z",
            DoubleTapReactionBlocker.findExtendedClickListenerField(ObfuscatedRecycler::class.java)?.name,
        )
    }

    @Test
    fun secretReadResolutionAcceptsConcreteRunnableReturn() {
        val method = TelegramObfuscationResolver.resolveSendSecretMessageReadMethod(ObfuscatedChat::class.java)
        assertEquals("z", method.name)
        assertEquals(ConcreteReadAction::class.java, method.returnType)
    }

    @Test
    fun messageListResolutionSkipsSearchListWithoutExtendedListener() {
        val chat = ObfuscatedLists()
        val field = DoubleTapReactionBlocker.findExtendedClickListenerField(ObfuscatedRecycler::class.java)!!
        assertSame(
            chat.z,
            DoubleTapReactionBlocker.findMessageListView(chat, ObfuscatedRecycler::class.java, field),
        )
    }

    @Test
    fun previewContainerResolutionIgnoresOtherFrameLayouts() {
        assertEquals(
            "z",
            TelegramObfuscationResolver.findContentPreviewContainerViewField(ObfuscatedPreview::class.java).name,
        )
    }

    @Test
    fun profileArraysResolveByLengthRegardlessOfFieldOrder() {
        val profile = ObfuscatedProfile()
        assertEquals("a", TelegramObfuscationResolver.findProfileOnlineTextViewField(profile).name)
        assertEquals("z", TelegramObfuscationResolver.findProfileNameTextViewFieldOrNull(profile)?.name)
        assertNull(TelegramObfuscationResolver.findProfileNameTextViewFieldOrNull(AmbiguousProfile()))
    }

    @Test
    fun ambiguousMethodSignaturesAreNotHooked() {
        assertNull(
            findDeclaredMethodByPredicate(AmbiguousMethods::class.java) {
                it.parameterCount == 0 && it.returnType == java.lang.Long.TYPE
            },
        )
    }

    @Test
    fun blockedStateComesFromControllerAndUnknownStateIsNotAllowed() {
        val chat = BlockedChat()
        assertTrue(isCurrentUserBlocked(chat, BlockedUser(918L)) == true)
        assertFalse(isCurrentUserBlocked(chat, BlockedUser(317L)) != false)
        assertTrue(isCurrentUserBlocked(Any(), BlockedUser(317L)) != false)
        assertNull(TelegramObfuscationResolver.resolveChatCanSendMessageMethodOrNull(Any::class.java))
    }

    @Test
    fun ordinaryChatRequiresResolvedCapabilityAndUnblockedUser() {
        assertTrue(canSendToCurrentConversation(SendingChat(317L)))
        assertFalse(canSendToCurrentConversation(SendingChat(918L)))
        assertFalse(canSendToCurrentConversation(SendingChat(317L, allowed = false)))
        assertFalse(canSendToCurrentConversation(CapabilityChat(317L)))
        assertFalse(canSendToCurrentConversation(SendingChat(317L, controller = Any())))
    }

    @Test
    fun repeatPreservesPeerAndInlinedSuggestionFieldAndRestoresReply() {
        val helper = ContextSendHelper()
        val suggestion = Suggestion()
        val originalReply = Any()
        val message = ContextMessage(originalReply)
        val chat = ContextChat(helper, suggestion)
        assertTrue(invokeProcessForwardFromMyName(chat, message) { _, error -> throw error })
        assertEquals(963L, helper.peer)
        assertSame(suggestion, helper.suggestion)
        assertNull(helper.reply)
        assertSame(originalReply, message.replyMessageObject)

        chat.z = null
        val override = Any()
        assertTrue(invokeProcessForwardFromMyName(chat, message, override) { _, error -> throw error })
        assertNull(helper.suggestion)
        assertSame(override, helper.reply)
        assertSame(originalReply, message.replyMessageObject)
    }

    @Test
    fun repeatRefusesMissingPeerOrAmbiguousSuggestionState() {
        val helper = ContextSendHelper()
        assertFalse(invokeProcessForwardFromMyName(MissingContextChat(helper), ContextMessage(null)) { _, _ -> })
        assertFalse(invokeProcessForwardFromMyName(AmbiguousContextChat(helper), ContextMessage(null)) { _, _ -> })
        assertEquals(0, helper.sendCount)
    }

    @Test
    fun repeatAcceptsResolvedZeroPeerAndNamedSuggestionGetter() {
        val helper = ContextSendHelper()
        val suggestion = Suggestion()
        assertTrue(invokeProcessForwardFromMyName(NamedContextChat(helper, suggestion), ContextMessage(null)) { _, error -> throw error })
        assertEquals(1, helper.sendCount)
        assertEquals(0L, helper.peer)
        assertSame(suggestion, helper.suggestion)
    }

    @Test
    fun premiumButtonResolutionRequiresUniqueFinalView() {
        assertEquals("z", TelegramObfuscationResolver.findUnlockPremiumButtonFieldOrNull(ObfuscatedUnlock::class.java)?.name)
        assertNull(TelegramObfuscationResolver.findUnlockPremiumButtonFieldOrNull(AmbiguousUnlock::class.java))
    }

    @Test
    fun ambiguousPreviewResolutionDoesNotAbortLaterFeatures() {
        val logs = mutableListOf<String>()
        assertNull(
            TelegramObfuscationResolver.resolveFeature("preview", logs::add) {
                TelegramObfuscationResolver.findContentPreviewContainerViewField(AmbiguousPreview::class.java)
            },
        )
        assertTrue(logs.single().contains("preview"))
        assertEquals(
            "z",
            TelegramObfuscationResolver.resolveFeature("chat", logs::add) {
                TelegramObfuscationResolver.resolveSendSecretMessageReadMethod(ObfuscatedChat::class.java).name
            },
        )
    }
}

internal interface ObfuscatedClickListener {
    fun a(view: View?): Boolean

    fun b(
        view: View?,
        x: Float,
        y: Float,
    )

    fun c(
        x: Float,
        y: Float,
        position: Int,
        view: View?,
    )
}

internal class ObfuscatedClicks : ObfuscatedClickListener {
    var doubleTapped = false
    var click: List<Any> = emptyList()

    override fun a(view: View?) = true

    override fun b(
        view: View?,
        x: Float,
        y: Float,
    ) {
        doubleTapped = true
    }

    override fun c(
        x: Float,
        y: Float,
        position: Int,
        view: View?,
    ) {
        click = listOf(x, y, position)
    }
}

private class ObfuscatedRecycler {
    var a: Runnable? = null
    var b: View.OnClickListener? = null
    var z: ObfuscatedClickListener? = null
}

private class ObfuscatedLists {
    val a = ObfuscatedRecycler()
    val z = ObfuscatedRecycler().also { it.z = ObfuscatedClicks() }
}

private class ObfuscatedPreview {
    @Suppress("ktlint:standard:property-naming")
    var Q: FrameLayout? = null
    var y: PreviewWindow? = null
    var z: PreviewDrawer? = null
}

private class PreviewWindow(
    context: Context,
) : FrameLayout(context)

private class PreviewDrawer(
    context: Context,
) : FrameLayout(context) {
    override fun onDraw(canvas: Canvas) = Unit
}

private class TestMessageObject

private class ConcreteReadAction : Runnable {
    override fun run() = Unit
}

private class ObfuscatedChat {
    fun a(message: TestMessageObject): Runnable = ConcreteReadAction()

    fun b(
        message: TestMessageObject,
        value: Int,
    ): Runnable = ConcreteReadAction()

    fun z(
        message: TestMessageObject,
        readNow: Boolean,
    ): ConcreteReadAction = ConcreteReadAction()
}

private class ObfuscatedProfile {
    val a = arrayOfNulls<View>(4)
    val b = arrayOfNulls<View>(3)
    val z = arrayOfNulls<View>(2)
}

private class AmbiguousProfile {
    val a = arrayOfNulls<View>(2)
    val z = arrayOfNulls<View>(2)
}

private class AmbiguousMethods {
    fun a() = 17L

    fun z() = 29L
}

private class BlockedUser(
    val id: Long,
)

private class BlockedChat {
    fun getMessagesController() = BlockedController()
}

private class BlockedController {
    val blockePeers = BlockedPeers()
}

private class BlockedPeers {
    fun indexOfKey(id: Long) = if (id == 918L) 0 else -1
}

private open class CapabilityChat(
    userId: Long,
    private val controller: Any = BlockedController(),
) {
    val chatMode = 0
    val currentUser = BlockedUser(userId)
    val currentChat: Any? = null

    fun isReport() = false

    fun getMessagesController() = controller
}

private class SendingChat(
    userId: Long,
    private val allowed: Boolean = true,
    controller: Any = BlockedController(),
) : CapabilityChat(userId, controller) {
    fun canSendMessage() = allowed
}

private class Suggestion

private class ContextMessage(
    var replyMessageObject: Any?,
)

private class ContextSendHelper {
    var peer = 0L
    var suggestion: Suggestion? = null
    var reply: Any? = null
    var sendCount = 0

    fun processForwardFromMyName(
        message: ContextMessage,
        dialogId: Long,
        payStars: Long,
        peerId: Long,
        params: Suggestion?,
    ) {
        peer = peerId
        suggestion = params
        reply = message.replyMessageObject
        sendCount++
    }
}

private class ContextChat(
    private val helper: ContextSendHelper,
    var z: Suggestion?,
) {
    fun getDialogId() = 123L

    fun getSendMessagesHelper() = helper

    fun getSendMonoForumPeerId() = 963L
}

private class MissingContextChat(
    private val helper: ContextSendHelper,
) {
    fun getDialogId() = 123L

    fun getSendMessagesHelper() = helper
}

private class NamedContextChat(
    private val helper: ContextSendHelper,
    private val suggestion: Suggestion,
) {
    fun getDialogId() = 123L

    fun getSendMessagesHelper() = helper

    fun getSendMonoForumPeerId() = 0L

    fun getSendMessageSuggestionParams() = suggestion
}

private class AmbiguousContextChat(
    private val helper: ContextSendHelper,
) {
    var a: Suggestion? = null
    var z: Suggestion? = null

    fun getDialogId() = 123L

    fun getSendMessagesHelper() = helper

    fun getSendMonoForumPeerId() = 963L
}

private class ObfuscatedUnlock {
    var a: View? = null
    val b: String = "description"
    val z: FrameLayout? = null
}

private class AmbiguousUnlock {
    val a: View? = null
    val z: FrameLayout? = null
}

private class AmbiguousPreview {
    var a: PreviewDrawer? = null
    var z: PreviewDrawer? = null
}
