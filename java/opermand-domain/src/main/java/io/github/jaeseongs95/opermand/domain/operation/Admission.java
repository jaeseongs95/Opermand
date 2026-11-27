package io.github.jaeseongs95.opermand.domain.operation;

import java.util.Objects;

/**
 * What the approval decided at intake, and so the state the operation is first stored in (defect D-0119): a rule that lets the operation run puts it in the queue
 * ({@link Queue}, state {@code QUEUED}), a rule that forbids it ends it at once ({@link Deny}, state {@code DENIED}), and anything else waits for the owner
 * ({@link Review}, state {@code PENDING}). There is no stored state before the judgement.
 */
public sealed interface Admission permits Admission.Queue, Admission.Deny, Admission.Review {

    /** The decision as the operation keeps it. */
    DecisionRecord.Decided decision();

    /** The admission that the reason fixes, for an operation of {@code kind} judged under the policy {@code policyRevision}. */
    static Admission of(DecisionReason reason, OperationKind kind, long policyRevision) {
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(kind, "kind");
        DecisionRecord.Decided decided = new DecisionRecord.Decided(reason.kind(), reason, ReviewReason.NONE, policyRevision);
        return switch (reason.kind()) {
            case EXECUTE -> new Queue(StartAuthority.Policy.of(reason, kind), decided);
            case DENY -> new Deny(decided);
            case REVIEW -> new Review(decided);
        };
    }

    /** The server's policy starts the operation: it is queued under this authority. */
    record Queue(StartAuthority.Policy authority, DecisionRecord.Decided decision) implements Admission {
        public Queue {
            Objects.requireNonNull(authority, "authority");
            Objects.requireNonNull(decision, "decision");
            if (!decision.isExecute()) {
                throw new IllegalArgumentException("A queued admission has a decision to execute");
            }
        }
    }

    /** The rule forbids the operation: it is stored as denied and nothing runs. */
    record Deny(DecisionRecord.Decided decision) implements Admission {
        public Deny {
            Objects.requireNonNull(decision, "decision");
            if (decision.kind() != DecisionKind.DENY) {
                throw new IllegalArgumentException("A denying admission has a decision to deny");
            }
        }
    }

    /** No rule decides: the operation waits for the owner. */
    record Review(DecisionRecord.Decided decision) implements Admission {
        public Review {
            Objects.requireNonNull(decision, "decision");
            if (decision.kind() != DecisionKind.REVIEW) {
                throw new IllegalArgumentException("A review admission has a decision to review");
            }
        }
    }
}
