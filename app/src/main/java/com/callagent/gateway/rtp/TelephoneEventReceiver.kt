package com.callagent.gateway.rtp

/** Consumes negotiated RFC2833/4733 telephone-event RTP packets. */
class TelephoneEventReceiver(
    private val payloadType: Int,
    private val onDigit: (Char) -> Unit,
) {
    private data class CompletedEvent(
        val ssrc: Long,
        val timestamp: Long,
        val event: Int,
    )

    private val completedEvents = LinkedHashSet<CompletedEvent>()

    /** Returns true when the packet belongs to the telephone-event stream. */
    @Synchronized
    fun offer(packet: RtpPacket): Boolean {
        if (packet.payloadType != payloadType) return false
        if (packet.payload.size < 4) return true

        val event = packet.payload[0].toInt() and 0xFF
        val ended = (packet.payload[1].toInt() and END_BIT) != 0
        if (!ended) return true

        val completed = CompletedEvent(packet.ssrc, packet.timestamp, event)
        if (!completedEvents.add(completed)) return true
        while (completedEvents.size > MAX_COMPLETED_EVENTS) {
            val iterator = completedEvents.iterator()
            iterator.next()
            iterator.remove()
        }

        digitForEvent(event)?.let(onDigit)
        return true
    }

    companion object {
        private const val END_BIT = 0x80
        private const val MAX_COMPLETED_EVENTS = 32

        fun digitForEvent(event: Int): Char? = when (event) {
            in 0..9 -> ('0'.code + event).toChar()
            10 -> '*'
            11 -> '#'
            else -> null
        }
    }
}
