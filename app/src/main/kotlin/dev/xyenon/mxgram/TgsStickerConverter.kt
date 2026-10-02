package dev.xyenon.mxgram

import android.graphics.Bitmap
import java.io.File
import java.util.zip.GZIPInputStream

internal class TgsStickerConverter(
    private val logError: (String, Throwable) -> Unit,
) {
    fun convertToAnimatedWebp(
        path: String,
        classLoader: ClassLoader,
    ): String? {
        return try {
            val frames = renderFrames(path, classLoader) ?: return null
            val outputPath = outputPathFor(path)
            val frameDurationMs = (1000f / frames.fps.coerceAtLeast(1)).toInt().coerceAtLeast(1)
            if (
                !AnimatedWebpEncoder.encodeFromCodecChunks(
                    width = frames.width,
                    height = frames.height,
                    frameDurationMs = frameDurationMs,
                    frameChunks = frames.frameChunks,
                    outputPath = outputPath,
                )
            ) {
                logError("Animated WebP encoder returned false for $path", IllegalStateException("encode failed"))
                return null
            }
            val outputFile = File(outputPath)
            val info = WebpAnimationInspector.inspect(outputFile)
            if (info == null || info.frameCount != frames.frameChunks.size) {
                outputFile.delete()
                logError(
                    "Animated WebP validation failed for $outputPath",
                    IllegalStateException("expected ${frames.frameChunks.size} frames, got ${info?.frameCount}"),
                )
                return null
            }
            android.util.Log.i(
                "MxGram",
                "Converted TGS to Animated WebP: $path -> $outputPath (${frames.frameChunks.size} frames, ${outputFile.length()} bytes)",
            )
            outputPath
        } catch (t: Throwable) {
            logError("Failed to convert TGS sticker to animated WebP", t)
            null
        }
    }

    private fun outputPathFor(path: String): String {
        val source = File(path)
        val baseName = source.nameWithoutExtension.ifEmpty { source.name }
        return File(source.parentFile, "$baseName.webp").absolutePath
    }

    private fun renderFrames(
        path: String,
        classLoader: ClassLoader,
    ): EncodedFrames? {
        var drawable: Any? = null
        var workingFile: File? = null
        return try {
            val sourceFile = File(path)
            val temporaryFile =
                File.createTempFile(
                    "mxgram-tgs-",
                    ".tgs",
                    sourceFile.parentFile ?: File("."),
                )
            workingFile = temporaryFile
            sourceFile.copyTo(temporaryFile, overwrite = true)

            val lottieClass = TelegramObfuscationResolver.resolveRLottieDrawable(classLoader)
            drawable = createLottieDrawable(lottieClass, temporaryFile.absolutePath)

            val width = invokeMethod(drawable, "getIntrinsicWidth") as? Int ?: return null
            val height = invokeMethod(drawable, "getIntrinsicHeight") as? Int ?: return null
            if (width <= 0 || height <= 0) {
                return null
            }
            val fps = readStickerFps(drawable)
            runCatching { invokeMethod(drawable, "setAllowDrawFramesWhileCacheGenerating", true) }
            invokePrepareForGenerateCache(drawable)
            runCatching {
                findMethodOrNull(drawable.javaClass, "setGeneratingFrame", java.lang.Integer.TYPE)
                    ?.invoke(drawable, 0)
            }
            val scratch = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val frameChunks = ArrayList<WebpFrame>()
            val getNextFrameMethod = resolveGetNextFrameMethod(drawable.javaClass) ?: return null
            try {
                while (true) {
                    scratch.eraseColor(0)
                    if ((getNextFrameMethod.invoke(drawable, scratch) as? Int) != 1) {
                        break
                    }
                    val chunk = WebpCodecChunks.encodeFrame(scratch) ?: return null
                    frameChunks.add(chunk)
                }
            } finally {
                scratch.recycle()
            }
            if (frameChunks.isEmpty()) {
                null
            } else {
                EncodedFrames(width, height, fps, frameChunks)
            }
        } catch (t: Throwable) {
            logError("Failed to render TGS sticker frames", t)
            null
        } finally {
            val createdDrawable = drawable
            if (createdDrawable != null) {
                try {
                    invokeReleaseForGenerateCache(createdDrawable)
                } catch (_: Throwable) {
                }
                try {
                    TelegramObfuscationResolver
                        .resolveRLottieRecycleMethod(createdDrawable.javaClass)
                        ?.invoke(createdDrawable, false)
                        ?: findMethodOrNull(createdDrawable.javaClass, "recycle")?.invoke(createdDrawable)
                } catch (t: Throwable) {
                    logError("Failed to recycle RLottieDrawable", t)
                }
            }
            workingFile?.delete()
        }
    }

    private fun readStickerFps(drawable: Any): Int =
        try {
            val metaData =
                TelegramObfuscationResolver.findRLottieMetaDataField(drawable.javaClass)?.get(drawable) as? IntArray
                    ?: findFieldOrNull(drawable.javaClass, "metaData")?.get(drawable) as? IntArray
            metaData?.getOrNull(1)?.takeIf { it > 0 } ?: DEFAULT_FPS
        } catch (_: Throwable) {
            DEFAULT_FPS
        }

    private fun invokePrepareForGenerateCache(drawable: Any) {
        val method =
            TelegramObfuscationResolver.resolveRLottiePrepareForGenerateCacheMethod(drawable.javaClass)
                ?: findMethodOrNull(drawable.javaClass, "prepareForGenerateCache")
        method?.invoke(drawable)
    }

    private fun invokeReleaseForGenerateCache(drawable: Any) {
        val method =
            TelegramObfuscationResolver.resolveRLottieReleaseForGenerateCacheMethod(drawable.javaClass)
                ?: findMethodOrNull(drawable.javaClass, "releaseForGenerateCache")
        method?.invoke(drawable)
    }

    private fun resolveGetNextFrameMethod(lottieClass: Class<*>): java.lang.reflect.Method? =
        TelegramObfuscationResolver.resolveRLottieGetNextFrameMethod(lottieClass)
            ?: findMethodOrNull(lottieClass, "getNextFrame", Bitmap::class.java)
            ?: lottieClass.methods.firstOrNull { method ->
                method.parameterCount == 1 &&
                    method.parameterTypes[0] == Bitmap::class.java &&
                    method.returnType == java.lang.Integer.TYPE
            }

    private fun invokeMethod(
        instance: Any,
        name: String,
        vararg args: Any?,
    ): Any? {
        for (method in instance.javaClass.methods) {
            if (method.name != name || method.parameterCount != args.size) {
                continue
            }
            method.isAccessible = true
            return method.invoke(instance, *args)
        }
        return null
    }

    private data class EncodedFrames(
        val width: Int,
        val height: Int,
        val fps: Int,
        val frameChunks: List<WebpFrame>,
    )

    companion object {
        private const val DEFAULT_FPS = 30
    }
}

