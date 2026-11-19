package io.github.jaeseongs95.portal.domain.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.jaeseongs95.portal.domain.id.TargetId;
import io.github.jaeseongs95.portal.domain.target.ProfileAlias;
import io.github.jaeseongs95.portal.domain.target.TargetConnection;
import io.github.jaeseongs95.portal.domain.target.TargetOs;
import io.github.jaeseongs95.portal.domain.target.Toggle;
import io.github.jaeseongs95.portal.domain.text.CommandText;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** S2-PO-01: the invariants of the policy document, the rule, the policy snapshot and the rule lookup. */
class PolicyDocumentTest {

    @Test
    @DisplayName("S2-PO-01 a policy has at most 32 target rules, one per target")
    void atMostThirtyTwoTargetsOnePerTarget() {
        List<TargetRule> rules = new ArrayList<>();
        for (int index = 0; index < 32; index++) {
            rules.add(ruleFor("target" + index));
        }
        assertThat(new CommandPolicyDocument(rules).targets()).hasSize(32);
        rules.add(ruleFor("target32"));
        assertThatThrownBy(() -> new CommandPolicyDocument(rules)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CommandPolicyDocument(List.of(ruleFor("same"), ruleFor("same")))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("S2-PO-01 a side of a rule lists at most 128 names, each once; Windows names are the same name in another letter case")
    void namesAreBoundedAndUnique() {
        List<PolicyName> many = new ArrayList<>();
        for (int index = 0; index < 128; index++) {
            many.add(new LinuxPolicyName("name" + index));
        }
        assertThat(new CommandNames(many).names()).hasSize(128);
        many.add(new LinuxPolicyName("name128"));
        assertThatThrownBy(() -> new CommandNames(many)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CommandNames(List.of(new LinuxPolicyName("ls"), new LinuxPolicyName("ls")))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CommandNames(List.of(new WindowsPolicyName("Microsoft.PowerShell.Utility\\Get-Date"),
            new WindowsPolicyName("microsoft.powershell.utility\\get-date")))).isInstanceOf(IllegalArgumentException.class);
        assertThat(new CommandNames(List.of(new LinuxPolicyName("Ls"), new LinuxPolicyName("ls"))).names()).hasSize(2);
    }

    @Test
    @DisplayName("S2-PO-01 a POSIX name starts with a letter, digit or underscore and has at most 80 characters of the allowed set")
    void posixNames() {
        assertThat(new LinuxPolicyName("a".repeat(80)).key()).hasSize(80);
        for (String bad : List.of("", "-rm", ".hidden", "a".repeat(81), "a b", "rm;ls", "ré")) {
            assertThatThrownBy(() -> new LinuxPolicyName(bad)).as(bad).isInstanceOf(IllegalArgumentException.class);
            assertThat(LinuxPolicyName.parse(bad)).as(bad).isInstanceOf(NormalizedName.Rejected.class);
        }
        assertThat(LinuxPolicyName.parse("python3.12")).isEqualTo(new NormalizedName.Accepted(new LinuxPolicyName("python3.12")));
    }

    @Test
    @DisplayName("S2-PO-01 a Windows name is a module-qualified cmdlet, an executable name or an absolute executable path, in normal form lower case with backslashes")
    void windowsNames() {
        assertThat(new WindowsPolicyName("Microsoft.PowerShell.Utility\\Get-Date").key()).isEqualTo("microsoft.powershell.utility\\get-date");
        assertThat(new WindowsPolicyName("Tool.EXE").key()).isEqualTo("tool.exe");
        assertThat(new WindowsPolicyName("C:/Windows/System32/whoami.exe").key()).isEqualTo("c:\\windows\\system32\\whoami.exe");
        assertThat(new WindowsPolicyName("c:\\scripts\\run.ps1").key()).isEqualTo("c:\\scripts\\run.ps1");
        for (String bad : List.of("Get-Date", "whoami", " tool.exe", "tool.exe ", "C:\\a\\..\\b.exe", "C:\\a\\.\\b.exe", "C:\\a \\b.exe", "C:\\a.\\b.exe", "C:\\a|b.exe",
            "C:\\a$b.exe", "C:\\a:b.exe", "x\u2018y.exe", "tool\u200b.exe", "C:\\run.sh", "a".repeat(513) + ".exe")) {
            assertThatThrownBy(() -> new WindowsPolicyName(bad)).as(bad).isInstanceOf(IllegalArgumentException.class);
            assertThat(WindowsPolicyName.parse(bad)).as(bad).isInstanceOf(NormalizedName.Rejected.class);
        }
    }

    @Test
    @DisplayName("S2-PO-01 a rule for a Linux target lists POSIX names and a rule for a Windows target lists Windows names, not mixed")
    void namesFollowThePlatform() {
        assertThatThrownBy(() -> rule(TargetOs.WINDOWS, new CommandNames(List.of(new LinuxPolicyName("ls"))))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> rule(TargetOs.LINUX, new CommandNames(List.of(new WindowsPolicyName("tool.exe"))))).isInstanceOf(IllegalArgumentException.class);
        assertThat(rule(TargetOs.WINDOWS, new CommandNames(List.of(new WindowsPolicyName("tool.exe")))).os()).isEqualTo(TargetOs.WINDOWS);
    }

    @Test
    @DisplayName("S-D0121-04 S-D0121-05 a rule applies to its own target, connection identity, write profile and OS; any mismatch returns NoRule")
    void ruleLookup() {
        TargetRule rule = RuleFixture.rule().build();
        CommandPolicyDocument document = new CommandPolicyDocument(List.of(rule));
        assertThat(document.ruleFor(RuleFixture.TARGET, RuleFixture.CONNECTION, RuleFixture.PROFILE, TargetOs.LINUX)).isEqualTo(new RuleLookup.Rule(rule));
        assertThat(document.ruleFor(RuleFixture.TARGET, TargetConnection.of("b".repeat(64)), RuleFixture.PROFILE, TargetOs.LINUX)).isInstanceOf(RuleLookup.NoRule.class);
        assertThat(document.ruleFor(RuleFixture.TARGET, RuleFixture.CONNECTION, new ProfileAlias("other"), TargetOs.LINUX)).isInstanceOf(RuleLookup.NoRule.class);
        assertThat(document.ruleFor(RuleFixture.TARGET, RuleFixture.CONNECTION, RuleFixture.PROFILE, TargetOs.WINDOWS)).isInstanceOf(RuleLookup.NoRule.class);
        assertThat(document.ruleFor(new TargetId("elsewhere"), RuleFixture.CONNECTION, RuleFixture.PROFILE, TargetOs.LINUX)).isInstanceOf(RuleLookup.NoRule.class);
        assertThat(CommandPolicyDocument.empty().ruleFor(RuleFixture.TARGET, RuleFixture.CONNECTION, RuleFixture.PROFILE, TargetOs.LINUX)).isInstanceOf(RuleLookup.NoRule.class);

        assertThat(TargetPolicy.none()).isEqualTo(new TargetPolicy(0L, new RuleLookup.NoRule()));
        assertThatThrownBy(() -> new TargetPolicy(1L, new RuleLookup.NoRule())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TargetPolicy(0L, new RuleLookup.Rule(rule))).isInstanceOf(IllegalArgumentException.class);
        TargetPolicy stored = new TargetPolicy(3L, new RuleLookup.Rule(rule));
        assertThat(stored.ruleFor(RuleFixture.TARGET, RuleFixture.CONNECTION, RuleFixture.PROFILE, TargetOs.LINUX)).isEqualTo(new RuleLookup.Rule(rule));
        assertThat(stored.ruleFor(RuleFixture.TARGET, TargetConnection.of("b".repeat(64)), RuleFixture.PROFILE, TargetOs.LINUX)).isInstanceOf(RuleLookup.NoRule.class);
    }

    @Test
    @DisplayName("S2-PO-01 a policy is replaced only by someone who saw the current revision; the second of two writers of one revision gets the conflict")
    void snapshotReplacement() {
        PolicySnapshot first = new PolicySnapshot(3, CommandPolicyDocument.empty());
        CommandPolicyDocument next = new CommandPolicyDocument(List.of(RuleFixture.rule().build()));
        PolicyReplacement replaced = first.replaceWith(next, 3);
        assertThat(replaced).isInstanceOf(PolicyReplacement.Replaced.class);
        PolicySnapshot second = ((PolicyReplacement.Replaced) replaced).snapshot();
        assertThat(second.revision()).isEqualTo(4);
        assertThat(second.document()).isEqualTo(next);
        assertThat(first.replaceWith(CommandPolicyDocument.empty(), 3)).isInstanceOf(PolicyReplacement.Replaced.class);
        assertThat(second.replaceWith(CommandPolicyDocument.empty(), 3)).isEqualTo(new PolicyReplacement.RevisionConflict(3, 4));
        assertThat(second.isRevision(4)).isTrue();
        assertThat(second.isRevision(3)).isFalse();
        assertThatThrownBy(() -> new PolicySnapshot(-1, CommandPolicyDocument.empty())).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("RC-A-423 a denied name is found among the names of the line and among the words of the text, even inside a path")
    void deniedNamesInWords() {
        TargetRule rule = RuleFixture.rule().denied("rm").build();
        CommandLineAnalysis names = new CommandLineAnalysis(List.of("sudo"), Completeness.INCOMPLETE, SudoUse.USES_SUDO);
        assertThat(rule.denies(names, new CommandText("sudo /bin/rm /tmp/x"))).isTrue();
        assertThat(rule.denies(names, new CommandText("echo rm"))).isTrue();
        assertThat(rule.denies(names, new CommandText("echo rmdir"))).isFalse();
        assertThat(rule.denies(new CommandLineAnalysis(List.of("rm"), Completeness.COMPLETE, SudoUse.NO_SUDO), new CommandText("$(x)"))).isTrue();
        assertThat(RuleFixture.rule().build().denies(names, new CommandText("rm x"))).isFalse();
    }

    @Test
    @DisplayName("S2-PO-01 sudo is approved by name only when the switch is on; the preapproved names leave sudo out otherwise")
    void preapprovedNames() {
        TargetRule off = RuleFixture.rule().commands("sudo", "id").build();
        TargetRule on = RuleFixture.rule().commands("id").sudo().build();
        assertThat(off.preapprovedNames()).extracting(PolicyName::key).containsExactly("id");
        assertThat(on.preapprovedNames()).extracting(PolicyName::key).containsExactly("id", "sudo");
        CommandLineAnalysis sudoId = new CommandLineAnalysis(List.of("sudo", "id"), Completeness.COMPLETE, SudoUse.USES_SUDO);
        assertThat(off.approves(sudoId)).isFalse();
        assertThat(on.approves(sudoId)).isTrue();
        assertThat(on.approves(new CommandLineAnalysis(List.of("id"), Completeness.INCOMPLETE, SudoUse.NO_SUDO))).isFalse();
    }

    @Test
    @DisplayName("S2-PO-01 a rule needs every part: a missing value is a programming error, and a switch left out is off")
    void everyPartIsRequired() {
        assertThatThrownBy(() -> new TargetRule(RuleFixture.TARGET, RuleFixture.CONNECTION, RuleFixture.PROFILE, TargetOs.LINUX, CommandNames.none(), CommandNames.none(), Toggle.OFF,
            Toggle.OFF, Toggle.OFF, Toggle.OFF, null)).isInstanceOf(NullPointerException.class);
        TargetRule bare = RuleFixture.rule().build();
        assertThat(bare.testServer()).isEqualTo(Toggle.OFF);
        assertThat(bare.sourceExecutionContract()).isEqualTo(SourceContract.NONE);
    }

    private TargetRule ruleFor(String target) {
        return new TargetRule(new TargetId(target), RuleFixture.CONNECTION, RuleFixture.PROFILE, TargetOs.LINUX, CommandNames.none(), CommandNames.none(), Toggle.OFF,
            Toggle.OFF, Toggle.OFF, Toggle.OFF, SourceContract.NONE);
    }

    private TargetRule rule(TargetOs os, CommandNames commands) {
        return new TargetRule(RuleFixture.TARGET, RuleFixture.CONNECTION, RuleFixture.PROFILE, os, commands, CommandNames.none(), Toggle.OFF, Toggle.OFF,
            Toggle.OFF, Toggle.OFF, SourceContract.NONE);
    }
}
