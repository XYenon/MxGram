package dev.xyenon.mxgram

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

class WebpAlphaChunkTest {
    @Test
    fun realLossyAlphaWebpCanBeRemuxedAsAnAnimatedFrame() {
        val source = TestFixtures.resource("lossy-alpha.webp").readBytes()
        val frame = checkNotNull(WebpCodecChunks.extractFromSingleImageWebp(source))
        assertEquals("VP8 ", frame.codecFourCc)
        assertTrue(checkNotNull(frame.alphaPayload).isNotEmpty())

        val output = TestFixtures.artifact("lossy-alpha-animation.webp")
        assertTrue(AnimatedWebpEncoder.encodeFromCodecChunks(8, 8, 33, listOf(frame), output.absolutePath))

        val topLevel = chunks(output.readBytes(), 12, output.length().toInt())
        val frameChunks = chunks(topLevel.single { it.fourCc == "ANMF" }.payload, 16, topLevel.single { it.fourCc == "ANMF" }.payload.size)
        assertEquals(listOf("ALPH", "VP8 "), frameChunks.map { it.fourCc })
    }

    @Test
    fun alphaAndVp8ArePreservedInsideAnimatedFrame() {
        val alpha = byteArrayOf(0, 0, 0x40, 0x80.toByte(), 0xff.toByte())
        val vp8 = byteArrayOf(1, 2, 3, 4)
        val source = webp(chunk("VP8X", ByteArray(10).also { it[0] = 0x10 }), chunk("ALPH", alpha), chunk("VP8 ", vp8))

        val frame = checkNotNull(WebpCodecChunks.extractFromSingleImageWebp(source))
        assertEquals("VP8 ", frame.codecFourCc)
        assertArrayEquals(alpha, frame.alphaPayload)
        assertArrayEquals(vp8, frame.codecPayload)

        val output = TestFixtures.artifact("alpha-frame.webp")
        assertTrue(AnimatedWebpEncoder.encodeFromCodecChunks(2, 2, 33, listOf(frame), output.absolutePath))

        val bytes = output.readBytes()
        assertEquals(bytes.size - 8, bytes.readLe32(4))
        val topLevel = chunks(bytes, 12, bytes.size)
        val vp8x = topLevel.single { it.fourCc == "VP8X" }
        assertEquals(0x12, vp8x.payload[0].toInt() and 0xff)
        val anmf = topLevel.single { it.fourCc == "ANMF" }
        val frameChunks = chunks(anmf.payload, 16, anmf.payload.size)
        assertEquals(listOf("ALPH", "VP8 "), frameChunks.map { it.fourCc })
        assertArrayEquals(alpha, frameChunks[0].payload)
        assertArrayEquals(vp8, frameChunks[1].payload)
        assertEquals(0, anmf.payload[16 + 8 + alpha.size].toInt())
    }

    @Test
    fun malformedAlphaFramesAreRejected() {
        val vp8l = byteArrayOf(0x2f, 0, 0, 0, 0x10)
        assertNull(WebpCodecChunks.extractFromSingleImageWebp(webp(chunk("ALPH", byteArrayOf(0)), chunk("VP8L", vp8l))))
        assertNull(WebpCodecChunks.extractFromSingleImageWebp(webp(chunk("ALPH", byteArrayOf(0)))))
        assertNull(WebpCodecChunks.extractFromSingleImageWebp(webp(chunk("VP8 ", byteArrayOf(1)), chunk("VP8 ", byteArrayOf(2)))))
        assertNull(
            WebpCodecChunks.extractFromSingleImageWebp(
                webp(chunk("ALPH", byteArrayOf(0)), chunk("VP8X", ByteArray(10)), chunk("VP8 ", byteArrayOf(1))),
            ),
        )

        val trailingFragment = webp(chunk("VP8 ", byteArrayOf(1))) + byteArrayOf(1, 2)
        trailingFragment.writeLe32(4, trailingFragment.size - 8)
        assertNull(WebpCodecChunks.extractFromSingleImageWebp(trailingFragment))
    }

    private fun webp(vararg chunks: ByteArray): ByteArray {
        val payload =
            ByteArrayOutputStream()
                .apply {
                    write("WEBP".toByteArray())
                    chunks.forEach(::write)
                }.toByteArray()
        return ByteArrayOutputStream()
            .apply {
                write("RIFF".toByteArray())
                writeLe32(payload.size)
                write(payload)
            }.toByteArray()
    }

    private fun chunk(
        fourCc: String,
        payload: ByteArray,
    ): ByteArray =
        ByteArrayOutputStream()
            .apply {
                write(fourCc.toByteArray())
                writeLe32(payload.size)
                write(payload)
                if (payload.size and 1 != 0) write(0)
            }.toByteArray()

    private fun chunks(
        bytes: ByteArray,
        start: Int,
        end: Int,
    ): List<ParsedChunk> {
        val result = mutableListOf<ParsedChunk>()
        var offset = start
        while (offset + 8 <= end) {
            val size = bytes.readLe32(offset + 4)
            val payloadStart = offset + 8
            val payloadEnd = payloadStart + size
            assertTrue(payloadEnd <= end)
            result += ParsedChunk(String(bytes, offset, 4), bytes.copyOfRange(payloadStart, payloadEnd))
            offset = payloadEnd + (size and 1)
        }
        assertEquals(end, offset)
        return result
    }

    private fun ByteArray.readLe32(offset: Int): Int =
        (this[offset].toInt() and 0xff) or
            ((this[offset + 1].toInt() and 0xff) shl 8) or
            ((this[offset + 2].toInt() and 0xff) shl 16) or
            ((this[offset + 3].toInt() and 0xff) shl 24)

    private fun ByteArray.writeLe32(
        offset: Int,
        value: Int,
    ) {
        this[offset] = (value and 0xff).toByte()
        this[offset + 1] = (value shr 8 and 0xff).toByte()
        this[offset + 2] = (value shr 16 and 0xff).toByte()
        this[offset + 3] = (value shr 24 and 0xff).toByte()
    }

    private fun ByteArrayOutputStream.writeLe32(value: Int) {
        write(value and 0xff)
        write(value shr 8 and 0xff)
        write(value shr 16 and 0xff)
        write(value shr 24 and 0xff)
    }

    private data class ParsedChunk(
        val fourCc: String,
        val payload: ByteArray,
    )
}
