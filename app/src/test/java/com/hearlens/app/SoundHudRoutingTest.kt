package com.hearlens.app

import org.junit.Assert.*
import org.junit.Test

class SoundHudRoutingTest {
    private fun board(now:Long=2000)=DirectionSnapshot(enabled=true,connected=true,
        config=DirectionConfig(frontDegrees=95.0,clockwise=false),reading=DirectionReading(176.0,1900),now=now)
    @Test fun screenshotCaseWithoutClassifierProduces279DegreeGlowAndNoInventedLabel() {
        val inactive=SoundHudState(enabled=false,now=2000)
        val result=SoundHudRouting.compose(inactive,board())
        assertTrue(result.enabled);assertEquals(279.0,result.liveDirection!!.bearing,0.0)
        assertEquals(1.0,result.liveDirection.opacity,0.0);assertTrue(result.events.isEmpty())
        assertFalse(inactive.enabled)
    }
    @Test fun runningClassifierStillShowsBoardGlowWhenNoClassHasBeenRecognized() {
        val result=SoundHudRouting.compose(SoundHudState(enabled=true,events=emptyList(),now=2000),board())
        assertNotNull(result.liveDirection);assertTrue(result.events.isEmpty())
    }
    @Test fun classifiedNameAndUnclassifiedCurrentDirectionRemainSeparate() {
        val event=SoundHudEvent(7,353,true,null,1500,1455)
        val result=SoundHudRouting.compose(SoundHudState(true,events=listOf(event),now=2000),board())
        assertNotNull(result.liveDirection);assertEquals(listOf(event),result.events);assertNull(result.events.single().bearing)
    }
    @Test fun expiredDisconnectedAndDisabledBoardNeverLeaveAGlow() {
        for(state in listOf(board(5000),board().copy(connected=false),board().copy(enabled=false),board().copy(reading=null))) {
            val result=SoundHudRouting.compose(SoundHudState(now=5000),state)
            assertNull(result.liveDirection);assertFalse(result.enabled)
        }
    }
    @Test fun userCanHideDirectionWithoutLosingSoundNames() {
        val event=SoundHudEvent(7,349,false,90.0,1500,1455)
        val d=board().copy(config=board().config.copy(showOverlay=false))
        val result=SoundHudRouting.compose(SoundHudState(true,events=listOf(event),now=2000),d)
        assertNull(result.liveDirection);assertEquals(349,result.events.single().index);assertNull(result.events.single().bearing)
    }
    @Test fun liveCueFadesOnSilenceWithoutPulsingAtEveryFreshPacket() {
        assertEquals(1.0,SoundHudRouting.compose(SoundHudState(),board(1900)).liveDirection!!.opacity,0.0)
        assertEquals(.5,SoundHudRouting.compose(SoundHudState(),board(4000)).liveDirection!!.opacity,1e-10)
        assertNull(SoundHudRouting.compose(SoundHudState(),board(4900)).liveDirection)
    }
    @Test fun shortTimeoutAlsoFadesAndCalibrationIsAppliedOnlyOnce() {
        val d=board().copy(config=board().config.copy(staleMs=500),now=2250)
        val result=SoundHudRouting.compose(SoundHudState(),d)
        assertEquals(279.0,result.liveDirection!!.bearing,0.0);assertTrue(result.liveDirection.opacity in 0.0..1.0)
        assertNull(SoundHudRouting.compose(SoundHudState(),d.copy(now=2400)).liveDirection)
    }
    @Test fun stoppedClassifierCannotReExposeOldLabelsAndNoBoardDoesNotFabricateBearing() {
        val off=SoundHudState(false,events=listOf(SoundHudEvent(1,349,false,90.0,0,0)),now=100)
        val result=SoundHudRouting.compose(off,null)
        assertFalse(result.enabled);assertTrue(result.events.isEmpty());assertNull(result.liveDirection)
    }
    @Test fun userConfiguredLongerTimeoutIsHonored() {
        val d=board(5900).copy(config=board().config.copy(staleMs=10000))
        assertNotNull(SoundHudRouting.compose(SoundHudState(),d).liveDirection)
        assertNull(SoundHudRouting.compose(SoundHudState(),d.copy(now=11901)).liveDirection)
    }
    @Test fun reducedMotionKeepsFreshBoardCueStaticUntilTimeout() {
        val hud=SoundHudState(settings=SoundGlowSettings(reducedMotion=true))
        assertEquals(1.0,SoundHudRouting.compose(hud,board(4600)).liveDirection!!.opacity,0.0)
        assertNull(SoundHudRouting.compose(hud,board(4900)).liveDirection)
    }
}
