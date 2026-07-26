package com.callagent.gateway.sip

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SipMessageTest {
    @Test
    fun parsesNegotiatedTelephoneEventPt101FromAudioSdp() {
        val message = SipMessage.parse(inviteWithSdp(
            "m=audio 30000 RTP/AVP 8 101\r\n" +
                "a=rtpmap:8 PCMA/8000\r\n" +
                "a=rtpmap:101 telephone-event/8000\r\n" +
                "a=fmtp:101 0-16\r\n"
        ))

        assertEquals(101, message?.sdpTelephoneEventPayloadType)
    }

    @Test
    fun ignoresTelephoneEventMappingNotOfferedByAudioMedia() {
        val message = SipMessage.parse(inviteWithSdp(
            "m=audio 30000 RTP/AVP 8\r\n" +
                "a=rtpmap:8 PCMA/8000\r\n" +
                "a=rtpmap:101 telephone-event/8000\r\n"
        ))

        assertNull(message?.sdpTelephoneEventPayloadType)
    }

    private fun inviteWithSdp(sdp: String): String =
        "INVITE sip:mi8@example.test SIP/2.0\r\n" +
            "Call-ID: dtmf-test\r\n" +
            "CSeq: 1 INVITE\r\n" +
            "Content-Type: application/sdp\r\n" +
            "Content-Length: ${sdp.length}\r\n\r\n" +
            sdp
}
