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
        keepHeatStepsRising()
    }

    override fun onViewCreated(view: android.view.View, savedInstanceState: android.os.Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // What each step gives up follows the settings; where the phone stands now, every few seconds
        collectWhileStarted(
            kotlinx.coroutines.flow.combine(
                storageRepository.heatConfigFlow,
                kotlinx.coroutines.flow.flow {
                    while (true) {
                        emit(Unit)
                        kotlinx.coroutines.delay(5_000)
                    }
                }
            ) { config, _ -> config }
        ) { config -> showHeatSteps(config) }
    }

    private fun showHeatSteps(config: com.dimadesu.lifestreamer.power.HeatConfig) {
        val battery = com.dimadesu.lifestreamer.power.HeatStatus.batteryC
            ?: com.dimadesu.lifestreamer.power.BatteryTemperature.read(requireContext())
        val step = com.dimadesu.lifestreamer.power.HeatStatus.step
        val now = "Now: battery ${battery?.let { "%.1f °C".format(java.util.Locale.US, it) } ?: "unknown"}, " +
                if (step == 0) "no step in force" else "step $step: ${com.dimadesu.lifestreamer.power.HeatStatus.text}"
        pref<Preference>(R.string.heat_steps_summary_key).summary = now + "\n\n" + config.summary()
    }

    /**
     * The three steps keep rising: moving one moves the others out of its way, as on the remote
     * page.
     */
    private fun keepHeatStepsRising() {
        val keys = listOf(R.string.heat_step1_c_key, R.string.heat_step2_c_key, R.string.heat_step3_c_key)
        val prefs = keys.map { pref<androidx.preference.SeekBarPreference>(it) }
        prefs.forEachIndexed { index, preference ->
            preference.setOnPreferenceChangeListener { _, newValue ->
                val steps = prefs.map { it.value }.toMutableList()
                steps[index] = newValue as Int
                val (ordered, moved) = com.dimadesu.lifestreamer.power.HeatConfig.orderSteps(steps, index)
                if (moved) {
                    ordered.forEachIndexed { i, value -> if (i != index) prefs[i].value = value }
                }
                true
            }
        }
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
                val battery = com.dimadesu.lifestreamer.power.BatteryTemperature.read(requireContext())
                "Thermal status: $level" +
                        (battery?.let { " · battery %.1f °C".format(java.util.Locale.US, it) } ?: "") + saver
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
