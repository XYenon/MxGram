package dev.xyenon.mxgram

import android.app.Activity
import java.io.File
import java.lang.reflect.Proxy

internal class StickerSaver(
    private val logError: (String, Throwable) -> Unit,
) {
    private val tgsStickerConverter = TgsStickerConverter(logError)

    fun saveMessageSticker(
        activity: Activity,
        messageObject: Any,
        onSaved: (() -> Unit)? = null,
    ) {
        try {
            val classLoader = activity.classLoader
            val path = resolveMessagePath(messageObject, classLoader) ?: return
            val mimeType = resolveMessageMimeType(messageObject, classLoader)
            saveStickerFile(activity, path, mimeType, classLoader, onSaved)
        } catch (t: Throwable) {
            logError("Failed to save message sticker", t)
        }
    }

    fun saveDocumentSticker(
        activity: Activity,
        document: Any,
        classLoader: ClassLoader?,
        account: Int,
        onSaved: (() -> Unit)? = null,
    ) {
        try {
            val path = resolveDocumentPath(document, classLoader, account) ?: return
            val mimeType = resolveDocumentMimeType(document)
            saveStickerFile(activity, path, mimeType, classLoader, onSaved)
        } catch (t: Throwable) {
            logError("Failed to save document sticker", t)
        }
    }

    private fun saveStickerFile(
        activity: Activity,
        path: String,
        mimeType: String?,
        classLoader: ClassLoader?,
        onSaved: (() -> Unit)?,
    ) {
        if (!File(path).exists()) {
            return
        }
        postToGlobalQueue(classLoader) {
            try {
                val outputPath = resolveOutputPath(path, mimeType, classLoader) ?: return@postToGlobalQueue
                val outputMimeType = resolveOutputMimeType(mimeType, outputPath)
                postToGlobalQueue(classLoader) {
                    saveToGallery(activity, outputPath, outputMimeType, classLoader, onSaved)
                }
            } catch (t: Throwable) {
                logError("Failed to process sticker before saving", t)
            }
        }
    }

    private fun resolveOutputPath(
        path: String,
        mimeType: String?,
        classLoader: ClassLoader?,
    ): String? {
        if (!isTgsSticker(mimeType) || classLoader == null) {
            return path
        }
        return tgsStickerConverter.convertToAnimatedWebp(path, classLoader) ?: path
    }

    private fun resolveOutputMimeType(
        sourceMimeType: String?,
        outputPath: String,
    ): String? {
        if (isTgsSticker(sourceMimeType) && outputPath.endsWith(".webp", ignoreCase = true)) {
            return "image/webp"
        }
        return sourceMimeType
    }

    private fun isTgsSticker(mimeType: String?): Boolean = mimeType == "application/x-tgsticker" || mimeType == "application/x-tgsdice"

    private fun saveToGallery(
        activity: Activity,
        path: String,
        mimeType: String?,
        classLoader: ClassLoader?,
        onSaved: (() -> Unit)?,
    ) {
        if (classLoader == null) {
            return
        }
        val mediaControllerClass = Class.forName("org.telegram.messenger.MediaController", false, classLoader)
        val callbackClass = Class.forName("org.telegram.messenger.Utilities\$Callback", false, classLoader)
        val callback =
            if (onSaved == null) {
                null
            } else {
                Proxy.newProxyInstance(classLoader, arrayOf(callbackClass)) { _, method, _ ->
                    if (method.name == "run") {
                        onSaved()
                    }
                    null
                }
            }
        val saveFile =
            mediaControllerClass.declaredMethods.firstOrNull { method ->
                val params = method.parameterTypes
                method.name == "saveFile" &&
                    params.size == 7 &&
                    params[0] == String::class.java &&
                    params[1].isAssignableFrom(activity.javaClass) &&
                    params[2] == java.lang.Integer.TYPE &&
                    params[5] == callbackClass &&
                    params[6] == java.lang.Boolean.TYPE
            } ?: return
        saveFile.isAccessible = true
        saveFile.invoke(null, path, activity, GALLERY_SAVE_TYPE, null, mimeType, callback, true)
    }

    private fun resolveMessageMimeType(
        messageObject: Any,
        classLoader: ClassLoader?,
    ): String? {
        val document = runCatching { findMethod(messageObject.javaClass, "getDocument").invoke(messageObject) }.getOrNull()
        return resolveDocumentMimeType(document)
    }

    private fun resolveDocumentMimeType(document: Any?): String? {
        if (document == null) {
            return null
        }
        return runCatching {
            findField(document.javaClass, "mime_type").get(document) as? String
        }.getOrNull()
    }

    private fun resolveMessagePath(
        messageObject: Any,
        classLoader: ClassLoader?,
    ): String? {
        if (classLoader == null) {
            return null
        }
        val messageOwner = findField(messageObject.javaClass, "messageOwner").get(messageObject) ?: return null
        var path = findField(messageOwner.javaClass, "attachPath").get(messageOwner) as? String
        if (!path.isNullOrEmpty() && File(path).exists()) {
            return path
        }
        val account = resolveMessageAccount(messageObject) ?: resolveCurrentAccount(classLoader)
        val fileLoaderClass = Class.forName("org.telegram.messenger.FileLoader", false, classLoader)
        val getInstance = fileLoaderClass.getMethod("getInstance", Int::class.javaPrimitiveType)
        val fileLoader = getInstance.invoke(null, account)
        val getPathToMessage =
            fileLoaderClass.getMethod(
                "getPathToMessage",
                Class.forName("org.telegram.tgnet.TLRPC\$Message", false, classLoader),
            )
        path = (getPathToMessage.invoke(fileLoader, messageOwner) as? File)?.absolutePath
        if (!path.isNullOrEmpty() && File(path).exists()) {
            return path
        }
        val document = findMethod(messageObject.javaClass, "getDocument").invoke(messageObject) ?: return null
        return resolveDocumentPath(document, classLoader, account)
    }

    private fun resolveMessageAccount(messageObject: Any): Int? =
        try {
            findField(messageObject.javaClass, "currentAccount").getInt(messageObject)
        } catch (_: Throwable) {
            null
        }

    private fun resolveDocumentPath(
        document: Any,
        classLoader: ClassLoader?,
        account: Int,
    ): String? {
        if (classLoader == null) {
            return null
        }
        val fileLoaderClass = Class.forName("org.telegram.messenger.FileLoader", false, classLoader)
        val getInstance = fileLoaderClass.getMethod("getInstance", Int::class.javaPrimitiveType)
        val fileLoader = getInstance.invoke(null, account)
        val getPathToAttach =
            fileLoaderClass.getMethod(
                "getPathToAttach",
                Class.forName("org.telegram.tgnet.TLObject", false, classLoader),
                java.lang.Boolean.TYPE,
            )
        val file = getPathToAttach.invoke(fileLoader, document, true) as? File ?: return null
        return if (file.exists()) file.absolutePath else null
    }

    private fun resolveCurrentAccount(classLoader: ClassLoader?): Int {
        if (classLoader == null) {
            return 0
        }
        return try {
            val userConfigClass = Class.forName("org.telegram.messenger.UserConfig", false, classLoader)
            val selectedAccountField = userConfigClass.getDeclaredField("selectedAccount")
            selectedAccountField.isAccessible = true
            selectedAccountField.getInt(null)
        } catch (_: Throwable) {
            0
        }
    }

    private fun postToGlobalQueue(
        classLoader: ClassLoader?,
        runnable: Runnable,
    ) {
        if (classLoader == null) {
            Thread(runnable).start()
            return
        }
        try {
            val utilitiesClass = Class.forName("org.telegram.messenger.Utilities", false, classLoader)
            val stageQueueField = utilitiesClass.getDeclaredField("globalQueue")
            stageQueueField.isAccessible = true
            val stageQueue = stageQueueField.get(null)
            val postRunnable = stageQueue.javaClass.getMethod("postRunnable", Runnable::class.java)
            postRunnable.invoke(stageQueue, runnable)
        } catch (_: Throwable) {
            Thread(runnable).start()
        }
    }

    companion object {
        private const val GALLERY_SAVE_TYPE = 0
    }
}

internal fun hasGalleryWritePermission(activity: Activity): Boolean {
    if (android.os.Build.VERSION.SDK_INT < 23) {
        return true
    }
    if (android.os.Build.VERSION.SDK_INT > 28) {
        return true
    }
    return activity.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
        android.content.pm.PackageManager.PERMISSION_GRANTED
}

internal fun requestGalleryWritePermission(activity: Activity) {
    activity.requestPermissions(arrayOf(android.Manifest.permission.WRITE_EXTERNAL_STORAGE), 4)
}
