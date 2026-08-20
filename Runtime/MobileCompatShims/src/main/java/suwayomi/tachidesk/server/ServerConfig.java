package suwayomi.tachidesk.server;

import kotlinx.coroutines.flow.MutableStateFlow;
import kotlinx.coroutines.flow.StateFlowKt;

/**
 * Mobile subset of Suwayomi's server configuration used by its network layer.
 * FlareSolverr remains disabled because iOS resolves challenges with WKWebView.
 */
public final class ServerConfig {
    private final MutableStateFlow<Boolean> flareSolverrEnabled =
        StateFlowKt.MutableStateFlow(Boolean.FALSE);
    private final MutableStateFlow<Boolean> flareSolverrAsResponseFallback =
        StateFlowKt.MutableStateFlow(Boolean.FALSE);
    private final MutableStateFlow<Integer> flareSolverrTimeout =
        StateFlowKt.MutableStateFlow(60);
    private final MutableStateFlow<String> flareSolverrUrl =
        StateFlowKt.MutableStateFlow("");
    private final MutableStateFlow<String> flareSolverrSessionName =
        StateFlowKt.MutableStateFlow("tachiyomiaz");
    private final MutableStateFlow<Integer> flareSolverrSessionTtl =
        StateFlowKt.MutableStateFlow(15);

    public MutableStateFlow<Boolean> getFlareSolverrEnabled() {
        return flareSolverrEnabled;
    }

    public MutableStateFlow<Boolean> getFlareSolverrAsResponseFallback() {
        return flareSolverrAsResponseFallback;
    }

    public MutableStateFlow<Integer> getFlareSolverrTimeout() {
        return flareSolverrTimeout;
    }

    public MutableStateFlow<String> getFlareSolverrUrl() {
        return flareSolverrUrl;
    }

    public MutableStateFlow<String> getFlareSolverrSessionName() {
        return flareSolverrSessionName;
    }

    public MutableStateFlow<Integer> getFlareSolverrSessionTtl() {
        return flareSolverrSessionTtl;
    }
}
