package io.github.jaeseongs95.portal.app.remote;

/** Starts a command with binary standard streams and no terminal (A-816 to A-829). The desktop module (stage 5) uses it for the helpers of a GUI lease. */
public interface RemoteStreamExecutor {

    StreamOpenResult open(StreamRequest request, ExecutionControl control);
}
