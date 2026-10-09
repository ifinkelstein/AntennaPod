package de.danoeh.antennapod.ui.screen.preferences;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.widget.EditText;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.collection.ArrayMap;
import androidx.preference.EditTextPreference;
import androidx.preference.ListPreference;
import androidx.preference.Preference;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import de.danoeh.antennapod.R;
import de.danoeh.antennapod.net.download.service.episode.adscan.AdScanWorker;
import de.danoeh.antennapod.storage.preferences.UserPreferences;
import de.danoeh.antennapod.ui.preferences.screen.AnimatedPreferenceFragment;
import de.danoeh.antennapod.ui.screen.feed.preferences.SkipPreferenceDialog;
import de.danoeh.antennapod.ui.screen.playback.VariableSpeedDialog;

import io.reactivex.rxjava3.schedulers.Schedulers;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

public class PlaybackPreferencesFragment extends AnimatedPreferenceFragment {
    private static final String PREF_PLAYBACK_SPEED_LAUNCHER = "prefPlaybackSpeedLauncher";
    private static final String PREF_PLAYBACK_REWIND_DELTA_LAUNCHER = "prefPlaybackRewindDeltaLauncher";
    private static final String PREF_PLAYBACK_FAST_FORWARD_DELTA_LAUNCHER = "prefPlaybackFastForwardDeltaLauncher";
    private static final List<String> AD_SKIP_SETTINGS = Arrays.asList(UserPreferences.PREF_AD_SKIP_ENABLED,
            UserPreferences.PREF_AD_SKIP_TRANSCRIPTION_URL,
            UserPreferences.PREF_AD_SKIP_TRANSCRIPTION_MODEL, UserPreferences.PREF_AD_SKIP_CHAT_URL,
            UserPreferences.PREF_AD_SKIP_CHAT_MODEL);

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        addPreferencesFromResource(R.xml.preferences_playback);

