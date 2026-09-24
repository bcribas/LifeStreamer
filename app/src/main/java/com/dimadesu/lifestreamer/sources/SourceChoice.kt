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
        /**
         * The second layer's source as the app saved it before (a PipSourceKind name). A second
         * camera was a kind then, not a camera: it is [secondCamera], or the test image.
         */
        fun fromLegacyKind(name: String?, secondCamera: () -> String?): SourceChoice? = when (name) {
            "TEST_IMAGE" -> TestImage
            "CAMERA" -> secondCamera()?.let { Camera(it) } ?: TestImage
            "USB" -> Usb
            "SCREEN" -> Screen
            "RTMP" -> Rtmp(1)
            else -> null
        }

        /** The other way, for a blob an older app may read back. */
        fun legacyKind(choice: SourceChoice?): String = when (choice) {
            is Camera -> "CAMERA"
            is Rtmp -> "RTMP"
            Usb -> "USB"
            Screen -> "SCREEN"
            TestImage, null -> "TEST_IMAGE"
        }

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
