package com.dimadesu.lifestreamer.audio

import com.dimadesu.lifestreamer.sources.SourceChoice
import org.junit.Assert.assertEquals
import org.junit.Test

class AudioRouteTest {
    private val q = 29

    @Test
    fun `an rtmp source brings its own sound`() {
        assertEquals(AudioRoute.APP_AUDIO, AudioRoute.of(SourceChoice.Rtmp(2), systemAudio = false, apiLevel = q))
        // SYS AUDIO is about the cameras: an RTMP layer still gives only its own sound
        assertEquals(AudioRoute.APP_AUDIO, AudioRoute.of(SourceChoice.Rtmp(1), systemAudio = true, apiLevel = q))
    }

    @Test
    fun `the rest take the microphone, or the phone's sound with sys audio`() {
        listOf(SourceChoice.Camera("0"), SourceChoice.Usb, SourceChoice.Screen, SourceChoice.TestImage, null).forEach {
            assertEquals(AudioRoute.MIC, AudioRoute.of(it, systemAudio = false, apiLevel = q))
            assertEquals(AudioRoute.PHONE_AUDIO, AudioRoute.of(it, systemAudio = true, apiLevel = q))
        }
    }

    @Test
    fun `without android 10 there is only the microphone`() {
        assertEquals(AudioRoute.MIC, AudioRoute.of(SourceChoice.Rtmp(1), systemAudio = true, apiLevel = 28))
    }
}
