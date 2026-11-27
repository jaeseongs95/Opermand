package io.github.jaeseongs95.opermand.server.usecase;

import java.util.List;
import java.util.Objects;

import io.github.jaeseongs95.opermand.app.remote.AgentCliPlan;
import io.github.jaeseongs95.opermand.app.remote.CommandKind;
import io.github.jaeseongs95.opermand.app.remote.ElevationPlan;
import io.github.jaeseongs95.opermand.app.remote.ExecReply;
import io.github.jaeseongs95.opermand.app.remote.FixedRemoteCommand;
import io.github.jaeseongs95.opermand.app.remote.PathPolicyBinding;
import io.github.jaeseongs95.opermand.app.remote.PermissionSupport;
import io.github.jaeseongs95.opermand.app.remote.PreparedCommand;
import io.github.jaeseongs95.opermand.app.remote.SourceArguments;
import io.github.jaeseongs95.opermand.app.remote.StdinBytes;
import io.github.jaeseongs95.opermand.app.remote.UnsupportedReason;
import io.github.jaeseongs95.opermand.app.remote.WorkingDirectory;
import io.github.jaeseongs95.opermand.app.target.TargetPlatform;
import io.github.jaeseongs95.opermand.domain.agentcli.AgentCliIntent;
import io.github.jaeseongs95.opermand.domain.agentcli.AgentRelease;
import io.github.jaeseongs95.opermand.domain.classify.CommandClassification;
import io.github.jaeseongs95.opermand.domain.classify.CommandClassifier;
import io.github.jaeseongs95.opermand.domain.command.CommandLineAnalysis;
import io.github.jaeseongs95.opermand.domain.command.CommandLineGrammar;
import io.github.jaeseongs95.opermand.domain.command.Completeness;
import io.github.jaeseongs95.opermand.domain.command.LinuxPolicyName;
import io.github.jaeseongs95.opermand.domain.command.NormalizedName;
import io.github.jaeseongs95.opermand.domain.command.SudoUse;
import io.github.jaeseongs95.opermand.domain.command.WindowsPolicyName;
import io.github.jaeseongs95.opermand.domain.command.TargetRule;
import io.github.jaeseongs95.opermand.domain.error.RequestRefusedException;
import io.github.jaeseongs95.opermand.domain.error.TransferErrorCode;
import io.github.jaeseongs95.opermand.domain.operation.Elevation;
import io.github.jaeseongs95.opermand.domain.path.PathSyntax;
import io.github.jaeseongs95.opermand.domain.target.SourceRuntime;
import io.github.jaeseongs95.opermand.domain.target.Target;
import io.github.jaeseongs95.opermand.domain.target.TargetCapabilities;
import io.github.jaeseongs95.opermand.domain.target.TargetOs;
import io.github.jaeseongs95.opermand.domain.text.CommandText;
import io.github.jaeseongs95.opermand.domain.text.SourceText;

/**
 * The operating system boundary of the use case tests: a platform that passes the command text on unchanged and classifies it with the real vendor classifier. The two
 * real platforms are beans of opermand-ssh. A command that contains {@link #TOO_LARGE} is refused the way the Windows platform refuses a line of more than 8000
 * characters, so a test can see what the dispatcher does with a refusal of the platform.
 */
final class TestPlatform implements TargetPlatform {

    static final String TOO_LARGE = "WINDOWS-LINE-TOO-LARGE";
    static final String WINDOWS_ADMIN_PROBE = "powershell.exe -NoLogo admin-probe";

    private final TargetOs os;
    private final CommandClassifier classifier;

    TestPlatform(TargetOs os, CommandClassifier classifier) {
        this.os = Objects.requireNonNull(os, "os");
        this.classifier = Objects.requireNonNull(classifier, "classifier");
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
        return target.capabilities();
    }

    @Override
    public CommandClassification classify(CommandText command) {
        return classifier.classify(command);
    }

    /**
     * A grammar for the use case tests: the first word of the line is the one name, and the analysis is complete when there is one. The tests only need the judgement of the
     * intake to see a name it can look up in a rule.
     */
    @Override
    public CommandLineGrammar commandLineGrammar() {
        return new CommandLineGrammar() {
            @Override
            public CommandLineAnalysis analyze(CommandText command) {
                String[] words = command.value().trim().split("\\s+");
                if (words[0].isEmpty()) {
                    return new CommandLineAnalysis(List.of(), Completeness.INCOMPLETE, SudoUse.NO_SUDO);
                }
                return new CommandLineAnalysis(List.of(words[0]), Completeness.COMPLETE, SudoUse.NO_SUDO);
            }

            @Override
            public NormalizedName normalize(String proposedName) {
                return new NormalizedName.Accepted(os == TargetOs.WINDOWS ? new WindowsPolicyName(proposedName) : new LinuxPolicyName(proposedName));
            }

            @Override
            public TargetOs platform() {
                return os;
            }
        };
    }

    /** As a Linux target does: a denied name is one the grammar finds in the line or one of its words. The intake now asks the platform for the deny list (defect D-0119). */
    @Override
    public boolean deniesName(TargetRule rule, CommandText command) {
        return rule.denies(commandLineGrammar().analyze(command), command);
    }

    @Override
    public PreparedCommand prepareCommand(CommandText command, WorkingDirectory workdir) {
        if (command.value().contains(TOO_LARGE)) {
            throw new RequestRefusedException(TransferErrorCode.WINDOWS_COMMAND_TOO_LARGE);
        }
        return new PreparedCommand(command.value(), StdinBytes.none(), CommandKind.COMMAND);
    }

    @Override
    public PreparedCommand prepareSource(SourceText source, SourceArguments args, WorkingDirectory workdir, SourceRuntime runtime, Elevation elevation) {
        return new PreparedCommand("source:" + runtime, new StdinBytes(source.value().getBytes(java.nio.charset.StandardCharsets.UTF_8)), CommandKind.SOURCE);
    }

    @Override
    public PathPolicyBinding pathPolicy(Target target) {
        return new PathPolicyBinding.NoRootPolicy();
    }

    @Override
    public PermissionSupport permissionSupport() {
        return os == TargetOs.WINDOWS ? new PermissionSupport.Unsupported(UnsupportedReason.WINDOWS_SFTP_CHMOD) : new PermissionSupport.Supported();
    }

    @Override
    public ElevationPlan elevationPlan() {
        return os == TargetOs.WINDOWS
            ? new ElevationPlan.WindowsAdministratorCheck(new FixedRemoteCommand("windows-admin-probe", WINDOWS_ADMIN_PROBE))
            : new ElevationPlan.NotSupported();
    }

    @Override
    public AgentCliPlan agentCliPlan(AgentCliIntent intent, AgentRelease release) {
        throw new IllegalStateException("The use cases of this slice install no agent CLI");
    }

    @Override
    public ExecReply execReply() {
        return ExecReply.AWAITED;
    }
}
