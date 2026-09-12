package dev.xyenon.mxgram

import android.graphics.Bitmap
import android.os.Build
import java.io.ByteArrayOutputStream

internal data class WebpFrame(
    val codecFourCc: String,
    val codecPayload: ByteArray,
    val alphaPayload: ByteArray? = null,
)

internal object WebpCodecChunks {
    fun encodeFrame(bitmap: Bitmap): WebpFrame? {
        val formats =
            buildList {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    add(Bitmap.CompressFormat.WEBP_LOSSLESS)
                }
                @Suppress("DEPRECATION")
                add(Bitmap.CompressFormat.WEBP)
            }
        for (format in formats) {
            val stream = ByteArrayOutputStream()
            if (!bitmap.compress(format, 100, stream)) {
                continue
            }
            val chunk = extractFromSingleImageWebp(stream.toByteArray()) ?: continue
            return chunk
        }
        return null
    }

    fun readVp8lDimensions(payload: ByteArray): Pair<Int, Int>? {
        if (payload.size < 5 || payload[0] != 0x2f.toByte()) {
            return null
        }
        val bits =
            (payload[1].toInt() and 0xff) or
                ((payload[2].toInt() and 0xff) shl 8) or
                ((payload[3].toInt() and 0xff) shl 16) or
                ((payload[4].toInt() and 0xff) shl 24)
        val width = (bits and 0x3fff) + 1
        val height = ((bits ushr 14) and 0x3fff) + 1
        return width to height
    }

    fun hasAlpha(frame: WebpFrame): Boolean {
        if (frame.alphaPayload != null) {
            return true
        }
        val payload = frame.codecPayload
        if (frame.codecFourCc != "VP8L" || payload.size < 5) {
            return false
        }
        return payload[0] == 0x2f.toByte() && (payload[4].toInt() and 0x10) != 0
    }

    fun extractFromSingleImageWebp(webpFile: ByteArray): WebpFrame? {
        if (webpFile.size < 12 || !webpFile.hasFourCc(0, "RIFF") || !webpFile.hasFourCc(8, "WEBP")) {
            return null
        }
        val riffEnd = webpFile.readLe32Unsigned(4) + 8L
        if (riffEnd < 12L || riffEnd > webpFile.size.toLong()) {
            return null
        }
        val limit = riffEnd.toInt()
        var offset = 12
        var alphaPayload: ByteArray? = null
        var codecFourCc: String? = null
        var codecPayload: ByteArray? = null
        var sawVp8x = false
        while (offset + 8 <= limit) {
            val fourCc = webpFile.fourCcAt(offset)
            val size = webpFile.readLe32Unsigned(offset + 4)
            val payloadStart = offset + 8
            val payloadEnd = payloadStart.toLong() + size
            val paddedEnd = payloadEnd + (size and 1L)
            if (payloadEnd > limit || paddedEnd > limit) {
                return null
            }
            val end = payloadEnd.toInt()
            when (fourCc) {
                "VP8X" -> {
                    if (sawVp8x || alphaPayload != null || codecFourCc != null) {
                        return null
                    }
                    sawVp8x = true
                }

                "ALPH" -> {
                    if (alphaPayload != null || codecFourCc != null) {
                        return null
                    }
                    alphaPayload = webpFile.copyOfRange(payloadStart, end)
                }

                "VP8 ", "VP8L" -> {
                    if (codecFourCc != null || (fourCc == "VP8L" && alphaPayload != null)) {
                        return null
                    }
                    val payload = webpFile.copyOfRange(payloadStart, end)
                    if (fourCc == "VP8L" && (payload.size < 5 || payload[0] != 0x2f.toByte())) {
                        return null
                    }
                    codecFourCc = fourCc
                    codecPayload = payload
                }
            }
            offset = paddedEnd.toInt()
        }
        if (offset != limit) {
            return null
        }
        val codec = codecFourCc ?: return null
        val payload = codecPayload ?: return null
        if (alphaPayload != null && codec != "VP8 ") {
            return null
        }
        return WebpFrame(codec, payload, alphaPayload)
    }

    private fun ByteArray.readLe32(offset: Int): Int =
        (this[offset].toInt() and 0xff) or
            ((this[offset + 1].toInt() and 0xff) shl 8) or
            ((this[offset + 2].toInt() and 0xff) shl 16) or
            ((this[offset + 3].toInt() and 0xff) shl 24)

    private fun ByteArray.readLe32Unsigned(offset: Int): Long = readLe32(offset).toLong() and 0xffff_ffffL

    private fun ByteArray.fourCcAt(offset: Int): String = String(this, offset, 4)

    private fun ByteArray.hasFourCc(
        offset: Int,
        expected: String,
    ): Boolean = offset + expected.length <= size && String(this, offset, expected.length) == expected
}
