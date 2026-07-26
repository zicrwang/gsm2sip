package com.callagent.gateway

import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceProfileTest {
    @Test
    fun mi8KeepsDigitalInjectionWhileDisconnectingPhysicalAudio() {
        val profile = DeviceProfile.sdm845Dipper()

        assertTrue(profile.mixerIncallMusicCmd.contains("'Incall_Music Audio Mixer MultiMedia1' 1"))
        assertTrue(profile.mixerIncallMusicCmd.contains("'Voice Tx Mute' 0"))
        for (tx in 6..8) {
            assertTrue(profile.mixerIncallMusicCmd.contains("'CDC_IF TX$tx MUX' 'ZERO'"))
        }
        assertTrue(profile.mixerIncallMusicCmd.contains("'QUAT_MI2S_RX Audio Mixer MultiMedia1' 0"))
    }
}
