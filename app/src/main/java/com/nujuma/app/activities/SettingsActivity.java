package com.nujuma.app.activities;

import android.content.Context;
import android.os.Bundle;
import android.os.Environment;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;

import com.nujuma.R;
import com.nujuma.shared.activities.ReportActivity;
import com.nujuma.shared.file.FileUtils;
import com.nujuma.shared.models.ReportInfo;
import com.nujuma.app.models.UserAction;
import com.nujuma.shared.interact.ShareUtils;
import com.nujuma.shared.android.PackageUtils;
import com.nujuma.shared.nujuma.settings.preferences.NujumaAPIAppSharedPreferences;
import com.nujuma.shared.nujuma.settings.preferences.NujumaFloatAppSharedPreferences;
import com.nujuma.shared.nujuma.settings.preferences.NujumaTaskerAppSharedPreferences;
import com.nujuma.shared.nujuma.settings.preferences.NujumaWidgetAppSharedPreferences;
import com.nujuma.shared.android.AndroidUtils;
import com.nujuma.shared.nujuma.NujumaConstants;
import com.nujuma.shared.nujuma.NujumaUtils;
import com.nujuma.shared.activity.media.AppCompatActivityUtils;
import com.nujuma.shared.theme.NightMode;

public class SettingsActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        AppCompatActivityUtils.setNightMode(this, NightMode.getAppNightMode().getName(), true);

        setContentView(R.layout.activity_settings);
        if (savedInstanceState == null) {
            getSupportFragmentManager()
                .beginTransaction()
                .replace(R.id.settings, new RootPreferencesFragment())
                .commit();
        }

        AppCompatActivityUtils.setToolbar(this, com.nujuma.shared.R.id.toolbar);
        AppCompatActivityUtils.setShowBackButtonInActionBar(this, true);
    }

    @Override
    public boolean onSupportNavigateUp() {
        onBackPressed();
        return true;
    }

    public static class RootPreferencesFragment extends PreferenceFragmentCompat {
        @Override
        public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
            Context context = getContext();
            if (context == null) return;

            setPreferencesFromResource(R.xml.root_preferences, rootKey);

            new Thread() {
                @Override
                public void run() {
                    configureNujumaAPIPreference(context);
                    configureNujumaFloatPreference(context);
                    configureNujumaTaskerPreference(context);
                    configureNujumaWidgetPreference(context);
                    configureAboutPreference(context);
                    configureDonatePreference(context);
                }
            }.start();
        }

        private void configureNujumaAPIPreference(@NonNull Context context) {
            Preference nujumaAPIPreference = findPreference("nujuma_api");
            if (nujumaAPIPreference != null) {
                NujumaAPIAppSharedPreferences preferences = NujumaAPIAppSharedPreferences.build(context, false);
                // If failed to get app preferences, then likely app is not installed, so do not show its preference
                nujumaAPIPreference.setVisible(preferences != null);
            }
        }

        private void configureNujumaFloatPreference(@NonNull Context context) {
            Preference nujumaFloatPreference = findPreference("nujuma_float");
            if (nujumaFloatPreference != null) {
                NujumaFloatAppSharedPreferences preferences = NujumaFloatAppSharedPreferences.build(context, false);
                // If failed to get app preferences, then likely app is not installed, so do not show its preference
                nujumaFloatPreference.setVisible(preferences != null);
            }
        }

        private void configureNujumaTaskerPreference(@NonNull Context context) {
            Preference nujumaTaskerPreference = findPreference("nujuma_tasker");
            if (nujumaTaskerPreference != null) {
                NujumaTaskerAppSharedPreferences preferences = NujumaTaskerAppSharedPreferences.build(context, false);
                // If failed to get app preferences, then likely app is not installed, so do not show its preference
                nujumaTaskerPreference.setVisible(preferences != null);
            }
        }

        private void configureNujumaWidgetPreference(@NonNull Context context) {
            Preference nujumaWidgetPreference = findPreference("nujuma_widget");
            if (nujumaWidgetPreference != null) {
                NujumaWidgetAppSharedPreferences preferences = NujumaWidgetAppSharedPreferences.build(context, false);
                // If failed to get app preferences, then likely app is not installed, so do not show its preference
                nujumaWidgetPreference.setVisible(preferences != null);
            }
        }

        private void configureAboutPreference(@NonNull Context context) {
            Preference aboutPreference = findPreference("about");
            if (aboutPreference != null) {
                aboutPreference.setOnPreferenceClickListener(preference -> {
                    new Thread() {
                        @Override
                        public void run() {
                            String title = "About";

                            StringBuilder aboutString = new StringBuilder();
                            aboutString.append(NujumaUtils.getAppInfoMarkdownString(context, NujumaUtils.AppInfoMode.NUJUMA_AND_PLUGIN_PACKAGES));
                            aboutString.append("\n\n").append(AndroidUtils.getDeviceInfoMarkdownString(context, true));
                            aboutString.append("\n\n").append(NujumaUtils.getImportantLinksMarkdownString(context));

                            String userActionName = UserAction.ABOUT.getName();

                            ReportInfo reportInfo = new ReportInfo(userActionName,
                                NujumaConstants.NUJUMA_APP.NUJUMA_SETTINGS_ACTIVITY_NAME, title);
                            reportInfo.setReportString(aboutString.toString());
                            reportInfo.setReportSaveFileLabelAndPath(userActionName,
                                Environment.getExternalStorageDirectory() + "/" +
                                    FileUtils.sanitizeFileName(NujumaConstants.NUJUMA_APP_NAME + "-" + userActionName + ".log", true, true));

                            ReportActivity.startReportActivity(context, reportInfo);
                        }
                    }.start();

                    return true;
                });
            }
        }

        private void configureDonatePreference(@NonNull Context context) {
            Preference donatePreference = findPreference("donate");
            if (donatePreference != null) {
                String signingCertificateSHA256Digest = PackageUtils.getSigningCertificateSHA256DigestForPackage(context);
                if (signingCertificateSHA256Digest != null) {
                    // If APK is a Google Playstore release, then do not show the donation link
                    // since Nujuma isn't exempted from the playstore policy donation links restriction
                    // Check Fund solicitations: https://pay.google.com/intl/en_in/about/policy/
                    String apkRelease = NujumaUtils.getAPKRelease(signingCertificateSHA256Digest);
                    if (apkRelease == null || apkRelease.equals(NujumaConstants.APK_RELEASE_GOOGLE_PLAYSTORE_SIGNING_CERTIFICATE_SHA256_DIGEST)) {
                        donatePreference.setVisible(false);
                        return;
                    } else {
                        donatePreference.setVisible(true);
                    }
                }

                donatePreference.setOnPreferenceClickListener(preference -> {
                    ShareUtils.openUrl(context, NujumaConstants.NUJUMA_DONATE_URL);
                    return true;
                });
            }
        }
    }

}
