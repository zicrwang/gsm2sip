package com.callagent.gateway.rtp

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RtpPacketTest {
    @Test
    fun decodesHeaderExtensionAndPadding() {
        val payload = byteArrayOf(0xD5.toByte(), 0x55)
        val bytes = byteArrayOf(
            0xB0.toByte(), 0x08, // V=2, padding, extension, PT=8
            0x00, 0x02, // sequence
            0x00, 0x00, 0x00, 0xA0.toByte(), // timestamp
            0x00, 0x00, 0x00, 0x07, // SSRC
            0x10, 0x00, 0x00, 0x01, // extension profile + one word
            0x01, 0x02, 0x03, 0x04,
            payload[0], payload[1],
            0x00, 0x02, // two bytes of padding
        )

        val decoded = RtpPacket.decode(bytes)
        assertEquals(2, decoded?.sequenceNumber)
        assertEquals(160L, decoded?.timestamp)
        assertArrayEquals(payload, decoded?.payload)
    }

    @Test
    fun rejectsInvalidPadding() {
        val bytes = RtpPacket(
            RtpPacket.PT_PCMA,
            1,
            0,
            1,
            byteArrayOf(0xD5.toByte()),
        ).encode()
        bytes[0] = 0xA0.toByte()
        bytes[bytes.lastIndex] = 20

        assertNull(RtpPacket.decode(bytes))
    }
}
