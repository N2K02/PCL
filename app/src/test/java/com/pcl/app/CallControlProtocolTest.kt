package com.pcl.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CallControlProtocolTest {
    @Test fun acceptsOnlyOppositeBluetoothRoles() {
        assertTrue(CallControlProtocol.peerRoleMatches(LinkService.ROLE_GATEWAY, LinkService.ROLE_REMOTE))
        assertTrue(CallControlProtocol.peerRoleMatches(LinkService.ROLE_REMOTE, LinkService.ROLE_GATEWAY))
        assertFalse(CallControlProtocol.peerRoleMatches(LinkService.ROLE_REMOTE, LinkService.ROLE_REMOTE))
        assertFalse(CallControlProtocol.peerRoleMatches(LinkService.ROLE_GATEWAY, "unknown"))
    }

    @Test fun delayedAcknowledgementNeverCompletesNewerRequest() {
        assertTrue(CallControlProtocol.matchesResult(7, "ANSWER", "7"))
        assertFalse(CallControlProtocol.matchesResult(8, "REJECT", "7"))
        assertFalse(CallControlProtocol.matchesResult(7, null, "7"))
        assertFalse(CallControlProtocol.matchesResult(7, "ANSWER", null))
        assertFalse(CallControlProtocol.matchesResult(7, "ANSWER", "not-a-number"))
        assertFalse(CallControlProtocol.matchesResult(0, "ANSWER", "0"))
    }

    @Test fun successRequiresActualTelecomStateNotMerelyAck() {
        assertTrue(CallControlProtocol.stateConfirmed("ANSWER", LinkService.CALL_OFFHOOK))
        assertTrue(CallControlProtocol.stateConfirmed("DIAL", LinkService.CALL_OFFHOOK))
        assertTrue(CallControlProtocol.stateConfirmed("DIAL_SIM", LinkService.CALL_OFFHOOK))
        assertTrue(CallControlProtocol.stateConfirmed("REJECT", LinkService.CALL_IDLE))
        assertTrue(CallControlProtocol.stateConfirmed("HANGUP", LinkService.CALL_IDLE))
        assertFalse(CallControlProtocol.stateConfirmed("ANSWER", LinkService.CALL_RINGING))
        assertFalse(CallControlProtocol.stateConfirmed("REJECT", LinkService.CALL_RINGING))
        assertFalse(CallControlProtocol.stateConfirmed("DIAL", LinkService.CALL_IDLE))
        assertFalse(CallControlProtocol.stateConfirmed(null, LinkService.CALL_IDLE))
    }
}
