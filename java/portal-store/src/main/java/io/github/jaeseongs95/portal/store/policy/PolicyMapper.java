package io.github.jaeseongs95.portal.store.policy;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import io.github.jaeseongs95.portal.domain.command.CommandNames;
import io.github.jaeseongs95.portal.domain.command.CommandPolicyDocument;
import io.github.jaeseongs95.portal.domain.command.LinuxPolicyName;
import io.github.jaeseongs95.portal.domain.command.PolicyName;
import io.github.jaeseongs95.portal.domain.command.TargetRule;
import io.github.jaeseongs95.portal.domain.command.WindowsPolicyName;
import io.github.jaeseongs95.portal.domain.id.TargetId;
import io.github.jaeseongs95.portal.domain.target.ProfileAlias;
import io.github.jaeseongs95.portal.domain.target.TargetConnection;
import io.github.jaeseongs95.portal.domain.target.TargetOs;

/**
 * Converts between the domain {@link CommandPolicyDocument} and the rows of the two child tables ({@code command_policy_rule} and {@code command_policy_name}).
 * The domain keeps no Spring Data annotation (rule A-DOMAIN), so the rows are separate records and this component is the only place that knows both.
 */
@Component
class PolicyMapper {

    /** One row per target rule, using each target's computed monotonic {@code ruleRevision}. */
    List<PolicyRuleRow> ruleRows(CommandPolicyDocument document, Map<TargetId, Long> ruleRevisions) {
        List<PolicyRuleRow> rows = new ArrayList<>();
        for (TargetRule rule : document.targets()) {
            rows.add(new PolicyRuleRow(rule.targetId().value(), rule.targetConnection().hex(), ruleRevisions.get(rule.targetId()), rule.profile().value(), rule.os(),
                rule.automaticUploads(), rule.automaticDownloads(), rule.automaticSudo(), rule.testServer(), rule.sourceExecutionContract()));
        }
        return rows;
    }

    /** One row per listed name, numbered from 0 within its list. */
    List<PolicyNameRow> nameRows(CommandPolicyDocument document) {
        List<PolicyNameRow> rows = new ArrayList<>();
        for (TargetRule rule : document.targets()) {
            addNames(rows, rule.targetId().value(), PolicyListKind.ALLOWED, rule.commands());
            addNames(rows, rule.targetId().value(), PolicyListKind.DENIED, rule.deniedCommands());
        }
        return rows;
    }

    /** Rebuilds a single target rule from its own rule row and name rows. */
    TargetRule rule(PolicyRuleRow rule, List<PolicyNameRow> ownNames) {
        return new TargetRule(new TargetId(rule.targetId()), TargetConnection.of(rule.targetConnection()), new ProfileAlias(rule.profile()), rule.os(),
            namesOf(rule.os(), ownNames, PolicyListKind.ALLOWED), namesOf(rule.os(), ownNames, PolicyListKind.DENIED), rule.automaticUploads(),
            rule.automaticDownloads(), rule.automaticSudo(), rule.testServer(), rule.sourceContract());
    }

    /** Rebuilds the document from the rows; the rules come in the order of the given rule rows. */
    CommandPolicyDocument document(List<PolicyRuleRow> rules, List<PolicyNameRow> names) {
        Map<String, List<PolicyNameRow>> namesByTarget = names.stream().collect(Collectors.groupingBy(PolicyNameRow::targetId));
        List<TargetRule> targets = new ArrayList<>();
        for (PolicyRuleRow rule : rules) {
            targets.add(rule(rule, namesByTarget.getOrDefault(rule.targetId(), List.of())));
        }
        return new CommandPolicyDocument(targets);
    }

    private void addNames(List<PolicyNameRow> rows, String targetId, PolicyListKind kind, CommandNames names) {
        int position = 0;
        for (PolicyName name : names.names()) {
            rows.add(new PolicyNameRow(targetId, kind, position, name.text()));
            position++;
        }
    }

    private CommandNames namesOf(TargetOs os, List<PolicyNameRow> rows, PolicyListKind kind) {
        List<PolicyName> names = rows.stream().filter(row -> row.listKind() == kind).sorted(Comparator.comparingInt(PolicyNameRow::position))
            .map(row -> policyName(os, row.commandName())).toList();
        return new CommandNames(names);
    }

    private PolicyName policyName(TargetOs os, String text) {
        return switch (os) {
            case LINUX -> new LinuxPolicyName(text);
            case WINDOWS -> new WindowsPolicyName(text);
        };
    }
}
