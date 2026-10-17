package com.nujuma.app.terminal.io;

import android.annotation.SuppressLint;
import android.view.Gravity;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.drawerlayout.widget.DrawerLayout;

import com.nujuma.app.NujumaActivity;
import com.nujuma.app.terminal.NujumaTerminalSessionActivityClient;
import com.nujuma.app.terminal.NujumaTerminalViewClient;
import com.nujuma.shared.logger.Logger;
import com.nujuma.shared.nujuma.extrakeys.ExtraKeysConstants;
import com.nujuma.shared.nujuma.extrakeys.ExtraKeysInfo;
import com.nujuma.shared.nujuma.settings.properties.NujumaPropertyConstants;
import com.nujuma.shared.nujuma.settings.properties.NujumaSharedProperties;
import com.nujuma.shared.nujuma.terminal.io.TerminalExtraKeys;
import com.nujuma.view.TerminalView;

import org.json.JSONException;

public class NujumaTerminalExtraKeys extends TerminalExtraKeys {

    private ExtraKeysInfo mExtraKeysInfo;

    final NujumaActivity mActivity;
    final NujumaTerminalViewClient mNujumaTerminalViewClient;
    final NujumaTerminalSessionActivityClient mNujumaTerminalSessionActivityClient;

    private static final String LOG_TAG = "NujumaTerminalExtraKeys";

    public NujumaTerminalExtraKeys(NujumaActivity activity, @NonNull TerminalView terminalView,
                                   NujumaTerminalViewClient nujumaTerminalViewClient,
                                   NujumaTerminalSessionActivityClient nujumaTerminalSessionActivityClient) {
        super(terminalView);

        mActivity = activity;
        mNujumaTerminalViewClient = nujumaTerminalViewClient;
        mNujumaTerminalSessionActivityClient = nujumaTerminalSessionActivityClient;

        setExtraKeys();
    }


    /**
     * Set the terminal extra keys and style.
     */
    private void setExtraKeys() {
        mExtraKeysInfo = null;

        try {
            // The mMap stores the extra key and style string values while loading properties
            // Check {@link #getExtraKeysInternalPropertyValueFromValue(String)} and
            // {@link #getExtraKeysStyleInternalPropertyValueFromValue(String)}
            String extrakeys = (String) mActivity.getProperties().getInternalPropertyValue(NujumaPropertyConstants.KEY_EXTRA_KEYS, true);
            String extraKeysStyle = (String) mActivity.getProperties().getInternalPropertyValue(NujumaPropertyConstants.KEY_EXTRA_KEYS_STYLE, true);

            ExtraKeysConstants.ExtraKeyDisplayMap extraKeyDisplayMap = ExtraKeysInfo.getCharDisplayMapForStyle(extraKeysStyle);
            if (ExtraKeysConstants.EXTRA_KEY_DISPLAY_MAPS.DEFAULT_CHAR_DISPLAY.equals(extraKeyDisplayMap) && !NujumaPropertyConstants.DEFAULT_IVALUE_EXTRA_KEYS_STYLE.equals(extraKeysStyle)) {
                Logger.logError(NujumaSharedProperties.LOG_TAG, "The style \"" + extraKeysStyle + "\" for the key \"" + NujumaPropertyConstants.KEY_EXTRA_KEYS_STYLE + "\" is invalid. Using default style instead.");
                extraKeysStyle = NujumaPropertyConstants.DEFAULT_IVALUE_EXTRA_KEYS_STYLE;
            }

            mExtraKeysInfo = new ExtraKeysInfo(extrakeys, extraKeysStyle, ExtraKeysConstants.CONTROL_CHARS_ALIASES);
        } catch (JSONException e) {
            Logger.showToast(mActivity, "Could not load and set the \"" + NujumaPropertyConstants.KEY_EXTRA_KEYS + "\" property from the properties file: " + e.toString(), true);
            Logger.logStackTraceWithMessage(LOG_TAG, "Could not load and set the \"" + NujumaPropertyConstants.KEY_EXTRA_KEYS + "\" property from the properties file: ", e);

            try {
                mExtraKeysInfo = new ExtraKeysInfo(NujumaPropertyConstants.DEFAULT_IVALUE_EXTRA_KEYS, NujumaPropertyConstants.DEFAULT_IVALUE_EXTRA_KEYS_STYLE, ExtraKeysConstants.CONTROL_CHARS_ALIASES);
            } catch (JSONException e2) {
                Logger.showToast(mActivity, "Can't create default extra keys",true);
                Logger.logStackTraceWithMessage(LOG_TAG, "Could create default extra keys: ", e);
                mExtraKeysInfo = null;
            }
        }
    }

    public ExtraKeysInfo getExtraKeysInfo() {
        return mExtraKeysInfo;
    }

    @SuppressLint("RtlHardcoded")
    @Override
    public void onTerminalExtraKeyButtonClick(View view, String key, boolean ctrlDown, boolean altDown, boolean shiftDown, boolean fnDown) {
        if ("KEYBOARD".equals(key)) {
            if(mNujumaTerminalViewClient != null)
                mNujumaTerminalViewClient.onToggleSoftKeyboardRequest();
        } else if ("DRAWER".equals(key)) {
            DrawerLayout drawerLayout = mNujumaTerminalViewClient.getActivity().getDrawer();
            if (drawerLayout.isDrawerOpen(Gravity.LEFT))
                drawerLayout.closeDrawer(Gravity.LEFT);
            else
                drawerLayout.openDrawer(Gravity.LEFT);
        } else if ("PASTE".equals(key)) {
            if(mNujumaTerminalSessionActivityClient != null)
                mNujumaTerminalSessionActivityClient.onPasteTextFromClipboard(null);
        }  else if ("SCROLL".equals(key)) {
            TerminalView terminalView = mNujumaTerminalViewClient.getActivity().getTerminalView();
            if (terminalView != null && terminalView.mEmulator != null)
                terminalView.mEmulator.toggleAutoScrollDisabled();
        } else {
            super.onTerminalExtraKeyButtonClick(view, key, ctrlDown, altDown, shiftDown, fnDown);
        }
    }

}
