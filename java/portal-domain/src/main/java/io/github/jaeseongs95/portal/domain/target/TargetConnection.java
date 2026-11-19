package io.github.jaeseongs95.portal.domain.target;

import io.github.jaeseongs95.portal.domain.id.CanonicalForm;
import io.github.jaeseongs95.portal.domain.id.Digest;
import io.github.jaeseongs95.portal.domain.id.Digester;
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

    /** Computes the canonical connection digest from the seven connection core fields of {@code profile}. */
    public static TargetConnection of(ConnectionProfile profile, Digester digester) {
        Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(digester, "digester");
        CanonicalForm form = new CanonicalForm();
        profile.describeConnectionTo(form);
        return new TargetConnection(form.digest(digester));
    }

    /** The 64 hex characters. */
    public String hex() {
        return value.hex();
    }
}
