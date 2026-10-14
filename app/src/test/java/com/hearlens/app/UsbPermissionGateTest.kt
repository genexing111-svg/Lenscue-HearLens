package com.hearlens.app

import org.junit.Assert.*
import org.junit.Test

class UsbPermissionGateTest {
    @Test fun alreadyAuthorizedNeverRequestsAgainIncludingExplicitRetry() {
        val gate=UsbPermissionGate()
        repeat(20) {assertFalse(gate.shouldRequest("board",true));assertFalse(gate.shouldRequest("board",true,true))}
    }
    @Test fun firstEntryRequestsOnceButResumeAndToggleDoNotLoopAfterDenial() {
        val gate=UsbPermissionGate();assertTrue(gate.shouldRequest("board",false))
        repeat(20) {assertFalse(gate.shouldRequest("board",false))}
        assertTrue(gate.shouldRequest("board",false,explicitRetry=true))
        assertFalse(gate.shouldRequest("board",false))
    }
    @Test fun removalAndNewAttachmentAllowOneNewRequest() {
        val gate=UsbPermissionGate();assertTrue(gate.shouldRequest("board",false))
        gate.retainAttached(emptySet());assertTrue(gate.shouldRequest("board",false))
        assertTrue(gate.shouldRequest("different-board",false))
        gate.retainAttached(setOf("different-board"));assertFalse(gate.shouldRequest("different-board",false))
        gate.detached("different-board");assertTrue(gate.shouldRequest("different-board",false))
    }
    @Test fun anAttemptIsNotTreatedAsAnActualSystemGrant() {
        val gate=UsbPermissionGate();assertTrue(gate.shouldRequest("board",false))
        assertFalse(gate.shouldRequest("board",true))
        assertFalse(gate.shouldRequest("board",false)) // still requires explicit retry, never bypasses Android.
        assertTrue(gate.shouldRequest("board",false,true))
    }
}
