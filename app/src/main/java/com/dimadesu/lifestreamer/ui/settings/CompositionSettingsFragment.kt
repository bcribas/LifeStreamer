package com.dimadesu.lifestreamer.ui.settings

import androidx.preference.Preference
import com.dimadesu.lifestreamer.R
import com.dimadesu.lifestreamer.composition.CompositionCapabilities

/**
 * Whether this device can run two of its own cameras at once, and what else it can compose.
 *
 * Read-only on purpose: it is a device fact, and its whole job is to answer "why can't I add
 * a second camera?" somewhere the operator can find it without asking.
 */
class CompositionSettingsFragment : BaseSettingsFragment() {
    override val preferencesRes = R.xml.settings_composition
    override val titleRes = R.string.composition_category_title

    override fun onPreferencesInflated() {
        pref<Preference>(R.string.composition_capabilities_key).summary = try {
            CompositionCapabilities(requireContext()).report().describe()
        } catch (t: Throwable) {
            "Could not determine this device's camera capabilities"
        }
    }
}
