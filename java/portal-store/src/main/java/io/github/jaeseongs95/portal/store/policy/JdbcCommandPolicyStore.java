package io.github.jaeseongs95.portal.store.policy;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.jdbc.core.JdbcAggregateOperations;
import org.springframework.stereotype.Repository;

import io.github.jaeseongs95.portal.app.policy.CommandPolicyStore;
import io.github.jaeseongs95.portal.domain.command.CommandPolicyDocument;
import io.github.jaeseongs95.portal.domain.command.PolicySnapshot;
import io.github.jaeseongs95.portal.domain.command.RuleLookup;
import io.github.jaeseongs95.portal.domain.command.TargetPolicy;
import io.github.jaeseongs95.portal.domain.command.TargetRule;
import io.github.jaeseongs95.portal.domain.error.RequestErrorCode;
import io.github.jaeseongs95.portal.domain.error.RequestRefusedException;
import io.github.jaeseongs95.portal.domain.id.TargetId;

/**
 * {@link CommandPolicyStore} over the tables of the migration V4 (design 8.2, 2.x {@code command-state.mjs}). The policy is a single revision row plus child rows
 * for the rules and names. {@link #replace} is a compare-and-set: one conditional UPDATE of the revision decides the winner (the row lock makes a second
 * writer wait for the first commit and then match no row), and the winner replaces the child rows in the same transaction, so the child rows always belong to
 * the revision that is stored. The rules come back ordered by target id.
 */
@Repository
public class JdbcCommandPolicyStore implements CommandPolicyStore {

    private static final int THE_ROW = 1;

    private final CommandPolicyRowRepository policies;
    private final PolicyRuleRowRepository rules;
    private final PolicyNameRowRepository names;
    private final JdbcAggregateOperations aggregates;
    private final PolicyMapper mapper;

    public JdbcCommandPolicyStore(CommandPolicyRowRepository policies, PolicyRuleRowRepository rules, PolicyNameRowRepository names,
                                  JdbcAggregateOperations aggregates, PolicyMapper mapper) {
        this.policies = policies;
        this.rules = rules;
        this.names = names;
        this.aggregates = aggregates;
        this.mapper = mapper;
    }

    /** The revision and the child rows are read in one snapshot owned by the calling service transaction. */
    @Override
    public PolicySnapshot current() {
        CommandPolicyRow row = policies.findById(THE_ROW)
            .orElseThrow(() -> new IllegalStateException("The command policy row (id 1) is missing; the migration V4 inserts it"));
        return new PolicySnapshot(row.revision(), mapper.document(rules.findAllOrdered(), names.findAllOrdered()));
    }

    /** Reads only the target's rule row and its name rows in the caller's {@code REPEATABLE_READ} snapshot (defect D-0121). */
    @Override
    public TargetPolicy targetPolicy(TargetId targetId) {
        Objects.requireNonNull(targetId, "targetId");
        Optional<PolicyRuleRow> found = rules.findByTargetId(targetId.value());
        if (found.isEmpty()) {
            return TargetPolicy.none();
        }
        PolicyRuleRow row = found.orElseThrow();
        TargetRule rule = mapper.rule(row, names.findByTargetIdOrdered(targetId.value()));
        return new TargetPolicy(row.ruleRevision(), new RuleLookup.Rule(rule));
    }

    @Override
    public PolicySnapshot replace(CommandPolicyDocument next, long expectedRevision) {
        if (policies.advanceRevision(expectedRevision) != 1) {
            // Another writer moved the revision first (or the caller read an older one): the caller reads the policy again and decides again, which only this code says (D-0164).
            throw new RequestRefusedException(RequestErrorCode.POLICY_REVISION_CONFLICT);
        }
        long nextRevision = expectedRevision + 1;
        List<PolicyRuleRow> previousRows = rules.findAllOrdered();
        Map<String, Long> previousRevisions = new HashMap<>();
        for (PolicyRuleRow row : previousRows) {
            previousRevisions.put(row.targetId(), row.ruleRevision());
        }
        CommandPolicyDocument previousDoc = mapper.document(previousRows, names.findAllOrdered());
        Map<TargetId, TargetRule> previousRules = new HashMap<>();
        for (TargetRule rule : previousDoc.targets()) {
            previousRules.put(rule.targetId(), rule);
        }
        Map<TargetId, Long> nextRuleRevisions = new HashMap<>();
        for (TargetRule rule : next.targets()) {
            TargetRule prior = previousRules.get(rule.targetId());
            long ruleRev = prior != null && prior.equals(rule) ? previousRevisions.get(rule.targetId().value()) : nextRevision;
            nextRuleRevisions.put(rule.targetId(), ruleRev);
        }
        names.deleteAllNames();
        rules.deleteAllRules();
        try {
            for (PolicyRuleRow row : mapper.ruleRows(next, nextRuleRevisions)) {
                aggregates.insert(row);
            }
            for (PolicyNameRow row : mapper.nameRows(next)) {
                aggregates.insert(row);
            }
        } catch (DuplicateKeyException e) {
            // Not a conflict that a new read resolves: the revision was won in this transaction, so the document itself holds a key twice (CommandPolicyDocument refuses a
            // target twice before it gets here). The refusal rolls the transaction, and with it the advanced revision, back.
            throw new RequestRefusedException(RequestErrorCode.REQUEST_REFUSED, e);
        }
        return new PolicySnapshot(nextRevision, next);
    }
}
