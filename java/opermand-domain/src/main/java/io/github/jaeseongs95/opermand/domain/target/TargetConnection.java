package io.github.jaeseongs95.opermand.domain.target;

import io.github.jaeseongs95.opermand.domain.id.CanonicalForm;
import io.github.jaeseongs95.opermand.domain.id.Digest;
import io.github.jaeseongs95.opermand.domain.id.Digester;
import java.util.List;
import java.util.Objects;

/**
 * The connection identity of a target: the digest of the seven fields that decide which machine, account and authentication method SSH reaches ({@code host},
 * {@code port}, {@code user}, {@code pinnedHostKey}, {@code via}, {@code auth}, {@code keyRef}). Non-connection target settings (display name, queue/concurrency
 * capacities, desktop, Windows runtime paths, profile workdir/tty, read profile alias, credential revision) are excluded so editing them does not silently disable
 * command policy rules bound to this target (defect D-0121).
 */
public record TargetConnection(Digest value) {

    public TargetConnection {
        Objects.requireNonNull(value, "value");
    }

    /** The connection identity for the 64 hex characters stored in a policy rule. */
    public static TargetConnection of(String hex) {
        return new TargetConnection(new Digest(hex));
    }

    /**
     * Computes the canonical connection digest from the seven connection core fields of {@code profile}, which names no jump host.
     *
     * @throws IllegalArgumentException when the profile names a jump host, whose profile this form does not have (use {@link #of(ConnectionProfile, List, Digester)})
     */
    public static TargetConnection of(ConnectionProfile profile, Digester digester) {
        return of(profile, List.of(), digester);
    }

    /**
     * Computes the canonical connection digest from the seven connection core fields of {@code profile} and of each jump profile it goes through, nearest first (defect D-0083):
     * a policy rule bound to this identity does not apply to a connection that passes other jump hosts behind the same alias.
     *
     * @throws IllegalArgumentException when the jump profiles do not fit the {@code via} links
     */
    public static TargetConnection of(ConnectionProfile profile, List<ConnectionProfile> jumps, Digester digester) {
        Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(digester, "digester");
        JumpChain chain = JumpChain.of(profile, jumps);
        CanonicalForm form = new CanonicalForm();
        profile.describeConnectionTo(form);
        chain.describeConnectionTo(form);
        return new TargetConnection(form.digest(digester));
    }

    /** The 64 hex characters. */
    public String hex() {
        return value.hex();
    }
}
