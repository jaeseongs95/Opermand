package io.github.jaeseongs95.portal.domain.command;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jaeseongs95.portal.domain.operation.DecisionKind;
import io.github.jaeseongs95.portal.domain.operation.DecisionReason;
import io.github.jaeseongs95.portal.domain.text.CommandText;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * S-D0119-01 to S-D0119-09: the rule judgement at intake, decided from values only. The order of a command line is the lead's decision Q2: deny list, read-only, allow
 * list, anything else is the owner's.
 */
class RuleApprovalPolicyTest {

    private final RuleApprovalPolicy policy = new RuleApprovalPolicy();

    @Test
    @DisplayName("S-D0119-01 a command whose names are all on the allow list is executed by rule; one unlisted name, or an unsure line, is the owner's")
    void allowListRunsTheCommand() {
        TargetRule rule = RuleFixture.rule().commands("ls").build();
        assertThat(command(rule, "ls /tmp", complete("ls"), false)).isEqualTo(DecisionReason.COMMAND_SET);
        assertThat(command(rule, "ls | rm x", complete("ls", "rm"), false)).isEqualTo(DecisionReason.NO_MATCHING_RULE);
        assertThat(command(rule, "ls $(cat x)", new CommandLineAnalysis(List.of("ls"), Completeness.INCOMPLETE, SudoUse.NO_SUDO), false)).isEqualTo(DecisionReason.NO_MATCHING_RULE);
    }

    @Test
    @DisplayName("S-D0119-02 RC-A-423 a denied name is refused, also inside sudo /bin/rm; the deny list wins over the allow list")
    void deniedIsRefused() {
        TargetRule rule = RuleFixture.rule().commands("sudo", "rm").denied("rm").sudo().build();
        assertThat(command(rule, "sudo /bin/rm /tmp/x", sudoNames("sudo", "rm"), false)).isEqualTo(DecisionReason.POLICY_DENIED);
        assertThat(DecisionReason.POLICY_DENIED.kind()).isEqualTo(DecisionKind.DENY);
    }

    @Test
    @DisplayName("S-D0119-03 a command no rule covers, and a target without a rule, is the owner's: nothing is pre-approved and nothing is refused")
    void noRuleIsReview() {
        assertThat(command(RuleFixture.rule().commands("ls").build(), "uname -a", complete("uname"), false)).isEqualTo(DecisionReason.NO_MATCHING_RULE);
        assertThat(decide(new RuleLookup.NoRule(), shell("uname -a", complete("uname")), false)).isEqualTo(DecisionReason.NO_MATCHING_RULE);
        assertThat(DecisionReason.NO_MATCHING_RULE.kind()).isEqualTo(DecisionKind.REVIEW);
    }

    @Test
    @DisplayName("S-D0119-04 sudo is the owner's unless the automaticSudo switch is on and the other names are listed")
    void sudoNeedsItsSwitch() {
        CommandLineAnalysis analysis = sudoNames("sudo", "systemctl");
        assertThat(command(RuleFixture.rule().commands("systemctl").build(), "sudo systemctl status x", analysis, false)).isEqualTo(DecisionReason.SUDO_NOT_ENABLED);
        assertThat(command(RuleFixture.rule().commands("systemctl").sudo().build(), "sudo systemctl status x", analysis, false)).isEqualTo(DecisionReason.COMMAND_SET);
        assertThat(command(RuleFixture.rule().commands("ls").sudo().build(), "sudo systemctl status x", analysis, false)).isEqualTo(DecisionReason.NO_MATCHING_RULE);
    }

    @Test
    @DisplayName("S-D0119-05 the order is deny list, read-only, allow list, the owner's: a read-only command runs without a rule, the deny list also stops a read-only command")
    void readOnlyComesAfterTheDenyList() {
        assertThat(decide(new RuleLookup.NoRule(), shell("cat /etc/os-release", complete("cat")), true)).isEqualTo(DecisionReason.READ_ONLY);
        assertThat(command(RuleFixture.rule().denied("cat").build(), "cat /etc/os-release", complete("cat"), true)).isEqualTo(DecisionReason.POLICY_DENIED);
        assertThat(command(RuleFixture.rule().commands("cat").build(), "cat /etc/os-release", complete("cat"), true)).as("read-only is asked before the allow list").isEqualTo(DecisionReason.READ_ONLY);
        assertThat(command(RuleFixture.rule().build(), "touch x", complete("touch"), false)).isEqualTo(DecisionReason.NO_MATCHING_RULE);
        assertThat(DecisionReason.READ_ONLY.isAutomatic()).isTrue();
    }

    @Test
    @DisplayName("S-D0119-06 the test server switch lifts the deny list and the sudo screening, for commands and for files")
    void testServerBypass() {
        TargetRule rule = RuleFixture.rule().denied("rm").testServer().build();
        assertThat(command(rule, "rm -rf /tmp/x", complete("rm"), false)).isEqualTo(DecisionReason.TEST_SERVER_BYPASS);
        assertThat(command(rule, "sudo id", sudoNames("sudo", "id"), false)).isEqualTo(DecisionReason.TEST_SERVER_BYPASS);
        assertThat(policy.decideUpload(new RuleLookup.Rule(rule), true, true)).isEqualTo(DecisionReason.TEST_SERVER_BYPASS);
        assertThat(policy.decideDownload(new RuleLookup.Rule(rule), true)).isEqualTo(DecisionReason.TEST_SERVER_BYPASS);
        assertThat(command(RuleFixture.rule().denied("rm").build(), "rm x", complete("rm"), false)).isEqualTo(DecisionReason.POLICY_DENIED);
    }

