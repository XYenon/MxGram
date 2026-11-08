package dev.xyenon.mxgram

import android.app.Activity
import android.util.Log
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
            Log.w(TAG, "saveStickerFile: sticker file does not exist: $path")
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
        val saveFile =
            mediaControllerClass.declaredMethods.firstOrNull { method ->
                val params = method.parameterTypes
                (
                    method.name == "saveFile" ||
                        java.lang.reflect.Modifier
                            .isStatic(method.modifiers)
                ) &&
                    params.size in 6..7 &&
                    params[0] == String::class.java &&
                    params[1].isAssignableFrom(activity.javaClass) &&
                    params[2] == java.lang.Integer.TYPE
            } ?: run {
                Log.w(TAG, "saveToGallery: MediaController.saveFile method not found")
                return
            }
        saveFile.isAccessible = true
        val callbackType = saveFile.parameterTypes[5]
        val callback =
            if (onSaved == null) {
                null
            } else {
                Proxy.newProxyInstance(classLoader, arrayOf(callbackType)) { _, method, _ ->
                    if (method.name == "run" || method.name == "accept") {
                        onSaved()
                    }
                    null
                }
            }
        Log.i(TAG, "saveToGallery: saving sticker to gallery: $path ($mimeType)")
        if (saveFile.parameterCount == 7) {
            saveFile.invoke(null, path, activity, GALLERY_SAVE_TYPE, null, mimeType, callback, true)
        } else {
            saveFile.invoke(null, path, activity, GALLERY_SAVE_TYPE, null, mimeType, callback)
        }
    }

    private fun resolveMessageMimeType(
        messageObject: Any,
        classLoader: ClassLoader?,
    ): String? {
        val document = TelegramObfuscationResolver.getDocument(messageObject)
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
        val messageOwner = TelegramObfuscationResolver.getMessageOwner(messageObject) ?: return null
        var path = runCatching { findField(messageOwner.javaClass, "attachPath").get(messageOwner) as? String }.getOrNull()
        if (!path.isNullOrEmpty() && File(path).exists()) {
            return path
        }
        val account = resolveMessageAccount(messageObject) ?: resolveCurrentAccount(classLoader)
        val fileLoaderClass = TelegramObfuscationResolver.resolveFileLoader(classLoader)
        val getPathToMessage = TelegramObfuscationResolver.resolveGetPathToMessageMethod(fileLoaderClass, classLoader)
        if (getPathToMessage != null) {
            val fileLoader = TelegramObfuscationResolver.resolveFileLoaderInstance(fileLoaderClass, account)
            path =
                (
                    if (java.lang.reflect.Modifier
                            .isStatic(getPathToMessage.modifiers)
                    ) {
                        getPathToMessage.invoke(null, messageOwner)
                    } else {
                        getPathToMessage.invoke(fileLoader, messageOwner)
                    } as? File
                )?.absolutePath
            if (!path.isNullOrEmpty() && File(path).exists()) {
                return path
            }
        }
        val document = TelegramObfuscationResolver.getDocument(messageObject) ?: return null
        return resolveDocumentPath(document, classLoader, account)
    }

    private fun resolveMessageAccount(messageObject: Any): Int? =
        try {
            findFieldOrNull(messageObject.javaClass, "currentAccount")?.getInt(messageObject)
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
        val fileLoaderClass = TelegramObfuscationResolver.resolveFileLoader(classLoader)
        val getPathToAttach = TelegramObfuscationResolver.resolveGetPathToAttachMethod(fileLoaderClass, classLoader) ?: return null
        val fileLoader = TelegramObfuscationResolver.resolveFileLoaderInstance(fileLoaderClass, account)
        val file =
            (
                if (java.lang.reflect.Modifier
                        .isStatic(getPathToAttach.modifiers)
                ) {
                    getPathToAttach.invoke(null, document, true)
                } else {
                    getPathToAttach.invoke(fileLoader, document, true)
                }
            ) as? File ?: return null
        return if (file.exists()) file.absolutePath else null
    }

    private fun resolveCurrentAccount(classLoader: ClassLoader?): Int {
        if (classLoader == null) {
            return 0
        }
        return try {
            val userConfigClass =
                findClassOrNull("org.telegram.messenger.UserConfig", classLoader)
                    ?: return 0
            val selectedAccountField =
                findFieldOrNull(userConfigClass, "selectedAccount")
                    ?: return 0
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
        private const val TAG = "MxGram"
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
