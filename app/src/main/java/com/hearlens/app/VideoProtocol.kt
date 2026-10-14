package com.hearlens.app

import java.util.ArrayDeque

data class CameraOption(
    val id: String, val label: String, val facing: String, val focalLengthMm: Float? = null,
    val logicalCameraId: String = id, val physicalCameraId: String? = null, val verified: Boolean = false,
)

data class CameraControls(
    val zoomMin: Float = 1f, val zoomMax: Float = 1f, val zoomRatio: Float = 1f,
    val exposureMin: Int = 0, val exposureMax: Int = 0, val exposureStep: Float = 0f, val exposureIndex: Int = 0,
    val torchSupported: Boolean = false, val torchEnabled: Boolean = false,
    val focusSupported: Boolean = false, val controlsError: String? = null,
)

data class VideoSettings(
    val fps: Int = 0, val quality: Int = 75, val maxWidth: Int = 960,
    val cameraId: String = "", val zoomRatio: Float = 1f, val exposureIndex: Int = 0, val torch: Boolean = false,
) {
    init {
        require(fps in 0..120 && quality in 45..95 && maxWidth in setOf(640, 960, 1280, 1920))
        require(cameraId.length <= 128 && zoomRatio.isFinite() && zoomRatio > 0f)
    }
}

internal object CameraSettingsRules {
    fun validate(settings: VideoSettings, ids: Set<String>, controls: CameraControls): Boolean =
        settings.cameraId in ids && settings.zoomRatio.isFinite() &&
            settings.zoomRatio in controls.zoomMin..controls.zoomMax &&
            settings.exposureIndex in controls.exposureMin..controls.exposureMax &&
            (!settings.torch || controls.torchSupported)

    fun resetForCamera(settings: VideoSettings, id: String, controls: CameraControls) = settings.copy(
        cameraId = id, zoomRatio = 1f.coerceIn(controls.zoomMin, controls.zoomMax),
        exposureIndex = 0.coerceIn(controls.exposureMin, controls.exposureMax), torch = false,
    )

    fun classifyFacing(facing: String, isDefault: Boolean, focal: Float?, sensorWidth: Float?, referenceFovRatio: Float?): String {
        if (facing == "front") return "前置摄像头"
        if (facing != "back") return "外接摄像头"
        if (isDefault) return "后置主摄"
        val ratio = if (focal != null && sensorWidth != null && sensorWidth > 0 && referenceFovRatio != null && referenceFovRatio > 0)
            (focal / sensorWidth) / referenceFovRatio else null
        return when {
            ratio != null && ratio < 0.8f -> "后置超广角"
            ratio != null && ratio > 1.4f -> "后置长焦"
            else -> "后置摄像头"
        }
    }
}

internal object ReturnOwnershipRules {
    fun canRequest(owner: String?, client: String?, enabled: Boolean, takeover: Boolean): Boolean =
        owner == null || (client != null && (client == owner || (enabled && takeover)))
    fun acceptsFrame(owner: String?, client: String?): Boolean = if (owner == null) client == null else client == owner
}

/** A bounded sliding-window measurement; callers provide monotonic milliseconds. */
internal class FrameRateMeter {
    private val times = ArrayDeque<Long>()
    fun tick(now: Long) {
        times.addLast(now)
        trim(now)
        while (times.size > 300) times.removeFirst()
    }
    fun fps(now: Long): Double {
        trim(now)
        if (times.size < 2 || now - times.last > 1000) return 0.0
        return (times.size - 1) * 1000.0 / (times.last - times.first).coerceAtLeast(1)
    }
    fun reset() = times.clear()
    private fun trim(now: Long) { while (times.isNotEmpty() && now - times.first > 2000) times.removeFirst() }
}

internal object FpsSelection {
    fun choose(ranges: List<Pair<Int, Int>>, requested: Int): Pair<Int, Int>? {
        val valid = ranges.filter { it.first > 0 && it.second >= it.first }
        if (requested == 0) return valid.maxWithOrNull(compareBy<Pair<Int, Int>> { it.second }.thenBy { it.first })
        val containing = valid.filter { requested in it.first..it.second }
        return if (containing.isNotEmpty()) containing.minWithOrNull(compareBy<Pair<Int, Int>> { it.second }.thenByDescending { it.first })
        else valid.minWithOrNull(compareBy<Pair<Int, Int>> { kotlin.math.abs(it.second - requested) }.thenByDescending { it.first })
    }
}

/** Bounds checking before Android allocates a decoded bitmap. Accept baseline/progressive JPEG. */
internal object JpegValidator {
    const val MAX_BYTES = 4 * 1024 * 1024
    fun dimensions(bytes: ByteArray): Pair<Int, Int>? {
        if (bytes.size !in 12..MAX_BYTES || u(bytes, 0) != 255 || u(bytes, 1) != 216 ||
            u(bytes, bytes.size - 2) != 255 || u(bytes, bytes.size - 1) != 217) return null
        var offset = 2
        var dimensions: Pair<Int, Int>? = null
        while (offset + 3 < bytes.size) {
            if (u(bytes, offset++) != 255) return null
            while (offset < bytes.size && u(bytes, offset) == 255) offset++
            if (offset >= bytes.size) return null
            val marker = u(bytes, offset++)
            if (marker == 218) return dimensions // Start of scan; decoder validates compressed data.
            if (marker == 217) return null
            if (marker in 208..215 || marker == 1) continue
            if (offset + 1 >= bytes.size) return null
            val length = u(bytes, offset) * 256 + u(bytes, offset + 1)
            if (length < 2 || offset + length > bytes.size) return null
            if (marker == 192 || marker == 194) {
                if (length < 11 || u(bytes, offset + 2) != 8) return null
                val height = u(bytes, offset + 3) * 256 + u(bytes, offset + 4)
                val width = u(bytes, offset + 5) * 256 + u(bytes, offset + 6)
                val channels = u(bytes, offset + 7)
                if (channels !in setOf(1, 3) || length != 8 + channels * 3 ||
                    width !in 1..4096 || height !in 1..4096 || width.toLong() * height > 5_000_000L) return null
                if (dimensions != null) return null
                dimensions = width to height
            } else if (marker in 193..207 && marker !in setOf(196, 200, 204)) return null
            offset += length
        }
        return null
    }
    private fun u(bytes: ByteArray, index: Int) = bytes[index].toInt() and 255
}
