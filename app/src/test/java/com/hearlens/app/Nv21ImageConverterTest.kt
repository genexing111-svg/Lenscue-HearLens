package com.hearlens.app

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer

class Nv21ImageConverterTest {
    @Test fun nv21RowCopyKeepsPaddingOutAndReusesBuffer() {
        val y = ByteBuffer.wrap(byteArrayOf(99, 1, 2, 3, 4, 99, 99, 5, 6, 7, 8)).apply { position(1) }
        val vu = ByteBuffer.wrap(byteArrayOf(99, 21, 11, 22, 12))
        val supplied = listOf(Nv21ImageConverter.Plane(y, 6, 1),
            Nv21ImageConverter.Plane(vu.duplicate().apply { position(2) }, 4, 2),
            Nv21ImageConverter.Plane(vu.duplicate().apply { position(1) }, 4, 2))
        val buffer = ByteArray(12)
        val actual = Nv21ImageConverter.copyNv21(4, 2, supplied, false, buffer)
        org.junit.Assert.assertSame(buffer, actual.bytes)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 21, 11, 22, 12), actual.bytes)
        val mirrored = Nv21ImageConverter.copyNv21(4, 2, supplied, true, buffer)
        assertArrayEquals(byteArrayOf(4, 3, 2, 1, 8, 7, 6, 5, 22, 12, 21, 11), mirrored.bytes)
    }

    @Test fun allRotationsKeepLumaAndChromaAligned() {
        val expected = mapOf(
            0 to intArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 21, 11, 22, 12),
            90 to intArrayOf(5, 1, 6, 2, 7, 3, 8, 4, 21, 11, 22, 12),
            180 to intArrayOf(8, 7, 6, 5, 4, 3, 2, 1, 22, 12, 21, 11),
            270 to intArrayOf(4, 8, 3, 7, 2, 6, 1, 5, 22, 12, 21, 11),
        )
        for ((rotation, bytes) in expected) {
            val image = Nv21ImageConverter.convert(4, 2, planes(), rotation, false)
            assertArrayEquals("rotation=$rotation", bytes.map(Int::toByte).toByteArray(), image.bytes)
            assertEquals(if (rotation % 180 == 0) 4 else 2, image.width)
            assertEquals(if (rotation % 180 == 0) 2 else 4, image.height)
        }
    }

    @Test fun frontCameraMirrorIsAppliedAfterRotation() {
        val expected = mapOf(
            0 to intArrayOf(4, 3, 2, 1, 8, 7, 6, 5, 22, 12, 21, 11),
            90 to intArrayOf(1, 5, 2, 6, 3, 7, 4, 8, 21, 11, 22, 12),
            180 to intArrayOf(5, 6, 7, 8, 1, 2, 3, 4, 21, 11, 22, 12),
            270 to intArrayOf(8, 4, 7, 3, 6, 2, 5, 1, 22, 12, 21, 11),
        )
        for ((rotation, bytes) in expected) {
            val image = Nv21ImageConverter.convert(4, 2, planes(), rotation, true)
            assertArrayEquals("mirrored rotation=$rotation", bytes.map(Int::toByte).toByteArray(), image.bytes)
        }
    }

    @Test fun paddedSharedChromaBuffersAndNonzeroPositionsAreHandled() {
        val y = ByteBuffer.wrap(byteArrayOf(99, 1, 2, 3, 4, 99, 99, 5, 6, 7, 8)).apply { position(1) }
        val vu = ByteBuffer.wrap(byteArrayOf(99, 21, 11, 22, 12))
        val v = vu.duplicate().apply { position(1) }
        val u = vu.duplicate().apply { position(2) }
        val result = Nv21ImageConverter.convert(4, 2, listOf(
            Nv21ImageConverter.Plane(y, 6, 1), Nv21ImageConverter.Plane(u, 4, 2), Nv21ImageConverter.Plane(v, 4, 2),
        ), 0, false)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 21, 11, 22, 12), result.bytes)
        assertEquals(1, y.position())
        assertEquals(2, u.position())
        assertEquals(1, v.position())
    }

    @Test(expected = IllegalArgumentException::class)
    fun oddDimensionsAreRejectedBeforeBufferAccess() {
        Nv21ImageConverter.convert(3, 2, planes(), 0, false)
    }

    @Test(expected = IllegalArgumentException::class)
    fun nonRightAngleRotationIsRejected() {
        Nv21ImageConverter.convert(4, 2, planes(), 45, false)
    }

    private fun planes() = listOf(
        Nv21ImageConverter.Plane(ByteBuffer.wrap(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)), 4, 1),
        Nv21ImageConverter.Plane(ByteBuffer.wrap(byteArrayOf(11, 12)), 2, 1),
        Nv21ImageConverter.Plane(ByteBuffer.wrap(byteArrayOf(21, 22)), 2, 1),
    )
}
