package com.callagent.gateway.rtp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TelephoneEventReceiverTest {
    @Test
    fun pt101TriggersDigitOnceForRepeatedEndPackets() {
        val digits = mutableListOf<Char>()
        val receiver = TelephoneEventReceiver(101, digits::add)

        assertTrue(receiver.offer(eventPacket(sequence = 10, ended = false, duration = 160)))
        assertTrue(receiver.offer(eventPacket(sequence = 11, ended = true, duration = 640)))
        assertTrue(receiver.offer(eventPacket(sequence = 12, ended = true, duration = 640)))
        assertTrue(receiver.offer(eventPacket(sequence = 13, ended = true, duration = 640)))

        assertEquals(listOf('1'), digits)
    }

    @Test
    fun ignoresAudioPayloadAndMapsStarAndHash() {
        val digits = mutableListOf<Char>()
        val receiver = TelephoneEventReceiver(101, digits::add)

        assertFalse(receiver.offer(eventPacket(payloadType = 8, event = 1, ended = true)))
        assertTrue(receiver.offer(eventPacket(event = 10, timestamp = 20_000, ended = true)))
        assertTrue(receiver.offer(eventPacket(event = 11, timestamp = 20_640, ended = true)))

        assertEquals(listOf('*', '#'), digits)
    }

    private fun eventPacket(
        payloadType: Int = 101,
        sequence: Int = 1,
        timestamp: Long = 10_000,
        event: Int = 1,
        ended: Boolean,
        duration: Int = 640,
    ) = RtpPacket(
        payloadType = payloadType,
        sequenceNumber = sequence,
        timestamp = timestamp,
        ssrc = 0x12345678,
        payload = byteArrayOf(
            event.toByte(),
            ((if (ended) 0x80 else 0) or 10).toByte(),
            (duration shr 8).toByte(),
            duration.toByte(),
        ),
    )
}
