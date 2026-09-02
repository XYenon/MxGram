package dev.xyenon.mxgram

import java.lang.reflect.Field
import java.lang.reflect.Method

internal fun getStaticIntFieldValue(
    type: Class<*>,
    name: String,
    fallback: Int,
): Int =
    try {
        val field = type.getDeclaredField(name)
        field.isAccessible = true
        field.getInt(null)
    } catch (_: Throwable) {
        fallback
    }

internal fun resolveTelegramDrawable(
    classLoader: ClassLoader?,
    drawableName: String,
    fallback: Int,
): Int {
    if (classLoader == null) {
        return fallback
    }
    return try {
        val drawableClass = Class.forName("org.telegram.messenger.R\$drawable", false, classLoader)
        val field = drawableClass.getDeclaredField(drawableName)
        field.isAccessible = true
        field.getInt(null)
    } catch (_: Throwable) {
        fallback
    }
}

@Throws(Exception::class)
internal fun invokeStaticBoolean(
    type: Class<*>,
    name: String,
    args: Array<Any?>?,
): Boolean {
    val result = invokeStaticBooleanOrNull(type, name, args)
    return result == true
}

@Throws(Exception::class)
internal fun invokeStaticBooleanOrNull(
    type: Class<*>,
    name: String,
    args: Array<Any?>?,
): Boolean? {
    val paramCount = args?.size ?: 0
    for (method in type.declaredMethods) {
        if (method.name != name || method.parameterCount != paramCount) {
            continue
        }
        if (!method.parameterTypes.indices.all { index ->
                isInvocationCompatible(method.parameterTypes[index], args?.get(index))
            }
        ) {
            continue
        }
        // Disambiguate ChatObject.canManageTopic overloads: we want the one that takes a topic
        // object (not a long topicId).
        if (name == "canManageTopic" && paramCount == 3 && method.parameterTypes[2] == java.lang.Long.TYPE) {
            continue
        }
        method.isAccessible = true
        val result = method.invoke(null, *(args ?: emptyArray<Any?>()))
        if (result is Boolean) {
            return result
        }
    }
    return null
}

private fun isInvocationCompatible(
    parameterType: Class<*>,
    argument: Any?,
): Boolean {
    if (argument == null) {
        return !parameterType.isPrimitive
    }
    if (!parameterType.isPrimitive) {
        return parameterType.isInstance(argument)
    }
    val boxedType =
        when (parameterType) {
            java.lang.Boolean.TYPE -> java.lang.Boolean::class.java
            java.lang.Byte.TYPE -> java.lang.Byte::class.java
            java.lang.Character.TYPE -> java.lang.Character::class.java
            java.lang.Short.TYPE -> java.lang.Short::class.java
            java.lang.Integer.TYPE -> java.lang.Integer::class.java
            java.lang.Long.TYPE -> java.lang.Long::class.java
            java.lang.Float.TYPE -> java.lang.Float::class.java
            java.lang.Double.TYPE -> java.lang.Double::class.java
            else -> return false
        }
    return boxedType.isInstance(argument)
}

@Throws(NoSuchFieldException::class)
internal fun findField(
    type: Class<*>,
    name: String,
): Field {
    var current: Class<*>? = type
    while (current != null) {
        try {
            val field = current.getDeclaredField(name)
            field.isAccessible = true
            return field
        } catch (_: NoSuchFieldException) {
            current = current.superclass
        }
    }
    throw NoSuchFieldException(type.name + '#' + name)
}

/**
 * Picks a declared method by name when Telegram ships multiple arities across releases.
 * When several arities match, the highest parameter count wins (e.g. 5 over 4, 19 over 18).
 * Verified against Telegram Android 12.9.2 (6991).
 */
internal fun findDeclaredMethodByNameAndArity(
    type: Class<*>,
    name: String,
    vararg arities: Int,
): Method? {
    if (arities.isEmpty()) {
        return null
    }
    val allowed = arities.toSet()
    return type.declaredMethods
        .filter { method -> method.name == name && method.parameterCount in allowed }
        .maxByOrNull { it.parameterCount }
}

@Throws(NoSuchMethodException::class)
internal fun findMethod(
    type: Class<*>,
    name: String,
    vararg parameterTypes: Class<*>,
): Method {
    var current: Class<*>? = type
    while (current != null) {
        try {
            val method = current.getDeclaredMethod(name, *parameterTypes)
            method.isAccessible = true
            return method
        } catch (_: NoSuchMethodException) {
            current = current.superclass
        }
    }
    throw NoSuchMethodException(type.name + '#' + name)
}
