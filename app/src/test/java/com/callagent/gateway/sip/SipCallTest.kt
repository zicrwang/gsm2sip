package com.callagent.gateway.sip

import org.junit.Assert.assertEquals
import org.junit.Test

class SipCallTest {
    @Test
    fun inboundLocalRequestReversesInviteRolesAndAddsLocalTag() {
        val headers = localRequestDialogHeaders(
            direction = SipCall.Direction.INBOUND,
            inviteFrom = "\"WPhone\" <sip:wphone@example.test>;tag=remote-123",
            inviteTo = "<sip:gateway@example.test>",
            localTag = "gw-local-456",
        )

        assertEquals("<sip:gateway@example.test>;tag=gw-local-456", headers.from)
        assertEquals("\"WPhone\" <sip:wphone@example.test>;tag=remote-123", headers.to)
    }

    @Test
    fun inboundLocalRequestKeepsExistingLocalTag() {
        val headers = localRequestDialogHeaders(
            direction = SipCall.Direction.INBOUND,
            inviteFrom = "<sip:wphone@example.test>;tag=remote-123",
            inviteTo = "<sip:gateway@example.test>;tag=existing-local",
            localTag = "gw-local-456",
        )

        assertEquals("<sip:gateway@example.test>;tag=existing-local", headers.from)
    }

    @Test
    fun outboundLocalRequestKeepsEstablishedDialogHeaders() {
        val headers = localRequestDialogHeaders(
            direction = SipCall.Direction.OUTBOUND,
            inviteFrom = "<sip:gateway@example.test>;tag=gw-local-456",
            inviteTo = "<sip:wphone@example.test>;tag=remote-123",
            localTag = "gw-local-456",
        )

        assertEquals("<sip:gateway@example.test>;tag=gw-local-456", headers.from)
        assertEquals("<sip:wphone@example.test>;tag=remote-123", headers.to)
    }
}
