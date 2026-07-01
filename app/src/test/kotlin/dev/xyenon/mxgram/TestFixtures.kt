package dev.xyenon.mxgram

import java.io.File
import java.security.MessageDigest

internal object TestFixtures {
    const val ANIMATED_STICKER_TGS = "AnimatedSticker.tgs"

    fun resource(name: String): File {
        val url =
            checkNotNull(TestFixtures::class.java.classLoader?.getResource(name)) {
                "Missing test resource: $name"
            }
        return File(url.toURI())
    }

    fun artifact(name: String): File {
        val dir = File("build/test-outputs").absoluteFile
        dir.mkdirs()
        return File(dir, name)
    }

    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) {
                    break
                }
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }
}
