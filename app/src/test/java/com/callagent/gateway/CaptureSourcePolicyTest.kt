package com.callagent.gateway

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureSourcePolicyTest {
    @Test
    fun verifiedDownlinkPolicyNeverOffersPhysicalOrMixedSources() {
        val sources = CaptureSourcePolicy.VERIFIED_DOWNLINK_ONLY.initialSources(
            preferTelephonyCapture = true
        )

        assertEquals(listOf(CaptureSourceKind.VOICE_DOWNLINK), sources)
        assertFalse(sources.contains(CaptureSourceKind.VOICE_CALL))
        assertFalse(sources.contains(CaptureSourceKind.VOICE_RECOGNITION))
        assertFalse(sources.contains(CaptureSourceKind.MIC))
        assertFalse(sources.contains(CaptureSourceKind.VOICE_COMMUNICATION))
    }

    @Test
    fun ordinaryPolicyKeepsInitialFallbacksForUnverifiedDevices() {
        val sources = CaptureSourcePolicy.INITIAL_FALLBACK_ALLOWED.initialSources(
            preferTelephonyCapture = false
        )

        assertFalse(sources.contains(CaptureSourceKind.VOICE_DOWNLINK))
        assertTrue(sources.contains(CaptureSourceKind.MIC))
    }
}
