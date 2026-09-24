package com.dimadesu.lifestreamer.ui.settings

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.preference.Preference
import com.dimadesu.lifestreamer.R
import com.dimadesu.lifestreamer.power.ThermalLevel

/** Mounted mode, heat, battery, and the preview's own size and rate. */
class PowerSettingsFragment : BaseSettingsFragment() {
    override val preferencesRes = R.xml.settings_power
    override val titleRes = R.string.settings_section_power

    override fun onPreferencesInflated() {
        loadPowerSettings()
    }

    override fun onResume() {
        super.onResume()
        // The thermal reading and the exemption are snapshots: refresh them when the screen returns
        runCatching { loadPowerSettings() }
    }

    /**
     * Fills in what the device says about its own temperature, and offers the battery
     * optimisation exemption.
     *
     * The permission for that exemption has been declared in the manifest since forever and was
     * never actually used, so the app could never protect itself from being restricted.
     */
    private fun loadPowerSettings() {
        val powerManager = requireContext().getSystemService(Context.POWER_SERVICE) as PowerManager

        pref<Preference>(R.string.thermal_status_key).summary = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val level = ThermalLevel.fromStatus(powerManager.currentThermalStatus)
                val saver = if (powerManager.isPowerSaveMode) {
                    "\n\nBattery saver is ON. With the screen off Android may restrict " +
                            "background work and interrupt the stream. Use Sustained " +
                            "performance above instead."
                } else {
                    ""
                }
                "Thermal status: $level$saver"
            } else {
                "This device cannot report its temperature (needs Android 10)."
            }
        } catch (t: Throwable) {
            "Could not read the thermal status"
        }

        pref<Preference>(R.string.battery_optimization_key).let { preference ->
            val exempt = try {
                powerManager.isIgnoringBatteryOptimizations(requireContext().packageName)
            } catch (t: Throwable) {
                false
            }

            preference.summary = if (exempt) {
                "Exempt. Android will not restrict the app in the background."
            } else {
                "Not exempt. Android may restrict the app in the background and interrupt a long stream. Tap to change."
            }

            preference.setOnPreferenceClickListener {
                try {
                    startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                } catch (t: Throwable) {
                    Toast.makeText(requireContext(), "Could not open battery settings", Toast.LENGTH_SHORT).show()
                }
                true
            }
        }
    }
}
