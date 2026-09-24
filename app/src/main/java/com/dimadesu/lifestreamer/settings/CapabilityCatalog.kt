package com.dimadesu.lifestreamer.settings

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.util.Size
import com.dimadesu.lifestreamer.R
import com.dimadesu.lifestreamer.models.EndpointType
import com.dimadesu.lifestreamer.settings.SettingsRules.Dimensions
import com.dimadesu.lifestreamer.utils.LowBitrateAudio
import com.dimadesu.lifestreamer.utils.ProfileLevelDisplay
import com.dimadesu.lifestreamer.utils.StreamerInfoFactory
import io.github.thibaultbee.streampack.core.elements.encoders.mediacodec.MediaCodecHelper
import io.github.thibaultbee.streampack.core.elements.sources.video.camera.extensions.defaultCameraId
import io.github.thibaultbee.streampack.core.streamers.single.AudioConfig
import io.github.thibaultbee.streampack.core.streamers.single.VideoConfig

/**
 * What this device and the chosen endpoint can do, as the choices a setting offers.
 *
 * The settings screen and the remote page both build their lists from here, so they offer the
 * same choices. Building it queries the codecs and the cameras: keep one per endpoint type.
 */
class CapabilityCatalog(private val context: Context, val endpointType: EndpointType) {

    data class Option(val value: String, val label: String)

    private val info = StreamerInfoFactory(context, endpointType).build()
    private val profileLevelDisplay = ProfileLevelDisplay(context)

    // region video

    fun videoEncoders(): List<Option> {
        val names = mapOf(
            MediaFormat.MIMETYPE_VIDEO_AVC to context.getString(R.string.video_encoder_h264),
            MediaFormat.MIMETYPE_VIDEO_HEVC to context.getString(R.string.video_encoder_h265),
            MediaFormat.MIMETYPE_VIDEO_H263 to context.getString(R.string.video_encoder_h263),
            MediaFormat.MIMETYPE_VIDEO_VP9 to context.getString(R.string.video_encoder_vp9),
            MediaFormat.MIMETYPE_VIDEO_VP8 to context.getString(R.string.video_encoder_vp8),
            MediaFormat.MIMETYPE_VIDEO_AV1 to context.getString(R.string.video_encoder_av1)
        )
        return info.video.supportedEncoders.map { Option(it, names[it] ?: it) }
    }

    fun defaultVideoEncoder(): String? = info.video.supportedEncoders.let { encoders ->
        when {
            encoders.isEmpty() -> null
            MediaFormat.MIMETYPE_VIDEO_AVC in encoders -> MediaFormat.MIMETYPE_VIDEO_AVC
            else -> encoders.first()
        }
    }

    /**
     * The resolutions the live can use: the cameras' own sizes the codec takes when
     * [hardwareOnly], otherwise a standard list filtered by the codec. Largest first.
     */
    fun videoResolutions(encoder: String, hardwareOnly: Boolean): List<Dimensions> {
        val sizes: List<Size> = if (hardwareOnly) {
            info.video.getSupportedResolutions(context, encoder)
        } else {
            val (widths, heights) = info.video.getSupportedResolutions(encoder)
            STANDARD_RESOLUTIONS.filter { widths.contains(it.width) && heights.contains(it.height) }
                .map { Size(it.width, it.height) }
        }
        return sizes.map { Dimensions(it.width, it.height) }
            .sortedWith(compareByDescending<Dimensions> { it.height }.thenByDescending { it.width })
    }

    /** Frame rates from [candidates] the encoder takes. */
    fun encoderFps(encoder: String, candidates: List<Int> = fpsCandidates()): List<Int> {
        val range = info.video.getSupportedFramerate(encoder)
        return candidates.filter { range.contains(it) }
    }

    /** Frame rates from [candidates] the default camera runs at. */
    fun cameraFps(encoder: String, candidates: List<Int> = fpsCandidates()): List<Int> {
        val ranges = info.video.getSupportedFramerates(context, encoder, context.defaultCameraId)
        return candidates.filter { fps -> ranges.any { it.contains(fps) } }
    }

    /** The live bitrate range in kb/s: the settings' own 200..10000, within the codec's. */
    fun videoBitrateRangeKbps(encoder: String): IntRange {
        val codec = info.video.getSupportedBitrates(encoder)
        return maxOf(VIDEO_BITRATE_MIN_KBPS, codec.lower / 1000)..minOf(VIDEO_BITRATE_MAX_KBPS, codec.upper / 1000)
    }

    fun videoProfiles(encoder: String): List<Option> =
        info.video.getSupportedAllProfiles(context, encoder, context.defaultCameraId).map {
            Option(it.toString(), profileLevelDisplay.getProfileName(encoder, it))
        }

    fun defaultVideoProfile(encoder: String): String = VideoConfig.getBestProfile(encoder).toString()

    fun videoLevels(encoder: String, profile: Int): List<Option> =
        profileLevelDisplay.getAllLevelSet(encoder)
            .filter { it <= MediaCodecHelper.getMaxLevel(encoder, profile) }
            .map { Option(it.toString(), profileLevelDisplay.getLevelName(encoder, it)) }

