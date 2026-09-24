package com.dimadesu.lifestreamer.ui.settings

import android.content.Context
import android.hardware.camera2.CameraManager
import androidx.preference.ListPreference
import androidx.preference.SeekBarPreference
import androidx.preference.SwitchPreference
import com.dimadesu.lifestreamer.R
import com.dimadesu.lifestreamer.settings.CapabilityCatalog
import com.dimadesu.lifestreamer.utils.DialogUtils
import io.github.thibaultbee.streampack.core.configuration.BitrateRegulatorConfig
import io.github.thibaultbee.streampack.core.elements.sources.video.camera.extensions.isFpsSupported
import io.github.thibaultbee.streampack.core.streamers.single.VideoConfig
import kotlinx.coroutines.flow.combine

/**
 * The live's video: encoder, resolution, frame rates, bitrate, profile and level, with the
 * choices the chosen destination's encoders offer.
 */
class VideoSettingsFragment : BaseSettingsFragment() {
    override val preferencesRes = R.xml.settings_video
    override val titleRes = R.string.video

    private lateinit var catalog: CapabilityCatalog

    private val videoEncoderListPreference by lazy { pref<ListPreference>(R.string.video_encoder_key) }
    private val videoResolutionHardwareFilterPreference by lazy {
        pref<SwitchPreference>(R.string.video_resolution_hardware_filter_key)
    }
    private val videoResolutionListPreference by lazy { pref<ListPreference>(R.string.video_resolution_key) }
    private val cameraFpsListPreference by lazy { pref<ListPreference>(R.string.camera_fps_key) }
    private val videoFpsListPreference by lazy { pref<ListPreference>(R.string.video_fps_key) }
    private val videoBitrateSeekBar by lazy { pref<SeekBarPreference>(R.string.video_bitrate_key) }
    private val videoProfileListPreference by lazy { pref<ListPreference>(R.string.video_profile_key) }
    private val videoLevelListPreference by lazy { pref<ListPreference>(R.string.video_level_key) }

    override fun onPreferencesInflated() {
        catalog = catalog()
        val encoders = catalog.videoEncoders()
        videoEncoderListPreference.entryValues = encoders.map { it.value }.toTypedArray()
        videoEncoderListPreference.entries = encoders.map { it.label }.toTypedArray()
        if (videoEncoderListPreference.entry == null) {
            videoEncoderListPreference.value = catalog.defaultVideoEncoder()
        }
        videoEncoderListPreference.setOnPreferenceChangeListener { _, newValue ->
            loadVideoSettings(newValue as String, resetToDefaults = true)
            true
        }
        videoResolutionHardwareFilterPreference.setOnPreferenceChangeListener { _, newValue ->
            videoEncoderListPreference.value?.let { loadVideoSettings(it, showHardwareOnly = newValue as Boolean) }
            true
        }
        cameraFpsListPreference.setOnPreferenceChangeListener { _, newValue ->
            (newValue as? String)?.toIntOrNull()?.let { warnCamerasWithout(it) }
            true
        }
        videoBitrateSeekBar.snapsBitrate()
        videoEncoderListPreference.value?.let { loadVideoSettings(it) }
    }

    override fun onViewCreated(view: android.view.View, savedInstanceState: android.os.Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // From the stored values: the preferences' own listeners run before the value is saved
        collectWhileStarted(
            combine(
                storageRepository.videoConfigFlow,
                storageRepository.bitrateRegulatorConfigFlow
            ) { video, regulator -> video to regulator }
        ) { (video, regulator) -> updateBitrateSummary(video, regulator) }
    }

    /**
     * Warns on the bitrate when it is too low for the resolution and frame rate: 200 kb/s is
     * watchable at 640x360 but a smear of blocks at 1080p30. While adaptive bitrate is on, its
     * target is the ceiling and the warning goes there (Destination and network); this one says so.
     */
    private fun updateBitrateSummary(video: VideoConfig?, regulator: BitrateRegulatorConfig?) {
        videoBitrateSeekBar.summary = when {
            video == null -> null
            regulator != null ->
                getString(R.string.video_bitrate_capped_by_regulator, regulator.videoBitrateRange.upper / 1000)
            else -> lowBitrateWarning(video.startBitrate, video, offeredResolutions(catalog))
        }
    }

