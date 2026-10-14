package com.hearlens.app

import org.junit.Assert.*
import org.junit.Test

class VideoProtocolTest {
    @Test fun cameraSelectionAndControlsUseDiscoveredLimits() {
        val limits = CameraControls(zoomMin = 1f, zoomMax = 4f, exposureMin = -3, exposureMax = 3, torchSupported = false)
        val valid = VideoSettings(cameraId = "actual-camera", zoomRatio = 2f, exposureIndex = 1)
        assertTrue(CameraSettingsRules.validate(valid, setOf("actual-camera"), limits))
        assertFalse(CameraSettingsRules.validate(valid.copy(cameraId = "invented"), setOf("actual-camera"), limits))
        assertFalse(CameraSettingsRules.validate(valid.copy(zoomRatio = 4.01f), setOf("actual-camera"), limits))
        assertFalse(CameraSettingsRules.validate(valid.copy(exposureIndex = 4), setOf("actual-camera"), limits))
        assertFalse(CameraSettingsRules.validate(valid.copy(torch = true), setOf("actual-camera"), limits))
        val reset = CameraSettingsRules.resetForCamera(valid.copy(torch = true), "new", limits.copy(zoomMin = 1.2f))
        assertEquals("new", reset.cameraId)
        assertEquals(1.2f, reset.zoomRatio)
        assertEquals(0, reset.exposureIndex)
        assertFalse(reset.torch)
    }

    @Test fun nonFiniteCameraNumbersAreRejected() {
        for (zoom in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, 0f, -1f)) {
            try { VideoSettings(zoomRatio = zoom); fail("invalid zoom accepted") } catch (_: IllegalArgumentException) { }
        }
    }

    @Test fun lensLabelsUseSensorFieldOfViewAndNeverInventMissingCameras() {
        val mainFov = 5.85f / 8.2575f
        assertEquals("后置超广角", CameraSettingsRules.classifyFacing("back", false, 1.86f, 4.1984f, mainFov))
        assertEquals("后置长焦", CameraSettingsRules.classifyFacing("back", false, 9f, 5.2429f, mainFov))
        assertEquals("后置摄像头", CameraSettingsRules.classifyFacing("back", false, null, null, mainFov))
        assertEquals("后置主摄", CameraSettingsRules.classifyFacing("back", true, 5.85f, 8.2575f, mainFov))
        assertEquals("前置摄像头", CameraSettingsRules.classifyFacing("front", false, 2.24f, 3.6392f, mainFov))
    }

    @Test fun secondPageCannotOverwriteOrStopTheCurrentReturnOwner() {
        assertTrue(ReturnOwnershipRules.canRequest(null, "page-a", true, false))
        assertTrue(ReturnOwnershipRules.acceptsFrame("page-a", "page-a"))
        assertFalse(ReturnOwnershipRules.acceptsFrame("page-a", "page-b"))
        assertFalse(ReturnOwnershipRules.acceptsFrame("page-a", null))
        assertFalse(ReturnOwnershipRules.canRequest("page-a", "page-b", true, false))
        assertFalse(ReturnOwnershipRules.canRequest("page-a", "page-b", false, true))
        assertTrue(ReturnOwnershipRules.canRequest("page-a", "page-b", true, true))
        assertTrue(ReturnOwnershipRules.canRequest("page-a", "page-a", false, false))
        assertFalse(ReturnOwnershipRules.canRequest("page-a", null, false, false))
        assertFalse(ReturnOwnershipRules.canRequest("page-a", null, true, false))
        assertFalse(ReturnOwnershipRules.canRequest("page-a", null, true, true))
        assertTrue(ReturnOwnershipRules.canRequest(null, null, true, false))
        assertTrue(ReturnOwnershipRules.canRequest(null, null, false, false))
        assertFalse(ReturnOwnershipRules.acceptsFrame(null, "page-a"))
        assertTrue(ReturnOwnershipRules.acceptsFrame(null, null))
    }

    @Test fun autoChoosesHighestCombinationRangeAndPrefersFixed() {
        assertEquals(60 to 60, FpsSelection.choose(listOf(15 to 30, 30 to 30, 15 to 60, 60 to 60), 0))
        assertEquals(30 to 30, FpsSelection.choose(listOf(15 to 30, 30 to 30, 60 to 60), 30))
        assertEquals(15 to 30, FpsSelection.choose(listOf(15 to 30, 30 to 30, 60 to 60), 24))
        assertEquals(60 to 60, FpsSelection.choose(listOf(15 to 30, 60 to 60), 120))
        assertNull(FpsSelection.choose(emptyList(), 0))
    }

    @Test fun ratesMeasureTimeAndBecomeZeroWhenStopped() {
        val meter = FrameRateMeter()
        repeat(61) { meter.tick(it * 1000L / 60) }
        assertEquals(60.0, meter.fps(1000), 0.1)
        assertEquals(0.0, meter.fps(2101), 0.0)
        meter.reset()
        assertEquals(0.0, meter.fps(2101), 0.0)
    }

    @Test fun settingsHaveSafeDefaultsAndExactAllowedWidths() {
        assertEquals(VideoSettings(0, 75, 960), VideoSettings())
        for (width in listOf(640, 960, 1280, 1920)) VideoSettings(120, 95, width)
        for (make in listOf<() -> VideoSettings>(
            { VideoSettings(-1) }, { VideoSettings(121) }, { VideoSettings(quality = 44) },
            { VideoSettings(quality = 96) }, { VideoSettings(maxWidth = 1000) },
        )) {
            try { make(); fail("invalid settings accepted") } catch (_: IllegalArgumentException) { }
        }
    }

    @Test fun jpegBoundsAreReadBeforeDecodingAndOversizedInputsAreRejected() {
        assertEquals(3200 to 1440, JpegValidator.dimensions(jpegHeader(3200, 1440)))
        assertEquals(1280 to 960, JpegValidator.dimensions(jpegHeader(1280, 960, 194)))
        assertNull(JpegValidator.dimensions(jpegHeader(4097, 1)))
        assertNull(JpegValidator.dimensions(jpegHeader(3200, 1600)))
        assertNull(JpegValidator.dimensions(jpegHeader(0, 100)))
        assertNull(JpegValidator.dimensions(ByteArray(JpegValidator.MAX_BYTES + 1)))
        assertNull(JpegValidator.dimensions(byteArrayOf(-1, -40, -1, -39)))
    }

    @Test fun truncatedOrMalformedJpegSegmentsAreRejected() {
        val jpeg = jpegHeader(640, 480)
        assertNull(JpegValidator.dimensions(jpeg.copyOf(jpeg.size - 1)))
        assertNull(JpegValidator.dimensions(jpeg.clone().also { it[5] = 1 }))
        assertNull(JpegValidator.dimensions(jpeg.clone().also { it[6] = 16 }))
        assertNull(JpegValidator.dimensions(jpeg.clone().also { it[3] = 193.toByte() }))
    }

    // Header validator fixture; compressed scan data is intentionally omitted from these unit tests.
    private fun jpegHeader(width: Int, height: Int, marker: Int = 192): ByteArray = byteArrayOf(
        -1, -40, -1, marker.toByte(), 0, 11, 8,
        (height shr 8).toByte(), height.toByte(), (width shr 8).toByte(), width.toByte(),
        1, 1, 0x11, 0, -1, -38, 0, 8, 1, 1, 0, 0, 63, 0, 7, -1, -39,
    )
}
