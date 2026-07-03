package io.legado.app.ui.config

import android.content.SharedPreferences
import android.os.Bundle
import android.view.View
import androidx.preference.ListPreference
import androidx.preference.Preference
import io.legado.app.R
import io.legado.app.constant.PreferKey
import io.legado.app.help.config.AppConfig
import io.legado.app.lib.prefs.fragment.PreferenceFragment
import io.legado.app.lib.theme.primaryColor
import io.legado.app.ui.widget.number.NumberPickerDialog
import io.legado.app.utils.setEdgeEffectColor

/**
 * 视频生成设置：API Key / BaseUrl / 服务商 / 图片尺寸 / 并发数 / 目标时长
 */
class VideoConfigFragment : PreferenceFragment(),
    SharedPreferences.OnSharedPreferenceChangeListener {

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        addPreferencesFromResource(R.xml.pref_config_video)
        upPreferenceSummary(PreferKey.agnesApiKey, maskKey(AppConfig.agnesApiKey))
        upPreferenceSummary(PreferKey.agnesBaseUrl, AppConfig.agnesBaseUrl)
        upPreferenceSummary(PreferKey.videoGenProvider, AppConfig.videoGenProvider)
        upPreferenceSummary(PreferKey.videoGenImageSize, AppConfig.videoGenImageSize)
        upPreferenceSummary(
            PreferKey.videoGenConcurrency,
            AppConfig.videoGenConcurrency.toString()
        )
        upPreferenceSummary(
            PreferKey.videoGenTargetDuration,
            AppConfig.videoGenTargetDuration.toString()
        )
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        activity?.setTitle(R.string.video_gen_config)
        preferenceManager.sharedPreferences?.registerOnSharedPreferenceChangeListener(this)
        listView.setEdgeEffectColor(primaryColor)
    }

    override fun onDestroy() {
        super.onDestroy()
        preferenceManager.sharedPreferences?.unregisterOnSharedPreferenceChangeListener(this)
    }

    override fun onPreferenceTreeClick(preference: Preference): Boolean {
        when (preference.key) {
            PreferKey.videoGenConcurrency -> NumberPickerDialog(requireContext())
                .setTitle(getString(R.string.video_gen_concurrency))
                .setMaxValue(8)
                .setMinValue(1)
                .setValue(AppConfig.videoGenConcurrency)
                .show {
                    AppConfig.videoGenConcurrency = it
                }

            PreferKey.videoGenTargetDuration -> NumberPickerDialog(requireContext())
                .setTitle(getString(R.string.video_gen_target_duration))
                .setMaxValue(300)
                .setMinValue(5)
                .setValue(AppConfig.videoGenTargetDuration)
                .show {
                    AppConfig.videoGenTargetDuration = it
                }
        }
        return super.onPreferenceTreeClick(preference)
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        when (key) {
            PreferKey.agnesApiKey ->
                upPreferenceSummary(key, maskKey(AppConfig.agnesApiKey))

            PreferKey.agnesBaseUrl ->
                upPreferenceSummary(key, AppConfig.agnesBaseUrl)

            PreferKey.videoGenProvider ->
                upPreferenceSummary(key, AppConfig.videoGenProvider)

            PreferKey.videoGenImageSize ->
                upPreferenceSummary(key, AppConfig.videoGenImageSize)

            PreferKey.videoGenConcurrency ->
                upPreferenceSummary(key, AppConfig.videoGenConcurrency.toString())

            PreferKey.videoGenTargetDuration ->
                upPreferenceSummary(key, AppConfig.videoGenTargetDuration.toString())
        }
    }

    private fun upPreferenceSummary(preferenceKey: String, value: String?) {
        val preference = findPreference<Preference>(preferenceKey) ?: return
        when (preferenceKey) {
            PreferKey.agnesApiKey -> preference.summary = value ?: ""
            PreferKey.agnesBaseUrl -> preference.summary = value
            PreferKey.videoGenProvider -> preference.summary = value
            PreferKey.videoGenImageSize -> preference.summary = value

            PreferKey.videoGenConcurrency -> preference.summary =
                getString(R.string.video_gen_concurrency_summary, value)

            PreferKey.videoGenTargetDuration -> preference.summary =
                getString(R.string.video_gen_target_duration_summary, value)

            else -> if (preference is ListPreference) {
                val index = preference.findIndexOfValue(value)
                preference.summary = if (index >= 0) preference.entries[index] else null
            } else {
                preference.summary = value
            }
        }
    }

    private fun maskKey(key: String?): String {
        if (key.isNullOrBlank()) return ""
        return if (key.length <= 8) {
            "*".repeat(key.length)
        } else {
            key.substring(0, 4) + "*".repeat(key.length - 8) + key.substring(key.length - 4)
        }
    }

}
