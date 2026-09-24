package com.dimadesu.lifestreamer.ui.settings

import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import com.dimadesu.lifestreamer.R
import com.dimadesu.lifestreamer.remote.RemoteAuth
import com.dimadesu.lifestreamer.remote.RemoteControlManager
import com.dimadesu.lifestreamer.remote.RemoteControlQrDialog
import kotlinx.coroutines.launch

/** The remote control page: on or off, its port, and the address, PIN and QR code. */
class RemoteControlSettingsFragment : BaseSettingsFragment() {
    override val preferencesRes = R.xml.settings_remote_control
    override val titleRes = R.string.remote_control_category_title

    override fun onPreferencesInflated() {
        loadRemoteControlSettings()
    }

    override fun onViewCreated(view: android.view.View, savedInstanceState: android.os.Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // The server starts asynchronously (switch -> DataStore -> service), so a one-shot read
        // right after the switch flips still sees the old state and the summary would read
        // "Not running" on a server that is up. Redraw when it actually comes up or goes down.
        collectWhileStarted(RemoteControlManager.stateFlow) { loadRemoteControlSettings() }
    }

    override fun onResume() {
        super.onResume()
        // The address only changes when the network does: refresh it whenever this screen returns
        runCatching { loadRemoteControlSettings() }
    }

    /** The address, the PIN and the QR code that carries both. */
    private fun loadRemoteControlSettings() {
        val addressPreference = pref<Preference>(R.string.remote_control_address_key)
        val url = RemoteControlManager.url(requireContext())
        // The PIN lives in the same DataStore the whole preference screen is backed by
        // (see preferenceDataStore above). Reading it from default SharedPreferences would
        // always come back empty: this app has no default SharedPreferences file.
        val pin = preferenceManager.preferenceDataStore
            ?.getString(getString(R.string.remote_control_pin_key), "")
            .orEmpty()

        addressPreference.summary = when {
            url == null && RemoteControlManager.lastError != null -> RemoteControlManager.lastError
            url == null -> "Not running. Turn it on above, and make sure Wi-Fi is connected."
            else -> "$url   ·   PIN $pin\n\n${getString(R.string.remote_control_warning)}"
        }

        addressPreference.setOnPreferenceClickListener {
            RemoteControlQrDialog.show(requireContext(), pin)
            true
        }

        pref<Preference>(R.string.remote_control_new_pin_key).setOnPreferenceClickListener {
            lifecycleScope.launch {
                val fresh = RemoteAuth {}.generatePin()
                storageRepository.setRemoteControlPin(fresh)
                loadRemoteControlSettings()
                Toast.makeText(requireContext(), "New PIN: $fresh", Toast.LENGTH_LONG).show()
            }
            true
        }
    }
}
