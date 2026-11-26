package io.github.jaeseongs95.portal.store.output;

import java.time.Instant;
import java.util.List;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import io.github.jaeseongs95.portal.app.output.OutputId;
import io.github.jaeseongs95.portal.app.output.OutputLookup;
import io.github.jaeseongs95.portal.app.output.OutputManifest;
import io.github.jaeseongs95.portal.app.output.OutputManifestStore;
import io.github.jaeseongs95.portal.app.output.OutputPayloadStore;
import io.github.jaeseongs95.portal.app.output.OutputState;
import io.github.jaeseongs95.portal.app.output.RetainedBytes;
import io.github.jaeseongs95.portal.domain.error.RequestRefusedException;

/**
 * The start-up reconciliation of the output manifests with the output files (2.x {@code CommandOutputs} constructor and {@code reap}, design 8.5). It is not in a
 * transaction: it reads the files first and then calls the manifest store, whose methods are short transactions of their own (Principles 4.4). It is not wired to an
 * {@code ApplicationRunner} here; the assembly of stage 3 calls it.
 *
 * <p>It is split in two so that a caller can honour a maintenance window: {@link #recover} never deletes an output, {@link #reap} does, and {@link #reconcile} runs both.
 * A file or a row that cannot be handled is logged and skipped: one bad output must not stop the start of the portal, and a restart never re-executes a command.
 */
@Component
public class OutputStartupCheck {

    private static final Logger LOG = LoggerFactory.getLogger(OutputStartupCheck.class);

    private final OutputManifestStore manifests;
    private final OutputPayloadStore payloads;

    public OutputStartupCheck(OutputManifestStore manifests, OutputPayloadStore payloads) {
        this.manifests = manifests;
        this.payloads = payloads;
    }

    /** {@link #recover} and then {@link #reap}. */
    public void reconcile(Instant now) {
        reconcile(now, Runnable::run);
    }

    /** {@link #recover} and then {@link #reap}, wrapping each short database mutation in {@code inTransaction}. */
    public void reconcile(Instant now, Consumer<Runnable> inTransaction) {
        recover(now, inTransaction);
        reap(now, inTransaction);
    }

    public void recover(Instant now) {
        recover(now, Runnable::run);
    }

    public void recover(Instant now, Consumer<Runnable> inTransaction) {
        quarantineOrphans(now);
        List<OutputManifest> unavailable = manifests.inState(OutputState.UNAVAILABLE);
        for (OutputManifest interrupted : manifests.inState(OutputState.PENDING)) {
            giveUp(interrupted, inTransaction);
        }
        for (OutputManifest old : unavailable) {
            giveUp(old, inTransaction);
        }
        inTransaction.accept(manifests::reconcileBudget);
    }

    /** Deletes the bytes and then the row of every output whose expiry is not after {@code now}. A failed delete keeps the row, so the next run tries again. */
    public void reap(Instant now) {
        reap(now, Runnable::run);
    }

    public void reap(Instant now, Consumer<Runnable> inTransaction) {
        for (OutputManifest expired : manifests.expired(now)) {
            try {
                payloads.delete(expired.id());
                inTransaction.accept(() -> manifests.delete(expired.id()));
            } catch (RequestRefusedException e) {
                LOG.error("The expired output {} could not be removed; it stays until the next run", expired.id().value(), e);
            }
        }
        inTransaction.accept(manifests::reconcileBudget);
    }

    private void quarantineOrphans(Instant now) {
        for (OutputId id : payloads.stored()) {
            if (manifests.find(id, now) instanceof OutputLookup.Missing) {
                try {
                    payloads.quarantine(id);
                } catch (RequestRefusedException e) {
                    LOG.error("The payload of the output {} has no manifest and could not be moved to the quarantine folder", id.value(), e);
                }
            }
        }
    }

    /** Measures retained bytes outside any transaction, then updates the manifest row inside {@code inTransaction}. */
    private void giveUp(OutputManifest output, Consumer<Runnable> inTransaction) {
        try {
            RetainedBytes retained = measure(output.id());
            inTransaction.accept(() -> manifests.markUnavailable(output.id(), output.expiresAt(), retained));
        } catch (RequestRefusedException e) {
            LOG.error("The output {} could not be settled at start-up", output.id().value(), e);
        }
    }

    private RetainedBytes measure(OutputId id) {
        try {
            return new RetainedBytes.Measured(payloads.retainedBytes(id));
        } catch (RequestRefusedException e) {
            LOG.warn("The bytes of output {} cannot be measured safely; its charge stays conservative", id.value(), e);
            return new RetainedBytes.Unmeasured();
        }
    }
}
