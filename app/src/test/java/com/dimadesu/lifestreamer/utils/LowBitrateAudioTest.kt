package com.dimadesu.lifestreamer.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LowBitrateAudioTest {
    @Test
    fun `mono follows the measured combinations`() {
        // Measured on the SM-G780F: 32k@44.1 stalls, 48k@44.1 is choppy, 32k@16 and 48k@32 clean
        assertEquals(16_000, LowBitrateAudio.coerceSampleRate(32_000, 1, 44_100))
        assertEquals(32_000, LowBitrateAudio.coerceSampleRate(48_000, 1, 44_100))
        assertEquals(16_000, LowBitrateAudio.coerceSampleRate(32_000, 1, 16_000))
        assertEquals(32_000, LowBitrateAudio.coerceSampleRate(48_000, 1, 32_000))
        assertNull(LowBitrateAudio.maxSampleRate(64_000, 1))
    }

    @Test
    fun `stereo stays at about one bit per sample per channel`() {
        assertEquals(16_000, LowBitrateAudio.maxSampleRate(32_000, 2))
        assertEquals(24_000, LowBitrateAudio.maxSampleRate(48_000, 2))
        assertEquals(32_000, LowBitrateAudio.maxSampleRate(64_000, 2))
        assertEquals(32_000, LowBitrateAudio.maxSampleRate(96_000, 2))
        // The app's default, stereo 128k at 44.1 kHz, must stay untouched
        assertEquals(44_100, LowBitrateAudio.coerceSampleRate(128_000, 2, 44_100))
    }

    @Test
    fun `a rate already under the cap is kept`() {
        assertEquals(22_050, LowBitrateAudio.coerceSampleRate(64_000, 2, 22_050))
    }
}
