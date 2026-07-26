package com.callagent.gateway.rtp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RtpJitterBufferTest {
    @Test
    fun reordersPacketsBeforePlayout() {
        val buffer = RtpJitterBuffer()
        buffer.offer(packet(100))
        buffer.offer(packet(102))
        buffer.offer(packet(101))

        assertSequence(100, buffer.poll())
        assertSequence(101, buffer.poll())
        assertSequence(102, buffer.poll())
        assertEquals(1, buffer.stats().reordered)
    }

    @Test
    fun handlesSequenceWrap() {
        val buffer = RtpJitterBuffer()
        buffer.offer(packet(65_534))
        buffer.offer(packet(65_535))
        buffer.offer(packet(0))

        assertSequence(65_534, buffer.poll())
        assertSequence(65_535, buffer.poll())
        assertSequence(0, buffer.poll())
    }

    @Test
    fun reportsDuplicateAndShortLoss() {
        val buffer = RtpJitterBuffer()
        assertTrue(buffer.offer(packet(10)))
        assertTrue(!buffer.offer(packet(10)))
        buffer.offer(packet(12))
        buffer.offer(packet(13))

        assertSequence(10, buffer.poll())
        assertEquals(RtpJitterBuffer.Playout.Missing, buffer.poll())
        assertSequence(12, buffer.poll())
        assertEquals(1, buffer.stats().duplicate)
        assertEquals(1, buffer.stats().concealed)
    }

    @Test
    fun resetsTimelineWhenSsrcChanges() {
        val buffer = RtpJitterBuffer()
        buffer.offer(packet(20, ssrc = 1))
        buffer.offer(packet(21, ssrc = 1))
        buffer.offer(packet(22, ssrc = 1))
        assertSequence(20, buffer.poll())

        buffer.offer(packet(5, ssrc = 2))
        buffer.offer(packet(6, ssrc = 2))
        buffer.offer(packet(7, ssrc = 2))
        assertSequence(5, buffer.poll())
        assertEquals(1, buffer.stats().ssrcChanges)
    }

    @Test
    fun boundsQueueAndCountsOverflow() {
        val buffer = RtpJitterBuffer(initialPrefillFrames = 3, maximumFrames = 5)
        for (sequence in 1..8) buffer.offer(packet(sequence))

        assertEquals(5, buffer.stats().buffered)
        assertEquals(3, buffer.stats().overflow)
        assertSequence(4, buffer.poll())
    }

    @Test
    fun advancesTimelineDuringUnderrunThenRebuffers() {
        val buffer = RtpJitterBuffer()
        buffer.offer(packet(40))
        buffer.offer(packet(41))
        buffer.offer(packet(42))

        assertSequence(40, buffer.poll())
        assertSequence(41, buffer.poll())
        assertSequence(42, buffer.poll())
        assertEquals(RtpJitterBuffer.Playout.Missing, buffer.poll())
        assertEquals(RtpJitterBuffer.Playout.Missing, buffer.poll())
        assertEquals(RtpJitterBuffer.Playout.Buffering, buffer.poll())
        assertTrue(!buffer.offer(packet(43)))
        assertEquals(1, buffer.stats().late)
        assertEquals(2, buffer.stats().concealed)
        assertEquals(3, buffer.stats().underruns)
    }

    private fun packet(sequence: Int, ssrc: Long = 7): RtpPacket = RtpPacket(
        payloadType = RtpPacket.PT_PCMA,
        sequenceNumber = sequence,
        timestamp = sequence.toLong() * 160,
        ssrc = ssrc,
        payload = ByteArray(160) { 0xD5.toByte() },
    )

    private fun assertSequence(expected: Int, playout: RtpJitterBuffer.Playout) {
        val packet = (playout as RtpJitterBuffer.Playout.Packet).packet
        assertEquals(expected, packet.sequenceNumber)
    }
}
