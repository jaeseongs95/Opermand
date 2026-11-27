package io.github.jaeseongs95.opermand.domain.agentcli;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

import io.github.jaeseongs95.opermand.domain.id.CanonicalForm;
import io.github.jaeseongs95.opermand.domain.id.Digest;
import io.github.jaeseongs95.opermand.domain.id.Digester;

/**
 * What the target-side installation plan records and verifies as its receipt: action, provider, version, checksum, the installation snapshot the plan expects and the
 * expiry. Hashed with {@link CanonicalForm}.
 */
public record AgentCliReceiptIntent(AgentCliAction action, AgentRelease release, Digest expectedInstallationRevision, Instant expiresAt) {

    public AgentCliReceiptIntent {
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(release, "release");
        Objects.requireNonNull(expectedInstallationRevision, "expectedInstallationRevision");
        Objects.requireNonNull(expiresAt, "expiresAt");
        expiresAt = expiresAt.truncatedTo(ChronoUnit.MILLIS);
        if (!expiresAt.isAfter(Instant.EPOCH) || expiresAt.isAfter(AgentCliIntent.LATEST_EXPIRY)) {
            throw new IllegalArgumentException("A CLI receipt expiry is after the epoch and not beyond 2^53-1 milliseconds");
        }
    }

    public CanonicalForm describeTo(CanonicalForm form) {
        Objects.requireNonNull(form, "form");
        return form
                .field("action", action.wire())
                .field("expected", expectedInstallationRevision.hex())
                .field("expires", expiresAt.toEpochMilli())
                .field("integrity", release.integrity())
                .field("provider", release.provider().wire())
                .field("version", release.version());
    }

    public Digest digest(Digester digester) {
        Objects.requireNonNull(digester, "digester");
        return describeTo(new CanonicalForm()).digest(digester);
    }
}