    fun defaultVideoLevel(encoder: String, profile: Int): String =
        VideoConfig.getBestLevel(encoder, profile).toString()

    /**
     * What the recording offers: the live's own resolution, then the live's resolutions with its
     * shape (the recording shares the live's canvas, another shape would be stretched).
     */
    fun recordingResolutions(encoder: String, hardwareOnly: Boolean, live: Dimensions?): List<Dimensions> =
        videoResolutions(encoder, hardwareOnly).filter { live == null || SettingsRules.sameShape(it, live) }

    // endregion

    // region audio

    fun audioEncoders(): List<Option> {
        val names = mapOf(
            MediaFormat.MIMETYPE_AUDIO_AAC to context.getString(R.string.audio_encoder_aac),
            MediaFormat.MIMETYPE_AUDIO_OPUS to context.getString(R.string.audio_encoder_opus)
        )
        return info.audio.supportedEncoders.map { Option(it, names[it] ?: it) }
    }

    fun defaultAudioEncoder(): String? = info.audio.supportedEncoders.let { encoders ->
        if (MediaFormat.MIMETYPE_AUDIO_AAC in encoders) MediaFormat.MIMETYPE_AUDIO_AAC else encoders.firstOrNull()
    }

    /** Channel configs ("16" mono, "12" stereo) the encoder takes. */
    fun audioChannelConfigs(encoder: String): List<Option> {
        val range = info.audio.getSupportedInputChannelRange(encoder)
        val values = context.resources.getStringArray(R.array.AudioChannelConfigEntryValues)
        val labels = context.resources.getStringArray(R.array.AudioChannelConfigEntries)
        return values.indices
            .filter { range.contains(AudioConfig.getNumberOfChannels(values[it].toInt())) }
            .map { Option(values[it], labels.getOrElse(it) { values[it] }) }
    }

    /** Bitrates in b/s the encoder takes, from the app's list. */
    fun audioBitrates(encoder: String): List<Option> {
        val range = info.audio.getSupportedBitrates(encoder)
        return context.resources.getStringArray(R.array.AudioBitrateEntryValues)
            .mapNotNull { it.toIntOrNull() }
            .filter { range.contains(it) }
            .map { Option(it.toString(), "${it / 1000} kbps") }
    }

    /**
     * Sample rates the encoder takes, capped for a low [bitrate] over [channelConfig]'s channels
     * (see [LowBitrateAudio]).
     */
    fun audioSampleRates(encoder: String, bitrate: Int?, channelConfig: Int?): List<Option> {
        val channels = channelConfig?.let { AudioConfig.getNumberOfChannels(it) } ?: 2
        val cap = bitrate?.let { LowBitrateAudio.maxSampleRate(it, channels) }
        val supported = info.audio.getSupportedSampleRates(encoder).toList()
        return supported.filter { cap == null || it <= cap }.ifEmpty { supported }
            .map { Option(it.toString(), "%.1f kHz".format(it / 1000f)) }
    }

    fun audioByteFormats(): List<Option> {
        val names = mapOf(
            AudioFormat.ENCODING_PCM_8BIT to context.getString(R.string.audio_byte_format_8bit),
            AudioFormat.ENCODING_PCM_16BIT to context.getString(R.string.audio_byte_format_16bit),
            AudioFormat.ENCODING_PCM_FLOAT to context.getString(R.string.audio_byte_format_float)
        )
        return info.audio.getSupportedByteFormats().map { Option(it.toString(), names[it] ?: it.toString()) }
    }

    fun audioProfiles(encoder: String): List<Option> =
        info.audio.getSupportedProfiles(encoder).map {
            Option(it.toString(), profileLevelDisplay.getProfileName(encoder, it))
        }

    fun defaultAudioProfile(encoder: String): String {
        val profiles = info.audio.getSupportedProfiles(encoder)
        return when {
            MediaCodecInfo.CodecProfileLevel.AACObjectLC in profiles -> MediaCodecInfo.CodecProfileLevel.AACObjectLC
            profiles.isNotEmpty() -> profiles.first()
            else -> 0
        }.toString()
    }

    // endregion

    private fun fpsCandidates(): List<Int> =
        context.resources.getStringArray(R.array.FpsEntries).mapNotNull { it.toIntOrNull() }

    companion object {
        const val VIDEO_BITRATE_MIN_KBPS = 200
        const val VIDEO_BITRATE_MAX_KBPS = 10_000

        val STANDARD_RESOLUTIONS = listOf(
            Dimensions(4032, 3024),
            Dimensions(3840, 2160),
            Dimensions(2560, 1440),
            Dimensions(1920, 1440),
            Dimensions(1920, 1080),
            Dimensions(1664, 936),
            Dimensions(1280, 720),
            Dimensions(1024, 768),
            Dimensions(960, 540),
            Dimensions(854, 480),
            Dimensions(640, 360),
            Dimensions(426, 240)
        )
    }
}
