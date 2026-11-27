package io.github.jaeseongs95.opermand.app.operation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.jaeseongs95.opermand.app.policy.CommandPolicyStore;
import io.github.jaeseongs95.opermand.app.remote.AgentCliPlan;
import io.github.jaeseongs95.opermand.app.remote.ElevationPlan;
import io.github.jaeseongs95.opermand.app.remote.ExecReply;
import io.github.jaeseongs95.opermand.app.remote.PathPolicyBinding;
import io.github.jaeseongs95.opermand.app.remote.PermissionSupport;
import io.github.jaeseongs95.opermand.app.remote.PreparedCommand;
import io.github.jaeseongs95.opermand.app.remote.SourceArguments;
import io.github.jaeseongs95.opermand.app.remote.WorkingDirectory;
import io.github.jaeseongs95.opermand.app.target.TargetPlatform;
import io.github.jaeseongs95.opermand.app.target.TargetPlatformRegistry;
import io.github.jaeseongs95.opermand.domain.agentcli.AgentCliIntent;
import io.github.jaeseongs95.opermand.domain.agentcli.AgentRelease;
import io.github.jaeseongs95.opermand.domain.classify.CommandClass;
import io.github.jaeseongs95.opermand.domain.classify.CommandClassification;
import io.github.jaeseongs95.opermand.domain.command.CommandLineAnalysis;
import io.github.jaeseongs95.opermand.domain.command.CommandLineGrammar;
import io.github.jaeseongs95.opermand.domain.command.CommandNames;
import io.github.jaeseongs95.opermand.domain.command.CommandPolicyDocument;
import io.github.jaeseongs95.opermand.domain.command.Completeness;
import io.github.jaeseongs95.opermand.domain.command.LinuxPolicyName;
import io.github.jaeseongs95.opermand.domain.command.NormalizedName;
import io.github.jaeseongs95.opermand.domain.command.PolicyName;
import io.github.jaeseongs95.opermand.domain.command.PolicySnapshot;
import io.github.jaeseongs95.opermand.domain.command.RuleApprovalPolicy;
import io.github.jaeseongs95.opermand.domain.command.RuleLookup;
import io.github.jaeseongs95.opermand.domain.command.SourceContract;
import io.github.jaeseongs95.opermand.domain.command.SudoUse;
import io.github.jaeseongs95.opermand.domain.command.TargetPolicy;
import io.github.jaeseongs95.opermand.domain.command.TargetRule;
import io.github.jaeseongs95.opermand.domain.error.DispatchFailure;
import io.github.jaeseongs95.opermand.domain.error.DispatchReason;
import io.github.jaeseongs95.opermand.domain.error.DispatchRefusedException;
import io.github.jaeseongs95.opermand.domain.error.DispatchStage;
import io.github.jaeseongs95.opermand.domain.file.FileBinding;
import io.github.jaeseongs95.opermand.domain.file.FileName;
import io.github.jaeseongs95.opermand.domain.id.ArtifactId;
import io.github.jaeseongs95.opermand.domain.operation.Elevation;
import io.github.jaeseongs95.opermand.domain.id.Digest;
import io.github.jaeseongs95.opermand.domain.id.Digester;
import io.github.jaeseongs95.opermand.domain.id.OwnerId;
import io.github.jaeseongs95.opermand.domain.id.RequestKey;
import io.github.jaeseongs95.opermand.domain.id.Sha256Digester;
import io.github.jaeseongs95.opermand.domain.id.TargetId;
import io.github.jaeseongs95.opermand.domain.operation.Admission;
import io.github.jaeseongs95.opermand.domain.operation.AgentCliInput;
import io.github.jaeseongs95.opermand.domain.operation.CommandInput;
import io.github.jaeseongs95.opermand.domain.operation.ConflictKey;
import io.github.jaeseongs95.opermand.domain.operation.Deadline;
import io.github.jaeseongs95.opermand.domain.operation.DecisionReason;
import io.github.jaeseongs95.opermand.domain.operation.DownloadInput;
import io.github.jaeseongs95.opermand.domain.operation.Elevation;
import io.github.jaeseongs95.opermand.domain.operation.NewOperation;
import io.github.jaeseongs95.opermand.domain.operation.OperationInput;
import io.github.jaeseongs95.opermand.domain.operation.OverwriteMode;
import io.github.jaeseongs95.opermand.domain.operation.UploadInput;
import io.github.jaeseongs95.opermand.domain.operation.UploadProtocol;
import io.github.jaeseongs95.opermand.domain.path.PathSyntax;
import io.github.jaeseongs95.opermand.domain.path.PosixRemotePath;
import io.github.jaeseongs95.opermand.domain.path.RemotePath;
import io.github.jaeseongs95.opermand.domain.target.AccessMode;
import io.github.jaeseongs95.opermand.domain.target.AuthMethod;
import io.github.jaeseongs95.opermand.domain.target.CapacityOverride;
import io.github.jaeseongs95.opermand.domain.target.ConnectionProfile;
import io.github.jaeseongs95.opermand.domain.target.DesktopSettings;
import io.github.jaeseongs95.opermand.domain.target.ProfileAlias;
import io.github.jaeseongs95.opermand.domain.target.ResolvedTarget;
import io.github.jaeseongs95.opermand.domain.target.Setting;
import io.github.jaeseongs95.opermand.domain.target.Target;
import io.github.jaeseongs95.opermand.domain.target.TargetCapabilities;
import io.github.jaeseongs95.opermand.domain.target.TargetDisplayName;
import io.github.jaeseongs95.opermand.domain.target.TargetOs;
import io.github.jaeseongs95.opermand.domain.target.Toggle;
import io.github.jaeseongs95.opermand.domain.target.TtyMode;
import io.github.jaeseongs95.opermand.domain.target.WindowsRuntime;
import io.github.jaeseongs95.opermand.domain.text.CommandText;
import io.github.jaeseongs95.opermand.domain.text.SourceText;
import io.github.jaeseongs95.opermand.domain.text.Utf8Text;

