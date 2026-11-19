package io.github.jaeseongs95.portal.domain.command;

import io.github.jaeseongs95.portal.domain.id.TargetId;
import io.github.jaeseongs95.portal.domain.target.ProfileAlias;
import io.github.jaeseongs95.portal.domain.target.TargetConnection;
import io.github.jaeseongs95.portal.domain.target.TargetOs;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The owner's command policy: at most 32 rules, one per target (command-policy.mjs:43-71). The revision of the policy is not part of the document; it belongs to
 * the stored copy and travels with it in a {@link PolicySnapshot}.
 */
public record CommandPolicyDocument(List<TargetRule> targets) {

    /** The most targets a policy may have a rule for. */
    public static final int MAX_TARGETS = 32;

    public CommandPolicyDocument {
        targets = List.copyOf(targets);
        if (targets.size() > MAX_TARGETS) {
            throw new IllegalArgumentException("A command policy has at most " + MAX_TARGETS + " target rules");
        }
        Set<TargetId> seen = new HashSet<>();
        for (TargetRule rule : targets) {
            if (!seen.add(rule.targetId())) {
                throw new IllegalArgumentException("A command policy has one rule per target: " + rule.targetId().value());
            }
        }
    }

    /** The policy with no rule at all: nothing is pre-approved. */
    public static CommandPolicyDocument empty() {
        return new CommandPolicyDocument(List.of());
    }

    /** The rule for this target, connection identity, write profile and operating system; a rule for another connection, profile or OS is no rule for this one. */
    public RuleLookup ruleFor(TargetId target, TargetConnection connection, ProfileAlias writeProfile, TargetOs os) {
        for (TargetRule rule : targets) {
            if (rule.appliesTo(target, connection, writeProfile, os)) {
                return new RuleLookup.Rule(rule);
            }
        }
        return new RuleLookup.NoRule();
    }
}
