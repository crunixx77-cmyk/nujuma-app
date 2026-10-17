package com.nujuma.app.fragments.settings;

import android.content.Context;
import android.os.Bundle;

import androidx.annotation.Keep;
import androidx.preference.PreferenceDataStore;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceManager;

import com.nujuma.R;
import com.nujuma.shared.nujuma.settings.preferences.NujumaAPIAppSharedPreferences;

@Keep
public class NujumaAPIPreferencesFragment extends PreferenceFragmentCompat {

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        Context context = getContext();
        if (context == null) return;

        PreferenceManager preferenceManager = getPreferenceManager();
        preferenceManager.setPreferenceDataStore(NujumaAPIPreferencesDataStore.getInstance(context));

        setPreferencesFromResource(R.xml.nujuma_api_preferences, rootKey);
    }

}

class NujumaAPIPreferencesDataStore extends PreferenceDataStore {

    private final Context mContext;
    private final NujumaAPIAppSharedPreferences mPreferences;

    private static NujumaAPIPreferencesDataStore mInstance;

    private NujumaAPIPreferencesDataStore(Context context) {
        mContext = context;
        mPreferences = NujumaAPIAppSharedPreferences.build(context, true);
    }

    public static synchronized NujumaAPIPreferencesDataStore getInstance(Context context) {
        if (mInstance == null) {
            mInstance = new NujumaAPIPreferencesDataStore(context);
        }
        return mInstance;
    }

}
