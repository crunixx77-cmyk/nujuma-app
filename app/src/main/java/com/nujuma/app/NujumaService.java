package com.nujuma.app;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.net.wifi.WifiManager;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.PowerManager;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.nujuma.R;
import com.nujuma.app.event.SystemEventReceiver;
import com.nujuma.app.terminal.NujumaTerminalSessionActivityClient;
import com.nujuma.app.terminal.NujumaTerminalSessionServiceClient;
import com.nujuma.shared.nujuma.plugins.NujumaPluginUtils;
import com.nujuma.shared.data.IntentUtils;
import com.nujuma.shared.net.uri.UriUtils;
import com.nujuma.shared.errors.Errno;
import com.nujuma.shared.shell.ShellUtils;
import com.nujuma.shared.shell.command.runner.app.AppShell;
import com.nujuma.shared.nujuma.settings.properties.NujumaAppSharedProperties;
import com.nujuma.shared.nujuma.shell.command.environment.NujumaShellEnvironment;
import com.nujuma.shared.nujuma.shell.NujumaShellUtils;
import com.nujuma.shared.nujuma.NujumaConstants;
import com.nujuma.shared.nujuma.NujumaConstants.NUJUMA_APP.NUJUMA_ACTIVITY;
import com.nujuma.shared.nujuma.NujumaConstants.NUJUMA_APP.NUJUMA_SERVICE;
import com.nujuma.shared.nujuma.settings.preferences.NujumaAppSharedPreferences;
import com.nujuma.shared.nujuma.shell.NujumaShellManager;
import com.nujuma.shared.nujuma.shell.command.runner.terminal.NujumaSession;
import com.nujuma.shared.nujuma.terminal.NujumaTerminalSessionClientBase;
import com.nujuma.shared.logger.Logger;
import com.nujuma.shared.notification.NotificationUtils;
import com.nujuma.shared.android.PermissionUtils;
import com.nujuma.shared.data.DataUtils;
import com.nujuma.shared.shell.command.ExecutionCommand;
import com.nujuma.shared.shell.command.ExecutionCommand.Runner;
import com.nujuma.shared.shell.command.ExecutionCommand.ShellCreateMode;
import com.nujuma.terminal.TerminalEmulator;
import com.nujuma.terminal.TerminalSession;
import com.nujuma.terminal.TerminalSessionClient;

import java.util.ArrayList;
import java.util.List;

/**
 * A service holding a list of {@link NujumaSession} in {@link NujumaShellManager#mNujumaSessions} and background {@link AppShell}
 * in {@link NujumaShellManager#mNujumaTasks}, showing a foreground notification while running so that it is not terminated.
 * The user interacts with the session through {@link NujumaActivity}, but this service may outlive
 * the activity when the user or the system disposes of the activity. In that case the user may
 * restart {@link NujumaActivity} later to yet again access the sessions.
 * <p/>
 * In order to keep both terminal sessions and spawned processes (who may outlive the terminal sessions) alive as long
 * as wanted by the user this service is a foreground service, {@link Service#startForeground(int, Notification)}.
 * <p/>
 * Optionally may hold a wake and a wifi lock, in which case that is shown in the notification - see
 * {@link #buildNotification()}.
 */
public final class NujumaService extends Service implements AppShell.AppShellClient, NujumaSession.NujumaSessionClient {

    /** This service is only bound from inside the same process and never uses IPC. */
    class LocalBinder extends Binder {
        public final NujumaService service = NujumaService.this;
    }

    private final IBinder mBinder = new LocalBinder();

    private final Handler mHandler = new Handler();


    /** The full implementation of the {@link TerminalSessionClient} interface to be used by {@link TerminalSession}
     * that holds activity references for activity related functions.
     * Note that the service may often outlive the activity, so need to clear this reference.
     */
    private NujumaTerminalSessionActivityClient mNujumaTerminalSessionActivityClient;

    /** The basic implementation of the {@link TerminalSessionClient} interface to be used by {@link TerminalSession}
     * that does not hold activity references and only a service reference.
     */
    private final NujumaTerminalSessionServiceClient mNujumaTerminalSessionServiceClient = new NujumaTerminalSessionServiceClient(this);

    /**
     * Nujuma app shared properties manager, loaded from nujuma.properties
     */
    private NujumaAppSharedProperties mProperties;

    /**
     * Nujuma app shell manager
     */
    private NujumaShellManager mShellManager;

    /** The wake lock and wifi lock are always acquired and released together. */
    private PowerManager.WakeLock mWakeLock;
    private WifiManager.WifiLock mWifiLock;

    /** If the user has executed the {@link NUJUMA_SERVICE#ACTION_STOP_SERVICE} intent. */
    boolean mWantsToStop = false;

    private static final String LOG_TAG = "NujumaService";

    @Override
    public void onCreate() {
        Logger.logVerbose(LOG_TAG, "onCreate");

        // Get Nujuma app SharedProperties without loading from disk since NujumaApplication handles
        // load and NujumaActivity handles reloads
        mProperties = NujumaAppSharedProperties.getProperties();

        mShellManager = NujumaShellManager.getShellManager();

        runStartForeground();

        SystemEventReceiver.registerPackageUpdateEvents(this);
    }

    @SuppressLint("Wakelock")
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Logger.logDebug(LOG_TAG, "onStartCommand");

        // Run again in case service is already started and onCreate() is not called
        runStartForeground();

        String action = null;
        if (intent != null) {
            Logger.logVerboseExtended(LOG_TAG, "Intent Received:\n" + IntentUtils.getIntentString(intent));
            action = intent.getAction();
        }