/**
 * What the judge hands to the rule (defect D-0119): S-D0119-05 (a read-only command runs by rule only on the read profile, and the owner's deny list reaches it),
 * S-D0119-08 and -09 (the elevation and the overwrite of a file operation are the arguments of the judgement), S-D0119-14 (an agent CLI operation is always the owner's)
 * and the deny list read by the target's platform (D5).
 */
class ApprovalJudgeTest {

    private static final Digester DIGESTER = new Sha256Digester();
    private static final TargetId ID = new TargetId("known");
    private static final ProfileAlias READ = new ProfileAlias("known-ro");
    private static final ProfileAlias WRITE = new ProfileAlias("known-rw");

    private final ResolvedTarget target = target();
    private final AtomicInteger deniesAsked = new AtomicInteger();
    private final Rules rules = new Rules();
    private final ApprovalJudge judge = new ApprovalJudge(rules, new TargetPlatformRegistry(List.of(new Stub(TargetOs.LINUX), new Stub(TargetOs.WINDOWS))),
        new RuleApprovalPolicy());

    private void rule(boolean testServer, boolean uploads, boolean downloads, boolean sudo, List<String> allowed, List<String> denied) {
        rules.rule = new TargetRule(ID, target.connection(), WRITE, TargetOs.LINUX, names(allowed), names(denied), uploads ? Toggle.ON : Toggle.OFF,
            downloads ? Toggle.ON : Toggle.OFF, sudo ? Toggle.ON : Toggle.OFF, testServer ? Toggle.ON : Toggle.OFF, SourceContract.NONE);
        if (rules.revision < 1L) {
            rules.revision = 1L;
        }
    }

    private static CommandNames names(List<String> texts) {
        List<PolicyName> names = new java.util.ArrayList<>();
        for (String text : texts) {
            names.add(new LinuxPolicyName(text));
        }
        return new CommandNames(names);
    }

    private DecisionReason reasonOf(Admission admission) {
        return admission.decision().reason();
    }

    private NewOperation spec(ProfileAlias profile, OperationInput input) {
        return NewOperation.of(DIGESTER, new RequestKey("judge-key-" + System.nanoTime() + "-0000"), new OwnerId("owner"), ID, target.revision(), profile, input,
            new ConflictKey.None(), new Deadline.None());
    }

    private NewOperation command(ProfileAlias profile, String text) {
        return spec(profile, new CommandInput(new CommandText(text), new Utf8Text(""), new Utf8Text("")));
    }

