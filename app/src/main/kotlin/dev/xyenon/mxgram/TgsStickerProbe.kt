package dev.xyenon.mxgram

import java.io.File
import java.util.zip.GZIPInputStream

internal data class TgsMetadata(
    val frameRate: Int,
    val frameCount: Int,
    val width: Int,
    val height: Int,
    val inPoint: Int,
    val outPoint: Int,
    val version: String,
    val layerCount: Int,
)

internal object TgsStickerProbe {
    fun readMetadata(file: File): TgsMetadata? {
        if (!file.exists()) {
            return null
        }
        return try {
            GZIPInputStream(file.inputStream()).use { input ->
                val json = input.readBytes().decodeToString()
                if (!json.startsWith("{")) {
                    return null
                }
                val inPoint = readIntField(json, "ip") ?: 0
                val outPoint = readIntField(json, "op") ?: return null
                TgsMetadata(
                    frameRate = readIntField(json, "fr") ?: return null,
                    frameCount = (outPoint - inPoint).takeIf { it > 0 } ?: return null,
                    width = readIntField(json, "w") ?: return null,
                    height = readIntField(json, "h") ?: return null,
                    inPoint = inPoint,
                    outPoint = outPoint,
                    version = readStringField(json, "v") ?: return null,
                    layerCount = readLayerCount(json) ?: return null,
                )
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun readIntField(
        json: String,
        field: String,
    ): Int? {
        val pattern = Regex(""""$field"\s*:\s*(-?\d+)""")
        val match = pattern.find(json) ?: return null
        return match.groupValues[1].toIntOrNull()
    }

    private fun readStringField(
        json: String,
        field: String,
    ): String? {
        val pattern = Regex(""""$field"\s*:\s*"([^"]+)"""")
        return pattern.find(json)?.groupValues?.getOrNull(1)
    }

    private fun readLayerCount(json: String): Int? {
        val layersIndex = json.indexOf("\"layers\"")
        if (layersIndex < 0) {
            return null
        }
        val arrayStart = json.indexOf('[', layersIndex)
        if (arrayStart < 0) {
            return null
        }
        var depth = 0
        var count = 0
        for (index in arrayStart until json.length) {
            when (json[index]) {
                '[', '{' -> {
                    if (json[index] == '{' && depth == 1) {
                        count += 1
                    }
                    depth += 1
                }

                ']', '}' -> {
                    depth -= 1
                    if (depth == 0 && json[index] == ']') {
                        return count
                    }
                }
            }
        }
        return null
    }
}
