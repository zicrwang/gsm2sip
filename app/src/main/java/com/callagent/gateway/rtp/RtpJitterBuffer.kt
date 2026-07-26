package com.callagent.gateway.rtp

import java.util.TreeMap
import kotlin.math.abs
import kotlin.math.ceil

/** Ordered, bounded playout buffer for 20 ms PCMA RTP frames. */
class RtpJitterBuffer(
    private val initialPrefillFrames: Int = 3,
    private val maximumFrames: Int = 12,
    private val maximumTargetFrames: Int = 6,
    private val rebufferAfterMissingFrames: Int = 3,
    private val maximumConcealedGapFrames: Int = 5,
) {
    sealed class Playout {
        data class Packet(val packet: RtpPacket) : Playout()
        object Missing : Playout()
        object Buffering : Playout()
    }

    data class Stats(
        val buffered: Int,
        val targetFrames: Int,
        val capacityFrames: Int,
        val accepted: Long,
        val played: Long,
        val concealed: Long,
        val underruns: Long,
        val duplicate: Long,
        val reordered: Long,
        val late: Long,
        val overflow: Long,
        val invalid: Long,
        val resync: Long,
        val ssrcChanges: Long,
        val jitterMs: Int,
    )

    private val frames = TreeMap<Long, RtpPacket>()
    private val recentlyPlayed = LinkedHashSet<Long>()
    private var receiveSsrc: Long? = null
    private var sequenceCycles = 0L
    private var highestSequence: Int? = null
    private var highestExtendedSequence: Long? = null
    private var expectedSequence: Long? = null
    private var playoutStarted = false
    private var consecutiveMisses = 0
    private var previousTransit: Double? = null
    private var jitter = 0.0
    private var jitterSampleCount = 0
    private var targetFrames = initialPrefillFrames
    private var lastImpairmentNanos = 0L
    private var lastTargetChangeNanos = 0L

    private var acceptedCount = 0L
    private var playedCount = 0L
    private var concealedCount = 0L
    private var underrunCount = 0L
    private var duplicateCount = 0L
    private var reorderedCount = 0L
    private var lateCount = 0L
    private var overflowCount = 0L
    private var invalidCount = 0L
    private var resyncCount = 0L
    private var ssrcChangeCount = 0L

    @Synchronized
    fun offer(packet: RtpPacket, arrivalNanos: Long = System.nanoTime()): Boolean {
        if (packet.payloadType != RtpPacket.PT_PCMA || packet.payload.size != SAMPLES_PER_FRAME) {
            invalidCount++
            return false
        }

        if (receiveSsrc != null && receiveSsrc != packet.ssrc) {
            ssrcChangeCount++
            resetTimeline()
        }
        receiveSsrc = packet.ssrc

        val extendedSequence = extendSequence(packet.sequenceNumber)
        updateJitter(packet.timestamp, arrivalNanos)
        if (frames.containsKey(extendedSequence) || recentlyPlayed.contains(extendedSequence)) {
            duplicateCount++
            return false
        }
        if (expectedSequence?.let { extendedSequence < it } == true) {
            lateCount++
            increaseTarget(arrivalNanos)
            return false
        }
        if (highestExtendedSequence?.let { extendedSequence < it } == true) {
            reorderedCount++
        }
        highestExtendedSequence = maxOf(highestExtendedSequence ?: extendedSequence, extendedSequence)
        frames[extendedSequence] = packet
        acceptedCount++
        adaptTarget(arrivalNanos)

        if (frames.size > maximumFrames) {
            // A stalled consumer can create a burst larger than capacity. Do
            // one explicit timeline resync instead of repeatedly advancing
            // the expected sequence and manufacturing conceal/late cascades.
            increaseTarget(arrivalNanos)
            val keep = targetFrames.coerceAtMost(maximumFrames)
            while (frames.size > keep) {
                frames.pollFirstEntry() ?: break
                overflowCount++
            }
            expectedSequence = frames.firstKey()
            playoutStarted = false
            consecutiveMisses = 0
            resyncCount++
        }
        return true
    }

    @Synchronized
    fun poll(): Playout {
        if (!playoutStarted) {
            if (frames.size < targetFrames) return Playout.Buffering
            expectedSequence = frames.firstKey()
            playoutStarted = true
            consecutiveMisses = 0
        }

        val expected = expectedSequence ?: return Playout.Buffering
        frames.remove(expected)?.let { packet ->
            expectedSequence = expected + 1
            consecutiveMisses = 0
            playedCount++
            rememberPlayed(expected)
            return Playout.Packet(packet)
        }

        val next = frames.firstEntry()?.key
        if (next != null && next > expected) {
            if (next - expected > maximumConcealedGapFrames) {
                increaseTarget(System.nanoTime())
                expectedSequence = next
                resyncCount++
                return Playout.Buffering
            }
            expectedSequence = expected + 1
            consecutiveMisses++
            concealedCount++
            increaseTarget(System.nanoTime())
            return Playout.Missing
        }

        underrunCount++
        consecutiveMisses++
        increaseTarget(System.nanoTime())
        if (consecutiveMisses >= rebufferAfterMissingFrames) {
            playoutStarted = false
            expectedSequence = expected + 1
            consecutiveMisses = 0
            return Playout.Buffering
        }
        expectedSequence = expected + 1
        concealedCount++
        return Playout.Missing
    }

    @Synchronized
    fun clear() {
        resetTimeline()
        acceptedCount = 0
        playedCount = 0
        concealedCount = 0
        underrunCount = 0
        duplicateCount = 0
        reorderedCount = 0
        lateCount = 0
        overflowCount = 0
        invalidCount = 0
        resyncCount = 0
        ssrcChangeCount = 0
        targetFrames = initialPrefillFrames
        lastImpairmentNanos = 0L
        lastTargetChangeNanos = 0L
        jitterSampleCount = 0
    }

    @Synchronized
    fun stats(): Stats = Stats(
        buffered = frames.size,
        targetFrames = targetFrames,
        capacityFrames = maximumFrames,
        accepted = acceptedCount,
        played = playedCount,
        concealed = concealedCount,
        underruns = underrunCount,
        duplicate = duplicateCount,
        reordered = reorderedCount,
        late = lateCount,
        overflow = overflowCount,
        invalid = invalidCount,
        resync = resyncCount,
        ssrcChanges = ssrcChangeCount,
        jitterMs = (jitter * 1_000 / RTP_CLOCK_RATE).toInt(),
    )

    private fun extendSequence(sequence: Int): Long {
        val highest = highestSequence
        if (highest == null) {
            highestSequence = sequence
            return sequence.toLong()
        }
        val delta = sequence - highest
        if (delta < -32_768) {
            sequenceCycles += 65_536
            highestSequence = sequence
            return sequenceCycles + sequence
        }
        if (delta > 32_768) {
            return sequenceCycles - 65_536 + sequence
        }
        if (delta > 0) highestSequence = sequence
        return sequenceCycles + sequence
    }

    private fun updateJitter(timestamp: Long, arrivalNanos: Long) {
        val arrival = arrivalNanos.toDouble() * RTP_CLOCK_RATE / 1_000_000_000.0
        val transit = arrival - timestamp
        previousTransit?.let { previous ->
            val delta = abs(transit - previous)
            jitter += (delta - jitter) / 16.0
            jitterSampleCount++
        }
        previousTransit = transit
    }

    private fun adaptTarget(nowNanos: Long) {
        val effectiveMaximumTarget = minOf(maximumTargetFrames, maximumFrames)
        val jitterFrames = if (jitterSampleCount >= JITTER_ADAPTATION_MIN_SAMPLES) {
            ceil(jitter / SAMPLES_PER_FRAME).toInt()
        } else {
            0
        }
        val networkTarget = (initialPrefillFrames + jitterFrames)
            .coerceIn(initialPrefillFrames, effectiveMaximumTarget)
        if (networkTarget > targetFrames) {
            targetFrames = networkTarget
            lastTargetChangeNanos = nowNanos
            return
        }
        if (targetFrames > networkTarget &&
            nowNanos - lastImpairmentNanos >= TARGET_DECREASE_STABLE_NANOS &&
            nowNanos - lastTargetChangeNanos >= TARGET_DECREASE_STABLE_NANOS) {
            targetFrames--
            lastTargetChangeNanos = nowNanos
        }
    }

    private fun increaseTarget(nowNanos: Long) {
        lastImpairmentNanos = nowNanos
        if (targetFrames < minOf(maximumTargetFrames, maximumFrames)) {
            targetFrames++
            lastTargetChangeNanos = nowNanos
        }
    }

    private fun rememberPlayed(sequence: Long) {
        recentlyPlayed.add(sequence)
        while (recentlyPlayed.size > RECENT_SEQUENCE_LIMIT) {
            val oldest = recentlyPlayed.iterator().next()
            recentlyPlayed.remove(oldest)
        }
    }

    private fun resetTimeline() {
        frames.clear()
        recentlyPlayed.clear()
        receiveSsrc = null
        sequenceCycles = 0
        highestSequence = null
        highestExtendedSequence = null
        expectedSequence = null
        playoutStarted = false
        consecutiveMisses = 0
        previousTransit = null
        jitter = 0.0
        jitterSampleCount = 0
    }

    companion object {
        private const val SAMPLES_PER_FRAME = 160
        private const val RTP_CLOCK_RATE = 8_000.0
        private const val RECENT_SEQUENCE_LIMIT = 64
        private const val TARGET_DECREASE_STABLE_NANOS = 10_000_000_000L
        private const val JITTER_ADAPTATION_MIN_SAMPLES = 10
    }
}
