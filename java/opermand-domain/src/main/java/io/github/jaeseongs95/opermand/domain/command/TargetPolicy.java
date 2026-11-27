package io.github.jaeseongs95.opermand.domain.command;

import io.github.jaeseongs95.opermand.domain.id.TargetId;
import io.github.jaeseongs95.opermand.domain.target.ProfileAlias;
import io.github.jaeseongs95.opermand.domain.target.TargetConnection;
import io.github.jaeseongs95.opermand.domain.target.TargetOs;
import java.util.Objects;

/**
 * The stored policy rule for a single target together with its monotonic {@code ruleRevision} (defect D-0121). When no rule exists for the target, {@code rule}
 * is {@link RuleLookup.NoRule} and {@code ruleRevision} is {@code 0}; when a rule exists, {@code ruleRevision} is at least {@code 1}.
 */
public record TargetPolicy(long ruleRevision, RuleLookup rule) {

    public TargetPolicy {
        Objects.requireNonNull(rule, "rule");
        switch (rule) {
            case RuleLookup.NoRule ignored -> {
                if (ruleRevision != 0L) {
                    throw new IllegalArgumentException("An absent target rule has ruleRevision 0, got " + ruleRevision);
                }
            }
            case RuleLookup.Rule ignored -> {
                if (ruleRevision < 1L) {
                    throw new IllegalArgumentException("A stored target rule has ruleRevision >= 1, got " + ruleRevision);
                }
            }
        }
    }

    /** No rule stored for the target ({@code ruleRevision = 0}). */
    public static TargetPolicy none() {
        return new TargetPolicy(0L, new RuleLookup.NoRule());
    }

    /**
     * Returns the rule when it applies to {@code target}, {@code connection}, {@code writeProfile} and {@code os}, or {@link RuleLookup.NoRule} otherwise.
     */
    public RuleLookup ruleFor(TargetId target, TargetConnection connection, ProfileAlias writeProfile, TargetOs os) {
        if (rule instanceof RuleLookup.Rule found && found.rule().appliesTo(target, connection, writeProfile, os)) {
            return found;
        }
        return new RuleLookup.NoRule();
    }
}