        if (action != null) {
            switch (action) {
                case NUJUMA_SERVICE.ACTION_STOP_SERVICE:
                    Logger.logDebug(LOG_TAG, "ACTION_STOP_SERVICE intent received");
                    actionStopService();
                    break;
                case NUJUMA_SERVICE.ACTION_WAKE_LOCK:
                    Logger.logDebug(LOG_TAG, "ACTION_WAKE_LOCK intent received");
                    actionAcquireWakeLock();
                    break;
                case NUJUMA_SERVICE.ACTION_WAKE_UNLOCK:
                    Logger.logDebug(LOG_TAG, "ACTION_WAKE_UNLOCK intent received");
                    actionReleaseWakeLock(true);
                    break;
                case NUJUMA_SERVICE.ACTION_SERVICE_EXECUTE:
                    Logger.logDebug(LOG_TAG, "ACTION_SERVICE_EXECUTE intent received");
                    actionServiceExecute(intent);
                    break;
                default:
                    Logger.logError(LOG_TAG, "Invalid action: \"" + action + "\"");
                    break;
            }
        }

        // If this service really do get killed, there is no point restarting it automatically - let the user do on next
        // start of {@link Term):
        return Service.START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        Logger.logVerbose(LOG_TAG, "onDestroy");

        NujumaShellUtils.clearNujumaTMPDIR(true);

        actionReleaseWakeLock(false);
        if (!mWantsToStop)
            killAllNujumaExecutionCommands();

        NujumaShellManager.onAppExit(this);

        SystemEventReceiver.unregisterPackageUpdateEvents(this);

