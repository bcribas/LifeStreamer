package com.dimadesu.lifestreamer.sources

/**
 * What feeds the picture: the whole of it (no composition), or one layer of a composition. The
 * same choices for both, so the phone and the page offer the same things everywhere.
 *
 * [key] is how it travels to the page and is stored: a flat string, as a sealed type does not
 * survive Gson and R8.
 */
sealed interface SourceChoice {
    val key: String

    data class Camera(val id: String) : SourceChoice {
        override val key get() = "camera:$id"
    }

    /** RTMP (or SRT) source [index], 1-based, as numbered in the settings. */
    data class Rtmp(val index: Int) : SourceChoice {
        override val key get() = "rtmp:$index"
    }

    data object Usb : SourceChoice {
        override val key = "usb"
    }

    data object Screen : SourceChoice {
        override val key = "screen"
    }

    /** The test image: what a layer shows when its source is missing, or on purpose. */
    data object TestImage : SourceChoice {
        override val key = "test"
    }

    companion object {
        fun parse(key: String?): SourceChoice? {
            val text = key?.trim() ?: return null
            return when {
                text == Usb.key -> Usb
                text == Screen.key -> Screen
                text == TestImage.key -> TestImage
                text.startsWith("camera:") -> text.removePrefix("camera:").takeIf { it.isNotEmpty() }?.let { Camera(it) }
                text.startsWith("rtmp:") -> text.removePrefix("rtmp:").toIntOrNull()?.takeIf { it >= 1 }?.let { Rtmp(it) }
                else -> null
            }
        }
    }
}
