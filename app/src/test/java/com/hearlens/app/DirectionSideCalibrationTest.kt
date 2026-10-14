package com.hearlens.app

import org.junit.Assert.*
import org.junit.Test

class DirectionSideCalibrationTest {
    private fun samples(vararg values:Double)=values.mapIndexed {i,v->DirectionReading(v,1000L+i*600)}
    private fun result(front:Double,side:CalibrationSide,vararg values:Double)=DirectionSideCalibration.evaluate(front,side,samples(*values))
    private fun clockwise(result:SideCalibrationResult)=(result as SideCalibrationResult.Success).clockwise
    @Test fun normalBoardCanCalibrateFromEitherSide() {
        assertTrue(clockwise(result(10.0,CalibrationSide.RIGHT,98.0,100.0,102.0)))
        assertTrue(clockwise(result(10.0,CalibrationSide.LEFT,278.0,280.0,282.0)))
    }
    @Test fun reversedBoardCanCalibrateFromEitherSide() {
        assertFalse(clockwise(result(10.0,CalibrationSide.RIGHT,278.0,280.0,282.0)))
        assertFalse(clockwise(result(10.0,CalibrationSide.LEFT,98.0,100.0,102.0)))
    }
    @Test fun repeatedCalibrationIsIdempotentAndDoesNotMoveFront() {
        val config=DirectionConfig(frontDegrees=355.0,clockwise=true,showOverlay=false)
        val once=config.copy(clockwise=clockwise(result(config.frontDegrees,CalibrationSide.RIGHT,263.0,265.0,267.0)))
        val twice=once.copy(clockwise=clockwise(result(once.frontDegrees,CalibrationSide.RIGHT,263.0,265.0,267.0)))
        assertEquals(once,twice);assertEquals(355.0,twice.frontDegrees,0.0);assertFalse(twice.showOverlay)
        assertEquals(90.0,DirectionMath.relative(265.0,twice.frontDegrees,twice.clockwise),0.0)
        assertEquals(270.0,DirectionMath.relative(85.0,twice.frontDegrees,twice.clockwise),0.0)
    }
    @Test fun circularMeanWorksAcrossZero() {
        val r=result(270.0,CalibrationSide.RIGHT,358.0,0.0,2.0) as SideCalibrationResult.Success
        assertTrue(r.clockwise);assertTrue(r.meanRawDegrees<.001 || r.meanRawDegrees>359.999)
    }
    @Test fun frontAndBackSourcesCannotInferHandedness() {
        for(center in listOf(0.0,20.0,160.0,180.0,200.0,340.0)) {
            assertTrue(result(0.0,CalibrationSide.RIGHT,*DoubleArray(3) {DirectionMath.normalize(center+it-1)}) is SideCalibrationResult.Rejected)
        }
    }
    @Test fun jumpingAndOppositeSourcesAreRejected() {
        for(values in listOf(doubleArrayOf(90.0,270.0,90.0),doubleArrayOf(50.0,90.0,130.0)))
            assertTrue(result(0.0,CalibrationSide.RIGHT,*values) is SideCalibrationResult.Rejected)
    }
    @Test fun requiresThreeDistinctRecordsOverTime() {
        assertTrue(result(0.0,CalibrationSide.RIGHT,90.0,91.0) is SideCalibrationResult.Rejected)
        val repeated=List(10) {DirectionReading(90.0,1000)}
        assertTrue(DirectionSideCalibration.evaluate(0.0,CalibrationSide.RIGHT,repeated) is SideCalibrationResult.Rejected)
        val burst=(0..5).map {DirectionReading(90.0,1000L+it)}
        assertTrue(DirectionSideCalibration.evaluate(0.0,CalibrationSide.RIGHT,burst) is SideCalibrationResult.Rejected)
    }
    @Test fun captureIgnoresPreButtonAndRepeatedSamples() {
        val c=SideCalibrationCapture(1000,0.0,CalibrationSide.RIGHT,7)
        fun state(t:Long)=DirectionSnapshot(enabled=true,connected=true,now=t+100,reading=DirectionReading(90.0,t))
        c.observe(state(900),7);assertEquals(0,c.count)
        c.observe(state(1200),7);c.observe(state(1200),7);assertEquals(1,c.count)
        c.observe(state(1800),7);c.observe(state(2400),7)
        assertTrue(c.finish(state(2400),7) is SideCalibrationResult.Success)
    }
    @Test fun disconnectOrNewUsbSessionInvalidatesEntireAttempt() {
        for(disconnect in listOf(true,false)) {
            val c=SideCalibrationCapture(0,0.0,CalibrationSide.LEFT,1)
            for(t in listOf(1000L,1600L,2200L))c.observe(DirectionSnapshot(true,true,reading=DirectionReading(270.0,t),now=t+10),1)
            val end=DirectionSnapshot(true,!disconnect,reading=DirectionReading(270.0,2200),now=2300)
            assertTrue(c.finish(end,if(disconnect)1 else 2) is SideCalibrationResult.Rejected)
        }
    }
    @Test fun frontChangeAndExpiredSignalCannotSaveCalibration() {
        for(changed in listOf(true,false)) {
            val c=SideCalibrationCapture(0,0.0,CalibrationSide.RIGHT,1)
            for(t in listOf(1000L,1600L,2200L))c.observe(DirectionSnapshot(true,true,reading=DirectionReading(90.0,t),now=t+10),1)
            val end=DirectionSnapshot(true,true,config=DirectionConfig(frontDegrees=if(changed)12.0 else 0.0),reading=DirectionReading(90.0,2200),now=if(changed)2300 else 8000)
            assertTrue(c.finish(end,1) is SideCalibrationResult.Rejected)
        }
    }
    @Test fun swapOnlyChangesLateralDirectionAndKeepsFrontBack() {
        for(front in listOf(0.0,30.0,355.0)) {
            assertEquals(0.0,DirectionMath.relative(front,front,false),0.0)
            assertEquals(180.0,DirectionMath.relative(DirectionMath.normalize(front+180),front,false),0.0)
            assertEquals(270.0,DirectionMath.relative(DirectionMath.normalize(front+90),front,false),0.0)
        }
    }
}
