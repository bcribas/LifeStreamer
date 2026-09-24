package com.dimadesu.lifestreamer.settings

import android.content.Context
import android.media.MediaFormat
import android.util.Log
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.dimadesu.lifestreamer.R
import com.dimadesu.lifestreamer.models.EndpointType
import com.dimadesu.lifestreamer.remote.RemoteDto
import com.dimadesu.lifestreamer.settings.SettingsRules.Dimensions
import com.dimadesu.lifestreamer.settings.SettingsSchema.Applies
import com.dimadesu.lifestreamer.settings.SettingsSchema.Field
import com.dimadesu.lifestreamer.settings.SettingsSchema.Format
import com.dimadesu.lifestreamer.settings.SettingsSchema.Options
import com.dimadesu.lifestreamer.settings.SettingsSchema.Type
import com.dimadesu.lifestreamer.utils.dataStore
import io.github.thibaultbee.streampack.core.streamers.single.AudioConfig
import io.github.thibaultbee.streampack.core.streamers.single.VideoConfig
import io.github.thibaultbee.streampack.ext.srt.configuration.mediadescriptor.SrtMtu
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Reads and changes the settings in [SettingsSchema] for the remote page, with the same rules and
 * choices as the settings screen.
 *
 * Every change is checked before anything is written: an unknown key, a wrong type, a value out
 * of range or not among the choices is refused with a reason, and the rest is written together.
 * A stored value the rest of the app could not read never gets in.
 */
class SettingsEditor(private val context: Context, private val host: Host = Idle) {

    interface Host {
        /** A live is open (streaming, connecting or reconnecting). */
        fun isLiveOpen(): Boolean

        /** The sources stream, for the live or a recording. */
        fun isPipelineBusy(): Boolean

        fun isRecording(): Boolean

        /** The video and audio configuration the streamer runs with now. */
        fun appliedConfigs(): Pair<VideoConfig?, AudioConfig?>

        /** Where recordings go, for display; null when no folder is picked. */
        suspend fun recordingFolderLabel(): String?
    }

    /** For the settings screen, which only uses [reconcileChoices]: nothing streams or runs. */
    object Idle : Host {
        override fun isLiveOpen() = false
        override fun isPipelineBusy() = false
        override fun isRecording() = false
        override fun appliedConfigs(): Pair<VideoConfig?, AudioConfig?> = null to null
        override suspend fun recordingFolderLabel(): String? = null
    }

    data class Result(
        val applied: List<String>,
        val pending: List<String>,
        val rejected: Map<String, String>,
        val warnings: List<String>,
    )

    private val dataStore = context.dataStore
    private val mutex = Mutex()
    private val catalogs = mutableMapOf<EndpointType, CapabilityCatalog>()

    private fun key(field: Field) = context.getString(field.keyRes)
    private fun key(res: Int) = context.getString(res)

    private fun catalogFor(type: EndpointType): CapabilityCatalog =
        synchronized(catalogs) { catalogs.getOrPut(type) { CapabilityCatalog(context, type) } }

    // region reading

    private fun defaultOf(field: Field): Any? {
        field.default?.let { return it }
        val text = field.defaultRes?.let { context.getString(it) } ?: return null
        return when (field.type) {
            Type.INT -> text.toIntOrNull()
            Type.BOOL -> text.toBooleanStrictOrNull()
            Type.STRING -> text
        }
    }

    private fun read(prefs: Preferences, field: Field): Any? = runCatching {
        val name = key(field)
        when (field.type) {
            Type.INT -> prefs[intPreferencesKey(name)]
            Type.BOOL -> prefs[booleanPreferencesKey(name)]
            Type.STRING -> prefs[stringPreferencesKey(name)]
        }
    }.getOrNull() ?: defaultOf(field)

    private fun values(prefs: Preferences): MutableMap<String, Any?> =
        SettingsSchema.fields.associateTo(mutableMapOf()) { key(it) to read(prefs, it) }

