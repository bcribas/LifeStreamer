package com.dimadesu.lifestreamer.audio

import com.dimadesu.lifestreamer.sources.SourceChoice

/** Where the live's sound comes from. */
enum class AudioRoute {
    /** The microphone, or the Bluetooth one when that is on. */
    MIC,

    /** What the app itself plays, captured: an RTMP source's sound. */
    APP_AUDIO,

    /** Everything the phone plays, captured (SYS AUDIO). */
    PHONE_AUDIO;

    /** Needs a screen-capture grant. */
    val captures: Boolean get() = this != MIC

    companion object {
        /** Capturing what plays needs Android 10. */
        const val API_CAPTURE = 29

        /**
         * The sound for [source]: the whole picture's source, or in a composition the 🔊 layer's.
         * An RTMP source brings its own sound; a camera, the USB camera, the screen and the test
         * image take the microphone, or the phone's sound with SYS AUDIO ([systemAudio]).
         */
        fun of(source: SourceChoice?, systemAudio: Boolean, apiLevel: Int): AudioRoute = when {
            apiLevel < API_CAPTURE -> MIC
            source is SourceChoice.Rtmp -> APP_AUDIO
            systemAudio -> PHONE_AUDIO
            else -> MIC
        }
    }
}
