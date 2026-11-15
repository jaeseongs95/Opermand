package io.github.jaeseongs95.portal.app.operation;

import org.springframework.stereotype.Service;

import io.github.jaeseongs95.portal.app.policy.CommandPolicyStore;
import io.github.jaeseongs95.portal.app.target.TargetPlatform;
import io.github.jaeseongs95.portal.app.target.TargetPlatformRegistry;
import io.github.jaeseongs95.portal.domain.classify.CommandClass;
import io.github.jaeseongs95.portal.domain.command.ApprovalSubject;
import io.github.jaeseongs95.portal.domain.command.CommandLineAnalysis;
import io.github.jaeseongs95.portal.domain.command.PolicySnapshot;
import io.github.jaeseongs95.portal.domain.command.RuleApprovalPolicy;
import io.github.jaeseongs95.portal.domain.command.RuleLookup;
import io.github.jaeseongs95.portal.domain.operation.Admission;
import io.github.jaeseongs95.portal.domain.operation.AgentCliInput;
import io.github.jaeseongs95.portal.domain.operation.CommandInput;
import io.github.jaeseongs95.portal.domain.operation.DecisionReason;
import io.github.jaeseongs95.portal.domain.operation.DownloadInput;
import io.github.jaeseongs95.portal.domain.operation.Elevation;
import io.github.jaeseongs95.portal.domain.operation.NewOperation;
import io.github.jaeseongs95.portal.domain.operation.OverwriteMode;
import io.github.jaeseongs95.portal.domain.operation.SourceInput;
import io.github.jaeseongs95.portal.domain.operation.UploadInput;
import io.github.jaeseongs95.portal.domain.target.ResolvedTarget;
import io.github.jaeseongs95.portal.domain.text.CommandText;

/**
 * Judges a new operation at intake by the owner's rule (defect D-0119): reads the current policy and the grammar and classification of the target's platform, hands them
 * to the pure {@link RuleApprovalPolicy} and returns the {@link Admission}, whose kind is the first state the operation is stored in. The judgement is the same one every
 * entrance gets, because {@link OperationIntake#submit} calls it. An agent CLI operation is not judged: the owner starts it (decision Q10).
 *
 * <p>The rule is looked up with the target's write profile, whatever profile the operation uses, so the owner's deny list reaches a read-only command on the read profile
 * too. A command runs as read-only only on the target's read profile (the read tool's path, as in 2.x); the write profile may be a privileged account, so a read-only command
 * submitted for it is judged like any other: deny list, allow list, the owner. A submitted source is judged without its text, which the intake does not hold (only its launcher and arguments); it never runs by rule alone, so a denied word in the
 * source body is not seen here; the owner reads the source before approving.
 */
@Service
public class ApprovalJudge {

    private final CommandPolicyStore policies;
    private final TargetPlatformRegistry platforms;
    private final RuleApprovalPolicy rules;

    public ApprovalJudge(CommandPolicyStore policies, TargetPlatformRegistry platforms, RuleApprovalPolicy rules) {
        this.policies = policies;
        this.platforms = platforms;
        this.rules = rules;
    }

    /** The admission of {@code spec}, whose target is {@code target} at the revision the request holds. */
    public Admission judge(NewOperation spec, ResolvedTarget target) {
        PolicySnapshot policy = policies.current();
        RuleLookup lookup = policy.document().ruleFor(spec.targetId(), spec.targetRevision(), target.target().writeProfile());
        TargetPlatform platform = platforms.platformOf(target.target().os());
        boolean readProfile = spec.profile().equals(target.target().readProfile());
        DecisionReason reason = switch (spec.input()) {
            case CommandInput command -> rules.decideCommand(lookup, shell(platform, command.command()), deniedOnTarget(platform, lookup, command.command()),
                readProfile && isReadOnly(platform, command.command()));
            case SourceInput source -> {
                ApprovalSubject subject = submitted(platform, source);
                yield rules.decideCommand(lookup, subject, lookup instanceof RuleLookup.Rule found && subject.deniedBy(found.rule()), false);
            }
            case UploadInput upload -> rules.decideUpload(lookup, upload.elevation() == Elevation.SUDO, upload.overwrite() == OverwriteMode.REPLACE);
            case DownloadInput download -> rules.decideDownload(lookup, download.elevation() == Elevation.SUDO);
            case AgentCliInput agentCli -> DecisionReason.AGENT_CLI_REVIEW;
        };
        return Admission.of(reason, spec.kind(), policy.revision());
    }

    /** Whether the owner's deny list forbids the command, as the target's platform reads names (2.x {@code deniedForTarget}: Windows compares the last path segment in lower case). */
    private static boolean deniedOnTarget(TargetPlatform platform, RuleLookup lookup, CommandText command) {
        return lookup instanceof RuleLookup.Rule found && platform.deniesName(found.rule(), command);
    }

    private static boolean isReadOnly(TargetPlatform platform, CommandText command) {
        return platform.classify(command).forMultiline(command).commandClass() == CommandClass.READ_ONLY;
    }

    private static ApprovalSubject shell(TargetPlatform platform, CommandText command) {
        return new ApprovalSubject.ShellCommand(command, platform.commandLineGrammar().analyze(command));
    }

    private static ApprovalSubject submitted(TargetPlatform platform, SourceInput source) {
        CommandLineAnalysis analysis = platform.commandLineGrammar().analyze(source.launcher());
        String scanned = source.launcher().value() + " " + source.argvJson().value();
        return new ApprovalSubject.SubmittedSource(source.launcher(), analysis, scanned, ApprovalSubject.EvidenceSize.WITHIN_LIMIT);
    }
}