    @Test
    @DisplayName("S-D0119-05 a read-only command runs by rule on the read profile only; on the write profile it is judged like any other command")
    void readOnlyRunsOnTheReadProfileOnly() {
        assertThat(judge.judge(command(READ, "cat /tmp/a"), target)).isInstanceOf(Admission.Queue.class);
        assertThat(reasonOf(judge.judge(command(READ, "cat /tmp/a"), target))).isEqualTo(DecisionReason.READ_ONLY);
        Admission onWrite = judge.judge(command(WRITE, "cat /tmp/a"), target);
        assertThat(onWrite).isInstanceOf(Admission.Review.class);
        assertThat(reasonOf(onWrite)).isEqualTo(DecisionReason.NO_MATCHING_RULE);
        rule(false, false, false, false, List.of("cat"), List.of());
        assertThat(reasonOf(judge.judge(command(WRITE, "cat /tmp/a"), target))).as("the allow list still lets it run").isEqualTo(DecisionReason.COMMAND_SET);
    }

    @Test
    @DisplayName("S-D0119-05 the owner's deny list is looked up with the write profile and stops a read-only command on the read profile")
    void theDenyListReachesTheReadProfile() {
        rule(false, false, false, false, List.of(), List.of("cat"));
        Admission read = judge.judge(command(READ, "cat /tmp/a"), target);
        assertThat(read).isInstanceOf(Admission.Deny.class);
        assertThat(reasonOf(read)).isEqualTo(DecisionReason.POLICY_DENIED);
        assertThat(reasonOf(judge.judge(command(WRITE, "cat /tmp/a"), target))).isEqualTo(DecisionReason.POLICY_DENIED);
    }

    @Test
    @DisplayName("S-D0119-05 D5 the deny list is read by the target's platform: the judge asks the platform, which for Windows compares the last path segment in lower case")
    void theDenyListIsReadByThePlatform() {
        rule(false, false, false, false, List.of(), List.of("rm"));
        int before = deniesAsked.get();
        Admission admission = judge.judge(command(WRITE, "REMOVE-ITEM C:\\x"), target);
        assertThat(deniesAsked.get()).as("the platform was asked").isGreaterThan(before);
        assertThat(admission).as("the stub platform denies a line that has remove-item, whatever the rule lists").isInstanceOf(Admission.Deny.class);
    }

    @Test
    @DisplayName("S-D0119-08 the elevation and the overwrite of an upload are the arguments of the judgement")
    void anUploadIsJudgedByItsElevationAndOverwrite() {
        rule(false, true, false, false, List.of(), List.of());
        assertThat(reasonOf(judge.judge(spec(WRITE, upload(OverwriteMode.FAIL_IF_EXISTS, Elevation.NONE)), target))).isEqualTo(DecisionReason.AUTOMATIC_UPLOAD);
        assertThat(reasonOf(judge.judge(spec(WRITE, upload(OverwriteMode.REPLACE, Elevation.NONE)), target))).isEqualTo(DecisionReason.NO_MATCHING_RULE);
        assertThat(reasonOf(judge.judge(spec(WRITE, upload(OverwriteMode.FAIL_IF_EXISTS, Elevation.SUDO)), target))).isEqualTo(DecisionReason.NO_MATCHING_RULE);
    }

    @Test
    @DisplayName("S-D0119-09 the elevation of a download is the argument of the judgement: elevation also needs the sudo switch")
    void aDownloadIsJudgedByItsElevation() {
        rule(false, false, true, false, List.of(), List.of());
        assertThat(reasonOf(judge.judge(spec(WRITE, download(Elevation.NONE)), target))).isEqualTo(DecisionReason.AUTOMATIC_DOWNLOAD);
        assertThat(reasonOf(judge.judge(spec(WRITE, download(Elevation.SUDO)), target))).isEqualTo(DecisionReason.NO_MATCHING_RULE);
        rule(false, false, true, true, List.of(), List.of());
        assertThat(reasonOf(judge.judge(spec(WRITE, download(Elevation.SUDO)), target))).isEqualTo(DecisionReason.AUTOMATIC_DOWNLOAD);
    }

    @Test
    @DisplayName("S-D0119-14 an agent CLI operation is always the owner's, whatever the rule says, even the test server switch")
    void anAgentCliOperationIsAlwaysReviewed() {
        rule(true, true, true, true, List.of("touch"), List.of());
        Admission admission = judge.judge(spec(WRITE, new AgentCliInput(new Digest("e".repeat(64)))), target);
        assertThat(admission).isInstanceOf(Admission.Review.class);
        assertThat(reasonOf(admission)).isEqualTo(DecisionReason.AGENT_CLI_REVIEW);
    }

