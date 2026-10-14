package com.hearlens.app

import org.junit.Assert.*
import org.junit.Test

class EnvironmentNoticeTest {
    @Test fun entersThenSlidesOffToOnThenDismisses() {
        val notice=EnvironmentNotice.transition(null,true,1000)
        assertEquals(0f,notice.frame(1000).reveal,0f)
        assertEquals(0f,notice.frame(1160).switchPosition,0f)
        assertEquals(1f,notice.frame(1220).reveal,0f)
        assertEquals(.5f,notice.frame(1320).switchPosition,.001f)
        assertEquals(1f,notice.frame(1480).switchPosition,0f)
        assertTrue(notice.frame(3000).reveal in 0f..1f)
        assertFalse(notice.frame(3160).active)
        assertEquals(0f,notice.frame(3160).reveal,0f)
    }
    @Test fun offNotificationReversesSwitchTravel() {
        val notice=EnvironmentNotice.transition(null,false,0)
        assertEquals(1f,notice.frame(160).switchPosition,0f)
        assertEquals(.5f,notice.frame(320).switchPosition,.001f)
        assertEquals(0f,notice.frame(480).switchPosition,0f)
    }
    @Test fun rapidPressReversesFromVisibleStateWithoutJumpOrQueue() {
        val on=EnvironmentNotice.transition(null,true,0)
        val off=EnvironmentNotice.transition(on,false,320)
        assertEquals(on.frame(320).switchPosition,off.frame(320).switchPosition,0f)
        assertEquals(on.frame(320).reveal,off.frame(320).reveal,0f)
        assertEquals(0f,off.frame(800).switchPosition,0f)
        assertFalse(off.frame(2480).active)
    }
    @Test fun expiredNotificationRestartsFromCorrectSide() {
        val old=EnvironmentNotice.transition(null,true,0)
        val off=EnvironmentNotice.transition(old,false,3000,"未授权麦克风")
        assertEquals(1f,off.from,0f);assertEquals(0f,off.entryFrom,0f)
        assertEquals("未授权麦克风",off.message)
    }
}