    @Test
    @DisplayName("S-D0119-07 submitted source never runs by rule alone: the owner's with a reason for each unmet condition, refused for a denied word, run on the test server")
    void submittedSource() {
        ApprovalSubject source = new ApprovalSubject.SubmittedSource(new CommandText("python3 -I -"), complete("python3"), "python3 -I -\nprint(1)\n[]",
            ApprovalSubject.EvidenceSize.WITHIN_LIMIT);
        assertThat(decide(new RuleLookup.Rule(RuleFixture.rule().pythonSource().sudo().build()), source, false)).isEqualTo(DecisionReason.NO_MATCHING_RULE);
        assertThat(decide(new RuleLookup.Rule(RuleFixture.rule().build()), source, false)).isEqualTo(DecisionReason.SOURCE_EXECUTION_NOT_ENABLED);
        assertThat(decide(new RuleLookup.NoRule(), source, false)).isEqualTo(DecisionReason.SOURCE_EXECUTION_NOT_ENABLED);
        assertThat(decide(new RuleLookup.Rule(RuleFixture.rule().pythonSource().build()), source, false)).isEqualTo(DecisionReason.SOURCE_PRIVILEGE_POLICY_REVIEW);
        ApprovalSubject withDenied = new ApprovalSubject.SubmittedSource(new CommandText("python3 -I -"), complete("python3"), "python3 -I -\nos.system(\"rm x\")\n[]",
            ApprovalSubject.EvidenceSize.WITHIN_LIMIT);
        assertThat(decide(new RuleLookup.Rule(RuleFixture.rule().pythonSource().sudo().denied("rm").build()), withDenied, false)).isEqualTo(DecisionReason.POLICY_DENIED);
        assertThat(decide(new RuleLookup.Rule(RuleFixture.rule().pythonSource().sudo().denied("rm").build()), source, false)).isEqualTo(DecisionReason.SOURCE_COMMAND_POLICY_REVIEW);
        assertThat(decide(new RuleLookup.Rule(RuleFixture.rule().testServer().build()), source, false)).isEqualTo(DecisionReason.TEST_SERVER_BYPASS);
    }

    @Test
    @DisplayName("S-D0119-08 an upload runs by rule only under its switch without overwriting or elevation; it is never refused")
    void uploads() {
        RuleLookup on = new RuleLookup.Rule(RuleFixture.rule().uploads().build());
        assertThat(policy.decideUpload(on, false, false)).isEqualTo(DecisionReason.AUTOMATIC_UPLOAD);
        assertThat(policy.decideUpload(on, false, true)).isEqualTo(DecisionReason.NO_MATCHING_RULE);
        assertThat(policy.decideUpload(on, true, false)).isEqualTo(DecisionReason.NO_MATCHING_RULE);
        assertThat(policy.decideUpload(new RuleLookup.Rule(RuleFixture.rule().build()), false, false)).isEqualTo(DecisionReason.NO_MATCHING_RULE);
        assertThat(policy.decideUpload(new RuleLookup.NoRule(), false, false)).isEqualTo(DecisionReason.NO_MATCHING_RULE);
        assertThat(DecisionReason.AUTOMATIC_UPLOAD.kind()).isEqualTo(DecisionKind.EXECUTE);
    }

    @Test
    @DisplayName("S-D0119-09 a download runs by rule under its switch; elevation also needs the sudo switch")
    void downloads() {
        RuleLookup on = new RuleLookup.Rule(RuleFixture.rule().downloads().build());
        assertThat(policy.decideDownload(on, false)).isEqualTo(DecisionReason.AUTOMATIC_DOWNLOAD);
        assertThat(policy.decideDownload(on, true)).isEqualTo(DecisionReason.NO_MATCHING_RULE);
        assertThat(policy.decideDownload(new RuleLookup.Rule(RuleFixture.rule().downloads().sudo().build()), true)).isEqualTo(DecisionReason.AUTOMATIC_DOWNLOAD);
        assertThat(policy.decideDownload(new RuleLookup.Rule(RuleFixture.rule().build()), false)).isEqualTo(DecisionReason.NO_MATCHING_RULE);
        assertThat(policy.decideDownload(new RuleLookup.NoRule(), false)).isEqualTo(DecisionReason.NO_MATCHING_RULE);
    }

    private DecisionReason command(TargetRule rule, String text, CommandLineAnalysis analysis, boolean readOnly) {
        return decide(new RuleLookup.Rule(rule), shell(text, analysis), readOnly);
    }

    /** The judge asks the target platform whether the deny list forbids the line; the tests ask the rule, which is what a Linux target does. */
    private DecisionReason decide(RuleLookup lookup, ApprovalSubject subject, boolean readOnly) {
        boolean denied = lookup instanceof RuleLookup.Rule found && subject.deniedBy(found.rule());
        return policy.decideCommand(lookup, subject, denied, readOnly);
    }

    private static ApprovalSubject shell(String text, CommandLineAnalysis analysis) {
        return new ApprovalSubject.ShellCommand(new CommandText(text), analysis);
    }

    private static CommandLineAnalysis complete(String... names) {
        return new CommandLineAnalysis(List.of(names), Completeness.COMPLETE, SudoUse.NO_SUDO);
    }

    private static CommandLineAnalysis sudoNames(String... names) {
        return new CommandLineAnalysis(List.of(names), Completeness.COMPLETE, SudoUse.USES_SUDO);
    }
}