    private fun loadVideoSettings(
        encoder: String,
        resetToDefaults: Boolean = false,
        showHardwareOnly: Boolean = videoResolutionHardwareFilterPreference.isChecked
    ) {
        // Resolutions
        catalog.videoResolutions(encoder, showHardwareOnly).map { it.toString() }.toTypedArray().run {
            videoResolutionListPreference.entries = this
            videoResolutionListPreference.entryValues = this
        }
        val savedVideoResolution = videoResolutionListPreference.value
        videoResolutionListPreference.value =
            if (videoResolutionListPreference.findIndexOfValue(savedVideoResolution) >= 0) savedVideoResolution
            else getString(R.string.default_video_resolution)
        videoResolutionListPreference.refreshStaleSettingUi()

        // Frame rates: the encoder's, and the default camera's
        fillFps(videoFpsListPreference, catalog.encoderFps(encoder))
        fillFps(cameraFpsListPreference, catalog.cameraFps(encoder))

        // Bitrate, within the codec's range
        catalog.videoBitrateRangeKbps(encoder).let {
            videoBitrateSeekBar.min = it.first
            videoBitrateSeekBar.max = it.last
        }

        // Profile, then level
        val profiles = catalog.videoProfiles(encoder)
        videoProfileListPreference.entries = profiles.map { it.label }.toTypedArray()
        videoProfileListPreference.entryValues = profiles.map { it.value }.toTypedArray()
        val savedProfile = videoProfileListPreference.value
        videoProfileListPreference.value =
            if (!resetToDefaults && videoProfileListPreference.findIndexOfValue(savedProfile) >= 0) savedProfile
            else catalog.defaultVideoProfile(encoder)
        videoProfileListPreference.refreshStaleSettingUi()
        videoProfileListPreference.setOnPreferenceChangeListener { _, newValue ->
            loadVideoLevel(encoder, (newValue as String).toInt())
            true
        }
        videoProfileListPreference.value?.toIntOrNull()?.let { loadVideoLevel(encoder, it, resetToDefaults) }
    }

    private fun fillFps(preference: ListPreference, fps: List<Int>) {
        fps.map { "$it" }.toTypedArray().run {
            preference.entries = this
            preference.entryValues = this
        }
        val saved = preference.value
        preference.value =
            if (preference.findIndexOfValue(saved) >= 0) saved else getString(R.string.default_fps)
        preference.refreshStaleSettingUi()
    }

    private fun loadVideoLevel(encoder: String, profile: Int, resetToDefaults: Boolean = false) {
        val levels = catalog.videoLevels(encoder, profile)
        videoLevelListPreference.entries = levels.map { it.label }.toTypedArray()
        videoLevelListPreference.entryValues = levels.map { it.value }.toTypedArray()
        val savedLevel = videoLevelListPreference.value
        videoLevelListPreference.value =
            if (!resetToDefaults && videoLevelListPreference.findIndexOfValue(savedLevel) >= 0) savedLevel
            else catalog.defaultVideoLevel(encoder, profile)
        videoLevelListPreference.refreshStaleSettingUi()
    }

    /** Says which cameras cannot run at [fps]: the default one's list does not cover the others. */
    private fun warnCamerasWithout(fps: Int) {
        val cameraManager = requireContext().getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val unsupportedCameras = cameraManager.cameraIdList.filter {
            !cameraManager.getCameraCharacteristics(it).isFpsSupported(fps)
        }
        if (unsupportedCameras.isNotEmpty()) {
            DialogUtils.showAlertDialog(
                requireContext(), getString(R.string.warning), resources.getQuantityString(
                    R.plurals.camera_frame_rate_not_supported,
                    unsupportedCameras.size,
                    unsupportedCameras.joinToString(", "),
                    fps
                )
            )
        }
    }
}