    @Test
    @DisplayName("S-D0121-01 the decision keeps the target rule's own ruleRevision, or 0 when no rule applies")
    void theDecisionKeepsThePolicyRevision() {
        assertThat(judge.judge(command(WRITE, "touch x"), target).decision().policyRevision()).isZero();
        rule(false, false, false, false, List.of("touch"), List.of());
        rules.revision = 9;
        assertThat(judge.judge(command(WRITE, "touch x"), target).decision().policyRevision()).isEqualTo(9L);
    }

    @Test
    @DisplayName("S-D0121-02 S-D0121-03 checkBeforeDispatch and checkBeforeOwnerApprove enforce POLICY_DENIED (unless testServer=ON) and POLICY_CHANGED while preserving READ_ONLY")
    void preDispatchAndOwnerApproveReevaluateCurrentRule() {
        Instant now = Instant.parse("2026-11-19T00:00:00Z");
        // Automatic upload revoked before dispatch -> POLICY_CHANGED
        rule(false, true, false, false, List.of(), List.of());
        NewOperation uploadSpec = spec(WRITE, upload(OverwriteMode.FAIL_IF_EXISTS, Elevation.NONE));
        var uploadOp = io.github.jaeseongs95.opermand.domain.operation.Operation.admit(uploadSpec, new io.github.jaeseongs95.opermand.domain.id.OperationId("1".repeat(48)),
            now, judge.judge(uploadSpec, target));
        rule(false, false, false, false, List.of(), List.of());
        assertThatThrownBy(() -> judge.checkBeforeDispatch(uploadOp, target)).isInstanceOfSatisfying(DispatchRefusedException.class,
            e -> assertThat(e.failure()).isEqualTo(DispatchFailure.refused(DispatchReason.POLICY_CHANGED, DispatchStage.POLICY_GUARD)));

        // READ_ONLY survives rule deletion, but is stopped by deniedCommands unless testServer=ON
        rules.rule = null;
        NewOperation readSpec = command(READ, "cat /tmp/a");
        var readOp = io.github.jaeseongs95.opermand.domain.operation.Operation.admit(readSpec, new io.github.jaeseongs95.opermand.domain.id.OperationId("2".repeat(48)),
            now, judge.judge(readSpec, target));
        judge.checkBeforeDispatch(readOp, target);

        rule(false, false, false, false, List.of(), List.of("cat"));
        assertThatThrownBy(() -> judge.checkBeforeDispatch(readOp, target)).isInstanceOfSatisfying(DispatchRefusedException.class,
            e -> assertThat(e.failure()).isEqualTo(DispatchFailure.refused(DispatchReason.POLICY_DENIED, DispatchStage.POLICY_GUARD)));
        assertThatThrownBy(() -> judge.checkBeforeOwnerApprove(readOp, target)).isInstanceOfSatisfying(DispatchRefusedException.class,
            e -> assertThat(e.failure()).isEqualTo(DispatchFailure.refused(DispatchReason.POLICY_DENIED, DispatchStage.POLICY_GUARD)));

        // testServer = ON takes precedence over deniedCommands
        rule(true, false, false, false, List.of(), List.of("cat"));
        judge.checkBeforeOwnerApprove(readOp, target);
        judge.checkBeforeDispatch(readOp, target);
    }

    private static UploadInput upload(OverwriteMode overwrite, Elevation elevation) {
        return new UploadInput(new ArtifactId("b".repeat(48)), new PosixRemotePath("/tmp/file.bin"), new FileName("file.bin"), 10, new Digest("d".repeat(64)), overwrite, elevation,
            new FileBinding.Unbound(), UploadProtocol.UNSPECIFIED);
    }

    private static DownloadInput download(Elevation elevation) {
        return new DownloadInput(RemotePath.parse("/var/log/x"), elevation, new FileBinding.Unbound(), new Utf8Text("c"), new Utf8Text("d"));
    }