    private fun endpointOf(values: Map<String, Any?>): EndpointType =
        EndpointType.fromId((values[key(R.string.endpoint_type_key)] as? String)?.toIntOrNull() ?: EndpointType.SRT.id)

    private fun videoEncoderOf(values: Map<String, Any?>, catalog: CapabilityCatalog): String =
        (values[key(R.string.video_encoder_key)] as? String) ?: catalog.defaultVideoEncoder() ?: MediaFormat.MIMETYPE_VIDEO_AVC

    private fun audioEncoderOf(values: Map<String, Any?>, catalog: CapabilityCatalog): String =
        (values[key(R.string.audio_encoder_key)] as? String) ?: catalog.defaultAudioEncoder() ?: MediaFormat.MIMETYPE_AUDIO_AAC

    private fun fromArrays(valuesRes: Int, labelsRes: Int): List<CapabilityCatalog.Option> {
        val values = context.resources.getStringArray(valuesRes)
        val labels = context.resources.getStringArray(labelsRes)
        return values.mapIndexed { i, v -> CapabilityCatalog.Option(v, labels.getOrElse(i) { v }) }
    }

    /** The choices [field] offers given the other [values] (e.g. resolutions for the encoder). */
    private fun options(field: Field, values: Map<String, Any?>): List<CapabilityCatalog.Option>? {
        val kind = field.options ?: return null
        val endpoint = endpointOf(values)
        val catalog by lazy { catalogFor(endpoint) }
        val videoEncoder by lazy { videoEncoderOf(values, catalog) }
        val audioEncoder by lazy { audioEncoderOf(values, catalog) }
        val hardwareOnly by lazy { values[key(R.string.video_resolution_hardware_filter_key)] as? Boolean ?: true }
        fun ints(list: List<Int>) = list.map { CapabilityCatalog.Option("$it", "$it") }
        fun sizes(list: List<Dimensions>) = list.map { CapabilityCatalog.Option("$it", "$it") }
        return runCatching {
            when (kind) {
                Options.VIDEO_ENCODERS -> catalog.videoEncoders()
                Options.VIDEO_RESOLUTIONS -> sizes(catalog.videoResolutions(videoEncoder, hardwareOnly))
                Options.CAMERA_FPS -> ints(catalog.cameraFps(videoEncoder))
                Options.ENCODER_FPS -> ints(catalog.encoderFps(videoEncoder))
                Options.VIDEO_PROFILES -> catalog.videoProfiles(videoEncoder)
                Options.VIDEO_LEVELS -> {
                    val profile = (values[key(R.string.video_profile_key)] as? String)?.toIntOrNull()
                        ?: catalog.defaultVideoProfile(videoEncoder).toInt()
                    catalog.videoLevels(videoEncoder, profile)
                }
                Options.AUDIO_ENCODERS -> catalog.audioEncoders()
                Options.AUDIO_CHANNELS -> catalog.audioChannelConfigs(audioEncoder)
                Options.AUDIO_BITRATES -> catalog.audioBitrates(audioEncoder)
                Options.AUDIO_SAMPLE_RATES -> catalog.audioSampleRates(
                    audioEncoder,
                    (values[key(R.string.audio_bitrate_key)] as? String)?.toIntOrNull(),
                    (values[key(R.string.audio_channel_config_key)] as? String)?.toIntOrNull()
                )
                Options.AUDIO_PROFILES -> catalog.audioProfiles(audioEncoder)
                Options.AUDIO_SOURCES -> fromArrays(R.array.AudioSourceTypeEntryValues, R.array.AudioSourceTypeEntries)
                Options.ORIENTATIONS -> fromArrays(R.array.stream_orientation_values, R.array.stream_orientation_entries)
                Options.ENDPOINT_TYPES -> EndpointType.entries.map {
                    CapabilityCatalog.Option("${it.id}", context.getString(it.labelResId))
                }
                Options.REGULATOR_MODES -> fromArrays(R.array.moblin_regulator_mode_values, R.array.moblin_regulator_mode_entries)
                Options.RECORDING_MODES -> fromArrays(R.array.RecordingModeValues, R.array.RecordingModeEntries)
                Options.RECORDING_RESOLUTIONS -> {
                    val live = Dimensions.parse(values[key(R.string.video_resolution_key)] as? String)
                    listOf(CapabilityCatalog.Option(
                        context.getString(R.string.recording_resolution_same_as_live),
                        context.getString(R.string.recording_resolution_same_as_live_entry)
                    )) + sizes(catalog.recordingResolutions(videoEncoder, hardwareOnly, live))
                }
                Options.SEGMENT_MINUTES -> fromArrays(R.array.RecordingSegmentValues, R.array.RecordingSegmentEntries)
                Options.PREVIEW_RESOLUTIONS -> fromArrays(R.array.preview_resolution_values, R.array.preview_resolution_entries)
                Options.PREVIEW_FPS -> fromArrays(R.array.preview_fps_values, R.array.preview_fps_entries)
            }
        }.onFailure { Log.w(TAG, "No choices for ${key(field)}: ${it.message}") }.getOrNull()
    }

