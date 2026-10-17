package com.nujuma.app.fragments.settings;

import android.content.Context;
import android.os.Bundle;

import androidx.annotation.Keep;
import androidx.preference.PreferenceDataStore;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceManager;

import com.nujuma.R;
import com.nujuma.shared.nujuma.settings.preferences.NujumaTaskerAppSharedPreferences;

@Keep
public class NujumaTaskerPreferencesFragment extends PreferenceFragmentCompat {

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        Context context = getContext();
        if (context == null) return;

        PreferenceManager preferenceManager = getPreferenceManager();
        preferenceManager.setPreferenceDataStore(NujumaTaskerPreferencesDataStore.getInstance(context));

        setPreferencesFromResource(R.xml.nujuma_tasker_preferences, rootKey);
    }

}

class NujumaTaskerPreferencesDataStore extends PreferenceDataStore {

    private final Context mContext;
    private final NujumaTaskerAppSharedPreferences mPreferences;

    private static NujumaTaskerPreferencesDataStore mInstance;

    private NujumaTaskerPreferencesDataStore(Context context) {
        mContext = context;
        mPreferences = NujumaTaskerAppSharedPreferences.build(context, true);
    }

    public static synchronized NujumaTaskerPreferencesDataStore getInstance(Context context) {
        if (mInstance == null) {
            mInstance = new NujumaTaskerPreferencesDataStore(context);
        }
        return mInstance;
    }

}