        setupPlaybackScreen();
        buildSmartMarkAsPlayedPreference();
        setupAdSkipPreferences();
    }

    private void setupAdSkipPreferences() {
        Preference apiKey = findPreference(UserPreferences.PREF_DEEPINFRA_API_KEY);
        updateApiKeySummary(apiKey);
        apiKey.setOnPreferenceClickListener(p -> {
            showApiKeyDialog(p);
            return true;
        });
        setupAdSkipTextPreference(UserPreferences.PREF_AD_SKIP_TRANSCRIPTION_URL,
                UserPreferences.DEFAULT_AD_SKIP_TRANSCRIPTION_URL);
        requireHttps(UserPreferences.PREF_AD_SKIP_TRANSCRIPTION_URL);
        setupAdSkipTextPreference(UserPreferences.PREF_AD_SKIP_TRANSCRIPTION_MODEL,
                UserPreferences.DEFAULT_AD_SKIP_TRANSCRIPTION_MODEL);
        setupAdSkipTextPreference(UserPreferences.PREF_AD_SKIP_CHAT_URL,
                UserPreferences.DEFAULT_AD_SKIP_CHAT_URL);
        requireHttps(UserPreferences.PREF_AD_SKIP_CHAT_URL);
        setupAdSkipTextPreference(UserPreferences.PREF_AD_SKIP_CHAT_MODEL,
                UserPreferences.DEFAULT_AD_SKIP_CHAT_MODEL);
        // Show the model that is actually used, which differs from the stored text for the retired default
        findPreference(UserPreferences.PREF_AD_SKIP_CHAT_MODEL)
                .setSummaryProvider(p -> UserPreferences.getAdSkipChatModel());
    }

    @Override
    public void onResume() {
        super.onResume();
        getPreferenceManager().getSharedPreferences()
                .registerOnSharedPreferenceChangeListener(adSkipSettingsListener);
    }

    @Override
    public void onPause() {
        getPreferenceManager().getSharedPreferences()
                .unregisterOnSharedPreferenceChangeListener(adSkipSettingsListener);
        super.onPause();
    }

    private final SharedPreferences.OnSharedPreferenceChangeListener adSkipSettingsListener = (prefs, key) -> {
        if (AD_SKIP_SETTINGS.contains(key)) {
            resumeScans();
        }
    };

    /**
     * The key is masked by default; the eye icon reveals it so a paste can be checked.
     */
    private void showApiKeyDialog(Preference preference) {
        View content = getLayoutInflater().inflate(R.layout.dialog_api_key, null);
        EditText input = content.findViewById(R.id.apiKeyInput);
        input.setText(UserPreferences.getDeepInfraApiKey());
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.pref_deepinfra_api_key_title)
                .setView(content)
                .setPositiveButton(R.string.confirm_label, (dialog, which) -> {
                    if (!UserPreferences.setDeepInfraApiKey(input.getText().toString())) {
                        Toast.makeText(requireContext(), R.string.pref_deepinfra_api_key_store_failed,
                                Toast.LENGTH_LONG).show();
                        return;
                    }
                    updateApiKeySummary(preference);
                    resumeScans();
                })
                .setNegativeButton(R.string.cancel_label, null)
                .show();
    }

    private void updateApiKeySummary(Preference preference) {
        // Never shows the key itself, only whether one is stored
        preference.setSummary(UserPreferences.getDeepInfraApiKey().isEmpty()
                ? R.string.pref_deepinfra_api_key_not_set : R.string.pref_deepinfra_api_key_saved);
    }

    private void requireHttps(String key) {
        findPreference(key).setOnPreferenceChangeListener((p, newValue) -> {
            if (UserPreferences.isSecureEndpoint((String) newValue)) {
                return true;
            }
            Toast.makeText(requireContext(), R.string.pref_ad_skip_https_required, Toast.LENGTH_LONG).show();
            return false;
        });
    }

    private void resumeScans() {
        // Scans paused by a bad key, missing credit or a wrong endpoint get another chance
        Context context = requireContext().getApplicationContext();
        Schedulers.io().scheduleDirect(() -> AdScanWorker.resumeUnfinished(context));
    }

    private void setupAdSkipTextPreference(String key, String defaultValue) {
        EditTextPreference preference = findPreference(key);
        preference.setSummaryProvider(p -> {
            String value = ((EditTextPreference) p).getText();
            return value == null || value.trim().isEmpty() ? defaultValue : value.trim();
        });
        preference.setOnBindEditTextListener(editText -> {
            editText.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
            if (editText.getText().length() == 0) {
                editText.setText(defaultValue);
            }
        });
    }

    @Override
    public void onStart() {
        super.onStart();
        ((PreferenceActivity) getActivity()).getSupportActionBar().setTitle(R.string.playback_pref);
    }

    private void setupPlaybackScreen() {
        final Activity activity = getActivity();

        findPreference(PREF_PLAYBACK_SPEED_LAUNCHER).setOnPreferenceClickListener(preference -> {
            new VariableSpeedDialog().show(getChildFragmentManager(), null);
            return true;
        });
        findPreference(PREF_PLAYBACK_REWIND_DELTA_LAUNCHER).setOnPreferenceClickListener(preference -> {
            SkipPreferenceDialog.showSkipPreference(activity, SkipPreferenceDialog.SkipDirection.SKIP_REWIND, null);
            return true;
        });
        findPreference(PREF_PLAYBACK_FAST_FORWARD_DELTA_LAUNCHER).setOnPreferenceClickListener(preference -> {
            SkipPreferenceDialog.showSkipPreference(activity, SkipPreferenceDialog.SkipDirection.SKIP_FORWARD, null);
            return true;
        });
        if (Build.VERSION.SDK_INT >= 31) {
            findPreference(UserPreferences.PREF_UNPAUSE_ON_HEADSET_RECONNECT).setVisible(false);
            findPreference(UserPreferences.PREF_UNPAUSE_ON_BLUETOOTH_RECONNECT).setVisible(false);
        }

        buildEnqueueLocationPreference();
    }

    private void buildEnqueueLocationPreference() {
        final Resources res = requireActivity().getResources();
        final Map<String, String> options = new ArrayMap<>();
        {
            String[] keys = res.getStringArray(R.array.enqueue_location_values);
            String[] values = res.getStringArray(R.array.enqueue_location_options);
            for (int i = 0; i < keys.length; i++) {
                options.put(keys[i], values[i]);
            }
        }

        ListPreference pref = requirePreference(UserPreferences.PREF_ENQUEUE_LOCATION);
        pref.setSummary(res.getString(R.string.pref_enqueue_location_sum, options.get(pref.getValue())));

        pref.setOnPreferenceChangeListener((preference, newValue) -> {
            if (!(newValue instanceof String)) {
                return false;
            }
            String newValStr = (String) newValue;
            pref.setSummary(res.getString(R.string.pref_enqueue_location_sum, options.get(newValStr)));
            return true;
        });
    }

    @NonNull
    private <T extends Preference> T requirePreference(@NonNull CharSequence key) {
        // Possibly put it to a common method in abstract base class
        T result = findPreference(key);
        if (result == null) {
            throw new IllegalArgumentException("Preference with key '" + key + "' is not found");

        }
        return result;
    }

    private void buildSmartMarkAsPlayedPreference() {
        final Resources res = getActivity().getResources();

        ListPreference pref = findPreference(UserPreferences.PREF_SMART_MARK_AS_PLAYED_SECS);
        String[] values = res.getStringArray(R.array.smart_mark_as_played_values);
        String[] entries = new String[values.length];
        for (int x = 0; x < values.length; x++) {
            if (x == 0) {
                entries[x] = res.getString(R.string.pref_smart_mark_as_played_disabled);
            } else {
                int v = Integer.parseInt(values[x]);
                if (v < 60) {
                    entries[x] = res.getQuantityString(R.plurals.time_seconds_quantified, v, v);
                } else {
                    v /= 60;
                    entries[x] = res.getQuantityString(R.plurals.time_minutes_quantified, v, v);
                }
            }
        }
        pref.setEntries(entries);
    }
}
