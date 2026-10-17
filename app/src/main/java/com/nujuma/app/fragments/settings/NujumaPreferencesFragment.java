package com.nujuma.app.fragments.settings;

import android.content.Context;
import android.os.Bundle;

import androidx.annotation.Keep;
import androidx.preference.PreferenceDataStore;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceManager;

import com.nujuma.R;
import com.nujuma.shared.nujuma.settings.preferences.NujumaAppSharedPreferences;

@Keep
public class NujumaPreferencesFragment extends PreferenceFragmentCompat {

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        Context context = getContext();
        if (context == null) return;

        PreferenceManager preferenceManager = getPreferenceManager();
        preferenceManager.setPreferenceDataStore(NujumaPreferencesDataStore.getInstance(context));

        setPreferencesFromResource(R.xml.nujuma_preferences, rootKey);
    }

}

class NujumaPreferencesDataStore extends PreferenceDataStore {

    private final Context mContext;
    private final NujumaAppSharedPreferences mPreferences;

    private static NujumaPreferencesDataStore mInstance;

    private NujumaPreferencesDataStore(Context context) {
        mContext = context;
        mPreferences = NujumaAppSharedPreferences.build(context, true);
    }

    public static synchronized NujumaPreferencesDataStore getInstance(Context context) {
        if (mInstance == null) {
            mInstance = new NujumaPreferencesDataStore(context);
        }
        return mInstance;
    }

}
