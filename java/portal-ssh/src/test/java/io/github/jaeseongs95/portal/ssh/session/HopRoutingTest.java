package io.github.jaeseongs95.portal.ssh.session;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jaeseongs95.portal.app.remote.SecretValue;
import io.github.jaeseongs95.portal.app.remote.SshCredential;
import io.github.jaeseongs95.portal.domain.target.AccessMode;
import io.github.jaeseongs95.portal.domain.target.AuthMethod;
import io.github.jaeseongs95.portal.domain.target.ConnectionProfile;
import io.github.jaeseongs95.portal.domain.target.ProfileAlias;
import io.github.jaeseongs95.portal.domain.target.Setting;
import io.github.jaeseongs95.portal.domain.target.TtyMode;
import io.github.jaeseongs95.portal.ssh.hostkey.ServerKeyPolicy;
import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.List;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.common.session.SessionContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Regression tests for SSH_PORTAL-D-0055: {@link HopRouting} must fail closed on unidentified sessions instead of falling back to {@link HopRouting#target()} and leaking
 * target credentials or applying target host-key verification.
 */
class HopRoutingTest {

    private static Hop hop(String alias, String host, int port, String user, String secret) {
        ConnectionProfile profile = new ConnectionProfile(host, port, user, Setting.unset(), Setting.unset(), AuthMethod.PASSWORD, Setting.unset(), Setting.unset(),
            AccessMode.READ_WRITE, TtyMode.NO_TTY);
        return new Hop(new ProfileAlias(alias), profile, new SshCredential.Password(SecretValue.of(secret)));
    }

    private static ClientSession stubSession(SocketAddress connectAddress, String username) {
        return (ClientSession) Proxy.newProxyInstance(ClientSession.class.getClassLoader(), new Class<?>[] {ClientSession.class}, (proxy, method, args) -> switch (method.getName()) {
            case "getConnectAddress" -> connectAddress;
            case "getUsername" -> username;
            case "equals" -> proxy == args[0];
            case "hashCode" -> System.identityHashCode(proxy);
            case "toString" -> "stub-session(" + username + "@" + connectAddress + ")";
            default -> throw new UnsupportedOperationException(method.getName());
        });
    }

    private static SessionContext nonClientSession() {
        return (SessionContext) Proxy.newProxyInstance(SessionContext.class.getClassLoader(), new Class<?>[] {SessionContext.class}, (proxy, method, args) -> switch (method.getName()) {
            case "equals" -> proxy == args[0];
            case "hashCode" -> System.identityHashCode(proxy);
            case "toString" -> "non-client-session";
            default -> throw new UnsupportedOperationException(method.getName());
        });
    }

    @Test
    @DisplayName("SSH_PORTAL-D-0055 unidentified session (null address, wrong host/port/user, or non-ClientSession) returns empty and never leaks target credentials")
    void unidentifiedSessionFailsClosedAndWithholdsTargetCredentials() throws Exception {
        Hop jump = hop("bastion", "bastion.example", 2222, "jump-user", "jump-secret");
        Hop target = hop("prod", "prod.example", 22, "prod-user", "prod-secret");
        HopRouting routing = new HopRouting(List.of(jump, target));
        ConnectAttempt attempt = new ConnectAttempt();
        HopIdentities identities = new HopIdentities(routing, attempt);
        ServerKeyPolicy alwaysAccept = profile -> (s, a, k) -> true;
        HopHostKeyVerifier verifier = new HopHostKeyVerifier(routing, alwaysAccept, attempt);

        ClientSession nullAddress = stubSession(null, "prod-user");
        ClientSession wrongHost = stubSession(InetSocketAddress.createUnresolved("rogue.example", 22), "prod-user");
        ClientSession wrongUser = stubSession(InetSocketAddress.createUnresolved("prod.example", 22), "attacker");
        ClientSession wrongJumpPort = stubSession(InetSocketAddress.createUnresolved("bastion.example", 2299), "jump-user");
        SessionContext plainContext = nonClientSession();

        for (ClientSession rogue : List.of(nullAddress, wrongHost, wrongUser, wrongJumpPort)) {
            assertThat(routing.find(rogue)).isEmpty();
            assertThat(verifier.verifyServerKey(rogue, rogue.getConnectAddress(), null)).isFalse();
            assertThat(identities.loadPasswords(rogue)).isEmpty();
            assertThat(identities.loadKeys(rogue)).isEmpty();
        }
        assertThat(routing.find(plainContext)).isEmpty();
        assertThat(identities.loadPasswords(plainContext)).isEmpty();
        assertThat(identities.loadKeys(plainContext)).isEmpty();
    }

    @Test
    @DisplayName("SSH_PORTAL-D-0055 verified jump and forwarded target sessions bind to their own hops and reveal only their own credentials")
    void verifiedSessionsBindToTheirOwnHops() {
        Hop jump = hop("bastion", "[::1]", 2222, "jump-user", "jump-secret");
        Hop target = hop("prod", "prod.example", 22, "prod-user", "prod-secret");
        HopRouting routing = new HopRouting(List.of(jump, target));
        ConnectAttempt attempt = new ConnectAttempt();
        HopIdentities identities = new HopIdentities(routing, attempt);

        ClientSession jumpSession = stubSession(InetSocketAddress.createUnresolved("::1", 2222), "jump-user");
        assertThat(routing.find(jumpSession, InetSocketAddress.createUnresolved("::1", 2222))).contains(jump);
        assertThat(identities.loadPasswords(jumpSession)).containsExactly("jump-secret");

        // MINA connects the target through a local loopback forwarding port while passing TARGET_SERVER as remoteAddress to ServerKeyVerifier.
        ClientSession targetSession = stubSession(new InetSocketAddress("127.0.0.1", 54321), "prod-user");
        assertThat(routing.find(targetSession, InetSocketAddress.createUnresolved("prod.example", 22))).contains(target);
        assertThat(identities.loadPasswords(targetSession)).containsExactly("prod-secret");
    }

    @Test
    @DisplayName("SSH_PORTAL-D-0055 ambiguous multi-hop loopback sessions without TARGET_SERVER fail closed instead of guessing target")
    void ambiguousMultiHopLoopbackFailsClosed() {
        Hop jump1 = hop("j1", "j1.example", 2201, "ops", "s1");
        Hop jump2 = hop("j2", "j2.example", 2202, "ops", "s2");
        Hop target = hop("prod", "prod.example", 2203, "ops", "s3");
        HopRouting routing = new HopRouting(List.of(jump1, jump2, target));

        ClientSession j1Session = stubSession(InetSocketAddress.createUnresolved("j1.example", 2201), "ops");
        assertThat(routing.find(j1Session)).contains(jump1);

        // Two unbound forwarded hops (j2 and prod) share username "ops": without remoteAddress matching one of them, loopback connectAddress is ambiguous and must return empty.
        ClientSession ambiguous = stubSession(new InetSocketAddress("127.0.0.1", 54321), "ops");
        assertThat(routing.find(ambiguous)).isEmpty();

        // Once remoteAddress identifies j2, j2 binds cleanly, and then the remaining forwarded hop uniquely identifies prod.
        assertThat(routing.find(ambiguous, InetSocketAddress.createUnresolved("j2.example", 2202))).contains(jump2);
        ClientSession targetSession = stubSession(new InetSocketAddress("127.0.0.1", 54322), "ops");
        assertThat(routing.find(targetSession)).contains(target);
    }
}
