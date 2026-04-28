package dev.xyenon.mxgram

import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy

internal object DoubleTapReactionBlocker {
    fun disable(
        chatActivity: Any,
        classLoader: ClassLoader?,
        logError: (String, Throwable) -> Unit,
    ) {
        try {
            val chatListView = findField(chatActivity.javaClass, "chatListView").get(chatActivity) ?: return

            val recyclerListViewClass =
                Class.forName("org.telegram.ui.Components.RecyclerListView", false, classLoader)
            val listenerField = findField(recyclerListViewClass, "onItemClickListenerExtended")
            val originalListener = listenerField.get(chatListView) ?: return
            if (Proxy.isProxyClass(originalListener.javaClass)) {
                val handler = Proxy.getInvocationHandler(originalListener)
                if (handler is DoubleTapDisablingHandler) {
                    return
                }
            }

            val listenerInterface =
                Class.forName(
                    "org.telegram.ui.Components.RecyclerListView\$OnItemClickListenerExtended",
                    false,
                    classLoader,
                )
            val proxy =
                Proxy.newProxyInstance(
                    classLoader,
                    arrayOf<Class<*>>(listenerInterface),
                    DoubleTapDisablingHandler(originalListener),
                )

            val setter = recyclerListViewClass.getDeclaredMethod("setOnItemClickListener", listenerInterface)
            setter.isAccessible = true
            setter.invoke(chatListView, proxy)
        } catch (t: Throwable) {
            logError("Failed to replace Telegram double-tap listener", t)
        }
    }

    private class DoubleTapDisablingHandler(
        private val original: Any,
    ) : InvocationHandler {
        @Throws(Throwable::class)
        override fun invoke(
            proxy: Any,
            method: Method,
            args: Array<Any?>?,
        ): Any? {
            val name = method.name
            if (name == "hasDoubleTap") {
                return false
            }
            if (name == "onDoubleTap") {
                return null
            }
            if (method.declaringClass == Any::class.java) {
                if (name == "toString") {
                    return "$original[doubleTapDisabled]"
                }
                if (name == "hashCode") {
                    return original.hashCode()
                }
                if (name == "equals") {
                    return proxy === args?.get(0)
                }
            }
            return method.invoke(original, *(args ?: emptyArray<Any?>()))
        }

        override fun equals(other: Any?): Boolean = this === other

        override fun hashCode(): Int = original.hashCode()
    }
}
