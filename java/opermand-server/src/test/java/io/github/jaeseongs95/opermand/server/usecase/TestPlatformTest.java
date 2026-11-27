package io.github.jaeseongs95.opermand.server.usecase;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.jaeseongs95.opermand.domain.classify.CommandClass;
import io.github.jaeseongs95.opermand.domain.classify.CommandClassification;
import io.github.jaeseongs95.opermand.domain.command.CommandNames;
import io.github.jaeseongs95.opermand.domain.command.LinuxPolicyName;
import io.github.jaeseongs95.opermand.domain.command.SourceContract;
import io.github.jaeseongs95.opermand.domain.command.TargetRule;
import io.github.jaeseongs95.opermand.domain.id.TargetId;
import io.github.jaeseongs95.opermand.domain.target.ProfileAlias;
import io.github.jaeseongs95.opermand.domain.target.TargetConnection;
import io.github.jaeseongs95.opermand.domain.target.TargetOs;
import io.github.jaeseongs95.opermand.domain.target.Toggle;
import io.github.jaeseongs95.opermand.domain.text.CommandText;

/**
 * The platform of the use case tests answers the deny list as a Linux target does (defect D-0119). The intake asks the platform whether the owner's deny list forbids a
 * line; a platform that always said no made every denied command in the use case tests wait for the owner instead.
 */
class TestPlatformTest {

    private final TestPlatform platform = new TestPlatform(TargetOs.LINUX, command -> CommandClassification.of(CommandClass.SAFE));

    @Test
    @DisplayName("S-D0119-02 the use case platform finds a denied name in the line, also behind sudo and a path, and does not deny an unlisted one")
    void itDeniesWhatTheRuleDenies() {
        TargetRule rule = new TargetRule(new TargetId("web-1"), TargetConnection.of("a".repeat(64)), new ProfileAlias("web-rw"), TargetOs.LINUX, CommandNames.none(),
            new CommandNames(List.of(new LinuxPolicyName("rm"))), Toggle.OFF, Toggle.OFF, Toggle.OFF, Toggle.OFF, SourceContract.NONE);

        assertThat(platform.deniesName(rule, new CommandText("sudo /bin/rm /tmp/x"))).isTrue();
        assertThat(platform.deniesName(rule, new CommandText("rm /tmp/x"))).isTrue();
        assertThat(platform.deniesName(rule, new CommandText("touch /tmp/x"))).isFalse();
    }
}
