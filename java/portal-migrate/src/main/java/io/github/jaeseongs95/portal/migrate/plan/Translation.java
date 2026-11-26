package io.github.jaeseongs95.portal.migrate.plan;

import java.util.Objects;

/** What the translator made of one 2.x row. */
public sealed interface Translation {

    /** The operation is moved; the plan says how. */
    record Import(ImportPlan plan) implements Translation {

        public Import {
            Objects.requireNonNull(plan, "plan");
        }
    }

    /** The row is a valid 2.x operation of a kind that is not moved; {@code kind} and {@code reason} are what the report says (grouped by them). */
    record NotMigrated(String kind, String reason) implements Translation {

        public NotMigrated {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(reason, "reason");
        }
    }

    /** The row cannot be written to 3.0 exactly as it is: the migration reports it and writes nothing at all. */
    record Rejected(String reason) implements Translation {

        public Rejected {
            Objects.requireNonNull(reason, "reason");
        }
    }
}
