package io.github.jaeseongs95.opermand.archchecks.fixtures.s3.app.remote;

/** Stands for opermand-app's RemoteExecutor port: A-NO-RETRY-EXEC covers every implementation, wherever it lives. */
public interface RemoteExecutor {
    ExecutionReport run(String request);
}
