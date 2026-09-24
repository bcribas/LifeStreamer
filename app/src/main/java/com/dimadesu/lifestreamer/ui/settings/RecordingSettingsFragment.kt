package com.dimadesu.lifestreamer.ui.settings

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.SeekBarPreference
import com.dimadesu.lifestreamer.R
import com.dimadesu.lifestreamer.models.EndpointFactory
import com.dimadesu.lifestreamer.recording.SafSegmentStore
import com.dimadesu.lifestreamer.settings.SettingsRules.Dimensions
import io.github.thibaultbee.streampack.core.streamers.single.VideoConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Recording to the phone while live: the folder, the mode, its own quality and the segments. */
class RecordingSettingsFragment : BaseSettingsFragment() {
    override val preferencesRes = R.xml.settings_recording
    override val titleRes = R.string.recording_category_title

    private val recordingFolderPreference by lazy { pref<Preference>(R.string.recording_folder_key) }
    private val recordingModePreference by lazy { pref<ListPreference>(R.string.recording_mode_key) }
    private val recordingResolutionPreference by lazy { pref<ListPreference>(R.string.recording_resolution_key) }
    private val recordingBitratePreference by lazy { pref<SeekBarPreference>(R.string.recording_video_bitrate_key) }
    private val catalog by lazy { catalog() }

    /**
     * The Storage Access Framework folder picker. Registered here, at construction, as the
     * Activity Result API requires.
     */
    private val recordingFolderPicker =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) onRecordingFolderPicked(uri)
        }

    override fun onPreferencesInflated() {
        // Recording "while live" means nothing when the endpoint is itself a file
        val fileEndpoint = EndpointFactory(storedEndpointType()).build().hasFileCapabilities
        val screen = preferenceScreen
        for (i in 0 until screen.preferenceCount) {
            screen.getPreference(i).isVisible = !fileEndpoint
        }
        pref<Preference>(R.string.recording_file_endpoint_note_key).isVisible = fileEndpoint
        if (fileEndpoint) return

        recordingFolderPreference.setOnPreferenceClickListener {
            lifecycleScope.launch {
                // Opens the picker where the current folder is, when there is one
                val current = storageRepository.recordingConfigFlow.first().folderUri
                recordingFolderPicker.launch(current?.let { Uri.parse(it) })
            }
            true
        }
        recordingModePreference.setOnPreferenceChangeListener { _, newValue ->
            applyRecordingModeVisibility(newValue as String)
            true
        }
        applyRecordingModeVisibility(recordingModePreference.value)
        recordingBitratePreference.snapsBitrate()
    }

    override fun onViewCreated(view: android.view.View, savedInstanceState: android.os.Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        if (EndpointFactory(storedEndpointType()).build().hasFileCapabilities) return
        collectWhileStarted(storageRepository.videoConfigFlow) { refreshRecordingResolutions(it) }
    }

    override fun onResume() {
        super.onResume()
        runCatching { refreshRecordingFolderSummary() }
    }

    /** Resolution and bitrate only mean something for a recording with its own encoder. */
    private fun applyRecordingModeVisibility(mode: String?) {
        val separate = mode != getString(R.string.recording_mode_live_copy)
        recordingResolutionPreference.isVisible = separate
        recordingBitratePreference.isVisible = separate
    }

    /**
     * "Same as the live", then the live's resolutions with its shape: the recording shares the
     * live's canvas, so another shape would be stretched.
     */
    private fun refreshRecordingResolutions(video: VideoConfig?) {
        val live = video?.let { Dimensions(it.resolution.width, it.resolution.height) }
        val store = preferenceManager.preferenceDataStore
        val encoder = video?.mimeType ?: catalog.defaultVideoEncoder() ?: return
        val hardwareOnly =
            store?.getBoolean(getString(R.string.video_resolution_hardware_filter_key), true) ?: true
        val sizes = catalog.recordingResolutions(encoder, hardwareOnly, live).map { it.toString() }
        val sameAsLive = getString(R.string.recording_resolution_same_as_live)
        recordingResolutionPreference.entries =
            (listOf(getString(R.string.recording_resolution_same_as_live_entry)) + sizes).toTypedArray()
        recordingResolutionPreference.entryValues = (listOf(sameAsLive) + sizes).toTypedArray()
        if (recordingResolutionPreference.findIndexOfValue(recordingResolutionPreference.value) < 0) {
            recordingResolutionPreference.value = sameAsLive
        }
        recordingResolutionPreference.refreshStaleSettingUi()
    }

    private fun onRecordingFolderPicked(uri: Uri) {
        val resolver = requireContext().contentResolver
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        try {
            resolver.takePersistableUriPermission(uri, flags)
        } catch (e: SecurityException) {
            Toast.makeText(requireContext(), "That folder cannot be written to", Toast.LENGTH_LONG).show()
            return
        }
        lifecycleScope.launch {
            val previous = storageRepository.recordingConfigFlow.first().folderUri
            if (previous != null && previous != uri.toString()) {
                runCatching { resolver.releasePersistableUriPermission(Uri.parse(previous), flags) }
            }
            storageRepository.setRecordingFolderUri(uri.toString())
            refreshRecordingFolderSummary()
        }
    }

    /** The folder, its volume and the room left, read off the main thread. */
    private fun refreshRecordingFolderSummary() {
        if (!recordingFolderPreference.isVisible) return
        val context = requireContext().applicationContext
        lifecycleScope.launch {
            val config = storageRepository.recordingConfigFlow.first()
            val summary = withContext(Dispatchers.IO) {
                val folder = config.folderUri ?: return@withContext getString(R.string.recording_folder_none)
                val store = SafSegmentStore(context, Uri.parse(folder))
                if (!store.hasPermission()) return@withContext getString(R.string.recording_folder_access_lost)
                val free = store.freeBytes()
                    ?: return@withContext "${store.label()}\nNot available (card removed?)"
                val freeGb = free / 1e9
                val hours = free * 8.0 / (config.videoBitrateBps + 128_000) / 3600
                "${store.label()}\n%.1f GB free, about %.0f h at the recording bitrate".format(freeGb, hours)
            }
            recordingFolderPreference.summary = summary
        }
    }
}
