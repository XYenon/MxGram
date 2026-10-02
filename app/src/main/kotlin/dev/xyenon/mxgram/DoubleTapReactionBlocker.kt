package dev.xyenon.mxgram

import android.util.Log
import android.view.View
import java.lang.reflect.Field
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy

internal object DoubleTapReactionBlocker {
    fun disable(
        chatActivity: Any,
        classLoader: ClassLoader?,
        logError: (String, Throwable) -> Unit,
    ) {
        val loader = classLoader ?: chatActivity.javaClass.classLoader
        try {
            val recyclerListViewClass =
                loader?.let { TelegramObfuscationResolver.resolveRecyclerListView(it) }
                    ?: run {
                        Log.w("MxGram", "DoubleTapReactionBlocker: RecyclerListView class not resolved")
                        return
                    }
            val listenerField =
                findFieldOrNull(recyclerListViewClass, "onItemClickListenerExtended")
                    ?: findExtendedClickListenerField(recyclerListViewClass)
                    ?: run {
                        Log.w("MxGram", "DoubleTapReactionBlocker: onItemClickListenerExtended field not found")
                        return
                    }
            val chatListView =
                findMessageListView(chatActivity, recyclerListViewClass, listenerField)
                    ?: run {
                        Log.w("MxGram", "DoubleTapReactionBlocker: message list with extended listener not found")
                        return
                    }
            val originalListener =
                listenerField.get(chatListView)
                    ?: run {
                        Log.w("MxGram", "DoubleTapReactionBlocker: original onItemClickListenerExtended is null")
                        return
                    }
            if (Proxy.isProxyClass(originalListener.javaClass)) {
                val handler = Proxy.getInvocationHandler(originalListener)
                if (handler is DoubleTapDisablingHandler) {
                    return
                }
            }

            val listenerInterface = listenerField.type
            val proxy =
                Proxy.newProxyInstance(
                    loader,
                    arrayOf(listenerInterface),
                    DoubleTapDisablingHandler(originalListener),
                )

            val setter =
                recyclerListViewClass.declaredMethods.firstOrNull {
                    (it.name == "setOnItemClickListener" || it.name == "setOnItemClickListenerExtended") &&
                        it.parameterCount == 1 && it.parameterTypes[0] == listenerInterface
                } ?: recyclerListViewClass.declaredMethods.single {
                    it.parameterTypes.contentEquals(arrayOf(listenerInterface)) && it.returnType == java.lang.Void.TYPE
                }
            setter.isAccessible = true
            setter.invoke(chatListView, proxy)
            Log.i("MxGram", "Disabled double-tap listener: ${listenerField.name}")
        } catch (t: Throwable) {
            logError("Failed to replace Telegram double-tap listener", t)
        }
    }

    internal fun findMessageListView(
        chatActivity: Any,
        listType: Class<*>,
        listenerField: Field,
    ): Any? {
        findFieldOrNull(chatActivity.javaClass, "chatListView")?.get(chatActivity)?.let { return it }
        // ChatActivity also owns search/mention lists. Only the message list uses this listener.
        return chatActivity.javaClass.declaredFields
            .asSequence()
            .filter { listType.isAssignableFrom(it.type) }
            .mapNotNull {
                it.isAccessible = true
                it.get(chatActivity)
            }.filter { listenerField.get(it) != null }
            .singleOrNull()
    }

    internal fun findExtendedClickListenerField(type: Class<*>): Field? =
        type.declaredFields
            .singleOrNull { field ->
                field.type.isInterface && field.type.methods.any(::isHasDoubleTap) &&
                    field.type.methods.any(::isOnDoubleTap)
            }?.also { it.isAccessible = true }

    private fun isHasDoubleTap(method: Method): Boolean =
        method.returnType == java.lang.Boolean.TYPE &&
            (method.name == "hasDoubleTap" || method.parameterTypes.contentEquals(arrayOf(View::class.java)))

    private fun isOnDoubleTap(method: Method): Boolean =
        method.returnType == java.lang.Void.TYPE &&
            (
                method.name == "onDoubleTap" ||
                    method.parameterTypes.contentEquals(
                        arrayOf(View::class.java, java.lang.Float.TYPE, java.lang.Float.TYPE),
                    )
            )

    internal class DoubleTapDisablingHandler(
        private val original: Any,
    ) : InvocationHandler {
        @Throws(Throwable::class)
        override fun invoke(
            proxy: Any,
            method: Method,
            args: Array<Any?>?,
        ): Any? {
            val name = method.name
            if (isHasDoubleTap(method)) {
                return false
            }
            if (isOnDoubleTap(method)) {
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
