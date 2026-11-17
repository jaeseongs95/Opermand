package io.github.jaeseongs95.portal.app.execution;

import java.util.Objects;

import io.github.jaeseongs95.portal.app.remote.DispatchGuard;
import io.github.jaeseongs95.portal.domain.error.DispatchRefusedException;
import io.github.jaeseongs95.portal.domain.id.OperationId;

/**
 * The per-execution {@link DispatchGuard} created by {@link DispatchChecks#guardFor(OperationId)} for one run of an operation (defect D-0149). An execution may cross to
 * its target in several remote steps (a Windows link probe and administrator check before an SFTP transfer, a file upload followed by {@code chmod}, an agent CLI
 * preparation, upload and unpack), and each step calls {@link #beforeDispatch()} after opening its channel.
 *
 * <p>The start check, the start audit and the {@code STARTING -> RUNNING} journal transition belong to the start of the execution, not to each transport channel: the
 * first call runs {@link DispatchChecks#beforeDispatch(OperationId)}; once that call succeeds, subsequent calls on the same execution return without repeating the audit
 * or failing on {@code RUNNING}/{@code TERMINATING}, while cancellation between steps is observed through {@code ExecutionControl.cancel()}. If the first call refuses,
 * the refusal is remembered and rethrown on any repeated call so no step can proceed.
 */
final class StartGuard implements DispatchGuard {

    private final DispatchChecks checks;
    private final OperationId id;
    private boolean started;
    private DispatchRefusedException refused;

    StartGuard(DispatchChecks checks, OperationId id) {
        this.checks = Objects.requireNonNull(checks, "checks");
        this.id = Objects.requireNonNull(id, "id");
    }

    @Override
    public synchronized void beforeDispatch() {
        if (started) {
            return;
        }
        if (refused != null) {
            throw refused;
        }
        try {
            checks.beforeDispatch(id);
            started = true;
        } catch (DispatchRefusedException e) {
            refused = e;
            throw e;
        }
    }

    synchronized boolean started() {
        return started;
    }
}