    private fun isOn(values: Map<String, Any?>, res: Int, default: Boolean) = values[key(res)] as? Boolean ?: default

    /** Whether [field] applies at all with the other [values], as the settings screen shows it. */
    private fun visible(field: Field, values: Map<String, Any?>): Boolean {
        val endpoint = endpointOf(values)
        if (field.endpoints != null && endpoint !in field.endpoints) return false
        return when (field.keyRes) {
            R.string.video_fps_key -> !isOn(values, R.string.match_fps_key, true)
            R.string.srt_server_video_target_bitrate_key,
            R.string.srt_server_video_min_bitrate_key,
            R.string.srt_server_moblin_regulator_mode_key ->
                isOn(values, R.string.srt_server_enable_bitrate_regulation_key, true)
            R.string.rtmp_server_video_target_bitrate_key,
            R.string.rtmp_server_video_min_bitrate_key ->
                isOn(values, R.string.rtmp_server_enable_bitrate_regulation_key, false)
            R.string.audio_profile_key ->
                (values[key(R.string.audio_encoder_key)] as? String ?: MediaFormat.MIMETYPE_AUDIO_AAC) ==
                        MediaFormat.MIMETYPE_AUDIO_AAC
            R.string.recording_resolution_key, R.string.recording_video_bitrate_key ->
                values[key(R.string.recording_mode_key)] != context.getString(R.string.recording_mode_live_copy)
            R.string.moblink_port_key, R.string.moblink_password_key ->
                isOn(values, R.string.moblink_enabled_key, false)
            else -> true
        }
    }

    /** "…ab", or the host and path of a URL with its last part hidden. */
    private fun hintFor(value: String): String {
        if (value.isEmpty()) return ""
        val scheme = value.indexOf("://")
        val lastSlash = value.lastIndexOf('/')
        return if (scheme >= 0 && lastSlash > scheme + 3) {
            value.substring(0, lastSlash + 1) + "…" + value.takeLast(2)
        } else {
            "…" + value.takeLast(2)
        }
    }

    // endregion

    suspend fun snapshot(): RemoteDto.SettingsDto {
        val prefs = dataStore.data.first()
        val values = values(prefs)
        val applied = runCatching { host.appliedConfigs() }.getOrDefault(null to null)
        val busy = host.isPipelineBusy()
        val recording = host.isRecording()
        val endpoint = endpointOf(values)
        val groups = SettingsSchema.Group.entries.map { group ->
            val fields = SettingsSchema.fields
                .filter { it.group == group && visible(it, values) }
                .map { field -> fieldDto(field, values, applied) }
            val note = when {
                group == SettingsSchema.Group.RECORDING && endpoint.name.endsWith("_FILE") ->
                    "The endpoint is a file: there is no live to record alongside."
                group == SettingsSchema.Group.RECORDING && recording ->
                    "A recording is running: changes here apply to the next one."
                group == SettingsSchema.Group.RECORDING ->
                    "Recording is switched on and off from the REC bar. The folder is picked on the phone."
                else -> null
            }
            RemoteDto.SettingsGroupDto(group.id, group.title, fields, note)
        }
        return RemoteDto.SettingsDto(host.isLiveOpen(), busy, groups, host.recordingFolderLabel())
    }

