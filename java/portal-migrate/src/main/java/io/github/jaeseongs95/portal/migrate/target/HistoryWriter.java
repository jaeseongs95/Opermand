package io.github.jaeseongs95.portal.migrate.target;

import io.github.jaeseongs95.portal.domain.capacity.Capacity;
import io.github.jaeseongs95.portal.domain.operation.Operation;
import io.github.jaeseongs95.portal.domain.operation.OperationState;
import io.github.jaeseongs95.portal.domain.schedule.ConcurrencyLimits;
import io.github.jaeseongs95.portal.migrate.plan.ImportPlan;
import io.github.jaeseongs95.portal.migrate.plan.Step;
import io.github.jaeseongs95.portal.store.journal.JdbcClaimStore;
import io.github.jaeseongs95.portal.store.journal.JdbcOperationJournal;

/**
 * Writes one planned operation into the 3.0 store through the store's own journal and claim store, the same transitions the server runs (never a direct SQL write): intake
 * with the 2.x id, key and time, then each step with the instant of the 2.x event it stands for. The caller runs all writes of a migration in one transaction, so a failure
 * anywhere leaves nothing behind.
 *
 * <p>The claim is made under limits that cannot refuse: the migration is not a dispatcher, and the limits of the running server (the concurrency, the unknown budget) are not
 * the migration's to apply; each claimed operation is settled in the same call, so no slot stays taken. The unknown budget of the server is checked beforehand
 * ({@code MigrationRun}), because the operations imported as unknown count against it later.
 */
public final class HistoryWriter {

    private static final ConcurrencyLimits UNLIMITED = ConcurrencyLimits.defaults().withGlobalConcurrency(new Capacity(Integer.MAX_VALUE))
        .withTargetConcurrency(new Capacity(Integer.MAX_VALUE)).withUnknownBudget(new Capacity(Integer.MAX_VALUE));

    private final JdbcOperationJournal journal;
    private final JdbcClaimStore claims;

    HistoryWriter(JdbcOperationJournal journal, JdbcClaimStore claims) {
        this.journal = journal;
        this.claims = claims;
    }

    public void write(ImportPlan plan) {
        journal.accept(plan.spec(), plan.admission(), plan.id(), plan.createdAt(), () -> { });
        for (Step step : plan.steps()) {
            Operation current = journal.find(plan.id()).orElseThrow(() -> new IllegalStateException("Operation " + plan.id().value() + " is not in the store after its intake"));
            if (step instanceof Step.Claim claim) {
                claims.claim(plan.id(), current.version(), UNLIMITED, claim.at());
                Operation claimed = journal.find(plan.id()).orElseThrow(() -> new IllegalStateException("Operation " + plan.id().value() + " vanished during its claim"));
                if (claimed.state() != OperationState.STARTING) {
                    throw new IllegalStateException("The store did not claim operation " + plan.id().value() + " (it is " + claimed.state() + ")");
                }
            } else {
                journal.save(step.advance(current), current.version());
            }
        }
    }
}
