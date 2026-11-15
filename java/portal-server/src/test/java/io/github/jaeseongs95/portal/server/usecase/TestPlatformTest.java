package io.github.jaeseongs95.portal.server.usecase;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.jaeseongs95.portal.domain.classify.CommandClass;
import io.github.jaeseongs95.portal.domain.classify.CommandClassification;
import io.github.jaeseongs95.portal.domain.command.CommandNames;
import io.github.jaeseongs95.portal.domain.command.Fallback;
import io.github.jaeseongs95.portal.domain.command.LinuxPolicyName;
import io.github.jaeseongs95.portal.domain.command.SourceContract;
import io.github.jaeseongs95.portal.domain.command.TargetRule;
import io.github.jaeseongs95.portal.domain.command.Thresholds;
import io.github.jaeseongs95.portal.domain.id.TargetId;
import io.github.jaeseongs95.portal.domain.target.ProfileAlias;
import io.github.jaeseongs95.portal.domain.target.TargetOs;
import io.github.jaeseongs95.portal.domain.target.TargetRevision;
import io.github.jaeseongs95.portal.domain.target.Toggle;
import io.github.jaeseongs95.portal.domain.text.CommandText;

/**
 * The platform of the use case tests answers the deny list as a Linux target does (defect D-0119). The intake asks the platform whether the owner's deny list forbids a
 * line; a platform that always said no made every denied command in the use case tests wait for the owner instead.
 */
class TestPlatformTest {

    private final TestPlatform platform = new TestPlatform(TargetOs.LINUX, command -> CommandClassification.of(CommandClass.SAFE));

    @Test
    @DisplayName("S-D0119-02 the use case platform finds a denied name in the line, also behind sudo and a path, and does not deny an unlisted one")
    void itDeniesWhatTheRuleDenies() {
        TargetRule rule = new TargetRule(new TargetId("web-1"), TargetRevision.of("a".repeat(64)), new ProfileAlias("web-rw"), TargetOs.LINUX, CommandNames.none(),
            new CommandNames(List.of(new LinuxPolicyName("rm"))), Toggle.OFF, Toggle.OFF, Toggle.OFF, Toggle.OFF, Toggle.OFF, Toggle.OFF, SourceContract.NONE, Fallback.NONE,
            Thresholds.none());

        assertThat(platform.deniesName(rule, new CommandText("sudo /bin/rm /tmp/x"))).isTrue();
        assertThat(platform.deniesName(rule, new CommandText("rm /tmp/x"))).isTrue();
        assertThat(platform.deniesName(rule, new CommandText("touch /tmp/x"))).isFalse();
    }
}
