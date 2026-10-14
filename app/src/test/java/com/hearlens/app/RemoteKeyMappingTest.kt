package com.hearlens.app

import org.junit.Assert.*
import org.junit.Test

class RemoteKeyMappingTest {
    @Test fun measuredShoulderVariantsShareOneActionForCalibratedScb03() {
        for(code in listOf(96,101))assertEquals("shoulder",RemoteKeyMapping.overrideAction("SC-B03-E01A",code,null,true))
    }
    @Test fun printedAStillUsesItsCalibratedVolumeDownCode() {
        assertEquals("confirm",RemoteKeyMapping.overrideAction("SC-B03-E01A",25,"confirm",true))
    }
    @Test fun uncalibratedAndroidButtonADefersToNormalConfirm() {
        assertNull(RemoteKeyMapping.overrideAction("SC-B03-E01A",96,null,false))
    }
    @Test fun otherGamepadsAreNotRemapped() {
        for(code in listOf(96,101,66,85))assertNull(RemoteKeyMapping.overrideAction("Other gamepad",code,null,true))
    }
    @Test fun explicitLearningOverridesEveryShoulderAlias() {
        for(code in listOf(96,101,66,85))assertEquals("confirm",RemoteKeyMapping.overrideAction("SC-B03-E01A",code,"confirm",true))
    }
    @Test fun calibratedXAndYMigrateWithoutChangingB() {
        for((code,action) in mapOf(24 to "menu",97 to "back",108 to "minus")) {
            assertEquals(when(action){"menu"->"language";"minus"->"perception";else->action},RemoteKeyMapping.overrideAction("SC-B03-E01A",code,action,true))
        }
    }
    @Test fun newPerceptionBindingSurvivesAndExplicitOtherActionsStillWin() {
        assertEquals("perception",RemoteKeyMapping.overrideAction("SC-B03-E01A",108,"perception",true))
        assertEquals("confirm",RemoteKeyMapping.overrideAction("SC-B03-E01A",108,"confirm",true))
    }
    @Test fun onePhysicalPressTogglesOnlyOnceAndNextPressWorks() {
        val gate=ShortcutPressGate()
        assertTrue(gate.key("1:Y",true,0))
        assertFalse(gate.key("1:Y",true,1))
        assertFalse(gate.key("1:Y",true,0))
        assertFalse(gate.key("1:Y",false,0))
        assertTrue(gate.key("1:Y",true,0))
        gate.reset();assertFalse(gate.key("1:Y",true,3))
        assertTrue(gate.key("1:Y",true,0))
    }
    @Test fun changingProfileBackToGamepadCanRestoreButtonA() {
        assertEquals("shoulder",RemoteKeyMapping.overrideAction("SC-B03-E01A",96,null,true))
        assertEquals("confirm",RemoteKeyMapping.overrideAction("SC-B03-E01A",96,"confirm",false))
    }
}
