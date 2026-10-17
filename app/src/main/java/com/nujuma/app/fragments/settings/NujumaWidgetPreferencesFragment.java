package com.nujuma.app.fragments.settings;

import android.content.Context;
import android.os.Bundle;

import androidx.annotation.Keep;
import androidx.preference.PreferenceDataStore;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceManager;

import com.nujuma.R;
import com.nujuma.shared.nujuma.settings.preferences.NujumaWidgetAppSharedPreferences;

@Keep
public class NujumaWidgetPreferencesFragment extends PreferenceFragmentCompat {

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        Context context = getContext();
        if (context == null) return;

        PreferenceManager preferenceManager = getPreferenceManager();
        preferenceManager.setPreferenceDataStore(NujumaWidgetPreferencesDataStore.getInstance(context));

        setPreferencesFromResource(R.xml.nujuma_widget_preferences, rootKey);
    }

}

class NujumaWidgetPreferencesDataStore extends PreferenceDataStore {

    private final Context mContext;
    private final NujumaWidgetAppSharedPreferences mPreferences;

    private static NujumaWidgetPreferencesDataStore mInstance;

    private NujumaWidgetPreferencesDataStore(Context context) {
        mContext = context;
        mPreferences = NujumaWidgetAppSharedPreferences.build(context, true);
    }

    public static synchronized NujumaWidgetPreferencesDataStore getInstance(Context context) {
        if (mInstance == null) {
            mInstance = new NujumaWidgetPreferencesDataStore(context);
        }
        return mInstance;
    }

}
