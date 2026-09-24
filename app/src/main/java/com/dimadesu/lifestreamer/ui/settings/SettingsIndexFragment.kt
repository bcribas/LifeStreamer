package com.dimadesu.lifestreamer.ui.settings

import android.media.MediaFormat
import android.net.Uri
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.preference.Preference
import com.dimadesu.lifestreamer.R
import com.dimadesu.lifestreamer.data.storage.DataStoreRepository.RecordingMode
import com.dimadesu.lifestreamer.models.EndpointFactory
import com.dimadesu.lifestreamer.models.EndpointType
import com.dimadesu.lifestreamer.remote.RemoteControlManager
import com.dimadesu.lifestreamer.utils.dataStore
import io.github.thibaultbee.streampack.core.streamers.single.AudioConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/**
 * The first settings screen: one entry per section, each summarizing what it holds, so most
 * questions ("where does it stream? at what size?") are answered without opening anything.
 */
class SettingsIndexFragment : BaseSettingsFragment() {
    override val preferencesRes = R.xml.settings_index
    override val titleRes = R.string.settings

    override fun onPreferencesInflated() {
        pref<Preference>(R.string.settings_section_composition_key).summary =
            getString(R.string.settings_summary_composition)
    }

    override fun onViewCreated(view: android.view.View, savedInstanceState: android.os.Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val repository = storageRepository

        summarize(
            R.string.settings_section_destination_key,
            combine(requireContext().dataStore.data, repository.bitrateRegulatorConfigFlow) { p, r -> p to r }
        ) { (prefs, regulator) ->
            val type = EndpointType.fromId(prefs.string(R.string.endpoint_type_key)?.toIntOrNull() ?: EndpointType.SRT.id)
            val endpoint = EndpointFactory(type).build()
            val where = when {
                endpoint.hasSrtlaCapabilities ->
                    "${prefs.string(R.string.srtla_receiver_host_key) ?: getString(R.string.default_srtla_receiver_host)}:" +
                            (prefs.string(R.string.srtla_receiver_port_key) ?: getString(R.string.default_srtla_receiver_port))
                endpoint.hasSrtCapabilities ->
                    "${prefs.string(R.string.srt_server_ip_key) ?: getString(R.string.default_srt_server_url)}:" +
                            (prefs.string(R.string.srt_server_port_key) ?: getString(R.string.default_srt_server_port))
                // The host alone: the rest of the URL carries the stream key
                endpoint.hasRtmpCapabilities ->
                    Uri.parse(prefs.string(R.string.rtmp_server_url_key) ?: getString(R.string.default_rtmp_url)).host
                endpoint.hasFileCapabilities ->
                    prefs.string(R.string.file_name_key) ?: getString(R.string.default_file_name)
                else -> null
            }
            val adaptive = regulator?.let {
                getString(R.string.settings_summary_adaptive, it.videoBitrateRange.lower / 1000, it.videoBitrateRange.upper / 1000)
            }
            listOfNotNull(getString(type.labelResId), where, adaptive).joinToString(" · ")
        }

        summarize(R.string.settings_section_video_key, repository.videoConfigFlow) { video ->
            video ?: return@summarize getString(R.string.settings_summary_off)
            val codec = when (video.mimeType) {
                MediaFormat.MIMETYPE_VIDEO_AVC -> "H.264"
                MediaFormat.MIMETYPE_VIDEO_HEVC -> "HEVC"
                MediaFormat.MIMETYPE_VIDEO_AV1 -> "AV1"
                // video/x-vnd.on2.vp8 -> VP8
                else -> video.mimeType.substringAfter('/').substringAfterLast('.').uppercase()
            }
            "${video.resolution} · ${video.fps} fps · $codec · ${video.startBitrate / 1000} kbps"
        }

        summarize(R.string.settings_section_audio_key, repository.audioConfigFlow) { audio ->
            audio ?: return@summarize getString(R.string.settings_summary_off)
            val codec = if (audio.mimeType == MediaFormat.MIMETYPE_AUDIO_OPUS) "Opus" else "AAC"
            val channels = getString(
                if (AudioConfig.getNumberOfChannels(audio.channelConfig) <= 1) R.string.audio_mono else R.string.audio_stereo
            )
            "$codec · ${audio.startBitrate / 1000} kbps $channels · ${"%.1f".format(audio.sampleRate / 1000f)} kHz"
        }

        summarize(
            R.string.settings_section_recording_key,
            combine(repository.recordingConfigFlow, repository.endpointTypeFlow) { r, t -> r to t }
        ) { (recording, type) ->
            if (EndpointFactory(type).build().hasFileCapabilities) {
                return@summarize getString(R.string.settings_summary_recording_file_endpoint)
            }
            listOfNotNull(
                getString(if (recording.enabled) R.string.settings_summary_on else R.string.settings_summary_off),
                if (recording.mode == RecordingMode.LIVE_COPY) getString(R.string.settings_summary_recording_copy)
                else getString(R.string.settings_summary_recording_separate, recording.videoBitrateBps / 1000),
                getString(R.string.settings_summary_segment, (recording.segmentDurationMs / 60_000).toInt()),
                if (recording.folderUri == null) getString(R.string.settings_summary_recording_no_folder) else null
            ).joinToString(" · ")
        }

        summarize(
            R.string.settings_section_remote_control_key,
            combine(repository.remoteControlConfigFlow, RemoteControlManager.stateFlow) { c, _ -> c }
        ) { config ->
            when {
                config == null -> getString(R.string.settings_summary_off)
                else -> RemoteControlManager.url(requireContext())
                    ?: getString(R.string.settings_summary_remote_not_running)
            }
        }

        summarize(
            R.string.settings_section_power_key,
            combine(
                repository.mountedModeFlow,
                repository.thermalBackoffEnabledFlow,
                repository.sustainedPerformanceFlow,
                repository.dimWhileLiveFlow
            ) { mounted, backoff, sustained, dim ->
                listOfNotNull(
                    R.string.mounted_mode_title.takeIf { mounted },
                    R.string.thermal_backoff_title.takeIf { backoff },
                    R.string.sustained_performance_title.takeIf { sustained },
                    R.string.dim_while_live_title.takeIf { dim }
                )
            }
        ) { on ->
            if (on.isEmpty()) getString(R.string.settings_summary_power_none)
            else on.joinToString(" · ") { getString(it) }
        }

        summarize(
            R.string.settings_section_rtmp_source_key,
            combine(repository.rtmpSourceCountFlow, repository.rtmpVideoSourceUrlFlow) { n, url -> n to url }
        ) { (count, url) ->
            getString(R.string.settings_summary_rtmp_sources, count, Uri.parse(url).host ?: url)
        }
    }

    private fun Preferences.string(keyRes: Int): String? =
        this[stringPreferencesKey(getString(keyRes))]?.takeIf { it.isNotBlank() }

    /** Keeps the entry's summary up to date with [flow] while the index shows. */
    private fun <T> summarize(keyRes: Int, flow: Flow<T>, text: (T) -> CharSequence?) {
        val preference = pref<Preference>(keyRes)
        collectWhileStarted(flow) { preference.summary = text(it) }
    }
}