    private static ResolvedTarget target() {
        Target entry = new Target(ID, new TargetDisplayName("Known one"), READ, WRITE, new ProfileAlias("cred1"), Toggle.ON, TargetOs.LINUX, WindowsRuntime.none(),
            new CapacityOverride.Inherited(), new CapacityOverride.Inherited(), new DesktopSettings.NoDesktop());
        return ResolvedTarget.of(entry, profile(AccessMode.READ_ONLY), profile(AccessMode.READ_WRITE), DIGESTER);
    }

    private static ConnectionProfile profile(AccessMode access) {
        return new ConnectionProfile("host.example", 22, "deploy", Setting.given("SHA256:test-host-key"), Setting.unset(), AuthMethod.AGENT, Setting.unset(), Setting.unset(),
            access, TtyMode.NO_TTY);
    }

    /** The policy of the installation: one rule or none, at a revision. */
    private static final class Rules implements CommandPolicyStore {

        TargetRule rule;
        long revision;

        @Override
        public PolicySnapshot current() {
            return new PolicySnapshot(revision, rule == null ? CommandPolicyDocument.empty() : new CommandPolicyDocument(List.of(rule)));
        }

        @Override
        public TargetPolicy targetPolicy(TargetId targetId) {
            if (rule == null || !rule.targetId().equals(targetId)) {
                return TargetPolicy.none();
            }
            return new TargetPolicy(revision, new RuleLookup.Rule(rule));
        }

        @Override
        public PolicySnapshot replace(CommandPolicyDocument next, long expectedRevision) {
            throw new IllegalStateException("not used by the judge");
        }
    }

    /**
     * A platform that classifies {@code cat} and {@code ls} as read-only, reads the first word as the one name, and denies a line that has {@code remove-item} in any letter
     * case, counting each time it is asked.
     */
    private final class Stub implements TargetPlatform {

        private final TargetOs os;

        Stub(TargetOs os) {
            this.os = os;
        }

        @Override
        public TargetOs os() {
            return os;
        }

        @Override
        public PathSyntax pathSyntax() {
            return os == TargetOs.WINDOWS ? PathSyntax.WINDOWS : PathSyntax.POSIX;
        }

        @Override
        public TargetCapabilities capabilities(Target target) {
            throw new IllegalStateException("not used by the judge");
        }

        @Override
        public CommandClassification classify(CommandText command) {
            Predicate<String> reads = text -> text.startsWith("cat ") || text.startsWith("ls");
            return CommandClassification.of(reads.test(command.value()) ? CommandClass.READ_ONLY : CommandClass.SAFE);
        }

        @Override
        public CommandLineGrammar commandLineGrammar() {
            return new CommandLineGrammar() {
                @Override
                public CommandLineAnalysis analyze(CommandText command) {
                    String[] words = command.value().trim().split("\\s+");
                    return new CommandLineAnalysis(List.of(words[0]), Completeness.COMPLETE, SudoUse.NO_SUDO);
                }

                @Override
                public NormalizedName normalize(String proposedName) {
                    return new NormalizedName.Accepted(new LinuxPolicyName(proposedName));
                }

                @Override
                public TargetOs platform() {
                    return os;
                }
            };
        }

        @Override
        public boolean deniesName(TargetRule rule, CommandText command) {
            deniesAsked.incrementAndGet();
            return command.value().toLowerCase(java.util.Locale.ROOT).contains("remove-item") || rule.denies(commandLineGrammar().analyze(command), command);
        }

        @Override
        public PreparedCommand prepareCommand(CommandText command, WorkingDirectory workdir) {
            throw new IllegalStateException("not used by the judge");
        }

        @Override
        public PreparedCommand prepareSource(SourceText source, SourceArguments args, WorkingDirectory workdir,
                                             io.github.jaeseongs95.opermand.domain.target.SourceRuntime runtime, Elevation elevation) {
            throw new IllegalStateException("not used by the judge");
        }

        @Override
        public PathPolicyBinding pathPolicy(Target target) {
            throw new IllegalStateException("not used by the judge");
        }

        @Override
        public PermissionSupport permissionSupport() {
            throw new IllegalStateException("not used by the judge");
        }

        @Override
        public ElevationPlan elevationPlan() {
            throw new IllegalStateException("not used by the judge");
        }

        @Override
        public AgentCliPlan agentCliPlan(AgentCliIntent intent, AgentRelease release) {
            throw new IllegalStateException("not used by the judge");
        }

        @Override
        public ExecReply execReply() {
            throw new IllegalStateException("not used by the judge");
        }
    }
}