        runStopForeground();
    }

    @Override
    public IBinder onBind(Intent intent) {
        Logger.logVerbose(LOG_TAG, "onBind");
        return mBinder;
    }

    @Override
    public boolean onUnbind(Intent intent) {
        Logger.logVerbose(LOG_TAG, "onUnbind");

        // Since we cannot rely on {@link NujumaActivity.onDestroy()} to always complete,
        // we unset clients here as well if it failed, so that we do not leave service and session
        // clients with references to the activity.
        if (mNujumaTerminalSessionActivityClient != null)
            unsetNujumaTerminalSessionClient();
        return false;
    }

    /** Make service run in foreground mode. */
    private void runStartForeground() {
        setupNotificationChannel();
        startForeground(NujumaConstants.NUJUMA_APP_NOTIFICATION_ID, buildNotification());
    }

    /** Make service leave foreground mode. */
    private void runStopForeground() {
        stopForeground(true);
    }

    /** Request to stop service. */
    private void requestStopService() {
        Logger.logDebug(LOG_TAG, "Requesting to stop service");
        runStopForeground();
        stopSelf();
    }

    /** Process action to stop service. */
    private void actionStopService() {
        mWantsToStop = true;
        killAllNujumaExecutionCommands();
        requestStopService();
    }

    /** Kill all NujumaSessions and NujumaTasks by sending SIGKILL to their processes.
     *
     * For NujumaSessions, all sessions will be killed, whether user manually exited Nujuma or if
     * onDestroy() was directly called because of unintended shutdown. The processing of results
     * will only be done if user manually exited nujuma or if the session was started by a plugin
     * which **expects** the result back via a pending intent.
     *
     * For NujumaTasks, only tasks that were started by a plugin which **expects** the result
     * back via a pending intent will be killed, whether user manually exited Nujuma or if
     * onDestroy() was directly called because of unintended shutdown. The processing of results
     * will always be done for the tasks that are killed. The remaining processes will keep on
     * running until the nujuma app process is killed by android, like by OOM, so we let them run
     * as long as they can.
     *
     * Some plugin execution commands may not have been processed and added to mNujumaSessions and
     * mNujumaTasks lists before the service is killed, so we maintain a separate
     * mPendingPluginExecutionCommands list for those, so that we can notify the pending intent
     * creators that execution was cancelled.
     *
     * Note that if user didn't manually exit Nujuma and if onDestroy() was directly called because
     * of unintended shutdown, like android deciding to kill the service, then there will be no
     * guarantee that onDestroy() will be allowed to finish and nujuma app process may be killed before
     * it has finished. This means that in those cases some results may not be sent back to their
     * creators for plugin commands but we still try to process whatever results can be processed
     * despite the unreliable behaviour of onDestroy().
     *
     * Note that if don't kill the processes started by plugins which **expect** the result back
     * and notify their creators that they have been killed, then they may get stuck waiting for
     * the results forever like in case of commands started by Nujuma:Tasker or RUN_COMMAND intent,
     * since once NujumaService has been killed, no result will be sent back. They may still get
     * stuck if nujuma app process gets killed, so for this case reasonable timeout values should
     * be used, like in Tasker for the Nujuma:Tasker actions.
     *
     * We make copies of each list since items are removed inside the loop.
     */
    private synchronized void killAllNujumaExecutionCommands() {
        boolean processResult;

        Logger.logDebug(LOG_TAG, "Killing NujumaSessions=" + mShellManager.mNujumaSessions.size() +
            ", NujumaTasks=" + mShellManager.mNujumaTasks.size() +
            ", PendingPluginExecutionCommands=" + mShellManager.mPendingPluginExecutionCommands.size());

        List<NujumaSession> nujumaSessions = new ArrayList<>(mShellManager.mNujumaSessions);
        List<AppShell> nujumaTasks = new ArrayList<>(mShellManager.mNujumaTasks);
        List<ExecutionCommand> pendingPluginExecutionCommands = new ArrayList<>(mShellManager.mPendingPluginExecutionCommands);

        for (int i = 0; i < nujumaSessions.size(); i++) {
            ExecutionCommand executionCommand = nujumaSessions.get(i).getExecutionCommand();
            processResult = mWantsToStop || executionCommand.isPluginExecutionCommandWithPendingResult();
            nujumaSessions.get(i).killIfExecuting(this, processResult);
            if (!processResult)
                mShellManager.mNujumaSessions.remove(nujumaSessions.get(i));
        }


        for (int i = 0; i < nujumaTasks.size(); i++) {
            ExecutionCommand executionCommand = nujumaTasks.get(i).getExecutionCommand();
            if (executionCommand.isPluginExecutionCommandWithPendingResult())
                nujumaTasks.get(i).killIfExecuting(this, true);
            else
                mShellManager.mNujumaTasks.remove(nujumaTasks.get(i));
        }

        for (int i = 0; i < pendingPluginExecutionCommands.size(); i++) {
            ExecutionCommand executionCommand = pendingPluginExecutionCommands.get(i);
            if (!executionCommand.shouldNotProcessResults() && executionCommand.isPluginExecutionCommandWithPendingResult()) {
                if (executionCommand.setStateFailed(Errno.ERRNO_CANCELLED.getCode(), this.getString(com.nujuma.shared.R.string.error_execution_cancelled))) {
                    NujumaPluginUtils.processPluginExecutionCommandResult(this, LOG_TAG, executionCommand);
                }
            }
        }
    }



    /** Process action to acquire Power and Wi-Fi WakeLocks. */
    @SuppressLint({"WakelockTimeout", "BatteryLife"})
    private void actionAcquireWakeLock() {
        if (mWakeLock != null) {
            Logger.logDebug(LOG_TAG, "Ignoring acquiring WakeLocks since they are already held");
            return;
        }

        Logger.logDebug(LOG_TAG, "Acquiring WakeLocks");

        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        mWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, NujumaConstants.NUJUMA_APP_NAME.toLowerCase() + ":service-wakelock");
        mWakeLock.acquire();

        // http://tools.android.com/tech-docs/lint-in-studio-2-3#TOC-WifiManager-Leak
        WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        mWifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, NujumaConstants.NUJUMA_APP_NAME.toLowerCase());
        mWifiLock.acquire();

        if (!PermissionUtils.checkIfBatteryOptimizationsDisabled(this)) {
            PermissionUtils.requestDisableBatteryOptimizations(this);
        }

        updateNotification();

        Logger.logDebug(LOG_TAG, "WakeLocks acquired successfully");

    }

    /** Process action to release Power and Wi-Fi WakeLocks. */
    private void actionReleaseWakeLock(boolean updateNotification) {
        if (mWakeLock == null && mWifiLock == null) {
            Logger.logDebug(LOG_TAG, "Ignoring releasing WakeLocks since none are already held");
            return;
        }

        Logger.logDebug(LOG_TAG, "Releasing WakeLocks");

        if (mWakeLock != null) {
            mWakeLock.release();
            mWakeLock = null;
        }

        if (mWifiLock != null) {
            mWifiLock.release();
            mWifiLock = null;
        }

        if (updateNotification)
            updateNotification();

        Logger.logDebug(LOG_TAG, "WakeLocks released successfully");
    }

    /** Process {@link NUJUMA_SERVICE#ACTION_SERVICE_EXECUTE} intent to execute a shell command in
     * a foreground NujumaSession or in a background NujumaTask. */
    private void actionServiceExecute(Intent intent) {
        if (intent == null) {
            Logger.logError(LOG_TAG, "Ignoring null intent to actionServiceExecute");
            return;
        }

        ExecutionCommand executionCommand = new ExecutionCommand(NujumaShellManager.getNextShellId());

        executionCommand.executableUri = intent.getData();
        executionCommand.isPluginExecutionCommand = true;

        // If EXTRA_RUNNER is passed, use that, otherwise check EXTRA_BACKGROUND and default to Runner.TERMINAL_SESSION
        executionCommand.runner = IntentUtils.getStringExtraIfSet(intent, NUJUMA_SERVICE.EXTRA_RUNNER,
            (intent.getBooleanExtra(NUJUMA_SERVICE.EXTRA_BACKGROUND, false) ? Runner.APP_SHELL.getName() : Runner.TERMINAL_SESSION.getName()));
        if (Runner.runnerOf(executionCommand.runner) == null) {
            String errmsg = this.getString(R.string.error_nujuma_service_invalid_execution_command_runner, executionCommand.runner);
            executionCommand.setStateFailed(Errno.ERRNO_FAILED.getCode(), errmsg);
            NujumaPluginUtils.processPluginExecutionCommandError(this, LOG_TAG, executionCommand, false);
            return;
        }

        if (executionCommand.executableUri != null) {
            Logger.logVerbose(LOG_TAG, "uri: \"" + executionCommand.executableUri + "\", path: \"" + executionCommand.executableUri.getPath() + "\", fragment: \"" + executionCommand.executableUri.getFragment() + "\"");

            // Get full path including fragment (anything after last "#")
            executionCommand.executable = UriUtils.getUriFilePathWithFragment(executionCommand.executableUri);
            executionCommand.arguments = IntentUtils.getStringArrayExtraIfSet(intent, NUJUMA_SERVICE.EXTRA_ARGUMENTS, null);
            if (Runner.APP_SHELL.equalsRunner(executionCommand.runner))
                executionCommand.stdin = IntentUtils.getStringExtraIfSet(intent, NUJUMA_SERVICE.EXTRA_STDIN, null);
            executionCommand.backgroundCustomLogLevel = IntentUtils.getIntegerExtraIfSet(intent, NUJUMA_SERVICE.EXTRA_BACKGROUND_CUSTOM_LOG_LEVEL, null);
        }

        executionCommand.workingDirectory = IntentUtils.getStringExtraIfSet(intent, NUJUMA_SERVICE.EXTRA_WORKDIR, null);
        executionCommand.isFailsafe = intent.getBooleanExtra(NUJUMA_ACTIVITY.EXTRA_FAILSAFE_SESSION, false);
        executionCommand.sessionAction = intent.getStringExtra(NUJUMA_SERVICE.EXTRA_SESSION_ACTION);
        executionCommand.shellName = IntentUtils.getStringExtraIfSet(intent, NUJUMA_SERVICE.EXTRA_SHELL_NAME, null);
        executionCommand.shellCreateMode = IntentUtils.getStringExtraIfSet(intent, NUJUMA_SERVICE.EXTRA_SHELL_CREATE_MODE, null);
        executionCommand.commandLabel = IntentUtils.getStringExtraIfSet(intent, NUJUMA_SERVICE.EXTRA_COMMAND_LABEL, "Execution Intent Command");
        executionCommand.commandDescription = IntentUtils.getStringExtraIfSet(intent, NUJUMA_SERVICE.EXTRA_COMMAND_DESCRIPTION, null);
        executionCommand.commandHelp = IntentUtils.getStringExtraIfSet(intent, NUJUMA_SERVICE.EXTRA_COMMAND_HELP, null);
        executionCommand.pluginAPIHelp = IntentUtils.getStringExtraIfSet(intent, NUJUMA_SERVICE.EXTRA_PLUGIN_API_HELP, null);
        executionCommand.resultConfig.resultPendingIntent = intent.getParcelableExtra(NUJUMA_SERVICE.EXTRA_PENDING_INTENT);
        executionCommand.resultConfig.resultDirectoryPath = IntentUtils.getStringExtraIfSet(intent, NUJUMA_SERVICE.EXTRA_RESULT_DIRECTORY, null);
        if (executionCommand.resultConfig.resultDirectoryPath != null) {
            executionCommand.resultConfig.resultSingleFile = intent.getBooleanExtra(NUJUMA_SERVICE.EXTRA_RESULT_SINGLE_FILE, false);
            executionCommand.resultConfig.resultFileBasename = IntentUtils.getStringExtraIfSet(intent, NUJUMA_SERVICE.EXTRA_RESULT_FILE_BASENAME, null);
            executionCommand.resultConfig.resultFileOutputFormat = IntentUtils.getStringExtraIfSet(intent, NUJUMA_SERVICE.EXTRA_RESULT_FILE_OUTPUT_FORMAT, null);
            executionCommand.resultConfig.resultFileErrorFormat = IntentUtils.getStringExtraIfSet(intent, NUJUMA_SERVICE.EXTRA_RESULT_FILE_ERROR_FORMAT, null);
            executionCommand.resultConfig.resultFilesSuffix = IntentUtils.getStringExtraIfSet(intent, NUJUMA_SERVICE.EXTRA_RESULT_FILES_SUFFIX, null);
        }

        if (executionCommand.shellCreateMode == null)
            executionCommand.shellCreateMode = ShellCreateMode.ALWAYS.getMode();

        // Add the execution command to pending plugin execution commands list
        mShellManager.mPendingPluginExecutionCommands.add(executionCommand);

        if (Runner.APP_SHELL.equalsRunner(executionCommand.runner))
            executeNujumaTaskCommand(executionCommand);
        else if (Runner.TERMINAL_SESSION.equalsRunner(executionCommand.runner))
            executeNujumaSessionCommand(executionCommand);
        else {
            String errmsg = getString(R.string.error_nujuma_service_unsupported_execution_command_runner, executionCommand.runner);
            executionCommand.setStateFailed(Errno.ERRNO_FAILED.getCode(), errmsg);
            NujumaPluginUtils.processPluginExecutionCommandError(this, LOG_TAG, executionCommand, false);
        }
    }





    /** Execute a shell command in background NujumaTask. */
    private void executeNujumaTaskCommand(ExecutionCommand executionCommand) {
        if (executionCommand == null) return;

        Logger.logDebug(LOG_TAG, "Executing background \"" + executionCommand.getCommandIdAndLabelLogString() + "\" NujumaTask command");

        // Transform executable path to shell/session name, e.g. "/bin/do-something.sh" => "do-something.sh".
        if (executionCommand.shellName == null && executionCommand.executable != null)
            executionCommand.shellName = ShellUtils.getExecutableBasename(executionCommand.executable);

        AppShell newNujumaTask = null;
        ShellCreateMode shellCreateMode = processShellCreateMode(executionCommand);
        if (shellCreateMode == null) return;
        if (ShellCreateMode.NO_SHELL_WITH_NAME.equals(shellCreateMode)) {
            newNujumaTask = getNujumaTaskForShellName(executionCommand.shellName);
            if (newNujumaTask != null)
                Logger.logVerbose(LOG_TAG, "Existing NujumaTask with \"" + executionCommand.shellName + "\" shell name found for shell create mode \"" + shellCreateMode.getMode() + "\"");
            else
                Logger.logVerbose(LOG_TAG, "No existing NujumaTask with \"" + executionCommand.shellName + "\" shell name found for shell create mode \"" + shellCreateMode.getMode() + "\"");
        }

        if (newNujumaTask == null)
            newNujumaTask = createNujumaTask(executionCommand);
    }

    /** Create a NujumaTask. */
    @Nullable
    public AppShell createNujumaTask(String executablePath, String[] arguments, String stdin, String workingDirectory) {
        return createNujumaTask(new ExecutionCommand(NujumaShellManager.getNextShellId(), executablePath,
            arguments, stdin, workingDirectory, Runner.APP_SHELL.getName(), false));
    }

    /** Create a NujumaTask. */
    @Nullable
    public synchronized AppShell createNujumaTask(ExecutionCommand executionCommand) {
        if (executionCommand == null) return null;

        Logger.logDebug(LOG_TAG, "Creating \"" + executionCommand.getCommandIdAndLabelLogString() + "\" NujumaTask");

        if (!Runner.APP_SHELL.equalsRunner(executionCommand.runner)) {
            Logger.logDebug(LOG_TAG, "Ignoring wrong runner \"" + executionCommand.runner + "\" command passed to createNujumaTask()");
            return null;
        }

        executionCommand.setShellCommandShellEnvironment = true;

        if (Logger.getLogLevel() >= Logger.LOG_LEVEL_VERBOSE)
            Logger.logVerboseExtended(LOG_TAG, executionCommand.toString());

        AppShell newNujumaTask = AppShell.execute(this, executionCommand, this,
            new NujumaShellEnvironment(), null,false);
        if (newNujumaTask == null) {
            Logger.logError(LOG_TAG, "Failed to execute new NujumaTask command for:\n" + executionCommand.getCommandIdAndLabelLogString());
            // If the execution command was started for a plugin, then process the error
            if (executionCommand.isPluginExecutionCommand)
                NujumaPluginUtils.processPluginExecutionCommandError(this, LOG_TAG, executionCommand, false);
            else {
                Logger.logError(LOG_TAG, "Set log level to debug or higher to see error in logs");
                Logger.logErrorPrivateExtended(LOG_TAG, executionCommand.toString());
            }
            return null;
        }

        mShellManager.mNujumaTasks.add(newNujumaTask);

        // Remove the execution command from the pending plugin execution commands list since it has
        // now been processed
        if (executionCommand.isPluginExecutionCommand)
            mShellManager.mPendingPluginExecutionCommands.remove(executionCommand);

        updateNotification();

        return newNujumaTask;
    }

    /** Callback received when a NujumaTask finishes. */
    @Override
    public void onAppShellExited(final AppShell nujumaTask) {
        mHandler.post(() -> {
            if (nujumaTask != null) {
                ExecutionCommand executionCommand = nujumaTask.getExecutionCommand();

                Logger.logVerbose(LOG_TAG, "The onNujumaTaskExited() callback called for \"" + executionCommand.getCommandIdAndLabelLogString() + "\" NujumaTask command");

                // If the execution command was started for a plugin, then process the results
                if (executionCommand != null && executionCommand.isPluginExecutionCommand)
                    NujumaPluginUtils.processPluginExecutionCommandResult(this, LOG_TAG, executionCommand);

                mShellManager.mNujumaTasks.remove(nujumaTask);
            }

            updateNotification();
        });
    }





    /** Execute a shell command in a foreground {@link NujumaSession}. */
    private void executeNujumaSessionCommand(ExecutionCommand executionCommand) {
        if (executionCommand == null) return;

        Logger.logDebug(LOG_TAG, "Executing foreground \"" + executionCommand.getCommandIdAndLabelLogString() + "\" NujumaSession command");

        // Transform executable path to shell/session name, e.g. "/bin/do-something.sh" => "do-something.sh".
        if (executionCommand.shellName == null && executionCommand.executable != null)
            executionCommand.shellName = ShellUtils.getExecutableBasename(executionCommand.executable);

        NujumaSession newNujumaSession = null;
        ShellCreateMode shellCreateMode = processShellCreateMode(executionCommand);
        if (shellCreateMode == null) return;
        if (ShellCreateMode.NO_SHELL_WITH_NAME.equals(shellCreateMode)) {
            newNujumaSession = getNujumaSessionForShellName(executionCommand.shellName);
            if (newNujumaSession != null)
                Logger.logVerbose(LOG_TAG, "Existing NujumaSession with \"" + executionCommand.shellName + "\" shell name found for shell create mode \"" + shellCreateMode.getMode() + "\"");
            else
                Logger.logVerbose(LOG_TAG, "No existing NujumaSession with \"" + executionCommand.shellName + "\" shell name found for shell create mode \"" + shellCreateMode.getMode() + "\"");
        }

        if (newNujumaSession == null)
            newNujumaSession = createNujumaSession(executionCommand);
        if (newNujumaSession == null) return;

        handleSessionAction(DataUtils.getIntFromString(executionCommand.sessionAction,
            NUJUMA_SERVICE.VALUE_EXTRA_SESSION_ACTION_SWITCH_TO_NEW_SESSION_AND_OPEN_ACTIVITY),
            newNujumaSession.getTerminalSession());
    }

    /**
     * Create a {@link NujumaSession}.
     * Currently called by {@link NujumaTerminalSessionActivityClient#addNewSession(boolean, String)} to add a new {@link NujumaSession}.
     */
    @Nullable
    public NujumaSession createNujumaSession(String executablePath, String[] arguments, String stdin,
                                             String workingDirectory, boolean isFailSafe, String sessionName) {
        ExecutionCommand executionCommand = new ExecutionCommand(NujumaShellManager.getNextShellId(),
            executablePath, arguments, stdin, workingDirectory, Runner.TERMINAL_SESSION.getName(), isFailSafe);
        executionCommand.shellName = sessionName;
        return createNujumaSession(executionCommand);
    }

    /** Create a {@link NujumaSession}. */
    @Nullable
    public synchronized NujumaSession createNujumaSession(ExecutionCommand executionCommand) {
        if (executionCommand == null) return null;

        Logger.logDebug(LOG_TAG, "Creating \"" + executionCommand.getCommandIdAndLabelLogString() + "\" NujumaSession");

        if (!Runner.TERMINAL_SESSION.equalsRunner(executionCommand.runner)) {
            Logger.logDebug(LOG_TAG, "Ignoring wrong runner \"" + executionCommand.runner + "\" command passed to createNujumaSession()");
            return null;
        }

        executionCommand.setShellCommandShellEnvironment = true;
        executionCommand.terminalTranscriptRows = mProperties.getTerminalTranscriptRows();

        if (Logger.getLogLevel() >= Logger.LOG_LEVEL_VERBOSE)
            Logger.logVerboseExtended(LOG_TAG, executionCommand.toString());

        // If the execution command was started for a plugin, only then will the stdout be set
        // Otherwise if command was manually started by the user like by adding a new terminal session,
        // then no need to set stdout
        NujumaSession newNujumaSession = NujumaSession.execute(this, executionCommand, getNujumaTerminalSessionClient(),
            this, new NujumaShellEnvironment(), null, executionCommand.isPluginExecutionCommand);
        if (newNujumaSession == null) {
            Logger.logError(LOG_TAG, "Failed to execute new NujumaSession command for:\n" + executionCommand.getCommandIdAndLabelLogString());
            // If the execution command was started for a plugin, then process the error
            if (executionCommand.isPluginExecutionCommand)
                NujumaPluginUtils.processPluginExecutionCommandError(this, LOG_TAG, executionCommand, false);
            else {
                Logger.logError(LOG_TAG, "Set log level to debug or higher to see error in logs");
                Logger.logErrorPrivateExtended(LOG_TAG, executionCommand.toString());
            }
            return null;
        }

        mShellManager.mNujumaSessions.add(newNujumaSession);

        // Remove the execution command from the pending plugin execution commands list since it has
        // now been processed
        if (executionCommand.isPluginExecutionCommand)
            mShellManager.mPendingPluginExecutionCommands.remove(executionCommand);

        // Notify {@link NujumaSessionsListViewController} that sessions list has been updated if
        // activity in is foreground
        if (mNujumaTerminalSessionActivityClient != null)
            mNujumaTerminalSessionActivityClient.nujumaSessionListNotifyUpdated();

        updateNotification();

        // No need to recreate the activity since it likely just started and theme should already have applied
        NujumaActivity.updateNujumaActivityStyling(this, false);

        return newNujumaSession;
    }

    /** Remove a NujumaSession. */
    public synchronized int removeNujumaSession(TerminalSession sessionToRemove) {
        int index = getIndexOfSession(sessionToRemove);

        if (index >= 0)
            mShellManager.mNujumaSessions.get(index).finish();

        return index;
    }

    /** Callback received when a {@link NujumaSession} finishes. */
    @Override
    public void onNujumaSessionExited(final NujumaSession nujumaSession) {
        if (nujumaSession != null) {
            ExecutionCommand executionCommand = nujumaSession.getExecutionCommand();

            Logger.logVerbose(LOG_TAG, "The onNujumaSessionExited() callback called for \"" + executionCommand.getCommandIdAndLabelLogString() + "\" NujumaSession command");

            // If the execution command was started for a plugin, then process the results
            if (executionCommand != null && executionCommand.isPluginExecutionCommand)
                NujumaPluginUtils.processPluginExecutionCommandResult(this, LOG_TAG, executionCommand);

            mShellManager.mNujumaSessions.remove(nujumaSession);

            // Notify {@link NujumaSessionsListViewController} that sessions list has been updated if
            // activity in is foreground
            if (mNujumaTerminalSessionActivityClient != null)
                mNujumaTerminalSessionActivityClient.nujumaSessionListNotifyUpdated();
        }

        updateNotification();
    }





    private ShellCreateMode processShellCreateMode(@NonNull ExecutionCommand executionCommand) {
        if (ShellCreateMode.ALWAYS.equalsMode(executionCommand.shellCreateMode))
            return ShellCreateMode.ALWAYS; // Default
        else if (ShellCreateMode.NO_SHELL_WITH_NAME.equalsMode(executionCommand.shellCreateMode))
            if (DataUtils.isNullOrEmpty(executionCommand.shellName)) {
                NujumaPluginUtils.setAndProcessPluginExecutionCommandError(this, LOG_TAG, executionCommand, false,
                    getString(R.string.error_nujuma_service_execution_command_shell_name_unset, executionCommand.shellCreateMode));
                return null;
            } else {
               return ShellCreateMode.NO_SHELL_WITH_NAME;
            }
        else {
            NujumaPluginUtils.setAndProcessPluginExecutionCommandError(this, LOG_TAG, executionCommand, false,
                getString(R.string.error_nujuma_service_unsupported_execution_command_shell_create_mode, executionCommand.shellCreateMode));
            return null;
        }
    }

    /** Process session action for new session. */
    private void handleSessionAction(int sessionAction, TerminalSession newTerminalSession) {
        Logger.logDebug(LOG_TAG, "Processing sessionAction \"" + sessionAction + "\" for session \"" + newTerminalSession.mSessionName + "\"");

        switch (sessionAction) {
            case NUJUMA_SERVICE.VALUE_EXTRA_SESSION_ACTION_SWITCH_TO_NEW_SESSION_AND_OPEN_ACTIVITY:
                setCurrentStoredTerminalSession(newTerminalSession);
                if (mNujumaTerminalSessionActivityClient != null)
                    mNujumaTerminalSessionActivityClient.setCurrentSession(newTerminalSession);
                startNujumaActivity();
                break;
            case NUJUMA_SERVICE.VALUE_EXTRA_SESSION_ACTION_KEEP_CURRENT_SESSION_AND_OPEN_ACTIVITY:
                if (getNujumaSessionsSize() == 1)
                    setCurrentStoredTerminalSession(newTerminalSession);
                startNujumaActivity();
                break;
            case NUJUMA_SERVICE.VALUE_EXTRA_SESSION_ACTION_SWITCH_TO_NEW_SESSION_AND_DONT_OPEN_ACTIVITY:
                setCurrentStoredTerminalSession(newTerminalSession);
                if (mNujumaTerminalSessionActivityClient != null)
                    mNujumaTerminalSessionActivityClient.setCurrentSession(newTerminalSession);
                break;
            case NUJUMA_SERVICE.VALUE_EXTRA_SESSION_ACTION_KEEP_CURRENT_SESSION_AND_DONT_OPEN_ACTIVITY:
                if (getNujumaSessionsSize() == 1)
                    setCurrentStoredTerminalSession(newTerminalSession);
                break;
            default:
                Logger.logError(LOG_TAG, "Invalid sessionAction: \"" + sessionAction + "\". Force using default sessionAction.");
                handleSessionAction(NUJUMA_SERVICE.VALUE_EXTRA_SESSION_ACTION_SWITCH_TO_NEW_SESSION_AND_OPEN_ACTIVITY, newTerminalSession);
                break;
        }
    }

    /** Launch the {@link }NujumaActivity} to bring it to foreground. */
    private void startNujumaActivity() {
        // For android >= 10, apps require Display over other apps permission to start foreground activities
        // from background (services). If it is not granted, then NujumaSessions that are started will
        // show in Nujuma notification but will not run until user manually clicks the notification.
        if (PermissionUtils.validateDisplayOverOtherAppsPermissionForPostAndroid10(this, true)) {
            NujumaActivity.startNujumaActivity(this);
        } else {
            NujumaAppSharedPreferences preferences = NujumaAppSharedPreferences.build(this);
            if (preferences == null) return;
            if (preferences.arePluginErrorNotificationsEnabled(false))
                Logger.showToast(this, this.getString(R.string.error_display_over_other_apps_permission_not_granted_to_start_terminal), true);
        }
    }





    /** If {@link NujumaActivity} has not bound to the {@link NujumaService} yet or is destroyed, then
     * interface functions requiring the activity should not be available to the terminal sessions,
     * so we just return the {@link #mNujumaTerminalSessionServiceClient}. Once {@link NujumaActivity} bind
     * callback is received, it should call {@link #setNujumaTerminalSessionClient} to set the
     * {@link NujumaService#mNujumaTerminalSessionActivityClient} so that further terminal sessions are directly
     * passed the {@link NujumaTerminalSessionActivityClient} object which fully implements the
     * {@link TerminalSessionClient} interface.
     *
     * @return Returns the {@link NujumaTerminalSessionActivityClient} if {@link NujumaActivity} has bound with
     * {@link NujumaService}, otherwise {@link NujumaTerminalSessionServiceClient}.
     */
    public synchronized NujumaTerminalSessionClientBase getNujumaTerminalSessionClient() {
        if (mNujumaTerminalSessionActivityClient != null)
            return mNujumaTerminalSessionActivityClient;
        else
            return mNujumaTerminalSessionServiceClient;
    }

    /** This should be called when {@link NujumaActivity#onServiceConnected} is called to set the
     * {@link NujumaService#mNujumaTerminalSessionActivityClient} variable and update the {@link TerminalSession}
     * and {@link TerminalEmulator} clients in case they were passed {@link NujumaTerminalSessionServiceClient}
     * earlier.
     *
     * @param nujumaTerminalSessionActivityClient The {@link NujumaTerminalSessionActivityClient} object that fully
     * implements the {@link TerminalSessionClient} interface.
     */
    public synchronized void setNujumaTerminalSessionClient(NujumaTerminalSessionActivityClient nujumaTerminalSessionActivityClient) {
        mNujumaTerminalSessionActivityClient = nujumaTerminalSessionActivityClient;

        for (int i = 0; i < mShellManager.mNujumaSessions.size(); i++)
            mShellManager.mNujumaSessions.get(i).getTerminalSession().updateTerminalSessionClient(mNujumaTerminalSessionActivityClient);
    }

    /** This should be called when {@link NujumaActivity} has been destroyed and in {@link #onUnbind(Intent)}
     * so that the {@link NujumaService} and {@link TerminalSession} and {@link TerminalEmulator}
     * clients do not hold an activity references.
     */
    public synchronized void unsetNujumaTerminalSessionClient() {
        for (int i = 0; i < mShellManager.mNujumaSessions.size(); i++)
            mShellManager.mNujumaSessions.get(i).getTerminalSession().updateTerminalSessionClient(mNujumaTerminalSessionServiceClient);

        mNujumaTerminalSessionActivityClient = null;
    }





    private Notification buildNotification() {
        Resources res = getResources();

        // Set pending intent to be launched when notification is clicked
        Intent notificationIntent = NujumaActivity.newInstance(this);
        PendingIntent contentIntent = PendingIntent.getActivity(this, 0, notificationIntent, 0);


        // Set notification text
        int sessionCount = getNujumaSessionsSize();
        int taskCount = mShellManager.mNujumaTasks.size();
        String notificationText = sessionCount + " session" + (sessionCount == 1 ? "" : "s");
        if (taskCount > 0) {
            notificationText += ", " + taskCount + " task" + (taskCount == 1 ? "" : "s");
        }

        final boolean wakeLockHeld = mWakeLock != null;
        if (wakeLockHeld) notificationText += " (wake lock held)";


        // Set notification priority
        // If holding a wake or wifi lock consider the notification of high priority since it's using power,
        // otherwise use a low priority
        int priority = (wakeLockHeld) ? Notification.PRIORITY_HIGH : Notification.PRIORITY_LOW;


        // Build the notification
        Notification.Builder builder =  NotificationUtils.geNotificationBuilder(this,
            NujumaConstants.NUJUMA_APP_NOTIFICATION_CHANNEL_ID, priority,
            NujumaConstants.NUJUMA_APP_NAME, notificationText, null,
            contentIntent, null, NotificationUtils.NOTIFICATION_MODE_SILENT);
        if (builder == null)  return null;

        // No need to show a timestamp:
        builder.setShowWhen(false);

        // Set notification icon
        builder.setSmallIcon(R.drawable.ic_service_notification);

        // Set background color for small notification icon
        builder.setColor(0xFF607D8B);

        // NujumaSessions are always ongoing
        builder.setOngoing(true);


        // Set Exit button action
        Intent exitIntent = new Intent(this, NujumaService.class).setAction(NUJUMA_SERVICE.ACTION_STOP_SERVICE);
        builder.addAction(android.R.drawable.ic_delete, res.getString(R.string.notification_action_exit), PendingIntent.getService(this, 0, exitIntent, 0));


        // Set Wakelock button actions
        String newWakeAction = wakeLockHeld ? NUJUMA_SERVICE.ACTION_WAKE_UNLOCK : NUJUMA_SERVICE.ACTION_WAKE_LOCK;
        Intent toggleWakeLockIntent = new Intent(this, NujumaService.class).setAction(newWakeAction);
        String actionTitle = res.getString(wakeLockHeld ? R.string.notification_action_wake_unlock : R.string.notification_action_wake_lock);
        int actionIcon = wakeLockHeld ? android.R.drawable.ic_lock_idle_lock : android.R.drawable.ic_lock_lock;
        builder.addAction(actionIcon, actionTitle, PendingIntent.getService(this, 0, toggleWakeLockIntent, 0));


        return builder.build();
    }

    private void setupNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;

        NotificationUtils.setupNotificationChannel(this, NujumaConstants.NUJUMA_APP_NOTIFICATION_CHANNEL_ID,
            NujumaConstants.NUJUMA_APP_NOTIFICATION_CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW);
    }

    /** Update the shown foreground service notification after making any changes that affect it. */
    private synchronized void updateNotification() {
        if (mWakeLock == null && mShellManager.mNujumaSessions.isEmpty() && mShellManager.mNujumaTasks.isEmpty()) {
            // Exit if we are updating after the user disabled all locks with no sessions or tasks running.
            requestStopService();
        } else {
            ((NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE)).notify(NujumaConstants.NUJUMA_APP_NOTIFICATION_ID, buildNotification());
        }
    }





    private void setCurrentStoredTerminalSession(TerminalSession terminalSession) {
        if (terminalSession == null) return;
        // Make the newly created session the current one to be displayed
        NujumaAppSharedPreferences preferences = NujumaAppSharedPreferences.build(this);
        if (preferences == null) return;
        preferences.setCurrentSession(terminalSession.mHandle);
    }

    public synchronized boolean isNujumaSessionsEmpty() {
        return mShellManager.mNujumaSessions.isEmpty();
    }

    public synchronized int getNujumaSessionsSize() {
        return mShellManager.mNujumaSessions.size();
    }

    public synchronized List<NujumaSession> getNujumaSessions() {
        return mShellManager.mNujumaSessions;
    }

    @Nullable
    public synchronized NujumaSession getNujumaSession(int index) {
        if (index >= 0 && index < mShellManager.mNujumaSessions.size())
            return mShellManager.mNujumaSessions.get(index);
        else
            return null;
    }

    @Nullable
    public synchronized NujumaSession getNujumaSessionForTerminalSession(TerminalSession terminalSession) {
        if (terminalSession == null) return null;

        for (int i = 0; i < mShellManager.mNujumaSessions.size(); i++) {
            if (mShellManager.mNujumaSessions.get(i).getTerminalSession().equals(terminalSession))
                return mShellManager.mNujumaSessions.get(i);
        }

        return null;
    }

    public synchronized NujumaSession getLastNujumaSession() {
        return mShellManager.mNujumaSessions.isEmpty() ? null : mShellManager.mNujumaSessions.get(mShellManager.mNujumaSessions.size() - 1);
    }

    public synchronized int getIndexOfSession(TerminalSession terminalSession) {
        if (terminalSession == null) return -1;

        for (int i = 0; i < mShellManager.mNujumaSessions.size(); i++) {
            if (mShellManager.mNujumaSessions.get(i).getTerminalSession().equals(terminalSession))
                return i;
        }
        return -1;
    }

    public synchronized TerminalSession getTerminalSessionForHandle(String sessionHandle) {
        TerminalSession terminalSession;
        for (int i = 0, len = mShellManager.mNujumaSessions.size(); i < len; i++) {
            terminalSession = mShellManager.mNujumaSessions.get(i).getTerminalSession();
            if (terminalSession.mHandle.equals(sessionHandle))
                return terminalSession;
        }
        return null;
    }

    public synchronized AppShell getNujumaTaskForShellName(String name) {
        if (DataUtils.isNullOrEmpty(name)) return null;
        AppShell appShell;
        for (int i = 0, len = mShellManager.mNujumaTasks.size(); i < len; i++) {
            appShell = mShellManager.mNujumaTasks.get(i);
            String shellName = appShell.getExecutionCommand().shellName;
            if (shellName != null && shellName.equals(name))
                return appShell;
        }
        return null;
    }

    public synchronized NujumaSession getNujumaSessionForShellName(String name) {
        if (DataUtils.isNullOrEmpty(name)) return null;
        NujumaSession nujumaSession;
        for (int i = 0, len = mShellManager.mNujumaSessions.size(); i < len; i++) {
            nujumaSession = mShellManager.mNujumaSessions.get(i);
            String shellName = nujumaSession.getExecutionCommand().shellName;
            if (shellName != null && shellName.equals(name))
                return nujumaSession;
        }
        return null;
    }



    public boolean wantsToStop() {
        return mWantsToStop;
    }

}
