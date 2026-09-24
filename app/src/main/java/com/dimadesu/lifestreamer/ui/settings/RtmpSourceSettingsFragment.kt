package com.dimadesu.lifestreamer.ui.settings

import androidx.lifecycle.lifecycleScope
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.SeekBarPreference
import com.dimadesu.lifestreamer.R
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** RTMP (or SRT) streams the app can pull in as a camera: their URLs and playback. */
class RtmpSourceSettingsFragment : BaseSettingsFragment() {
    override val preferencesRes = R.xml.settings_rtmp_source
    override val titleRes = R.string.rtmp_source_category_title

    override fun onPreferencesInflated() {
        val buffer = pref<SeekBarPreference>(R.string.rtmp_source_buffer_for_playback_ms_key)
        buffer.setOnPreferenceChangeListener { _, newValue ->
            val rounded = (newValue as Int / 500) * 500
            if (rounded != newValue) {
                buffer.value = rounded
                false
            } else {
                true
            }
        }

        // Read count synchronously (via PreferencesDataStoreAdapter) to avoid UI jump
        val count = preferenceManager.preferenceDataStore?.getInt(
            getString(R.string.rtmp_source_count_key), 1
        ) ?: 1
        rebuildRtmpSourceUrlPreferences(pref(R.string.rtmp_source_key), count)
    }

    /**
     * Rebuild the dynamic RTMP source URL preferences (sources 2+) and add/remove buttons.
     */
    private fun rebuildRtmpSourceUrlPreferences(category: PreferenceCategory, count: Int) {
        // Remove previously added dynamic preferences (URLs 2+, add/remove buttons)
        // Identified by order: URL prefs have order 12..N, add=800, remove=801
        val toRemove = mutableListOf<Preference>()
        for (i in 0 until category.preferenceCount) {
            val pref = category.getPreference(i)
            val order = pref.order
            if ((order in 12..899) || order == 800 || order == 801) {
                toRemove.add(pref)
            }
        }
        toRemove.forEach { category.removePreference(it) }

        // Add EditTextPreference for each additional URL (index 2..count)
        for (i in 2..count) {
            val actualKey = storageRepository.rtmpSourceUrlKey(i)
            val urlPref = EditTextPreference(requireContext()).apply {
                key = actualKey
                title = getString(R.string.rtmp_source_url_title_numbered, i)
                order = 10 + i // after URL 1 (order=10), before shared settings (order=900)
                summaryProvider = EditTextPreference.SimpleSummaryProvider.getInstance()
            }
            category.addPreference(urlPref)
            // Set default if empty (must be done after adding to category so DataStore adapter is active)
            if (urlPref.text.isNullOrEmpty()) {
                urlPref.text = storageRepository.defaultRtmpSourceUrl(i)
            }
        }

        // "Add RTMP source" button
        val addPref = Preference(requireContext()).apply {
            key = "rtmp_source_add_btn"
            title = getString(R.string.rtmp_source_add_title)
            order = 800
            setOnPreferenceClickListener {
                lifecycleScope.launch {
                    val currentCount = storageRepository.rtmpSourceCountFlow.first()
                    val newCount = currentCount + 1
                    storageRepository.setRtmpSourceCount(newCount)
                    rebuildRtmpSourceUrlPreferences(category, newCount)
                }
                true
            }
        }
        category.addPreference(addPref)

        // "Remove last RTMP source" button (only when count > 1)
        if (count > 1) {
            val removePref = Preference(requireContext()).apply {
                key = "rtmp_source_remove_btn"
                title = getString(R.string.rtmp_source_remove_title)
                order = 801
                setOnPreferenceClickListener {
                    lifecycleScope.launch {
                        val currentCount = storageRepository.rtmpSourceCountFlow.first()
                        if (currentCount > 1) {
                            storageRepository.removeRtmpSourceUrl(currentCount)
                            storageRepository.setRtmpSourceCount(currentCount - 1)
                            rebuildRtmpSourceUrlPreferences(category, currentCount - 1)
                        }
                    }
                    true
                }
            }
            category.addPreference(removePref)
        }
    }
}
