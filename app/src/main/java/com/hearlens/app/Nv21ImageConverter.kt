package com.hearlens.app

import java.nio.ByteBuffer

/** Converts strided YUV planes to upright NV21 without JPEG decoding or bitmap allocations. */
internal object Nv21ImageConverter {
    data class Plane(val buffer: ByteBuffer, val rowStride: Int, val pixelStride: Int)
    data class Image(val bytes: ByteArray, val width: Int, val height: Int)

    /** CameraX has already rotated NV21 in native code. Copy whole rows, then mirror in-place. */
    fun copyNv21(width: Int, height: Int, planes: List<Plane>, mirror: Boolean, reusable: ByteArray? = null): Image {
        require(width > 0 && height > 0 && width % 2 == 0 && height % 2 == 0 && planes.size == 3)
        val size = width * height * 3 / 2
        val bytes = reusable?.takeIf { it.size == size } ?: ByteArray(size)
        val y = planes[0].buffer.duplicate()
        val v = planes[2].buffer.duplicate()
        val yStart = y.position()
        val vStart = v.position()
        if (planes[0].pixelStride != 1 || planes[2].pixelStride != 2 ||
            vStart + (height / 2 - 1) * planes[2].rowStride + width > v.limit()) {
            // Defensive fallback for vendor buffers whose final shared U byte is omitted.
            return convert(width, height, planes, 0, mirror)
        }
        for (row in 0 until height) {
            y.position(yStart + row * planes[0].rowStride)
            y.get(bytes, row * width, width)
        }
        for (row in 0 until height / 2) {
            v.position(vStart + row * planes[2].rowStride)
            v.get(bytes, width * height + row * width, width)
        }
        if (mirror) {
            for (row in 0 until height) {
                var left = row * width
                var right = left + width - 1
                while (left < right) {
                    val temp = bytes[left]; bytes[left] = bytes[right]; bytes[right] = temp
                    left++; right--
                }
            }
            for (row in 0 until height / 2) {
                var left = width * height + row * width
                var right = left + width - 2
                while (left < right) {
                    val vValue = bytes[left]; val uValue = bytes[left + 1]
                    bytes[left] = bytes[right]; bytes[left + 1] = bytes[right + 1]
                    bytes[right] = vValue; bytes[right + 1] = uValue
                    left += 2; right -= 2
                }
            }
        }
        return Image(bytes, width, height)
    }

    fun convert(width: Int, height: Int, planes: List<Plane>, rotation: Int, mirror: Boolean): Image {
        require(width > 0 && height > 0 && width % 2 == 0 && height % 2 == 0)
        require(planes.size == 3 && rotation in setOf(0, 90, 180, 270))
        val outputWidth = if (rotation % 180 == 0) width else height
        val outputHeight = if (rotation % 180 == 0) height else width
        val bytes = ByteArray(width * height * 3 / 2)
        copyPlane(planes[0], width, height, rotation, mirror, bytes, 0, 1)
        // NV21 stores V then U, interleaved after the full-resolution luma plane.
        copyPlane(planes[2], width / 2, height / 2, rotation, mirror, bytes, width * height, 2)
        copyPlane(planes[1], width / 2, height / 2, rotation, mirror, bytes, width * height + 1, 2)
        return Image(bytes, outputWidth, outputHeight)
    }

    private fun copyPlane(
        plane: Plane, width: Int, height: Int, rotation: Int, mirror: Boolean,
        target: ByteArray, offset: Int, targetPixelStride: Int,
    ) {
        val outputWidth = if (rotation % 180 == 0) width else height
        val source = plane.buffer.duplicate()
        val sourceStart = source.position()
        for (y in 0 until height) {
            var destinationX: Int
            val destinationY: Int
            var stepX: Int
            val stepY: Int
            when (rotation) {
                0 -> { destinationX = 0; destinationY = y; stepX = 1; stepY = 0 }
                90 -> { destinationX = height - 1 - y; destinationY = 0; stepX = 0; stepY = 1 }
                180 -> { destinationX = width - 1; destinationY = height - 1 - y; stepX = -1; stepY = 0 }
                else -> { destinationX = y; destinationY = width - 1; stepX = 0; stepY = -1 }
            }
            if (mirror) {
                destinationX = outputWidth - 1 - destinationX
                stepX = -stepX
            }
            var destination = offset + (destinationY * outputWidth + destinationX) * targetPixelStride
            val destinationStep = (stepY * outputWidth + stepX) * targetPixelStride
            var sourceIndex = sourceStart + y * plane.rowStride
            repeat(width) {
                target[destination] = source.get(sourceIndex)
                sourceIndex += plane.pixelStride
                destination += destinationStep
            }
        }
    }
}
