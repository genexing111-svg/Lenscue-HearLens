package com.hearlens.app

/** Values are percentages except output pixels, degrees and caption size (at 1280 px). */
data class StereoSettings(
    val outputWidth: Int = 960, val wirelessFps: Int = 15, val wirelessQuality: Int = 60,
    val scale: Int = 90, val spacing: Int = 0,
    val leftSize: Int = 0, val rightSize: Int = 0, val leftY: Int = 0, val rightY: Int = 0,
    val brightness: Int = 100, val contrast: Int = 100, val saturation: Int = 100,
    val rotation: Int = 0, val mirror: Boolean = false,
    val caption: String = "", val fontSize: Int = 36, val textY: Int = 75,
    val textColor: String = "#FFFFFF", val align: String = "center", val background: Int = 76,
    val stroke: Int = 1, val eyeMode: String = "both", val calibration: String = "off",
    val phoneRenderWidth: Int = 0, val phoneFps: Int = 0, val wirelessAdaptive: Boolean = true,
) {
    init {
        require(outputWidth in setOf(640, 960, 1280, 1600, 1920, 2560))
        require(wirelessFps in 1..30 && wirelessQuality in 35..95)
        require(phoneRenderWidth in setOf(0, 1280, 1600, 1920, 2400, 2560, 3200, 3840))
        require(phoneFps in setOf(0, 15, 24, 30, 45, 60, 90, 120))
        require(scale in 50..100 && spacing in -100..100)
        require(listOf(leftSize, rightSize, leftY, rightY).all { it in -10..10 })
        require(brightness in 50..150 && contrast in 50..150 && saturation in 0..200)
        require(rotation in setOf(0, 90, 180, 270) && caption.length <= 120)
        require(fontSize in 18..64 && textY in 10..90 && textColor.matches(Regex("#[0-9A-Fa-f]{6}")))
        require(align in setOf("left", "center", "right") && background in 0..100 && stroke in 0..6)
        require(eyeMode in setOf("both", "left", "right") && calibration in setOf("off", "cross", "grid"))
    }
}

internal object StereoGeometry {
    data class Box(val x: Float, val y: Float, val width: Float, val height: Float)
    /** Complete source stays inside each eye; spacing and size never crop its edges. */
    fun fit(sw: Int, sh: Int, ew: Float, eh: Float, scale: Int, size: Int, spacing: Int, vertical: Int, right: Boolean): Box {
        require(sw > 0 && sh > 0 && ew > 0 && eh > 0)
        val factor = (scale / 100f * (1f + size / 100f)).coerceIn(.1f, 1f)
        val ratio = minOf(ew / sw, eh / sh) * factor
        val w = sw * ratio; val h = sh * ratio
        val dx = (ew - w) / 2; val dy = (eh - h) / 2
        return Box(dx + dx * spacing.coerceIn(-100, 100) / 100f * (if (right) 1 else -1),
            (dy + eh * vertical / 100f).coerceIn(0f, eh - h), w, h)
    }
}
