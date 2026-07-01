package dev.xyenon.mxgram

import android.graphics.Bitmap
import java.io.File

internal class TgsStickerConverter(
    private val logError: (String, Throwable) -> Unit,
) {
    fun convertToAnimatedWebp(
        path: String,
        classLoader: ClassLoader,
    ): String? {
        val frames = renderFrames(path, classLoader) ?: return null
        return try {
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
            outputPath
        } catch (t: Throwable) {
            logError("Failed to encode TGS sticker as animated WebP", t)
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
        val cacheOptionsClass = Class.forName("org.telegram.messenger.utils.BitmapsCache\$CacheOptions", false, classLoader)
        val cacheOptions = cacheOptionsClass.getDeclaredConstructor().newInstance()
        val lottieClass = Class.forName("org.telegram.ui.Components.RLottieDrawable", false, classLoader)
        val drawable =
            lottieClass
                .getConstructor(
                    File::class.java,
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                    cacheOptionsClass,
                    java.lang.Boolean.TYPE,
                    IntArray::class.java,
                    Int::class.javaPrimitiveType,
                ).newInstance(File(path), 512, 512, cacheOptions, false, null, 0)

        return try {
            val width = invokeMethod(drawable, "getIntrinsicWidth") as? Int ?: return null
            val height = invokeMethod(drawable, "getIntrinsicHeight") as? Int ?: return null
            if (width <= 0 || height <= 0) {
                return null
            }
            val fps = readStickerFps(drawable)
            invokeMethod(drawable, "setAllowDrawFramesWhileCacheGenerating", true)
            invokeMethod(drawable, "prepareForGenerateCache")
            findMethod(drawable.javaClass, "setGeneratingFrame", Int::class.javaPrimitiveType!!).invoke(drawable, 0)
            val scratch = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val frameChunks = ArrayList<Pair<String, ByteArray>>()
            try {
                while (true) {
                    scratch.eraseColor(0)
                    if ((invokeMethod(drawable, "getNextFrame", scratch) as? Int) != 1) {
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
            try {
                findMethod(drawable.javaClass, "recycle", Boolean::class.javaPrimitiveType!!).invoke(drawable, false)
            } catch (t: Throwable) {
                logError("Failed to recycle RLottieDrawable", t)
            }
            try {
                invokeMethod(drawable, "releaseForGenerateCache")
            } catch (_: Throwable) {
            }
        }
    }

    private fun readStickerFps(drawable: Any): Int =
        try {
            val metaData = findField(drawable.javaClass, "metaData").get(drawable) as? IntArray
            metaData?.getOrNull(1)?.takeIf { it > 0 } ?: DEFAULT_FPS
        } catch (_: Throwable) {
            DEFAULT_FPS
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
        val frameChunks: List<Pair<String, ByteArray>>,
    )

    companion object {
        private const val DEFAULT_FPS = 30
    }
}
