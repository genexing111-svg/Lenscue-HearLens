package com.hearlens.app

import org.junit.Assert.*
import org.junit.Test

class DirectionProtocolTest {
    private fun angle(text:String,format:DirectionFormat=DirectionFormat.AUTO)=(DirectionProtocol.parse(text,format) as DirectionEvent.Angle).degrees
    @Test fun acceptsActualCh340Esp32BoardCapture() {
        val records=listOf("angle: 354.0 deg","angle: 355.0 deg","angle: 1.0 deg","angle: 339.0 deg","angle: 236.0 deg")
        assertEquals(listOf(354.0,355.0,1.0,339.0,236.0),records.map {angle(it)})
    }
    @Test fun bootLogsAndUnlabelledNumbersAreNeverAutomaticAngles() {
        listOf("ESP-ROM:esp32c3-api1-20210207","Build:Feb 7 2021","rst:0x1 (POWERON)","entry 0x403cbf10","123","angle: NaN deg","angle: Infinity deg","angle: -1 deg","angle: 361 deg","log angle: 23.0 deg","angle: 20 deg status=4").forEach {assertNull(it,DirectionProtocol.parse(it,DirectionFormat.AUTO))}
    }
    @Test fun jsonIsStrictAndInvalidSignalCanExplicitlyClearDirection() {
        assertEquals(270.5,angle("{\"angle\":270.5}"),0.0)
        assertEquals(12.0,angle("{\"azimuth\":12,\"valid\":true}"),0.0)
        assertEquals(DirectionEvent.NoDirection,DirectionProtocol.parse("{\"valid\":false}",DirectionFormat.AUTO))
        listOf("{\"angle\":\"12\"}","{\"angle\":12,\"doa\":30}","{\"angle\":true}","{\"angle\":30,\"valid\":\"true\"}","{broken}").forEach {assertNull(DirectionProtocol.parse(it,DirectionFormat.JSON))}
    }
    @Test fun exactNumericModeRequiresExplicitChoiceAndRawModeNeverParses() {
        assertEquals(123.5,angle("123.5",DirectionFormat.NUMBER),0.0)
        assertNull(DirectionProtocol.parse("angle: 123 deg",DirectionFormat.NUMBER))
        assertNull(DirectionProtocol.parse("angle: 123 deg",DirectionFormat.RAW))
        assertNull(DirectionProtocol.parse("{\"angle\":12}",DirectionFormat.LABEL))
        assertEquals(0.0,angle("angle: 360.0 deg"),0.0)
    }
    @Test fun handlesEveryUsbFragmentBoundaryAndMultipleRecordsInOneRead() {
        val text="angle: 354.0 deg\r\nangle: 1.0 deg\n方向角: 2.5°\r"
        val bytes=text.toByteArray()
        for(split in 0..bytes.size) {
            val decoder=DirectionLineDecoder();val result=mutableListOf<Double>()
            val sink:(String)->Unit={result+=angle(it)}
            decoder.feed(bytes.copyOfRange(0,split),sink);decoder.feed(bytes.copyOfRange(split,bytes.size),sink)
            assertEquals("split $split",listOf(354.0,1.0,2.5),result)
        }
    }
    @Test fun incompletePacketsWaitAndOversizedPacketsCannotLeaveValidTails() {
        val decoder=DirectionLineDecoder();val lines=mutableListOf<String>()
        decoder.feed("angle: 23".toByteArray(),lines::add);assertTrue(lines.isEmpty())
        decoder.feed(".0 deg\n".toByteArray(),lines::add);assertEquals(listOf("angle: 23.0 deg"),lines)
        decoder.feed(("x".repeat(10000)+"angle: 99 deg\nangle: 45 deg\n").toByteArray(),lines::add)
        assertEquals(listOf("angle: 23.0 deg","angle: 45 deg"),lines);assertEquals(1L,decoder.discardedLines)
    }
    @Test fun binaryCorruptionIsDiscardedUntilNextRecord() {
        val decoder=DirectionLineDecoder();val lines=mutableListOf<String>()
        decoder.feed(byteArrayOf(0)+"angle: 90 deg\nangle: 180 deg\n".toByteArray(),lines::add)
        assertEquals(listOf("angle: 180 deg"),lines)
    }
    @Test fun calibrationWrapsAcrossNorthAndCanInvertBoardRotation() {
        assertEquals(6.0,DirectionMath.relative(1.0,355.0,true),0.0)
        assertEquals(354.0,DirectionMath.relative(1.0,355.0,false),0.0)
        assertEquals(0.0,DirectionMath.relative(355.0,355.0,true),0.0)
        assertEquals("右方",DirectionMath.label(DirectionMath.relative(80.0,350.0,true)))
        assertEquals("左方",DirectionMath.label(DirectionMath.relative(80.0,350.0,false)))
    }
    @Test fun allEightSectorsAndWrapBoundariesAreConsistent() {
        assertEquals(listOf("正前方","右前方","右方","右后方","正后方","左后方","左方","左前方"),(0..7).map {DirectionMath.label(it*45.0)})
        assertEquals("正前方",DirectionMath.label(359.9));assertEquals("正前方",DirectionMath.label(337.5))
        assertEquals("右前方",DirectionMath.label(22.5))
    }
    @Test fun staleDisconnectedAndDisabledReadingsNeverExposeLiveAngle() {
        val s=DirectionSnapshot(enabled=true,connected=true,reading=DirectionReading(123.0,1000),now=1200)
        assertTrue(s.fresh);assertEquals(123.0,s.relative!!,0.0)
        for(invalid in listOf(s.copy(now=4001),s.copy(connected=false),s.copy(enabled=false),s.copy(reading=null))) {
            assertFalse(invalid.fresh);assertNull(invalid.relative);assertTrue(invalid.json().isNull("relativeDegrees"));assertTrue(invalid.json().isNull("rawDegrees"))
        }
    }
    @Test fun manualCalibrationRejectsInvalidConfiguration() {
        listOf<()->DirectionConfig>({DirectionConfig(frontDegrees=360.0)},{DirectionConfig(frontDegrees=-1.0)},
            {DirectionConfig(frontDegrees=Double.NaN)},{DirectionConfig(baudRate=0)},{DirectionConfig(staleMs=0)}).forEach {
            try {it();fail("Invalid direction configuration accepted")}catch(_:IllegalArgumentException){}
        }
    }
}
