package io.github.jaeseongs95.opermand.ssh.session;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.common.session.SessionContext;
import org.apache.sshd.common.util.net.SshdSocketAddress;

/**
 * Tells which server of a connection a MINA session is for. MINA's one client asks its host key verifier and its identity providers about every session of a chain
 * (the jump sessions and the target's), and the answer differs by server: the credential and the pinned host key are the profile's.
 *
 * <p>Matching is fail-closed (defect SSH_PORTAL-D-0055): an unidentified session never falls back to {@link #target()}. Each session is bound once by matching its
 * {@code (host, port, user)} against the {@code remoteAddress} MINA reports (which carries the {@code TARGET_SERVER} address for forwarded hops) or its
 * {@code connectAddress}, and cached for subsequent credential and lifecycle callbacks on the same {@link ClientSession}. If MINA invokes a callback for a forwarded hop
 * over a loopback forwarding port before {@code TARGET_SERVER} is visible, it binds only when there is a single remaining unbound forwarded hop with the same user.
 */
final class HopRouting {

    private final List<Hop> hops;
    private final Map<ClientSession, Hop> bound = new ConcurrentHashMap<>();

    HopRouting(List<Hop> hops) {
        this.hops = List.copyOf(hops);
    }

    List<Hop> hops() {
        return hops;
    }

    Hop target() {
        return hops.get(hops.size() - 1);
    }

    /** The hop the session is for, or empty when the session cannot be identified. */
    Optional<Hop> find(SessionContext session) {
        return find(session, null);
    }

    /** The hop the session is for, using the peer {@code remoteAddress} when supplied (such as from {@code ServerKeyVerifier}). */
    Optional<Hop> find(SessionContext session, SocketAddress remoteAddress) {
        if (!(session instanceof ClientSession clientSession)) {
            return Optional.empty();
        }
        Hop existing = bound.get(clientSession);
        if (existing != null) {
            return Optional.of(existing);
        }
        String username = clientSession.getUsername();
        if (username == null) {
            return Optional.empty();
        }
        Optional<Hop> matched = matchByAddress(remoteAddress, username);
        if (matched.isEmpty()) {
            matched = matchByAddress(clientSession.getConnectAddress(), username);
        }
        if (matched.isEmpty()) {
            matched = matchUniqueForwardedHop(clientSession.getConnectAddress(), username);
        }
        if (matched.isPresent()) {
            Hop previous = bound.putIfAbsent(clientSession, matched.orElseThrow());
            return Optional.of(previous != null ? previous : matched.orElseThrow());
        }
        return Optional.empty();
    }

    private Optional<Hop> matchByAddress(SocketAddress rawAddress, String username) {
        if (rawAddress == null) {
            return Optional.empty();
        }
        InetSocketAddress address = SshdSocketAddress.toInetSocketAddress(rawAddress);
        Hop firstBoundMatch = null;
        for (Hop hop : hops) {
            if (matches(hop, address, username)) {
                if (!bound.containsValue(hop)) {
                    return Optional.of(hop);
                }
                if (firstBoundMatch == null) {
                    firstBoundMatch = hop;
                }
            }
        }
        return Optional.ofNullable(firstBoundMatch);
    }

    private Optional<Hop> matchUniqueForwardedHop(SocketAddress connectAddress, String username) {
        if (hops.size() <= 1 || connectAddress == null) {
            return Optional.empty();
        }
        InetSocketAddress address = SshdSocketAddress.toInetSocketAddress(connectAddress);
        if (address.getAddress() == null || !address.getAddress().isLoopbackAddress()) {
            return Optional.empty();
        }
        for (Hop hop : hops) {
            if (hop.profile().port() == address.getPort()) {
                return Optional.empty();
            }
        }
        Hop candidate = null;
        for (int index = 1; index < hops.size(); index++) {
            Hop hop = hops.get(index);
            if (!bound.containsValue(hop) && hop.profile().user().equals(username)) {
                if (candidate != null) {
                    return Optional.empty();
                }
                candidate = hop;
            }
        }
        return Optional.ofNullable(candidate);
    }

    private static boolean matches(Hop hop, InetSocketAddress address, String username) {
        if (hop.profile().port() != address.getPort() || !hop.profile().user().equals(username)) {
            return false;
        }
        String host = hop.profile().host();
        String bare = host.length() > 2 && host.startsWith("[") && host.endsWith("]") ? host.substring(1, host.length() - 1) : host;
        if (bare.equalsIgnoreCase(address.getHostString())) {
            return true;
        }
        return !address.isUnresolved() && address.getAddress() != null && bare.equalsIgnoreCase(address.getAddress().getHostAddress());
    }
}
