package dev.xyenon.mxgram

import java.io.File

internal data class WebpAnimationInfo(
    val width: Int,
    val height: Int,
    val frameCount: Int,
    val hasAlpha: Boolean,
    val loopCount: Int,
    val frameDurationsMs: List<Int>,
    val disposeToBackground: List<Boolean>,
)

internal object WebpAnimationInspector {
    // libwebp mux: ANIMATION_FLAG = 0x02, ALPHA_FLAG = 0x10
    private const val VP8X_ANIMATION_FLAG = 0x02
    private const val VP8X_ALPHA_FLAG = 0x10
    private const val ANMF_DISPOSE_TO_BACKGROUND = 0x01

    fun inspect(file: File): WebpAnimationInfo? {
        if (!file.exists() || file.length() < 16) {
            return null
        }
        return inspect(file.readBytes())
    }

    fun inspect(bytes: ByteArray): WebpAnimationInfo? {
        if (bytes.size < 16 || !bytes.hasFourCc(0, "RIFF") || !bytes.hasFourCc(8, "WEBP")) {
            return null
        }
        var offset = 12
        var width = 0
        var height = 0
        var hasAlpha = false
        var animated = false
        var loopCount = 0
        var frameCount = 0
        var hasAnimChunk = false
        val frameDurationsMs = mutableListOf<Int>()
        val disposeToBackground = mutableListOf<Boolean>()

        while (offset + 8 <= bytes.size) {
            val fourCc = bytes.fourCcAt(offset)
            val size = bytes.readLe32(offset + 4)
            val payloadStart = offset + 8
            val payloadEnd = payloadStart + size
            if (payloadEnd > bytes.size) {
                return null
            }
            val payload = bytes.copyOfRange(payloadStart, payloadEnd)
            when (fourCc) {
                "VP8X" -> {
                    if (payload.size < 10) {
                        return null
                    }
                    val flags = payload[0].toInt() and 0xff
                    hasAlpha = flags and VP8X_ALPHA_FLAG != 0
                    animated = flags and VP8X_ANIMATION_FLAG != 0
                    width = payload.readLe24(4) + 1
                    height = payload.readLe24(7) + 1
                }

                "ANIM" -> {
                    if (payload.size < 6) {
                        return null
                    }
                    hasAnimChunk = true
                    loopCount = payload.readLe16(3)
                }

                "ANMF" -> {
                    frameCount += 1
                    if (payload.size < 16 || !payloadContainsCodecChunk(payload)) {
                        return null
                    }
                    frameDurationsMs += payload.readUint24(12)
                    disposeToBackground += payload[15].toInt() and ANMF_DISPOSE_TO_BACKGROUND != 0
                }
            }
            offset = payloadEnd + (size and 1)
        }

        if ((!animated && !hasAnimChunk) || width <= 0 || height <= 0 || frameCount <= 0) {
            return null
        }
        return WebpAnimationInfo(
            width = width,
            height = height,
            frameCount = frameCount,
            hasAlpha = hasAlpha,
            loopCount = loopCount,
            frameDurationsMs = frameDurationsMs,
            disposeToBackground = disposeToBackground,
        )
    }

    fun isValidAnimatedWebp(file: File): Boolean = inspect(file) != null

    private fun payloadContainsCodecChunk(payload: ByteArray): Boolean {
        var offset = 16
        while (offset + 8 <= payload.size) {
            val fourCc = payload.fourCcAt(offset)
            if (fourCc == "VP8L" || fourCc == "VP8 ") {
                return true
            }
            val size = payload.readLe32(offset + 4)
            offset += 8 + size + (size and 1)
        }
        return false
    }

    private fun ByteArray.readUint24(offset: Int): Int =
        (this[offset].toInt() and 0xff) or
            ((this[offset + 1].toInt() and 0xff) shl 8) or
            ((this[offset + 2].toInt() and 0xff) shl 16)

    private fun ByteArray.readLe16(offset: Int): Int = (this[offset].toInt() and 0xff) or ((this[offset + 1].toInt() and 0xff) shl 8)

    private fun ByteArray.readLe24(offset: Int): Int =
        (this[offset].toInt() and 0xff) or
            ((this[offset + 1].toInt() and 0xff) shl 8) or
            ((this[offset + 2].toInt() and 0xff) shl 16)

    private fun ByteArray.readLe32(offset: Int): Int =
        (this[offset].toInt() and 0xff) or
            ((this[offset + 1].toInt() and 0xff) shl 8) or
            ((this[offset + 2].toInt() and 0xff) shl 16) or
            ((this[offset + 3].toInt() and 0xff) shl 24)

    private fun ByteArray.fourCcAt(offset: Int): String = String(this, offset, 4)

    private fun ByteArray.hasFourCc(
        offset: Int,
        expected: String,
    ): Boolean = offset + expected.length <= size && String(this, offset, expected.length) == expected
}
