package io.github.jaeseongs95.opermand.domain.command;

import io.github.jaeseongs95.opermand.domain.id.TargetId;
import io.github.jaeseongs95.opermand.domain.target.ProfileAlias;
import io.github.jaeseongs95.opermand.domain.target.TargetConnection;
import io.github.jaeseongs95.opermand.domain.target.TargetOs;
import io.github.jaeseongs95.opermand.domain.target.Toggle;
import java.util.ArrayList;
import java.util.List;

/** Builds a {@link TargetRule} for the tests: everything off, no names, until a test says otherwise. */
final class RuleFixture {

    static final TargetId TARGET = new TargetId("synthetic");
    static final TargetConnection CONNECTION = TargetConnection.of("a".repeat(64));
    static final ProfileAlias PROFILE = new ProfileAlias("writer");

    private TargetOs os = TargetOs.LINUX;
    private final List<String> commands = new ArrayList<>();
    private final List<String> denied = new ArrayList<>();
    private Toggle automaticSudo = Toggle.OFF;
    private Toggle testServer = Toggle.OFF;
    private Toggle automaticUploads = Toggle.OFF;
    private Toggle automaticDownloads = Toggle.OFF;
    private SourceContract sourceContract = SourceContract.NONE;

    static RuleFixture rule() {
        return new RuleFixture();
    }

    RuleFixture windows() {
        os = TargetOs.WINDOWS;
        return this;
    }

    RuleFixture commands(String... names) {
        commands.addAll(List.of(names));
        return this;
    }

    RuleFixture denied(String... names) {
        denied.addAll(List.of(names));
        return this;
    }

    RuleFixture sudo() {
        automaticSudo = Toggle.ON;
        return this;
    }

    RuleFixture testServer() {
        testServer = Toggle.ON;
        return this;
    }

    RuleFixture uploads() {
        automaticUploads = Toggle.ON;
        return this;
    }

    RuleFixture downloads() {
        automaticDownloads = Toggle.ON;
        return this;
    }

    RuleFixture pythonSource() {
        sourceContract = SourceContract.PYTHON_SOURCE_V1;
        return this;
    }

    TargetRule build() {
        return new TargetRule(TARGET, CONNECTION, PROFILE, os, names(commands), names(denied), automaticUploads, automaticDownloads, automaticSudo,
            testServer, sourceContract);
    }

    private CommandNames names(List<String> texts) {
        List<PolicyName> names = new ArrayList<>();
        for (String text : texts) {
            names.add(os == TargetOs.WINDOWS ? new WindowsPolicyName(text) : new LinuxPolicyName(text));
        }
        return new CommandNames(names);
    }
}
