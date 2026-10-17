package com.nujuma.app.terminal;

import android.app.Service;

import androidx.annotation.NonNull;

import com.nujuma.app.NujumaService;
import com.nujuma.shared.nujuma.shell.command.runner.terminal.NujumaSession;
import com.nujuma.shared.nujuma.terminal.NujumaTerminalSessionClientBase;
import com.nujuma.terminal.TerminalSession;
import com.nujuma.terminal.TerminalSessionClient;

/** The {@link TerminalSessionClient} implementation that may require a {@link Service} for its interface methods. */
public class NujumaTerminalSessionServiceClient extends NujumaTerminalSessionClientBase {

    private static final String LOG_TAG = "NujumaTerminalSessionServiceClient";

    private final NujumaService mService;

    public NujumaTerminalSessionServiceClient(NujumaService service) {
        this.mService = service;
    }

    @Override
    public void setTerminalShellPid(@NonNull TerminalSession terminalSession, int pid) {
        NujumaSession nujumaSession = mService.getNujumaSessionForTerminalSession(terminalSession);
        if (nujumaSession != null)
            nujumaSession.getExecutionCommand().mPid = pid;
    }

}
