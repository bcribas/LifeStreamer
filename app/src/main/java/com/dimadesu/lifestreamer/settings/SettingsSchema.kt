package com.dimadesu.lifestreamer.settings

import com.dimadesu.lifestreamer.R
import com.dimadesu.lifestreamer.models.EndpointType

/**
 * The settings the remote page may read and change, described once.
 *
 * The type of each is the one the settings screen writes (sliders an Int, lists and text fields a
 * String, switches a Boolean): the stored type is part of the contract, since the repository reads
 * each key with one type. Keys are string resources, like everywhere else in the app.
 *
 * Deliberately absent: the remote control's own switch, port and PIN (changing them from the page
 * would cut the page off), the recording folder (Android lets only its own picker grant one), the
 * file name and the RTMP sources.
 */
object SettingsSchema {

    enum class Type { INT, STRING, BOOL }

    enum class Group(val id: String, val title: String) {
        VIDEO("video", "Video"),
        AUDIO("audio", "Audio"),
        DESTINATION("destination", "Destination and network"),
        RECORDING("recording", "Recording"),
        POWER("power", "Power and screen"),
    }

    /** When a change takes effect. */
    enum class Applies {
        /** At once. */
        NOW,

        /** At once when nothing streams; otherwise saved and applied when the live and recording stop. */
        WHEN_IDLE,

        /** Read when a live starts; refused while one runs, so it cannot change under it. */
        NEXT_LIVE,

        /** Read when a recording starts. */
        NEXT_RECORDING,
    }

    /** Where a list's choices come from: fixed, or what the device and endpoint can do. */
    enum class Options {
        VIDEO_ENCODERS, VIDEO_RESOLUTIONS, CAMERA_FPS, ENCODER_FPS, VIDEO_PROFILES, VIDEO_LEVELS,
        AUDIO_ENCODERS, AUDIO_CHANNELS, AUDIO_BITRATES, AUDIO_SAMPLE_RATES, AUDIO_PROFILES,
        AUDIO_SOURCES, ORIENTATIONS, ENDPOINT_TYPES, REGULATOR_MODES,
        RECORDING_MODES, RECORDING_RESOLUTIONS, SEGMENT_MINUTES, PREVIEW_RESOLUTIONS, PREVIEW_FPS,
    }

    /** What a text field must hold. */
    enum class Format { TEXT, HOST, PORT, LATENCY_MS, MTU }

    data class Field(
        val keyRes: Int,
        val type: Type,
        val group: Group,
        val label: String,
        val applies: Applies,
        /** Default when never set: the settings screen's. A resource, or a literal. */
        val defaultRes: Int? = null,
        val default: Any? = null,
        val options: Options? = null,
        val format: Format = Format.TEXT,
        val min: Int? = null,
        val max: Int? = null,
        val unit: String? = null,
        /** Never sent to the page: only whether it is set, and a hint. */
        val secret: Boolean = false,
        /** Shown only for these endpoint types; null for all. */
        val endpoints: Set<EndpointType>? = null,
        val help: String? = null,
    )

    private val SRT_LIKE = setOf(EndpointType.SRT, EndpointType.SRTLA)

