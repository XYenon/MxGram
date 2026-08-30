package dev.xyenon.mxgram

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TelegramReflectionTest {
    @Test
    fun invokeStaticBoolean_selectsCompatibleOverload() {
        assertTrue(
            invokeStaticBoolean(
                OverloadedBooleanMethods::class.java,
                "matches",
                arrayOf(TestUser()),
            ),
        )
        assertFalse(
            invokeStaticBoolean(
                OverloadedBooleanMethods::class.java,
                "matches",
                arrayOf(1L),
            ),
        )
    }
}

private class TestUser

private object OverloadedBooleanMethods {
    @JvmStatic
    fun matches(id: Long): Boolean = id != 1L

    @JvmStatic
    fun matches(user: TestUser): Boolean = user.javaClass == TestUser::class.java
}
