package io.github.jaeseongs95.portal.migrate.run;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

import io.github.jaeseongs95.portal.domain.operation.Operation;
import io.github.jaeseongs95.portal.domain.operation.OperationState;
import io.github.jaeseongs95.portal.migrate.report.MigrationReport;
import io.github.jaeseongs95.portal.migrate.target.ExistingHistory;

/**
 * The second half of the checks, against the 3.0 store: an operation that the store already holds with the same id, request key and contract is one an earlier run moved
 * (a rerun does not move it again); the same request key or id with anything else is a conflict, named with the 3.0 operation it collides with. Then the unknown budget: the
 * operations that end up unknown count against the budget of the 3.0 scheduler, which starts no new operation while it is used up (2.4.2 stopped all new commands that way
 * with 32 unknown operations), so a migration that would use the budget up is reported as a conflict and writes nothing.
 */
public final class TargetCheck {

    private final ExistingHistory existing;
    private final MigrationReport report;

    public TargetCheck(ExistingHistory existing, MigrationReport report) {
        this.existing = existing;
        this.report = report;
    }

    /** The ids of the operations the store already holds as moved by an earlier run. */
    public Set<String> check(MigrationInput input) {
        Set<String> alreadyMoved = new HashSet<>();
        int pendingUnknown = 0;
        for (PlannedRow row : input.planned()) {
            Optional<Operation> sameKey = existing.byRequestKey(row.key());
            if (sameKey.isPresent()) {
                Operation found = sameKey.orElseThrow();
                if (found.id().equals(row.id()) && found.hasContract(row.contract())) {
                    alreadyMoved.add(row.id().value());
                } else {
                    report.issue("REQUEST_KEY_IN_TARGET", row.reference(), "request key " + row.key().value() + " of operation " + row.id().value() + " is already used by operation "
                        + found.id().value() + " in the 3.0 store, which is " + (found.id().equals(row.id()) ? "the same id with another contract" : "another operation"));
                }
                continue;
            }
            if (existing.byId(row.id()).isPresent()) {
                report.issue("ID_IN_TARGET", row.reference(), "operation id " + row.id().value() + " is already in the 3.0 store under another request key");
                continue;
            }
            if (row.finalState() == OperationState.UNKNOWN) {
                pendingUnknown++;
            }
        }
        int existingUnknown = existing.unknownOperations();
        report.targetFound(alreadyMoved.size(), existingUnknown, input.unknownBudget());
        if (existingUnknown + pendingUnknown >= input.unknownBudget()) {
            report.issue("UNKNOWN_BUDGET", "target store", "the store would hold " + (existingUnknown + pendingUnknown) + " unknown operations (" + existingUnknown + " now, " + pendingUnknown
                + " to move) and the unknown budget is " + input.unknownBudget() + ": the 3.0 server starts no new operation while the budget is used up. Nothing was written. Reconcile the 2.x"
                + " unknown operations first, or state the larger budget the server runs with (--unknown-budget)");
        }
        return alreadyMoved;
    }
}
