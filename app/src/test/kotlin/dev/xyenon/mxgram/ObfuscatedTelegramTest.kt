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