    private fun fieldDto(
        field: Field,
        values: Map<String, Any?>,
        applied: Pair<VideoConfig?, AudioConfig?>,
    ): RemoteDto.SettingFieldDto {
        val name = key(field)
        val options = options(field, values)
        // Never set (profile, level): show what the encoder will use
        val value = values[name] ?: defaultChoice(field, values)
        var min = field.min
        var max = field.max
        if (field.keyRes == R.string.video_bitrate_key) {
            runCatching {
                val catalog = catalogFor(endpointOf(values))
                val range = catalog.videoBitrateRangeKbps(videoEncoderOf(values, catalog))
                min = range.first
                max = range.last
            }
        }
        // Saved but not what the streamer runs with: changed during a live, or with the app's
        // screen away (it applies changes only while it is up); the next live applies it either way.
        // (A running recording is said once, in the group's note, not on every field.)
        val pending = field.applies == Applies.WHEN_IDLE && differsFromApplied(field, values, applied)
        val secretValue = if (field.secret) value as? String ?: "" else null
        return RemoteDto.SettingFieldDto(
            key = name,
            label = field.label,
            type = field.type.name.lowercase(),
            value = if (field.secret) null else value,
            secret = field.secret,
            isSet = secretValue?.isNotEmpty(),
            hint = secretValue?.let { hintFor(it) },
            options = options?.map { RemoteDto.SettingOptionDto(it.value, it.label) },
            min = min,
            max = max,
            unit = field.unit,
            format = field.format.name.lowercase(),
            applies = field.applies.name.lowercase(),
            help = field.help,
            pending = pending,
        )
    }

    // region changing

    private class Refused(val reason: String) : Exception(reason)

    /** [raw] turned into what the store holds for [field], or [Refused] with the reason. */
    private fun normalize(field: Field, raw: Any?, values: Map<String, Any?>): Any {
        when (field.type) {
            Type.BOOL -> return when (raw) {
                is Boolean -> raw
                "true" -> true
                "false" -> false
                else -> throw Refused("Expected true or false")
            }

            Type.INT -> {
                val number = when (raw) {
                    is Number -> raw.toDouble().takeIf { it == Math.floor(it) }?.toInt()
                    is String -> raw.trim().toIntOrNull()
                    else -> null
                } ?: throw Refused("Expected a whole number")
                var min = field.min ?: Int.MIN_VALUE
                var max = field.max ?: Int.MAX_VALUE
                if (field.keyRes == R.string.video_bitrate_key) {
                    val catalog = catalogFor(endpointOf(values))
                    val range = catalog.videoBitrateRangeKbps(videoEncoderOf(values, catalog))
                    min = range.first
                    max = range.last
                }
                if (number < min || number > max) throw Refused("Must be between $min and $max")
                // Every INT setting here is a bitrate in kb/s
                return SettingsRules.snapBitrateKbps(number).coerceIn(min, max)
            }

            Type.STRING -> {
                val text = when (raw) {
                    null -> if (field.secret) return "" else throw Refused("A value is needed")
                    is String -> raw
                    is Number -> raw.toDouble().let { if (it == Math.floor(it)) it.toLong().toString() else it.toString() }
                    is Boolean -> raw.toString()
                    else -> throw Refused("Expected text")
                }
                if (text.length > 512) throw Refused("Too long")
                options(field, values)?.let { choices ->
                    if (choices.none { it.value == text }) throw Refused("Not one of the choices")
                    return text
                }
                return when (field.format) {
                    Format.HOST -> SettingsRules.host(text) ?: throw Refused("Not a host name or address")
                    Format.PORT -> SettingsRules.port(text)?.toString() ?: throw Refused("Port must be 1-65535")
                    Format.LATENCY_MS -> SettingsRules.latencyMs(text)?.toString() ?: throw Refused("Latency must be 20-60000 ms")
                    Format.MTU -> text.trim().toIntOrNull()
                        ?.takeIf { it in SrtMtu.UI_MIN_MTU..SrtMtu.DEFAULT_MTU }?.toString()
                        ?: throw Refused("MTU must be ${SrtMtu.UI_MIN_MTU}-${SrtMtu.DEFAULT_MTU}")
                    Format.TEXT -> {
                        if (field.keyRes == R.string.srt_server_passphrase_key || field.keyRes == R.string.srtla_passphrase_key) {
                            // SRT only takes passphrases of 10 to 79 characters (or none)
                            if (text.isNotEmpty() && text.length !in 10..79) throw Refused("An SRT passphrase has 10 to 79 characters")
                        }
                        text
                    }
                }
            }
        }
    }

