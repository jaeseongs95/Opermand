package io.github.jaeseongs95.portal.migrate.plan;

import java.time.Instant;
import java.util.Objects;

import io.github.jaeseongs95.portal.domain.error.DispatchFailure;
import io.github.jaeseongs95.portal.domain.operation.ExecutionOutcome;
import io.github.jaeseongs95.portal.domain.operation.Operation;
import io.github.jaeseongs95.portal.domain.operation.StartAuthority;

/**
 * One transition of the 3.0 operation that stands for what happened to a 2.x operation. The transitions are the ones the 3.0 state table allows ({@link Operation}): an
 * operation that ran passes through queued, starting and running, because the domain has no shorter way to a settled result, and every step carries the instant of the 2.x
 * event it stands for (created, started, finished), so the event rows of the imported operation tell the truth about when, not about when the migration ran.
 */
public sealed interface Step {

    /** The instant the transition is recorded with. */
    Instant at();

    /** The next operation. {@link Claim} is not applied here: the store's claim does it, with its limits and slots. */
    Operation advance(Operation current);

    /** The owner approved a pending operation. */
    record OwnerApproval(Instant at) implements Step {

        @Override
        public Operation advance(Operation current) {
            return current.enqueue(new StartAuthority.Owner(), at);
        }
    }

    /** A dispatcher took the queued operation: it is starting. */
    record Claim(Instant at) implements Step {

        @Override
        public Operation advance(Operation current) {
            return current.claim(at);
        }
    }

    /** The first step across the external effect boundary. */
    record MarkRunning(Instant at) implements Step {

        @Override
        public Operation advance(Operation current) {
            return current.markRunning(at);
        }
    }

    /** The executor reported how it ended; the outcome fixes the state (completed only with evidence, otherwise unknown). */
    record Settle(ExecutionOutcome outcome, Instant at) implements Step {

        public Settle {
            Objects.requireNonNull(outcome, "outcome");
        }

        @Override
        public Operation advance(Operation current) {
            return current.settle(outcome, at);
        }
    }

    /** The operation was in flight when 2.x stopped: what was running is unknown and is never replayed. */
    record RecoverAfterRestart(Instant at) implements Step {

        @Override
        public Operation advance(Operation current) {
            return current.recoverAfterRestart(at);
        }
    }

    /** The owner or the policy refused it. */
    record Deny(Instant at) implements Step {

        @Override
        public Operation advance(Operation current) {
            return current.deny(at);
        }
    }

    /** Its deadline passed before it was dispatched. */
    record Expire(Instant at) implements Step {

        @Override
        public Operation advance(Operation current) {
            return current.expire(at);
        }
    }

    /** It was cancelled before it was dispatched. */
    record Cancel(Instant at) implements Step {

        @Override
        public Operation advance(Operation current) {
            return current.requestCancel(at);
        }
    }

    /** A guard stopped it before dispatch. */
    record Refuse(DispatchFailure failure, Instant at) implements Step {

        public Refuse {
            Objects.requireNonNull(failure, "failure");
        }

        @Override
        public Operation advance(Operation current) {
            return current.refuseBeforeDispatch(failure, at);
        }
    }
}
