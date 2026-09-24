package com.dimadesu.lifestreamer.ui.settings

import android.media.AudioFormat
import android.media.MediaFormat
import android.widget.Toast
import androidx.preference.ListPreference
import androidx.preference.PreferenceCategory
import androidx.preference.SwitchPreference
import com.dimadesu.lifestreamer.R
import com.dimadesu.lifestreamer.settings.CapabilityCatalog
import com.dimadesu.lifestreamer.utils.LowBitrateAudio
import io.github.thibaultbee.streampack.core.streamers.single.AudioConfig

/**
 * The live's audio: encoder, channels, bitrate, sample rate (capped at low bitrates), profile and
 * source, with the choices the chosen destination's encoders offer.
 */
class AudioSettingsFragment : BaseSettingsFragment() {
    override val preferencesRes = R.xml.settings_audio
    override val titleRes = R.string.audio

    private lateinit var catalog: CapabilityCatalog

    private val audioEnablePreference by lazy { pref<SwitchPreference>(R.string.audio_enable_key) }
    private val audioSettingsCategory by lazy { pref<PreferenceCategory>(R.string.audio_settings_key) }
    private val audioEncoderListPreference by lazy { pref<ListPreference>(R.string.audio_encoder_key) }
    private val audioChannelConfigListPreference by lazy { pref<ListPreference>(R.string.audio_channel_config_key) }
    private val audioBitrateListPreference by lazy { pref<ListPreference>(R.string.audio_bitrate_key) }
    private val audioSampleRateListPreference by lazy { pref<ListPreference>(R.string.audio_sample_rate_key) }
    private val audioByteFormatListPreference by lazy { pref<ListPreference>(R.string.audio_byte_format_key) }
    private val audioProfileListPreference by lazy { pref<ListPreference>(R.string.audio_profile_key) }

    override fun onPreferencesInflated() {
        catalog = catalog()
        val encoders = catalog.audioEncoders()
        audioEncoderListPreference.entryValues = encoders.map { it.value }.toTypedArray()
        audioEncoderListPreference.entries = encoders.map { it.label }.toTypedArray()
        if (audioEncoderListPreference.entry == null) {
            audioEncoderListPreference.value = catalog.defaultAudioEncoder()
        }
        audioEncoderListPreference.setOnPreferenceChangeListener { _, newValue ->
            loadAudioSettings(newValue as String)
            true
        }
        audioSettingsCategory.isVisible = audioEnablePreference.isChecked
        audioEnablePreference.setOnPreferenceChangeListener { _, newValue ->
            audioSettingsCategory.isVisible = newValue as Boolean
            true
        }
        audioEncoderListPreference.value?.let { loadAudioSettings(it) }
    }

    private fun loadAudioSettings(encoder: String) {
        val channels = catalog.audioChannelConfigs(encoder)
        audioChannelConfigListPreference.entries = channels.map { it.label }.toTypedArray()
        audioChannelConfigListPreference.entryValues = channels.map { it.value }.toTypedArray()

        val bitrates = catalog.audioBitrates(encoder)
        audioBitrateListPreference.entries = bitrates.map { it.label }.toTypedArray()
        audioBitrateListPreference.entryValues = bitrates.map { it.value }.toTypedArray()
        if (audioBitrateListPreference.entry == null) {
            audioBitrateListPreference.value = "128000"
        }

        // The sample rate is capped at the low bitrates (see LowBitrateAudio). A bitrate or
        // channel change re-inflates it, and says so when that lowers the rate already chosen.
        // The listeners run before the new value is saved, so each passes the one it is changing.
        inflateAudioSampleRates(
            encoder,
            audioBitrateListPreference.value?.toIntOrNull(),
            audioChannelConfigListPreference.value?.toIntOrNull()
        )
        audioBitrateListPreference.setOnPreferenceChangeListener { _, newValue ->
            reapplyAudioSampleRateCap(
                encoder,
                (newValue as String).toInt(),
                audioChannelConfigListPreference.value?.toIntOrNull()
            )
            true
        }
        audioChannelConfigListPreference.setOnPreferenceChangeListener { _, newValue ->
            reapplyAudioSampleRateCap(
                encoder,
                audioBitrateListPreference.value?.toIntOrNull(),
                (newValue as String).toInt()
            )
            true
        }

        val byteFormats = catalog.audioByteFormats()
        audioByteFormatListPreference.entries = byteFormats.map { it.label }.toTypedArray()
        audioByteFormatListPreference.entryValues = byteFormats.map { it.value }.toTypedArray()
        if (audioByteFormatListPreference.entry == null) {
            audioByteFormatListPreference.value = "${AudioFormat.ENCODING_PCM_16BIT}"
        }

        audioProfileListPreference.isVisible = encoder == MediaFormat.MIMETYPE_AUDIO_AAC
        val profiles = catalog.audioProfiles(encoder)
        audioProfileListPreference.entries = profiles.map { it.label }.toTypedArray()
        audioProfileListPreference.entryValues = profiles.map { it.value }.toTypedArray()
        if (audioProfileListPreference.entry == null) {
            audioProfileListPreference.value = catalog.defaultAudioProfile(encoder)
        }
    }

    private fun reapplyAudioSampleRateCap(encoder: String, bitrate: Int?, channelConfig: Int?) {
        val before = audioSampleRateListPreference.value
        inflateAudioSampleRates(encoder, bitrate, channelConfig)
        val after = audioSampleRateListPreference.value
        if (after != before && after != null && bitrate != null) {
            val channels = channelConfig?.let { AudioConfig.getNumberOfChannels(it) } ?: 2
            Toast.makeText(
                requireContext(),
                getString(
                    R.string.audio_sample_rate_lowered,
                    "%.1f".format(after.toFloat() / 1000),
                    bitrate / 1000,
                    getString(if (channels <= 1) R.string.audio_mono else R.string.audio_stereo)
                ),
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun inflateAudioSampleRates(encoder: String, bitrate: Int?, channelConfig: Int?) {
        val sampleRates = catalog.audioSampleRates(encoder, bitrate, channelConfig)
        val capped = bitrate?.let {
            LowBitrateAudio.maxSampleRate(it, channelConfig?.let { c -> AudioConfig.getNumberOfChannels(c) } ?: 2)
        } != null
        audioSampleRateListPreference.entries = sampleRates.map { it.label }.toTypedArray()
        audioSampleRateListPreference.entryValues = sampleRates.map { it.value }.toTypedArray()
        if (audioSampleRateListPreference.entry == null) {
            val rates = sampleRates.mapNotNull { it.value.toIntOrNull() }
            audioSampleRateListPreference.value = when {
                capped -> "${rates.max()}"
                44100 in rates -> "44100"
                48000 in rates -> "48000"
                else -> "${rates.first()}"
            }
        }
        audioSampleRateListPreference.refreshStaleSettingUi()
    }
}