    private fun put(prefs: MutablePreferences, field: Field, value: Any) {
        val name = key(field)
        when (field.type) {
            Type.INT -> prefs[intPreferencesKey(name)] = value as Int
            Type.BOOL -> prefs[booleanPreferencesKey(name)] = value as Boolean
            Type.STRING -> prefs[stringPreferencesKey(name)] = value as String
        }
    }

    suspend fun apply(changes: Map<String, Any?>): Result = mutex.withLock {
        val prefs = dataStore.data.first()
        val values = values(prefs)
        val accepted = linkedMapOf<Field, Any>()
        val rejected = linkedMapOf<String, String>()
        val warnings = mutableListOf<String>()
        val live = host.isLiveOpen()
        val byKey = SettingsSchema.fields.associateBy { key(it) }
        changes.keys.filter { it !in byKey }.forEach { rejected[it] = "Not a setting the page can change" }

        // Schema order puts the choices' parents (encoder, filter, bitrate, channels) before them
        for (field in SettingsSchema.fields) {
            val name = key(field)
            if (!changes.containsKey(name)) continue
            val raw = changes[name]
            if (field.applies == Applies.NEXT_LIVE && live) {
                rejected[name] = "Stop the live to change this"
                continue
            }
            // For a secret, an empty field means "leave it as it is"
            if (field.secret && raw is String && raw.isEmpty()) continue
            try {
                val value = normalize(field, raw, values)
                values[name] = value
                accepted[field] = value
            } catch (refused: Refused) {
                rejected[name] = refused.reason
            } catch (t: Throwable) {
                rejected[name] = t.message ?: "Could not be checked"
            }
        }
        if (accepted.isEmpty()) return@withLock Result(emptyList(), emptyList(), rejected, warnings)

        // The regulator's minimum stays under its target, the one not set following the other
        for ((targetRes, minRes) in listOf(
            R.string.srt_server_video_target_bitrate_key to R.string.srt_server_video_min_bitrate_key,
            R.string.rtmp_server_video_target_bitrate_key to R.string.rtmp_server_video_min_bitrate_key,
        )) {
            val target = values[key(targetRes)] as? Int ?: continue
            val min = values[key(minRes)] as? Int ?: continue
            val targetSet = accepted.keys.any { it.keyRes == targetRes }
            val minSet = accepted.keys.any { it.keyRes == minRes }
            if (!targetSet && !minSet) continue
            val (newMin, newTarget) = SettingsRules.coupleRegulator(min, target, targetWasSet = targetSet)
            if (newMin != min) {
                SettingsSchema.fields.first { it.keyRes == minRes }.let { values[key(it)] = newMin; accepted[it] = newMin }
                warnings += "Minimum bitrate lowered to $newMin kbps to stay under the target"
            }
            if (newTarget != target) {
                SettingsSchema.fields.first { it.keyRes == targetRes }.let { values[key(it)] = newTarget; accepted[it] = newTarget }
                warnings += "Target bitrate raised to $newTarget kbps to stay above the minimum"
            }
        }

        // Choices that a changed parent no longer offers move to the closest valid one
        if (accepted.keys.any { it.keyRes in PARENTS }) {
            warnings += fixChoices(values, accepted)
        }

        lowBitrateWarning(values)?.let { warnings += it }

        dataStore.edit { store -> accepted.forEach { (field, value) -> put(store, field, value) } }

        val recording = host.isRecording()
        // The app's screen applies video and audio at once when it is up and nothing streams; give
        // it a moment, then report what the streamer really runs with.
        if (accepted.keys.any { it.applies == Applies.WHEN_IDLE }) kotlinx.coroutines.delay(800)
        val running = runCatching { host.appliedConfigs() }.getOrDefault(null to null)
        val applied = mutableListOf<String>()
        val pending = mutableListOf<String>()
        accepted.keys.forEach { field ->
            val waits = when (field.applies) {
                Applies.WHEN_IDLE -> differsFromApplied(field, values, running)
                Applies.NEXT_RECORDING -> recording
                else -> false
            }
            (if (waits) pending else applied) += key(field)
        }
        Log.i(TAG, "Remote settings: applied=$applied pending=$pending rejected=${rejected.keys}")
        Result(applied, pending, rejected, warnings)
    }

