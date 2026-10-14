package com.hearlens.app

import org.junit.Assert.*
import org.junit.Test

class PerformancePolicyTest {
    @Test fun phoneResolutionUsesRealMaximumAndEvenBuffers() {
        assertEquals(3200 to 1440,PhoneResolution.size(0,3200,1440))
        assertEquals(1920 to 864,PhoneResolution.size(1920,3200,1440))
        assertEquals(2400 to 1080,PhoneResolution.size(3840,2400,1080))
        for(w in listOf(1280,1600,1920,2400,0)) { val size=PhoneResolution.size(w,3200,1440);assertEquals(0,size.second%2);assertTrue(size.first<=3200) }
    }
    @Test fun sustainedSlowRenderDropsAndRecoversWithHysteresis() {
        val p=DisplayPacer();p.reset(60)
        repeat(80){p.sample(24.0,60)}
        assertTrue(p.target<=30)
        repeat(10){p.sample(3.0,60)}
        assertTrue(p.target<=30) // A few fast frames cannot immediately bounce back.
        repeat(2000){p.sample(3.0,60)}
        assertEquals(60,p.target)
    }
    @Test fun cannotExceedCameraOrUserCap() {
        val p=DisplayPacer();p.reset(120)
        repeat(2000){p.sample(2.0,24)}
        assertEquals(24,p.target)
        p.reset(5);repeat(100){p.sample(300.0,5)};assertEquals(5,p.target)
    }
    @Test fun joystickHasDeadZoneAndChoosesOnlyOneDirection() {
        assertNull(RemoteDirection.choose(.2f,-.3f))
        assertNull(RemoteDirection.choose(Float.NaN,1f))
        assertEquals("up",RemoteDirection.choose(.6f,-.9f))
        assertEquals("right",RemoteDirection.choose(1f,.1f))
        assertEquals("left",RemoteDirection.choose(-.8f,0f))
        assertEquals("down",RemoteDirection.choose(0f,.8f))
    }
}
