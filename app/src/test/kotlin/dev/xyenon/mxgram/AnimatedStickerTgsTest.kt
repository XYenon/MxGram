package dev.xyenon.mxgram

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AnimatedStickerTgsTest {
    @Test
    fun animatedStickerFixture_matchesUserProvidedFileHash() {
        assertEquals(EXPECTED_SHA256, TestFixtures.sha256(fixture()))
    }

    @Test
    fun animatedStickerFixture_isGzipWrappedLottieJson() {
        val json = checkNotNull(readGzippedLottieJson(fixture()))
        assertTrue(json.startsWith("{"))
        assertTrue(json.contains("\"fr\""))
        assertTrue(json.contains("\"layers\""))
    }

    @Test
    fun currentRlottieConstructor_receivesDecompressedJson() {
        val drawable =
            createLottieDrawable(
                CurrentRlottieDrawable::class.java,
                FakeCacheOptions::class.java,
                FakeCacheOptions(),
                fixture().absolutePath,
            ) as CurrentRlottieDrawable

        assertEquals(fixture().absolutePath, drawable.file.absolutePath)
        assertTrue(drawable.json?.startsWith("{") == true)
    }

    @Test
    fun currentRlottieConstructor_receivesNullForPlainJson() {
        val plainJson = TestFixtures.artifact("plain-lottie.json").apply { writeText("{}") }
        val drawable =
            createLottieDrawable(
                CurrentRlottieDrawable::class.java,
                FakeCacheOptions::class.java,
                FakeCacheOptions(),
                plainJson.absolutePath,
            ) as CurrentRlottieDrawable

        assertNull(drawable.json)
    }

    @Test
    fun legacyRlottieConstructor_remainsSupported() {
        val drawable =
            createLottieDrawable(
                LegacyRlottieDrawable::class.java,
                FakeCacheOptions::class.java,
                FakeCacheOptions(),
                fixture().absolutePath,
            ) as LegacyRlottieDrawable

        assertEquals(fixture().absolutePath, drawable.file.absolutePath)
    }

    @Test
    fun animatedStickerFixture_exposesPinnedLottieStructure() {
        val metadata = metadata()

        assertEquals("5.5.2", metadata.version)
        assertEquals(0, metadata.inPoint)
        assertEquals(25, metadata.outPoint)
        assertEquals(30, metadata.frameRate)
        assertEquals(25, metadata.frameCount)
        assertEquals(512, metadata.width)
        assertEquals(512, metadata.height)
        assertEquals(5, metadata.layerCount)
    }

    @Test
    fun animatedStickerFixture_renderedFrameFixtures_matchStickerCanvas() {
        val metadata = metadata()

        for (index in 0 until metadata.frameCount) {
            val chunk = frameChunk(index)
            assertEquals("VP8L", chunk.first)
            assertTrue(WebpCodecChunks.hasAlpha(chunk))
            val dimensions = checkNotNull(WebpCodecChunks.readVp8lDimensions(chunk.second))
            assertEquals(metadata.width, dimensions.first)
            assertEquals(metadata.height, dimensions.second)
        }
    }

    @Test
    fun animatedStickerFixture_renderedFrames_includeMultipleDistinctImages() {
        val metadata = metadata()
        val payloadHashes =
            (0 until metadata.frameCount)
                .map { frameChunk(it).second.contentHashCode() }
                .toSet()

        assertTrue(
            "Expected multiple visually distinct frames, got ${payloadHashes.size}",
            payloadHashes.size >= 3,
        )
        assertTrue(payloadHashes.contains(frameChunk(0).second.contentHashCode()))
        assertTrue(payloadHashes.contains(frameChunk(metadata.frameCount - 1).second.contentHashCode()))
    }

    @Test
    fun animatedStickerFixture_encodesRenderedFramesToAnimatedWebp() {
        val metadata = metadata()
        val frameDurationMs = expectedFrameDurationMs(metadata)
        val frameChunks = (0 until metadata.frameCount).map { frameChunk(it) }
        val output = TestFixtures.artifact("AnimatedSticker.webp")

        assertTrue(
            AnimatedWebpEncoder.encodeFromCodecChunks(
                width = metadata.width,
                height = metadata.height,
                frameDurationMs = frameDurationMs,
                frameChunks = frameChunks,
                outputPath = output.absolutePath,
            ),
        )

        val info = checkNotNull(WebpAnimationInspector.inspect(output))
        assertEquals(metadata.width, info.width)
        assertEquals(metadata.height, info.height)
        assertEquals(metadata.frameCount, info.frameCount)
        assertTrue(info.hasAlpha)
        assertEquals(0, info.loopCount)
        assertEquals(List(metadata.frameCount) { frameDurationMs }, info.frameDurationsMs)
        assertEquals(List(metadata.frameCount) { true }, info.disposeToBackground)
        assertTrue(output.length() > 100_000)
        println("AnimatedSticker.tgs test artifact: ${output.absolutePath}")
    }

    private fun fixture() = TestFixtures.resource(TestFixtures.ANIMATED_STICKER_TGS)

    private fun metadata() = checkNotNull(TgsStickerProbe.readMetadata(fixture()))

    private fun frameChunk(index: Int): Pair<String, ByteArray> {
        val frameResource = "animated-sticker-frames/frame-${index.toString().padStart(2, '0')}.webp"
        return checkNotNull(WebpCodecChunks.extractFromSingleImageWebp(TestFixtures.resource(frameResource).readBytes()))
    }

    private fun expectedFrameDurationMs(metadata: TgsMetadata): Int = (1000f / metadata.frameRate).toInt()

    private companion object {
        private const val EXPECTED_SHA256 =
            "3653a17df7cf7e032a4458999a7d0a9a6837f881a800650d8b2b3c899fd1e99d"
    }
}

internal class FakeCacheOptions

internal class CurrentRlottieDrawable(
    val file: File,
    val json: String?,
    @Suppress("UNUSED_PARAMETER") width: Int,
    @Suppress("UNUSED_PARAMETER") height: Int,
    @Suppress("UNUSED_PARAMETER") cacheOptions: FakeCacheOptions,
    @Suppress("UNUSED_PARAMETER") limitFps: Boolean,
    @Suppress("UNUSED_PARAMETER") colorReplacement: IntArray?,
    @Suppress("UNUSED_PARAMETER") fitzModifier: Int,
    @Suppress("UNUSED_PARAMETER") isSingleChannel: Boolean,
)

internal class LegacyRlottieDrawable(
    val file: File,
    @Suppress("UNUSED_PARAMETER") width: Int,
    @Suppress("UNUSED_PARAMETER") height: Int,
    @Suppress("UNUSED_PARAMETER") cacheOptions: FakeCacheOptions,
    @Suppress("UNUSED_PARAMETER") limitFps: Boolean,
    @Suppress("UNUSED_PARAMETER") colorReplacement: IntArray?,
    @Suppress("UNUSED_PARAMETER") fitzModifier: Int,
)