    /**
     * Moves each choice the other [values] no longer offer (a resolution the new encoder lacks) to
     * the closest valid one, in [values] and [accepted], skipping the ones in [accepted]. A choice
     * with no value at all (a profile never picked: the encoder chooses) is left alone.
     *
     * @return what moved, for the user
     */
    private fun fixChoices(values: MutableMap<String, Any?>, accepted: MutableMap<Field, Any>): List<String> {
        val moved = mutableListOf<String>()
        for (field in SettingsSchema.fields) {
            if (field.options == null || accepted.containsKey(field) || !visible(field, values)) continue
            val current = values[key(field)]?.toString() ?: continue
            val choices = options(field, values) ?: continue
            if (choices.isEmpty() || choices.any { it.value == current }) continue
            val replacementValue = when (field.keyRes) {
                // Capped sample rates: the highest allowed
                R.string.audio_sample_rate_key -> choices.maxByOrNull { it.value.toIntOrNull() ?: 0 }!!.value
                else -> defaultChoice(field, values)?.toString()?.takeIf { d -> choices.any { it.value == d } }
                    ?: choices.first().value
            }
            val label = choices.first { it.value == replacementValue }.label
            values[key(field)] = replacementValue
            accepted[field] = replacementValue
            moved += "${field.group.title} ${field.label.lowercase()}: now $label ($current is not offered)"
        }
        return moved
    }

    /**
     * After a change made elsewhere (the settings screen's destination type), moves every stored
     * choice that is no longer offered to the closest valid one, as the remote page's changes do.
     *
     * @return what moved, for the user
     */
    suspend fun reconcileChoices(): List<String> = mutex.withLock {
        val values = values(dataStore.data.first())
        val accepted = linkedMapOf<Field, Any>()
        val moved = fixChoices(values, accepted)
        if (accepted.isNotEmpty()) {
            dataStore.edit { store -> accepted.forEach { (field, value) -> put(store, field, value) } }
            Log.i(TAG, "Reconciled choices: ${accepted.keys.map { key(it) }}")
        }
        moved
    }

    /** Whether [field]'s stored value is not what the streamer runs with (so it waits). */
    private fun differsFromApplied(
        field: Field,
        values: Map<String, Any?>,
        applied: Pair<VideoConfig?, AudioConfig?>,
    ): Boolean {
        val stored = values[key(field)] ?: return false
        val (video, audio) = applied
        fun int() = stored.toString().toIntOrNull()
        return when (field.keyRes) {
            R.string.video_encoder_key -> video != null && stored != video.mimeType
            R.string.video_resolution_key -> video != null &&
                    Dimensions.parse(stored as? String) != Dimensions(video.resolution.width, video.resolution.height)
            R.string.camera_fps_key -> video != null && int() != (video.cameraFps ?: video.fps)
            R.string.video_fps_key -> video != null && int() != video.fps
            R.string.video_bitrate_key -> video != null && (stored as? Int)?.times(1000) != video.startBitrate
            R.string.video_profile_key -> video != null && int() != video.profile
            R.string.video_level_key -> video != null && int() != video.level
            R.string.audio_encoder_key -> audio != null && stored != audio.mimeType
            R.string.audio_channel_config_key -> audio != null && int() != audio.channelConfig
            R.string.audio_bitrate_key -> audio != null && int() != audio.startBitrate
            R.string.audio_sample_rate_key -> audio != null && int() != audio.sampleRate
            R.string.audio_profile_key -> audio != null && int() != audio.profile
            else -> false
        }
    }

