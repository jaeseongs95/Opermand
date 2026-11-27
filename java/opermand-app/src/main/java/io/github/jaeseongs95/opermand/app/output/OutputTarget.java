package io.github.jaeseongs95.opermand.app.output;

import java.util.Objects;

import io.github.jaeseongs95.opermand.domain.id.TargetId;
import io.github.jaeseongs95.opermand.domain.target.TargetRevision;

/** The target an output belongs to, if the execution had one (2.x {@code binding.targetId}, {@code targetRevision?}). */
public sealed interface OutputTarget permits OutputTarget.Unbound, OutputTarget.Bound {

    record Unbound() implements OutputTarget {
    }

    record Bound(TargetId targetId, TargetRevision targetRevision) implements OutputTarget {

        public Bound {
            Objects.requireNonNull(targetId, "targetId");
            Objects.requireNonNull(targetRevision, "targetRevision");
        }
    }
}
