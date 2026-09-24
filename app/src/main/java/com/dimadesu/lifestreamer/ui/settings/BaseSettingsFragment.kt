package com.dimadesu.lifestreamer.ui.settings

import android.os.Bundle
import android.text.InputFilter
import android.text.InputType
import androidx.annotation.StringRes
import androidx.annotation.XmlRes
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SeekBarPreference
import com.dimadesu.lifestreamer.R
import com.dimadesu.lifestreamer.data.storage.DataStoreRepository
import com.dimadesu.lifestreamer.data.storage.PreferencesDataStoreAdapter
import com.dimadesu.lifestreamer.models.EndpointType
import com.dimadesu.lifestreamer.settings.CapabilityCatalog
import com.dimadesu.lifestreamer.settings.SettingsRules
import com.dimadesu.lifestreamer.settings.SettingsRules.Dimensions
import com.dimadesu.lifestreamer.utils.dataStore
import io.github.thibaultbee.streampack.core.streamers.single.VideoConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/**
 * One section of the settings: its own XML, backed by the DataStore, with the helpers the
 * sections share.
 */
abstract class BaseSettingsFragment : PreferenceFragmentCompat() {

    @get:XmlRes
    protected abstract val preferencesRes: Int

    /** The bar's title while this section shows. */
    @get:StringRes
    abstract val titleRes: Int

    protected val storageRepository by lazy {
        DataStoreRepository(requireContext(), requireContext().dataStore)
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        preferenceManager.preferenceDataStore =
            PreferencesDataStoreAdapter(requireContext().dataStore, writeScope)
        setPreferencesFromResource(preferencesRes, rootKey)
        onPreferencesInflated()
    }

    /** Sets up the inflated preferences: lists, listeners, what shows. */
    protected abstract fun onPreferencesInflated()

    override fun onResume() {
        super.onResume()
        activity?.title = getString(titleRes)
    }

    protected fun <T : Preference> pref(@StringRes keyRes: Int): T =
        findPreference(getString(keyRes))
            ?: error("${getString(keyRes)} is not in ${javaClass.simpleName}")

    /** The endpoint type as stored now: it decides the choices several sections offer. */
    protected fun storedEndpointType(): EndpointType = EndpointType.fromId(
        preferenceManager.preferenceDataStore
            ?.getString(getString(R.string.endpoint_type_key), null)
            ?.toIntOrNull() ?: EndpointType.SRT.id
    )

    protected fun catalog(): CapabilityCatalog = CapabilityCatalog(requireContext(), storedEndpointType())

    /** Runs [block] on each value of [flow] while this section is on screen. */
    protected fun <T> collectWhileStarted(flow: Flow<T>, block: suspend (T) -> Unit) {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                flow.collect { runCatching { block(it) } }
            }
        }
    }

    /**
     * Snaps the slider to round bitrates (see [SettingsRules.snapBitrateKbps]); [onSnapped] gets
     * each value it keeps.
     */
    protected fun SeekBarPreference.snapsBitrate(onSnapped: (Int) -> Unit = {}) {
        setOnPreferenceChangeListener { _, newValue ->
            val rounded = SettingsRules.snapBitrateKbps(newValue as Int)
            onSnapped(rounded)
            if (rounded != newValue) {
                value = rounded
                false
            } else {
                true
            }
        }
    }

    protected fun EditTextPreference.numeric(maxLength: Int) {
        setOnBindEditTextListener { editText ->
            editText.inputType = InputType.TYPE_CLASS_NUMBER
            editText.filters = arrayOf(InputFilter.LengthFilter(maxLength))
        }
    }

    protected fun EditTextPreference.address() {
        setOnBindEditTextListener { editText ->
            editText.inputType = InputType.TYPE_TEXT_VARIATION_URI
        }
    }

    /**
     * Re-assigns SimpleSummaryProvider to force a notifyChanged() call, which makes the UI
     * rebind and re-read getEntry() against the updated entries list. Without this, the summary
     * label can be stale when setValue() receives the same string value it already holds
     * (e.g. AVCProfileBaseline=1 and HEVCProfileMain=1 are the same int), so the framework
     * skips the UI update entirely.
     */
    protected fun ListPreference.refreshStaleSettingUi() {
        summaryProvider = ListPreference.SimpleSummaryProvider.getInstance()
    }

    /**
     * The warning for a bitrate too low for the live's resolution and frame rate, with the
     * nearest fix among [offered] (see [SettingsRules.lowBitrate]); null when it is enough.
     */
    protected fun lowBitrateWarning(bitrateBps: Int, video: VideoConfig, offered: List<Dimensions>): String? {
        // The encoder is fed every frame the cameras deliver, whatever its own FPS setting says.
        val fps = maxOf(video.fps, video.cameraFps ?: video.fps)
        val resolution = Dimensions(video.resolution.width, video.resolution.height)
        val low = SettingsRules.lowBitrate(bitrateBps, resolution, fps, offered) ?: return null
        val kbps = bitrateBps / 1000
        val advice = low.smaller?.let { getString(R.string.bitrate_warning_use_resolution, kbps, it.toString()) }
            ?: low.smallerAt15Fps?.let { getString(R.string.bitrate_warning_use_15_fps, kbps, it.toString()) }
            ?: getString(R.string.bitrate_warning_raise_bitrate)
        return getString(R.string.bitrate_warning_low, resolution.toString(), fps, low.neededKbps, advice)
    }

    /** The resolutions the Video section offers now, for the warning's advice. */
    protected fun offeredResolutions(catalog: CapabilityCatalog): List<Dimensions> = runCatching {
        val store = preferenceManager.preferenceDataStore
        val encoder = store?.getString(getString(R.string.video_encoder_key), null)
            ?: catalog.defaultVideoEncoder() ?: return@runCatching emptyList()
        val hardwareOnly = store?.getBoolean(getString(R.string.video_resolution_hardware_filter_key), true) ?: true
        catalog.videoResolutions(encoder, hardwareOnly)
    }.getOrDefault(emptyList())

    private companion object {
        /**
         * Writes outlive the section: DataStore runs a write in its caller's context, so one made
         * in the section's own scope is lost when back is pressed right after a change.
         */
        val writeScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    }
}
