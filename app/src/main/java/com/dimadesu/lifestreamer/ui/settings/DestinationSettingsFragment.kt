package com.dimadesu.lifestreamer.ui.settings

import android.widget.Toast
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.PreferenceCategory
import androidx.preference.SeekBarPreference
import androidx.preference.SwitchPreference
import com.dimadesu.lifestreamer.R
import com.dimadesu.lifestreamer.models.EndpointFactory
import com.dimadesu.lifestreamer.models.EndpointType
import com.dimadesu.lifestreamer.models.FileExtension
import com.dimadesu.lifestreamer.settings.SettingsEditor
import com.dimadesu.lifestreamer.utils.DialogUtils
import io.github.thibaultbee.streampack.core.configuration.BitrateRegulatorConfig
import io.github.thibaultbee.streampack.core.streamers.single.VideoConfig
import io.github.thibaultbee.streampack.ext.srt.configuration.mediadescriptor.SrtMtu
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * Where the live goes and how: the endpoint type and what it decides (the SRT, RTMP, SRTLA and
 * file settings, the transport and the bitrate regulator).
 */
class DestinationSettingsFragment : BaseSettingsFragment() {
    override val preferencesRes = R.xml.settings_destination
    override val titleRes = R.string.settings_section_destination

    private val endpointTypePreference by lazy { pref<ListPreference>(R.string.endpoint_type_key) }
    private val srtEndpointPreference by lazy { pref<PreferenceCategory>(R.string.srt_server_key) }
    private val rtmpEndpointPreference by lazy { pref<PreferenceCategory>(R.string.rtmp_server_key) }
    private val srtlaEndpointPreference by lazy { pref<PreferenceCategory>(R.string.srtla_key) }
    private val fileEndpointPreference by lazy { pref<PreferenceCategory>(R.string.file_endpoint_key) }
    private val srtTransportPreference by lazy { pref<PreferenceCategory>(R.string.srt_transport_key) }
    private val srtMtuPreference by lazy { pref<EditTextPreference>(R.string.srt_mtu_key) }
    private val bitrateRegulationPreference by lazy { pref<PreferenceCategory>(R.string.bitrate_regulation_key) }
    private val serverEnableBitrateRegulationPreference by lazy {
        pref<SwitchPreference>(R.string.srt_server_enable_bitrate_regulation_key)
    }
    private val serverTargetVideoBitratePreference by lazy {
        pref<SeekBarPreference>(R.string.srt_server_video_target_bitrate_key)
    }
    private val serverMinVideoBitratePreference by lazy {
        pref<SeekBarPreference>(R.string.srt_server_video_min_bitrate_key)
    }
    private val regulatorModePreference by lazy { pref<ListPreference>(R.string.srt_server_moblin_regulator_mode_key) }
    private val rtmpServerEnableBitrateRegulationPreference by lazy {
        pref<SwitchPreference>(R.string.rtmp_server_enable_bitrate_regulation_key)
    }
    private val rtmpServerTargetVideoBitratePreference by lazy {
        pref<SeekBarPreference>(R.string.rtmp_server_video_target_bitrate_key)
    }
    private val rtmpServerMinVideoBitratePreference by lazy {
        pref<SeekBarPreference>(R.string.rtmp_server_video_min_bitrate_key)
    }
    private val fileNamePreference by lazy { pref<EditTextPreference>(R.string.file_name_key) }

    override fun onPreferencesInflated() {
        loadEndpoint()
        loadRegulator()
        loadSrtTransportSettings()
    }

    override fun onViewCreated(view: android.view.View, savedInstanceState: android.os.Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // Collected from the stored values rather than from each preference's change listener:
        // those run before the new value is saved.
        collectWhileStarted(
            combine(
                storageRepository.videoConfigFlow,
                storageRepository.bitrateRegulatorConfigFlow
            ) { video, regulator -> video to regulator }
        ) { (video, regulator) -> updateBitrateWarning(video, regulator) }

        // Another destination offers other encoders and sizes: move what the Video and Audio
        // sections hold to what it offers, as the one settings screen used to when it re-read them.
        collectWhileStarted(storageRepository.endpointTypeFlow.drop(1)) {
            val context = requireContext().applicationContext
            // Off the main thread: listing the choices asks the codecs and the cameras
            val moved = withContext(Dispatchers.Default) { SettingsEditor(context).reconcileChoices() }
            if (moved.isNotEmpty()) {
                // A dialog, not a toast: a toast cuts all but two lines, and these say what changed
                DialogUtils.showAlertDialog(
                    requireContext(), getString(R.string.settings_choices_moved_title), moved.joinToString("\n")
                )
            }
        }
    }

