package io.github.jaeseongs95.portal.app.artifact;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import io.github.jaeseongs95.portal.app.storage.ReapGate;
import io.github.jaeseongs95.portal.app.storage.ReapPermission;
import io.github.jaeseongs95.portal.app.storage.ReapReport;

/**
 * Removes the artifacts whose time has come: the bytes, the row and the charge of each (2.x {@code reap}). It asks the {@link ReapGate} before every run, so a
 * maintenance reservation (RC-A-173) and a failing guard of the file stores (RC-B-004) hold the whole run back: nothing is deleted and nothing is un-charged until they are
 * gone. An artifact that is pinned by a submitted operation is never listed by the registry; a consumed one has no bytes and loses only its row.
 *
 * <p>The order for the others is the registry's: the row goes to {@code DELETING} first, then the bytes are deleted, then the row is released, so a crash in the middle
 * leaves a row that the next run, or the start-up check, finishes: a row in {@code DELETING} is removed again whatever its expiry (defect D-0012: a failed discard of a download
 * used to leave its charge in the budget until the next start). One artifact that cannot be removed is counted and kept for the next run; it does not stop the run. A row that
 * another use case removed meanwhile (a discard that finished after the list was taken) counts as removed. A row in {@code DELETING} is released as {@code ABANDONED}, not as
 * {@code EXPIRED}, because it did not expire.
 */
@Component
public class ArtifactReaper {

    private static final Logger LOG = LoggerFactory.getLogger(ArtifactReaper.class);

    private final ArtifactRegistry registry;
    private final ArtifactPayloadStore payloads;
    private final ReapGate gate;
    private final Clock clock;
    private final QuarantineNotice quarantine;

    public ArtifactReaper(ArtifactRegistry registry, ArtifactPayloadStore payloads, ReapGate gate, Clock clock, QuarantineNotice quarantine) {
        this.registry = registry;
        this.payloads = payloads;
        this.gate = gate;
        this.clock = clock;
        this.quarantine = quarantine;
    }

    /** One run. The scheduler calls {@link #reapOnSchedule()}; a caller that wants the report (a test, the lifecycle) calls this. */
    public ReapReport reap() {
        if (gate.permission() instanceof ReapPermission.Suspended suspended) {
            return new ReapReport.Suspended(suspended.reason());
        }
        int removed = 0;
        int failed = 0;
        // The rows that a removal left in DELETING come first, whatever their expiry: they hold a charge for bytes nobody uses (defect D-0012).
        List<ArtifactRecord> due = new ArrayList<>(registry.deleting());
        due.addAll(registry.expired(clock.instant()));
        for (ArtifactRecord artifact : due) {
            try {
                remove(artifact);
                removed++;
            } catch (RuntimeException e) {
                // One artifact that cannot be removed (a locked file, a conflicting writer) must not stop the others; it is listed again by the next run.
                failed++;
                LOG.warn("The artifact " + artifact.id().value() + " could not be removed and stays until the next run", e);
            }
        }
        quarantine.log();
        return new ReapReport.Reaped(removed, failed);
    }

    /** One minute after the start and one minute after each run (2.x reaped on a timer; the interval is a setting of stage 4). */
    @Scheduled(initialDelay = 60_000L, fixedDelay = 60_000L)
    public void reapOnSchedule() {
        reap();
    }

    /**
     * Removes one listed artifact. A row that is {@code DELETING} did not expire: a removal began and did not finish, so it is released as {@link ReleaseReason#ABANDONED}
     * (the reason the start-up check gives it); only a row that was listed because its expiry passed is released as {@link ReleaseReason#EXPIRED}.
     *
     * <p>The row may be gone between the list and the start of the removal: the discard that left it {@code DELETING} can still be running and finish first. That is the
     * outcome the run wanted, so it is no failure and no retry (Principles 4.4: the one who lost the race is not run again). The registry tells it by the answer of
     * {@link ArtifactRegistry#beginDeleteIfPresent}, not by an exception, so every refusal that is thrown, {@code ARTIFACT_NOT_FOUND} included, is counted as a failure with
     * its cause.
     */
    private void remove(ArtifactRecord due) {
        ReleaseReason reason = due.state() == ArtifactState.DELETING ? ReleaseReason.ABANDONED : ReleaseReason.EXPIRED;
        if (due.state() != ArtifactState.CONSUMED) {
            if (!registry.beginDeleteIfPresent(due.id())) {
                LOG.debug("The artifact {} was removed by another use case before the reap took it", due.id().value());
                return;
            }
            payloads.delete(due.id());
        }
        registry.release(due.id(), reason);
    }
}
