package io.github.jaeseongs95.opermand.domain.target;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.jaeseongs95.opermand.domain.id.Digester;
import io.github.jaeseongs95.opermand.domain.id.Sha256Digester;
import io.github.jaeseongs95.opermand.domain.id.TargetId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TargetRevisionTest {

    private final Digester digester = new Sha256Digester();

    private TargetRevision revision(Target target, ConnectionProfile reader, ConnectionProfile writer) {
        return TargetRevision.of(target, reader, writer, digester);
    }

    @Test
    @DisplayName("S2-VA-07 the same input gives the same revision every time, whitespace around a display name does not matter")
    void deterministic() {
        TargetRevision first = revision(TargetFixtures.linux("alpha"), TargetFixtures.reader(), TargetFixtures.writer());
        assertThat(revision(TargetFixtures.linux("alpha"), TargetFixtures.reader(), TargetFixtures.writer())).isEqualTo(first);
        Target padded = new Target(new TargetId("alpha"), TargetDisplayName.of("  Target alpha  "), new ProfileAlias("read-alpha"), new ProfileAlias("write-alpha"), new ProfileAlias("rev1"),
            Toggle.ON, TargetOs.LINUX, WindowsRuntime.none(), new CapacityOverride.Inherited(),
            new CapacityOverride.Inherited(), new DesktopSettings.NoDesktop());
        assertThat(revision(padded, TargetFixtures.reader(), TargetFixtures.writer())).as("the display name is trimmed when it is read").isEqualTo(first);
        assertThat(first.hex()).matches("[a-f0-9]{64}");
        assertThat(TargetRevision.of(first.hex())).isEqualTo(first);
    }

    @Test
    @DisplayName("S2-VA-07 a change of any one field of the entry or of either profile gives another revision")
    void everyFieldCounts() {
        Target base = TargetFixtures.linux("alpha");
        ConnectionProfile reader = TargetFixtures.reader();
        ConnectionProfile writer = TargetFixtures.writer();
        TargetRevision original = revision(base, reader, writer);
        List<TargetRevision> changed = new ArrayList<>();
        changed.add(revision(TargetFixtures.linux("alpha2"), reader, writer));
        changed.add(revision(new Target(base.id(), TargetDisplayName.of("Target alph a"), base.readProfile(), base.writeProfile(), base.credentialRevision(), base.enabled(), base.os(),
            base.windows(), base.queueCapacity(), base.maxConcurrency(), base.desktop()), reader, writer));
        changed.add(revision(new Target(base.id(), base.displayName(), new ProfileAlias("read-other"), base.writeProfile(), base.credentialRevision(), base.enabled(), base.os(),
            base.windows(), base.queueCapacity(), base.maxConcurrency(), base.desktop()), reader, writer));
        changed.add(revision(new Target(base.id(), base.displayName(), base.readProfile(), new ProfileAlias("write-other"), base.credentialRevision(), base.enabled(), base.os(),
            base.windows(), base.queueCapacity(), base.maxConcurrency(), base.desktop()), reader, writer));
        changed.add(revision(new Target(base.id(), base.displayName(), base.readProfile(), base.writeProfile(), new ProfileAlias("rev2"), base.enabled(), base.os(),
            base.windows(), base.queueCapacity(), base.maxConcurrency(), base.desktop()), reader, writer));
        changed.add(revision(TargetFixtures.withEnabled(base, Toggle.OFF), reader, writer));
        changed.add(revision(TargetFixtures.withCapacities(base, 5, 2), reader, writer));
        changed.add(revision(new Target(base.id(), base.displayName(), base.readProfile(), base.writeProfile(), base.credentialRevision(), base.enabled(), base.os(),
            base.windows(), base.queueCapacity(), base.maxConcurrency(), new DesktopSettings.AttachedRfb(Toggle.ON, 5900, DesktopGeometry.standard())), reader, writer));
        changed.add(revision(new Target(base.id(), base.displayName(), base.readProfile(), base.writeProfile(), base.credentialRevision(), base.enabled(), base.os(),
            base.windows(), base.queueCapacity(), base.maxConcurrency(), new DesktopSettings.AttachedRfb(Toggle.ON, 5901, DesktopGeometry.standard())), reader, writer));
        changed.add(revision(base, profileWith("other.example.test", 22, "deploy", "key-ref-1", AccessMode.READ_ONLY), writer));
        changed.add(revision(base, profileWith("host.example.test", 2222, "deploy", "key-ref-1", AccessMode.READ_ONLY), writer));
        changed.add(revision(base, profileWith("host.example.test", 22, "root", "key-ref-1", AccessMode.READ_ONLY), writer));
        changed.add(revision(base, profileWith("host.example.test", 22, "deploy", "key-ref-2", AccessMode.READ_ONLY), writer));
        changed.add(revision(base, reader, new ConnectionProfile("host.example.test", 22, "deploy", Setting.given("ssh-ed25519 OTHER"), Setting.unset(), AuthMethod.KEY,
            Setting.given("key-ref-1"), Setting.unset(), AccessMode.READ_WRITE, TtyMode.NO_TTY)));
        changed.add(TargetRevision.of(base, reader, behindJump("jump"), List.of(jumpHost("jump.example.test")), digester));
        changed.add(revision(base, reader, new ConnectionProfile("host.example.test", 22, "deploy", Setting.given("ssh-ed25519 AAAAC3Nza"), Setting.unset(), AuthMethod.PASSWORD,
            Setting.given("key-ref-1"), Setting.unset(), AccessMode.READ_WRITE, TtyMode.NO_TTY)));
        changed.add(revision(base, reader, new ConnectionProfile("host.example.test", 22, "deploy", Setting.given("ssh-ed25519 AAAAC3Nza"), Setting.unset(), AuthMethod.KEY,
            Setting.given("key-ref-1"), Setting.unset(), AccessMode.READ_WRITE, TtyMode.TTY)));
        for (TargetRevision other : changed) {
            assertThat(other).isNotEqualTo(original);
        }
        Set<TargetRevision> distinct = new HashSet<>(changed);
        assertThat(distinct).as("no two different changes give the same revision").hasSize(changed.size());
    }

    @Test
    @DisplayName("S2-VA-07 a Windows target's settings are part of the revision")
    void windowsSettingsCount() {
        Target plain = TargetFixtures.windows("win", WindowsRuntime.none(), new DesktopSettings.NoDesktop());
        Target withPython = TargetFixtures.windows("win", new WindowsRuntime(Setting.unset(), Setting.given(new WindowsPythonExecutable("C:\\Python312\\python.exe"))),
            new DesktopSettings.NoDesktop());
        assertThat(revision(plain, TargetFixtures.reader(), TargetFixtures.writer())).isNotEqualTo(revision(withPython, TargetFixtures.reader(), TargetFixtures.writer()));
    }

    @Test
    @DisplayName("S2-VA-07 a target revision is 64 lowercase hex characters")
    void shape() {
        assertThatThrownBy(() -> TargetRevision.of("abc")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TargetRevision.of("A".repeat(64))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("S-D0083-01 (D-0083): the jump profile is part of the revision: another jump host behind the same alias, or another authentication of it, gives another revision")
    void theJumpHostCountsBehindTheSameAlias() {
        Target base = TargetFixtures.linux("alpha");
        ConnectionProfile reader = TargetFixtures.reader();
        ConnectionProfile writer = behindJump("jump");
        TargetRevision original = TargetRevision.of(base, reader, writer, List.of(jumpHost("jump.example.test")), digester);

        assertThat(TargetRevision.of(base, reader, writer, List.of(jumpHost("jump.example.test")), digester)).as("the same route, the same revision").isEqualTo(original);
        assertThat(TargetRevision.of(base, reader, writer, List.of(jumpHost("other-jump.example.test")), digester)).as("another host behind the alias").isNotEqualTo(original);
        assertThat(TargetRevision.of(base, reader, writer, List.of(new ConnectionProfile("jump.example.test", 22, "deploy", Setting.given("ssh-ed25519 AAAAC3Nza"), Setting.unset(),
            AuthMethod.PASSWORD, Setting.given("key-ref-1"), Setting.unset(), AccessMode.READ_WRITE, TtyMode.NO_TTY)), digester)).as("another authentication of the jump host").isNotEqualTo(original);
        assertThat(TargetRevision.of(base, reader, writer, List.of(jumpHost("jump.example.test", 2222)), digester)).as("another port of the jump host").isNotEqualTo(original);
    }

    @Test
    @DisplayName("S-D0083-02 (D-0083): the same holds for the connection identity a rule is bound to, and a chain that does not fit the via links is refused")
    void theJumpHostCountsForTheConnectionIdentity() {
        ConnectionProfile writer = behindJump("jump");
        TargetConnection original = TargetConnection.of(writer, List.of(jumpHost("jump.example.test")), digester);

        assertThat(TargetConnection.of(writer, List.of(jumpHost("jump.example.test")), digester)).isEqualTo(original);
        assertThat(TargetConnection.of(writer, List.of(jumpHost("other-jump.example.test")), digester)).isNotEqualTo(original);
        assertThat(TargetConnection.of(TargetFixtures.writer(), digester)).as("the identity of a profile without a jump host is unchanged by the chain support")
            .isEqualTo(TargetConnection.of(TargetFixtures.writer(), List.of(), digester));

        Target base = TargetFixtures.linux("alpha");
        assertThatThrownBy(() -> TargetRevision.of(base, TargetFixtures.reader(), writer, digester)).as("a jump host is named and no profile of it is given")
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TargetConnection.of(writer, digester)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TargetRevision.of(base, TargetFixtures.reader(), TargetFixtures.writer(), List.of(jumpHost("jump.example.test")), digester))
            .as("a jump profile is given and the profile names no jump host").isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TargetRevision.of(base, TargetFixtures.reader(), writer, List.of(behindJump("second")), digester))
            .as("the jump profile names a jump host of its own that is not given").isInstanceOf(IllegalArgumentException.class);
    }

    private static ConnectionProfile behindJump(String alias) {
        return new ConnectionProfile("host.example.test", 22, "deploy", Setting.given("ssh-ed25519 AAAAC3Nza"), Setting.given(new ProfileAlias(alias)), AuthMethod.KEY,
            Setting.given("key-ref-1"), Setting.unset(), AccessMode.READ_WRITE, TtyMode.NO_TTY);
    }

    private static ConnectionProfile jumpHost(String host) {
        return jumpHost(host, 22);
    }

    private static ConnectionProfile jumpHost(String host, int port) {
        return new ConnectionProfile(host, port, "deploy", Setting.given("ssh-ed25519 AAAAC3Nza"), Setting.unset(), AuthMethod.KEY, Setting.given("key-ref-1"), Setting.unset(),
            AccessMode.READ_WRITE, TtyMode.NO_TTY);
    }

    private static ConnectionProfile profileWith(String host, int port, String user, String keyRef, AccessMode access) {
        return new ConnectionProfile(host, port, user, Setting.given("ssh-ed25519 AAAAC3Nza"), Setting.unset(), AuthMethod.KEY, Setting.given(keyRef), Setting.unset(), access,
            TtyMode.NO_TTY);
    }
}