    /**
     * Warns, on the regulator's target, when it is too low for the resolution and frame rate:
     * while regulation is on, the target is the ceiling (the Video section warns otherwise).
     */
    private fun updateBitrateWarning(video: VideoConfig?, regulator: BitrateRegulatorConfig?) {
        serverTargetVideoBitratePreference.summary = null
        rtmpServerTargetVideoBitratePreference.summary = null
        if (video == null || regulator == null) return
        val slider = if (EndpointFactory(storedEndpointType()).build().hasRtmpCapabilities) {
            rtmpServerTargetVideoBitratePreference
        } else {
            serverTargetVideoBitratePreference
        }
        slider.summary = lowBitrateWarning(
            regulator.videoBitrateRange.upper, video, offeredResolutions(catalog())
        )
    }

    private fun loadEndpoint() {
        endpointTypePreference.entryValues = EndpointType.entries.map { "${it.id}" }.toTypedArray()
        endpointTypePreference.entries =
            EndpointType.entries.map { getString(it.labelResId) }.toTypedArray()
        // Only set default if user has never set a value (check .value not .entry)
        // .entry can be null during initialization even with saved value
        if (endpointTypePreference.value.isNullOrEmpty()) {
            endpointTypePreference.value = "${EndpointType.SRT.id}"
        }
        endpointTypePreference.value?.toIntOrNull()?.let { setEndpointType(it) }
        endpointTypePreference.setOnPreferenceChangeListener { _, newValue ->
            (newValue as? String)?.toIntOrNull()?.let { setEndpointType(it) }
            true
        }

        pref<EditTextPreference>(R.string.srt_server_ip_key).address()
        pref<EditTextPreference>(R.string.srt_server_port_key).numeric(5)
        pref<EditTextPreference>(R.string.srt_server_latency_key).numeric(5)
        pref<EditTextPreference>(R.string.srtla_receiver_host_key).address()
        pref<EditTextPreference>(R.string.srtla_receiver_port_key).numeric(5)
        pref<EditTextPreference>(R.string.srtla_listen_port_key).numeric(5)
        pref<EditTextPreference>(R.string.srtla_latency_key).numeric(5)
    }

    private fun setEndpointType(id: Int) {
        val endpointType = EndpointType.fromId(id)
        val endpoint = EndpointFactory(endpointType).build()
        srtEndpointPreference.isVisible = endpoint.hasSrtCapabilities
        rtmpEndpointPreference.isVisible = endpoint.hasRtmpCapabilities
        srtlaEndpointPreference.isVisible = endpoint.hasSrtlaCapabilities
        fileEndpointPreference.isVisible = endpoint.hasFileCapabilities
        bitrateRegulationPreference.isVisible =
            endpoint.hasSrtCapabilities || endpoint.hasSrtlaCapabilities || endpoint.hasRtmpCapabilities
        // The MTU applies to plain SRT and to SRTLA alike, so it belongs to neither category.
        srtTransportPreference.isVisible = endpoint.hasSrtCapabilities || endpoint.hasSrtlaCapabilities
        showRegulator(
            srtOn = serverEnableBitrateRegulationPreference.isChecked,
            rtmpOn = rtmpServerEnableBitrateRegulationPreference.isChecked,
            endpointType = endpointType
        )

        // Update file extension
        if (endpoint.hasFileCapabilities) {
            // Remove previous extension (only once)
            fileNamePreference.text = fileNamePreference.text?.substringBeforeLast(".") ?: "output"
            // Add correct extension
            fileNamePreference.text += when {
                endpoint.hasFLVCapabilities -> FileExtension.FLV.extension
                endpoint.hasTSCapabilities -> FileExtension.TS.extension
                endpoint.hasMP4Capabilities -> FileExtension.MP4.extension
                endpointType == EndpointType.WEBM_FILE -> FileExtension.WEBM.extension
                endpointType == EndpointType.OGG_FILE -> FileExtension.OGG.extension
                endpointType == EndpointType.THREEGP_FILE -> FileExtension.THREEGP.extension
                else -> throw IOException("Unknown file type")
            }
        }
    }

