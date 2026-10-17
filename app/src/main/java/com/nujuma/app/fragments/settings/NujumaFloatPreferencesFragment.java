package com.nujuma.app.fragments.settings;

import android.content.Context;
import android.os.Bundle;

import androidx.annotation.Keep;
import androidx.preference.PreferenceDataStore;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceManager;

import com.nujuma.R;
import com.nujuma.shared.nujuma.settings.preferences.NujumaFloatAppSharedPreferences;

@Keep
public class NujumaFloatPreferencesFragment extends PreferenceFragmentCompat {

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        Context context = getContext();
        if (context == null) return;

        PreferenceManager preferenceManager = getPreferenceManager();
        preferenceManager.setPreferenceDataStore(NujumaFloatPreferencesDataStore.getInstance(context));

        setPreferencesFromResource(R.xml.nujuma_float_preferences, rootKey);
    }

}

class NujumaFloatPreferencesDataStore extends PreferenceDataStore {

    private final Context mContext;
    private final NujumaFloatAppSharedPreferences mPreferences;

    private static NujumaFloatPreferencesDataStore mInstance;

    private NujumaFloatPreferencesDataStore(Context context) {
        mContext = context;
        mPreferences = NujumaFloatAppSharedPreferences.build(context, true);
    }

    public static synchronized NujumaFloatPreferencesDataStore getInstance(Context context) {
        if (mInstance == null) {
            mInstance = new NujumaFloatPreferencesDataStore(context);
        }
        return mInstance;
    }

}
