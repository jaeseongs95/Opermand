package io.github.jaeseongs95.portal.app.execution;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import io.github.jaeseongs95.portal.app.file.DownloadJob;
import io.github.jaeseongs95.portal.app.file.DownloadService;
import io.github.jaeseongs95.portal.app.file.TransferOutcomes;
import io.github.jaeseongs95.portal.app.remote.ExecutionControl;
import io.github.jaeseongs95.portal.app.remote.ExecutionReport;
import io.github.jaeseongs95.portal.app.remote.ExecutionTimeline;
import io.github.jaeseongs95.portal.domain.error.DispatchFailure;
import io.github.jaeseongs95.portal.domain.error.DispatchReason;
import io.github.jaeseongs95.portal.domain.error.DispatchRefusedException;
import io.github.jaeseongs95.portal.domain.error.DispatchStage;
import io.github.jaeseongs95.portal.domain.error.RequestRefusedException;
import io.github.jaeseongs95.portal.domain.operation.DownloadInput;
import io.github.jaeseongs95.portal.domain.operation.Operation;
import io.github.jaeseongs95.portal.domain.operation.OperationKind;

/**
 * Runs the operations of kind {@code DOWNLOAD}: it hands the stored input to {@link DownloadService}, which reads the file into a new artifact and answers the outcome the
 * operation settles from. A download only reads, so every failure is {@code FAILED} and never {@code UNKNOWN}, a lost connection included (decision D3-17, defect D-0013);
 * that decision is written once, in {@code TransferOutcomes}, for what the adapter answers, and here for what it does not answer: an exception.
 *
 * <p>The exceptions are sorted here and nowhere else, so that none of them reaches the general handler of the dispatcher, which settles an exception as an unknown outcome
 * that has to be reconciled (right for a write, wrong for a read): a {@link DispatchRefusedException} (the guard refused, the target is gone) goes on as it is, a
 * {@link RequestRefusedException} becomes a refusal before dispatch, and any other runtime exception (the database, the artifact store, an error of the code, whether it came
 * before the service, inside it or from the adapter) is a failure of the portal that left the target as it was: a read-only local failure that needs no reconciliation, with its
 * cause in the log (audit F4; cases S-D0013-24 to S-D0013-26 and S-D0013-33).
 */
@Component
public class DownloadOperationRunner implements OperationRunner {

    private static final Logger LOG = LoggerFactory.getLogger(DownloadOperationRunner.class);

    private final DownloadService downloads;
    private final RunTargets targets;
    private final TransferOutcomes outcomes;

    public DownloadOperationRunner(DownloadService downloads, RunTargets targets, TransferOutcomes outcomes) {
        this.downloads = downloads;
        this.targets = targets;
        this.outcomes = outcomes;
    }

    @Override
    public OperationKind kind() {
        return OperationKind.DOWNLOAD;
    }

    @Override
    public ExecutionReport run(Operation operation, ExecutionControl control) {
        if (!(operation.input() instanceof DownloadInput input)) {
            throw new IllegalArgumentException("Not a download operation: " + operation.kind());
        }
        try {
            DownloadJob job = new DownloadJob(targets.of(operation), operation.targetRevision(), input);
            return new ExecutionReport(downloads.download(job, control).outcome(), ExecutionTimeline.empty());
        } catch (DispatchRefusedException e) {
            if (control.guard() instanceof StartGuard startGuard && startGuard.started()) {
                LOG.error("A download refused a step after its execution had started; it is settled as a local failure of the portal and the target was only read", e);
                return new ExecutionReport(outcomes.portalFailure(e), ExecutionTimeline.empty());
            }
            throw e;
        } catch (RequestRefusedException e) {
            if (control.guard() instanceof StartGuard startGuard && startGuard.started()) {
                LOG.error("A download refused a step after its execution had started; it is settled as a local failure of the portal and the target was only read", e);
                return new ExecutionReport(outcomes.portalFailure(e), ExecutionTimeline.empty());
            }
            throw new DispatchRefusedException(DispatchFailure.refused(DispatchReason.UNKNOWN, DispatchStage.FILE_GUARD), e);
        } catch (RuntimeException e) {
            LOG.error("A download failed with an exception that no result describes; it is settled as a local failure of the portal and the target was only read", e);
            return new ExecutionReport(outcomes.portalFailure(e), ExecutionTimeline.empty());
        }
    }
}