    /** Each regulator's switch shows for its endpoints, and its settings while it is on. */
    private fun showRegulator(srtOn: Boolean, rtmpOn: Boolean, endpointType: EndpointType = currentEndpointType()) {
        val endpoint = EndpointFactory(endpointType).build()
        val isSrt = endpoint.hasSrtCapabilities || endpoint.hasSrtlaCapabilities
        val isRtmp = endpoint.hasRtmpCapabilities
        serverEnableBitrateRegulationPreference.isVisible = isSrt
        serverTargetVideoBitratePreference.isVisible = isSrt && srtOn
        serverMinVideoBitratePreference.isVisible = isSrt && srtOn
        regulatorModePreference.isVisible = isSrt && srtOn
        rtmpServerEnableBitrateRegulationPreference.isVisible = isRtmp
        rtmpServerTargetVideoBitratePreference.isVisible = isRtmp && rtmpOn
        rtmpServerMinVideoBitratePreference.isVisible = isRtmp && rtmpOn
    }

    /** The type chosen on this screen, which may not be saved yet. */
    private fun currentEndpointType() =
        EndpointType.fromId(endpointTypePreference.value?.toIntOrNull() ?: EndpointType.SRT.id)

    private fun loadRegulator() {
        serverEnableBitrateRegulationPreference.setOnPreferenceChangeListener { _, newValue ->
            showRegulator(srtOn = newValue as Boolean, rtmpOn = rtmpServerEnableBitrateRegulationPreference.isChecked)
            true
        }
        rtmpServerEnableBitrateRegulationPreference.setOnPreferenceChangeListener { _, newValue ->
            showRegulator(srtOn = serverEnableBitrateRegulationPreference.isChecked, rtmpOn = newValue as Boolean)
            true
        }
        // The minimum stays at or under the target, the other one following
        serverTargetVideoBitratePreference.snapsBitrate { target ->
            if (target < serverMinVideoBitratePreference.value) serverMinVideoBitratePreference.value = target
        }
        serverMinVideoBitratePreference.snapsBitrate { min ->
            if (min > serverTargetVideoBitratePreference.value) serverTargetVideoBitratePreference.value = min
        }
        rtmpServerTargetVideoBitratePreference.snapsBitrate { target ->
            if (target < rtmpServerMinVideoBitratePreference.value) rtmpServerMinVideoBitratePreference.value = target
        }
        rtmpServerMinVideoBitratePreference.snapsBitrate { min ->
            if (min > rtmpServerTargetVideoBitratePreference.value) rtmpServerTargetVideoBitratePreference.value = min
        }
    }

    private fun loadSrtTransportSettings() {
        srtMtuPreference.numeric(4)

        // Shows the derivation rather than leaving the operator to guess where the steps are —
        // 1360 and 1359 produce different payloads and nothing else would say so.
        fun summaryFor(value: String?): String {
            val mtu = value?.toIntOrNull() ?: SrtMtu.DEFAULT_MTU
            return SrtMtu.describe(mtu)
        }

        srtMtuPreference.summary = summaryFor(srtMtuPreference.text)

        srtMtuPreference.setOnPreferenceChangeListener { preference, newValue ->
            val mtu = (newValue as? String)?.toIntOrNull()
            if (mtu == null || mtu !in SrtMtu.UI_MIN_MTU..SrtMtu.DEFAULT_MTU) {
                Toast.makeText(
                    requireContext(),
                    getString(R.string.srt_mtu_invalid, SrtMtu.UI_MIN_MTU, SrtMtu.DEFAULT_MTU),
                    Toast.LENGTH_SHORT
                ).show()
                false
            } else {
                preference.summary = summaryFor(newValue)
                true
            }
        }
    }
}
