package dev.xyenon.mxgram

import android.graphics.Bitmap
import android.os.Build
import java.io.ByteArrayOutputStream

internal object WebpCodecChunks {
    fun encodeFrame(bitmap: Bitmap): Pair<String, ByteArray>? {
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

    fun hasAlpha(chunk: Pair<String, ByteArray>): Boolean {
        val (codec, payload) = chunk
        if (codec != "VP8L" || payload.isEmpty()) {
            return false
        }
        return payload[0] == 0x2f.toByte() && (payload[4].toInt() and 0x10) != 0
    }

    fun extractFromSingleImageWebp(webpFile: ByteArray): Pair<String, ByteArray>? {
        if (webpFile.size < 12 || !webpFile.hasFourCc(0, "RIFF") || !webpFile.hasFourCc(8, "WEBP")) {
            return null
        }
        var offset = 12
        while (offset + 8 <= webpFile.size) {
            val fourCc = webpFile.fourCcAt(offset)
            val size = webpFile.readLe32(offset + 4)
            val payloadStart = offset + 8
            val payloadEnd = payloadStart + size
            if (payloadEnd > webpFile.size) {
                return null
            }
            if (fourCc == "VP8L" || fourCc == "VP8 ") {
                return fourCc to webpFile.copyOfRange(payloadStart, payloadEnd)
            }
            offset = payloadEnd + (size and 1)
        }
        return null
    }

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
