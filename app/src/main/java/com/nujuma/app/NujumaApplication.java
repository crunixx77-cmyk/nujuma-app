package com.nujuma.app;

import android.app.Application;
import android.content.Context;

import com.nujuma.BuildConfig;
import com.nujuma.shared.errors.Error;
import com.nujuma.shared.logger.Logger;
import com.nujuma.shared.nujuma.NujumaBootstrap;
import com.nujuma.shared.nujuma.NujumaConstants;
import com.nujuma.shared.nujuma.crash.NujumaCrashUtils;
import com.nujuma.shared.nujuma.file.NujumaFileUtils;
import com.nujuma.shared.nujuma.settings.preferences.NujumaAppSharedPreferences;
import com.nujuma.shared.nujuma.settings.properties.NujumaAppSharedProperties;
import com.nujuma.shared.nujuma.shell.command.environment.NujumaShellEnvironment;
import com.nujuma.shared.nujuma.shell.am.NujumaAmSocketServer;
import com.nujuma.shared.nujuma.shell.NujumaShellManager;
import com.nujuma.shared.nujuma.theme.NujumaThemeUtils;

public class NujumaApplication extends Application {

    private static final String LOG_TAG = "NujumaApplication";

    public void onCreate() {
        super.onCreate();

        Context context = getApplicationContext();

        // Set crash handler for the app
        NujumaCrashUtils.setDefaultCrashHandler(this);

        // Set log config for the app
        setLogConfig(context);

        Logger.logDebug("Starting Application");

        // Set NujumaBootstrap.NUJUMA_APP_PACKAGE_MANAGER and NujumaBootstrap.NUJUMA_APP_PACKAGE_VARIANT
        NujumaBootstrap.setNujumaPackageManagerAndVariant(BuildConfig.NUJUMA_PACKAGE_VARIANT);

        // Init app wide SharedProperties loaded from nujuma.properties
        NujumaAppSharedProperties properties = NujumaAppSharedProperties.init(context);

        // Init app wide shell manager
        NujumaShellManager shellManager = NujumaShellManager.init(context);

        // Set NightMode.APP_NIGHT_MODE
        NujumaThemeUtils.setAppNightMode(properties.getNightMode());

        // Check and create nujuma files directory. If failed to access it like in case of secondary
        // user or external sd card installation, then don't run files directory related code
        Error error = NujumaFileUtils.isNujumaFilesDirectoryAccessible(this, true, true);
        boolean isNujumaFilesDirectoryAccessible = error == null;
        if (isNujumaFilesDirectoryAccessible) {
            Logger.logInfo(LOG_TAG, "Nujuma files directory is accessible");
            /*
            error = NujumaFileUtils.isAppsNujumaAppDirectoryAccessible(true, true);
            if (error != null) {
                Logger.logErrorExtended(LOG_TAG, "Create apps/nujuma-app directory failed\n" + error);
                return;
            }

            // Setup nujuma-am-socket server
            NujumaAmSocketServer.setupNujumaAmSocketServer(context);
             */
        } else {
            Logger.logErrorExtended(LOG_TAG, "Nujuma files directory is not accessible\n" + error);
        }

        // Init NujumaShellEnvironment constants and caches after everything has been setup including nujuma-am-socket server
        NujumaShellEnvironment.init(this);

        if (isNujumaFilesDirectoryAccessible) {
            NujumaShellEnvironment.writeEnvironmentToFile(this);
        }
    }

    public static void setLogConfig(Context context) {
        Logger.setDefaultLogTag(NujumaConstants.NUJUMA_APP_NAME);

        // Load the log level from shared preferences and set it to the {@link Logger.CURRENT_LOG_LEVEL}
        NujumaAppSharedPreferences preferences = NujumaAppSharedPreferences.build(context);
        if (preferences == null) return;
        preferences.setLogLevel(null, preferences.getLogLevel());
    }

}