internal fun createLottieDrawable(
    lottieClass: Class<*>,
    path: String,
): Any = createLottieDrawable(lottieClass, null, null, path)

internal fun createLottieDrawable(
    lottieClass: Class<*>,
    cacheOptionsClass: Class<*>?,
    cacheOptions: Any?,
    path: String,
): Any {
    val file = File(path)
    val json = readGzippedLottieJson(file)

    for (constructor in lottieClass.constructors) {
        val params = constructor.parameterTypes
        if (params.isEmpty() || params[0] != File::class.java) {
            continue
        }
        val instance =
            runCatching {
                when (params.size) {
                    // 9-arg: (File, String, int, int, CacheOptions, boolean, int[], int, boolean)
                    9 -> {
                        val options = cacheOptions ?: instantiateCacheOptions(params[4])
                        constructor.newInstance(file, json, 512, 512, options, false, null, 0, false)
                    }

                    // 8-arg: (File, String, int, int, CacheOptions, boolean, int, boolean) - Telegram 12.10.3 (xj0)
                    8 -> {
                        val options = cacheOptions ?: instantiateCacheOptions(params[4])
                        constructor.newInstance(file, json, 512, 512, options, false, 0, false)
                    }

                    // 7-arg: (File, int, int, CacheOptions, boolean, int[], int)
                    7 -> {
                        val options = cacheOptions ?: instantiateCacheOptions(params[3])
                        constructor.newInstance(file, 512, 512, options, false, null, 0)
                    }

                    else -> {
                        null
                    }
                }
            }.getOrNull()
        if (instance != null) {
            return instance
        }
    }
    throw NoSuchMethodException("No matching constructor found for ${lottieClass.name}")
}

private fun instantiateCacheOptions(cacheOptionsClass: Class<*>): Any? =
    runCatching { cacheOptionsClass.getDeclaredConstructor().newInstance() }.getOrNull()
        ?: runCatching {
            cacheOptionsClass.getDeclaredConstructor(java.lang.Integer.TYPE).newInstance(100)
        }.getOrNull()
        ?: runCatching {
            cacheOptionsClass
                .getDeclaredConstructor(
                    java.lang.Integer.TYPE,
                    java.lang.Boolean.TYPE,
                    java.lang.Boolean.TYPE,
                ).newInstance(100, false, false)
        }.getOrNull()

internal fun readGzippedLottieJson(file: File): String? {
    return file.inputStream().buffered().use { input ->
        input.mark(2)
        val isGzip = input.read() == GZIP_MAGIC_FIRST && input.read() == GZIP_MAGIC_SECOND
        input.reset()
        if (!isGzip) {
            return@use null
        }
        GZIPInputStream(input).bufferedReader(Charsets.UTF_8).use { reader ->
            reader.readText()
        }
    }
}

private const val GZIP_MAGIC_FIRST = 0x1F
private const val GZIP_MAGIC_SECOND = 0x8B