    val fields: List<Field> = listOf(
        // VIDEO
        Field(R.string.video_encoder_key, Type.STRING, Group.VIDEO, "Encoder", Applies.WHEN_IDLE,
            defaultRes = R.string.default_video_encoder, options = Options.VIDEO_ENCODERS),
        Field(R.string.video_resolution_hardware_filter_key, Type.BOOL, Group.VIDEO,
            "Only resolutions the cameras support", Applies.WHEN_IDLE, default = true),
        Field(R.string.video_resolution_key, Type.STRING, Group.VIDEO, "Resolution", Applies.WHEN_IDLE,
            defaultRes = R.string.default_video_resolution, options = Options.VIDEO_RESOLUTIONS),
        Field(R.string.camera_fps_key, Type.STRING, Group.VIDEO, "Camera frame rate", Applies.WHEN_IDLE,
            defaultRes = R.string.default_fps, options = Options.CAMERA_FPS, unit = "fps"),
        Field(R.string.match_fps_key, Type.BOOL, Group.VIDEO, "Encoder frame rate = camera frame rate",
            Applies.WHEN_IDLE, default = true),
        Field(R.string.video_fps_key, Type.STRING, Group.VIDEO, "Encoder frame rate", Applies.WHEN_IDLE,
            defaultRes = R.string.default_fps, options = Options.ENCODER_FPS, unit = "fps"),
        Field(R.string.video_bitrate_key, Type.INT, Group.VIDEO, "Bitrate", Applies.WHEN_IDLE,
            defaultRes = R.string.default_video_bitrate, min = 200, max = 10_000, unit = "kbps",
            help = "Where the encoder starts; with adaptive bitrate on, the target caps it"),
        Field(R.string.video_profile_key, Type.STRING, Group.VIDEO, "Profile", Applies.WHEN_IDLE,
            options = Options.VIDEO_PROFILES),
        Field(R.string.video_level_key, Type.STRING, Group.VIDEO, "Level", Applies.WHEN_IDLE,
            options = Options.VIDEO_LEVELS),
        Field(R.string.stream_orientation_key, Type.STRING, Group.VIDEO, "Stream orientation", Applies.NOW,
            defaultRes = R.string.stream_orientation_value_auto, options = Options.ORIENTATIONS),

        // AUDIO
        Field(R.string.audio_encoder_key, Type.STRING, Group.AUDIO, "Encoder", Applies.WHEN_IDLE,
            defaultRes = R.string.default_audio_encoder, options = Options.AUDIO_ENCODERS),
        Field(R.string.audio_channel_config_key, Type.STRING, Group.AUDIO, "Channels", Applies.WHEN_IDLE,
            default = "12", options = Options.AUDIO_CHANNELS),
        Field(R.string.audio_bitrate_key, Type.STRING, Group.AUDIO, "Bitrate", Applies.WHEN_IDLE,
            default = "128000", options = Options.AUDIO_BITRATES),
        Field(R.string.audio_sample_rate_key, Type.STRING, Group.AUDIO, "Sample rate", Applies.WHEN_IDLE,
            default = "44100", options = Options.AUDIO_SAMPLE_RATES,
            help = "Low bitrates cap it: higher rates make the audio choppy or silent"),
        Field(R.string.audio_profile_key, Type.STRING, Group.AUDIO, "Profile", Applies.WHEN_IDLE,
            default = "2", options = Options.AUDIO_PROFILES),
        Field(R.string.audio_source_type_key, Type.STRING, Group.AUDIO, "Audio source", Applies.NOW,
            default = "5", options = Options.AUDIO_SOURCES),

        // DESTINATION
        Field(R.string.endpoint_type_key, Type.STRING, Group.DESTINATION, "Type", Applies.NEXT_LIVE,
            default = "2", options = Options.ENDPOINT_TYPES),
        Field(R.string.srt_server_ip_key, Type.STRING, Group.DESTINATION, "SRT server", Applies.NEXT_LIVE,
            defaultRes = R.string.default_srt_server_url, format = Format.HOST, endpoints = setOf(EndpointType.SRT)),
        Field(R.string.srt_server_port_key, Type.STRING, Group.DESTINATION, "SRT port", Applies.NEXT_LIVE,
            defaultRes = R.string.default_srt_server_port, format = Format.PORT, endpoints = setOf(EndpointType.SRT)),
        Field(R.string.srt_server_stream_id_key, Type.STRING, Group.DESTINATION, "Stream ID", Applies.NEXT_LIVE,
            defaultRes = R.string.default_srt_server_stream_id, endpoints = setOf(EndpointType.SRT)),
        Field(R.string.srt_server_latency_key, Type.STRING, Group.DESTINATION, "Latency", Applies.NEXT_LIVE,
            defaultRes = R.string.default_srt_server_latency, format = Format.LATENCY_MS, unit = "ms",
            endpoints = setOf(EndpointType.SRT)),
        Field(R.string.srt_server_passphrase_key, Type.STRING, Group.DESTINATION, "Passphrase", Applies.NEXT_LIVE,
            defaultRes = R.string.default_srt_server_passphrase, secret = true, endpoints = setOf(EndpointType.SRT)),
        Field(R.string.rtmp_server_url_key, Type.STRING, Group.DESTINATION, "RTMP URL (with the stream key)",
            Applies.NEXT_LIVE, defaultRes = R.string.default_rtmp_url, secret = true,
            endpoints = setOf(EndpointType.RTMP)),
        Field(R.string.srtla_receiver_host_key, Type.STRING, Group.DESTINATION, "SRTLA receiver", Applies.NEXT_LIVE,
            defaultRes = R.string.default_srtla_receiver_host, format = Format.HOST, endpoints = setOf(EndpointType.SRTLA)),
        Field(R.string.srtla_receiver_port_key, Type.STRING, Group.DESTINATION, "SRTLA receiver port", Applies.NEXT_LIVE,
            defaultRes = R.string.default_srtla_receiver_port, format = Format.PORT, endpoints = setOf(EndpointType.SRTLA)),
        Field(R.string.srtla_listen_port_key, Type.STRING, Group.DESTINATION, "SRTLA local port", Applies.NEXT_LIVE,
            defaultRes = R.string.default_srtla_listen_port, format = Format.PORT, endpoints = setOf(EndpointType.SRTLA)),
        Field(R.string.srtla_stream_id_key, Type.STRING, Group.DESTINATION, "Stream ID", Applies.NEXT_LIVE,
            default = "", endpoints = setOf(EndpointType.SRTLA)),
        Field(R.string.srtla_latency_key, Type.STRING, Group.DESTINATION, "Latency", Applies.NEXT_LIVE,
            defaultRes = R.string.default_srtla_latency, format = Format.LATENCY_MS, unit = "ms",
            endpoints = setOf(EndpointType.SRTLA)),
        Field(R.string.srtla_passphrase_key, Type.STRING, Group.DESTINATION, "Passphrase", Applies.NEXT_LIVE,
            default = "", secret = true, endpoints = setOf(EndpointType.SRTLA)),
        Field(R.string.moblink_enabled_key, Type.BOOL, Group.DESTINATION, "Moblink relays", Applies.NOW,
            default = false, endpoints = setOf(EndpointType.SRTLA)),
        Field(R.string.moblink_port_key, Type.STRING, Group.DESTINATION, "Moblink port", Applies.NOW,
            defaultRes = R.string.default_moblink_port, format = Format.PORT, endpoints = setOf(EndpointType.SRTLA)),
        Field(R.string.moblink_password_key, Type.STRING, Group.DESTINATION, "Moblink password", Applies.NOW,
            defaultRes = R.string.default_moblink_password, secret = true, endpoints = setOf(EndpointType.SRTLA)),
        Field(R.string.srt_mtu_key, Type.STRING, Group.DESTINATION, "MTU", Applies.NEXT_LIVE,
            defaultRes = R.string.default_srt_mtu, format = Format.MTU, unit = "bytes", endpoints = SRT_LIKE),
        Field(R.string.srt_resilient_link_key, Type.BOOL, Group.DESTINATION,
            "Reconnect without restarting the encoders (experimental)", Applies.NEXT_LIVE,
            default = false, endpoints = SRT_LIKE),
        Field(R.string.srt_server_enable_bitrate_regulation_key, Type.BOOL, Group.DESTINATION,
            "Adaptive bitrate", Applies.NOW, default = true, endpoints = SRT_LIKE),
        Field(R.string.srt_server_video_target_bitrate_key, Type.INT, Group.DESTINATION, "Target bitrate",
            Applies.NOW, default = 5000, min = 200, max = 10_000, unit = "kbps", endpoints = SRT_LIKE),
        Field(R.string.srt_server_video_min_bitrate_key, Type.INT, Group.DESTINATION, "Minimum bitrate",
            Applies.NOW, default = 300, min = 100, max = 10_000, unit = "kbps", endpoints = SRT_LIKE),
        Field(R.string.srt_server_moblin_regulator_mode_key, Type.STRING, Group.DESTINATION, "Algorithm",
            Applies.NOW, defaultRes = R.string.srt_server_moblin_regulator_mode_value_belabox,
            options = Options.REGULATOR_MODES, endpoints = SRT_LIKE),
        Field(R.string.rtmp_server_enable_bitrate_regulation_key, Type.BOOL, Group.DESTINATION,
            "Adaptive bitrate", Applies.NOW, default = false, endpoints = setOf(EndpointType.RTMP)),
        Field(R.string.rtmp_server_video_target_bitrate_key, Type.INT, Group.DESTINATION, "Target bitrate",
            Applies.NOW, default = 5000, min = 200, max = 10_000, unit = "kbps", endpoints = setOf(EndpointType.RTMP)),
        Field(R.string.rtmp_server_video_min_bitrate_key, Type.INT, Group.DESTINATION, "Minimum bitrate",
            Applies.NOW, default = 300, min = 100, max = 10_000, unit = "kbps", endpoints = setOf(EndpointType.RTMP)),

        // RECORDING
        Field(R.string.recording_mode_key, Type.STRING, Group.RECORDING, "Quality", Applies.NEXT_RECORDING,
            defaultRes = R.string.recording_mode_separate, options = Options.RECORDING_MODES),
        Field(R.string.recording_resolution_key, Type.STRING, Group.RECORDING, "Resolution", Applies.NEXT_RECORDING,
            defaultRes = R.string.recording_resolution_same_as_live, options = Options.RECORDING_RESOLUTIONS),
        Field(R.string.recording_video_bitrate_key, Type.INT, Group.RECORDING, "Bitrate", Applies.NEXT_RECORDING,
            default = 8000, min = 1000, max = 50_000, unit = "kbps"),
        Field(R.string.recording_segment_minutes_key, Type.STRING, Group.RECORDING, "Segment length",
            Applies.NEXT_RECORDING, default = "5", options = Options.SEGMENT_MINUTES),

        // POWER
        Field(R.string.mounted_mode_key, Type.BOOL, Group.POWER, "Mounted in the car", Applies.NOW, default = false),
        Field(R.string.thermal_backoff_key, Type.BOOL, Group.POWER, "Back off when hot", Applies.NOW, default = true),
        Field(R.string.sustained_performance_key, Type.BOOL, Group.POWER, "Sustained performance mode",
            Applies.NOW, default = false),
        Field(R.string.dim_while_live_key, Type.BOOL, Group.POWER, "Dim the screen while live", Applies.NOW, default = false),
        Field(R.string.preview_resolution_key, Type.STRING, Group.POWER, "Preview resolution", Applies.NOW,
            defaultRes = R.string.default_preview_resolution, options = Options.PREVIEW_RESOLUTIONS),
        Field(R.string.preview_fps_key, Type.STRING, Group.POWER, "Preview frame rate", Applies.NOW,
            defaultRes = R.string.default_preview_fps, options = Options.PREVIEW_FPS),
    )
}
