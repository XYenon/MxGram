package dev.xyenon.mxgram

import android.graphics.Bitmap
import java.io.ByteArrayOutputStream
import java.io.FileOutputStream

internal object AnimatedWebpEncoder {
    // libwebp mux: ANIMATION_FLAG = 0x02, ALPHA_FLAG = 0x10
    private const val VP8X_ANIMATION_FLAG: Byte = 0x02
    private const val VP8X_ALPHA_FLAG: Byte = 0x10

    // ANMF: dispose to ANIM background before drawing the next frame
    private const val ANMF_DISPOSE_TO_BACKGROUND: Int = 0x01

    fun encode(
        frames: List<Bitmap>,
        frameDurationMs: Int,
        outputPath: String,
    ): Boolean {
        if (frames.isEmpty()) {
            return false
        }
        val width = frames[0].width
        val height = frames[0].height
        if (width <= 0 || height <= 0) {
            return false
        }
        val frameChunks =
            frames.mapNotNull { frame ->
                if (frame.width != width || frame.height != height) {
                    return@mapNotNull null
                }
                WebpCodecChunks.encodeFrame(frame)
            }
        if (frameChunks.size != frames.size) {
            return false
        }
        return writeAnimatedWebp(
            width = width,
            height = height,
            frameDurationMs = frameDurationMs.coerceAtLeast(1),
            frameChunks = frameChunks,
            hasAlpha = frames.any { it.hasAlpha() },
            outputPath = outputPath,
        )
    }

    internal fun encodeFromCodecChunks(
        width: Int,
        height: Int,
        frameDurationMs: Int,
        frameChunks: List<Pair<String, ByteArray>>,
        outputPath: String,
        hasAlpha: Boolean = frameChunks.any { WebpCodecChunks.hasAlpha(it) },
    ): Boolean {
        if (width <= 0 || height <= 0 || frameChunks.isEmpty()) {
            return false
        }
        return writeAnimatedWebp(
            width = width,
            height = height,
            frameDurationMs = frameDurationMs.coerceAtLeast(1),
            frameChunks = frameChunks,
            hasAlpha = hasAlpha,
            outputPath = outputPath,
        )
    }

    private fun writeAnimatedWebp(
        width: Int,
        height: Int,
        frameDurationMs: Int,
        frameChunks: List<Pair<String, ByteArray>>,
        hasAlpha: Boolean,
        outputPath: String,
    ): Boolean {
        val webpPayload = ByteArrayOutputStream()
        webpPayload.write(buildVp8xChunk(width, height, hasAlpha))
        webpPayload.write(buildAnimChunk())
        for ((codec, payload) in frameChunks) {
            webpPayload.write(buildAnmfChunk(width, height, frameDurationMs, codec, payload))
        }

        val riffPayload = ByteArrayOutputStream()
        riffPayload.write("WEBP".toByteArray())
        riffPayload.write(webpPayload.toByteArray())

        return try {
            FileOutputStream(outputPath).use { stream ->
                stream.write("RIFF".toByteArray())
                writeLe32(stream, riffPayload.size())
                stream.write(riffPayload.toByteArray())
            }
            true
        } catch (_: Throwable) {
            false
        }
    }

    private fun buildVp8xChunk(
        width: Int,
        height: Int,
        hasAlpha: Boolean,
    ): ByteArray {
        val payload = ByteArray(10)
        payload[0] = (VP8X_ANIMATION_FLAG.toInt() or if (hasAlpha) VP8X_ALPHA_FLAG.toInt() else 0).toByte()
        writeLe24(payload, 4, width - 1)
        writeLe24(payload, 7, height - 1)
        return wrapChunk("VP8X", payload)
    }

    private fun buildAnimChunk(): ByteArray {
        val payload = ByteArray(6)
        writeLe24(payload, 0, 0)
        writeLe16(payload, 3, 0)
        return wrapChunk("ANIM", payload)
    }

    private fun buildAnmfChunk(
        width: Int,
        height: Int,
        frameDurationMs: Int,
        codecFourCc: String,
        codecPayload: ByteArray,
    ): ByteArray {
        val payload = ByteArrayOutputStream()
        writeUint24(payload, 0)
        writeUint24(payload, 0)
        writeUint24(payload, width - 1)
        writeUint24(payload, height - 1)
        writeUint24(payload, frameDurationMs)
        payload.write(ANMF_DISPOSE_TO_BACKGROUND)
        payload.write(wrapChunk(codecFourCc, codecPayload))
        return wrapChunk("ANMF", payload.toByteArray())
    }

    private fun wrapChunk(
        fourCc: String,
        payload: ByteArray,
    ): ByteArray {
        val paddedSize = payload.size + (payload.size and 1)
        val chunk = ByteArray(8 + paddedSize)
        fourCc.toByteArray().copyInto(chunk, 0, 0, 4)
        writeLe32(chunk, 4, payload.size)
        payload.copyInto(chunk, 8)
        return chunk
    }

    private fun writeUint24(
        stream: ByteArrayOutputStream,
        value: Int,
    ) {
        stream.write(value and 0xff)
        stream.write(value shr 8 and 0xff)
        stream.write(value shr 16 and 0xff)
    }

    private fun writeLe16(
        buffer: ByteArray,
        offset: Int,
        value: Int,
    ) {
        buffer[offset] = (value and 0xff).toByte()
        buffer[offset + 1] = (value shr 8 and 0xff).toByte()
    }

    private fun writeLe24(
        buffer: ByteArray,
        offset: Int,
        value: Int,
    ) {
        buffer[offset] = (value and 0xff).toByte()
        buffer[offset + 1] = (value shr 8 and 0xff).toByte()
        buffer[offset + 2] = (value shr 16 and 0xff).toByte()
    }

    private fun writeLe32(
        buffer: ByteArray,
        offset: Int,
        value: Int,
    ) {
        buffer[offset] = (value and 0xff).toByte()
        buffer[offset + 1] = (value shr 8 and 0xff).toByte()
        buffer[offset + 2] = (value shr 16 and 0xff).toByte()
        buffer[offset + 3] = (value shr 24 and 0xff).toByte()
    }

    private fun writeLe32(
        stream: FileOutputStream,
        value: Int,
    ) {
        stream.write(value and 0xff)
        stream.write(value shr 8 and 0xff)
        stream.write(value shr 16 and 0xff)
        stream.write(value shr 24 and 0xff)
    }
}