    /** What a list uses when nothing was chosen: the encoder's best profile and level, else the first. */
    private fun defaultChoice(field: Field, values: Map<String, Any?>): Any? = runCatching {
        val catalog = catalogFor(endpointOf(values))
        val encoder = videoEncoderOf(values, catalog)
        when (field.keyRes) {
            R.string.video_profile_key -> catalog.defaultVideoProfile(encoder)
            R.string.video_level_key -> catalog.defaultVideoLevel(
                encoder,
                (values[key(R.string.video_profile_key)] as? String)?.toIntOrNull()
                    ?: catalog.defaultVideoProfile(encoder).toInt()
            )
            R.string.audio_profile_key -> catalog.defaultAudioProfile(audioEncoderOf(values, catalog))
            else -> null
        }
    }.getOrNull()

    /** The screen's low-bitrate check, on the bitrate that caps the live. */
    private fun lowBitrateWarning(values: Map<String, Any?>): String? {
        val endpoint = endpointOf(values)
        val resolution = Dimensions.parse(values[key(R.string.video_resolution_key)] as? String) ?: return null
        val cameraFps = (values[key(R.string.camera_fps_key)] as? String)?.toIntOrNull() ?: return null
        val encoderFps = if (isOn(values, R.string.match_fps_key, true)) cameraFps
        else (values[key(R.string.video_fps_key)] as? String)?.toIntOrNull() ?: cameraFps
        val fps = maxOf(cameraFps, encoderFps)
        val kbps = when {
            endpoint == EndpointType.RTMP && isOn(values, R.string.rtmp_server_enable_bitrate_regulation_key, false) ->
                values[key(R.string.rtmp_server_video_target_bitrate_key)] as? Int
            endpoint in setOf(EndpointType.SRT, EndpointType.SRTLA) &&
                    isOn(values, R.string.srt_server_enable_bitrate_regulation_key, true) ->
                values[key(R.string.srt_server_video_target_bitrate_key)] as? Int
            else -> values[key(R.string.video_bitrate_key)] as? Int
        } ?: return null
        val catalog = runCatching { catalogFor(endpoint) }.getOrNull() ?: return null
        val offered = runCatching {
            catalog.videoResolutions(
                videoEncoderOf(values, catalog),
                isOn(values, R.string.video_resolution_hardware_filter_key, true)
            )
        }.getOrDefault(emptyList())
        val low = SettingsRules.lowBitrate(kbps * 1000, resolution, fps, offered) ?: return null
        val fix = when {
            low.smaller != null -> "use ${low.smaller} or lower"
            low.smallerAt15Fps != null -> "use ${low.smallerAt15Fps} with the cameras at ${SettingsRules.LOW_FPS} fps"
            else -> "raise the bitrate"
        }
        return "$kbps kbps is low for $resolution at $fps fps (about ${low.neededKbps} kbps needed): $fix"
    }

    // endregion

    private companion object {
        const val TAG = "SettingsEditor"

        /** Settings whose value changes the choices other settings offer. */
        val PARENTS = setOf(
            R.string.endpoint_type_key, R.string.video_encoder_key, R.string.video_resolution_hardware_filter_key,
            R.string.video_resolution_key, R.string.video_profile_key, R.string.audio_encoder_key,
            R.string.audio_bitrate_key, R.string.audio_channel_config_key,
        )
    }
}
