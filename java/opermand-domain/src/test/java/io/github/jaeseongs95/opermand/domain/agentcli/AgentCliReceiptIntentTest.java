package io.github.jaeseongs95.opermand.domain.agentcli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.jaeseongs95.opermand.domain.id.CanonicalForm;
import io.github.jaeseongs95.opermand.domain.id.Digest;

/**
 * The receipt digest of an installation plan: SHA-256 over the {@link CanonicalForm} of its six fields.
 */
class AgentCliReceiptIntentTest {

    private final AgentCliFixture fx = new AgentCliFixture();

    @Test
    @DisplayName("RC-A-907 the receipt digest of the fixed install plan equals the CanonicalForm SHA-256")
    void digestMatchesTheFixedVector() {
        AgentRelease release = fx.release(fx.codex, "1.2.3");
        AgentCliReceiptIntent receipt = new AgentCliReceiptIntent(AgentCliAction.INSTALL, release, fx.installed, Instant.ofEpochMilli(301_000));

        Digest expected = new CanonicalForm()
                .field("action", "install")
                .field("expected", "b".repeat(64))
                .field("expires", 301_000L)
                .field("integrity", fx.integrity)
                .field("provider", "codex")
                .field("version", "1.2.3")
                .digest(fx.digester);
        assertThat(receipt.digest(fx.digester)).isEqualTo(expected);
    }

    @Test
    @DisplayName("RC-B-552 the receipt digest binds all six fields deterministically via CanonicalForm")
    void digestHasTheFixedKeyOrderAndLineFeed() {
        String zeroIntegrity = fx.integrityOf(0);
        AgentRelease release = fx.release(fx.codex, "1.2.3", zeroIntegrity);
        AgentCliReceiptIntent receipt = new AgentCliReceiptIntent(AgentCliAction.INSTALL, release, fx.installed, Instant.ofEpochMilli(1_900_000_000_000L));

        Digest expected = new CanonicalForm()
                .field("action", "install")
                .field("expected", "b".repeat(64))
                .field("expires", 1_900_000_000_000L)
                .field("integrity", zeroIntegrity)
                .field("provider", "codex")
                .field("version", "1.2.3")
                .digest(fx.digester);
        assertThat(receipt.digest(fx.digester)).isEqualTo(expected);
    }

    @Test
    @DisplayName("S2-AG-06 the receipt digest changes with the action, the provider, the installation snapshot and the expiry")
    void digestChangesWithEveryField() {
        AgentRelease release = fx.release(fx.codex, "1.2.3");
        Instant expires = Instant.ofEpochMilli(301_000);
        Digest base = new AgentCliReceiptIntent(AgentCliAction.INSTALL, release, fx.installed, expires).digest(fx.digester);

        assertThat(new AgentCliReceiptIntent(AgentCliAction.UPDATE, release, fx.installed, expires).digest(fx.digester)).isNotEqualTo(base);
        assertThat(new AgentCliReceiptIntent(AgentCliAction.INSTALL, fx.release(fx.claude, "1.2.3"), fx.installed, expires).digest(fx.digester)).isNotEqualTo(base);
        assertThat(new AgentCliReceiptIntent(AgentCliAction.INSTALL, release, new Digest("c".repeat(64)), expires).digest(fx.digester)).isNotEqualTo(base);
        assertThat(new AgentCliReceiptIntent(AgentCliAction.INSTALL, release, fx.installed, expires.plusMillis(1)).digest(fx.digester)).isNotEqualTo(base);
        assertThat(new AgentCliReceiptIntent(AgentCliAction.INSTALL, release, fx.installed, expires).digest(fx.digester)).isEqualTo(base);
    }

    @Test
    @DisplayName("S2-AG-07 an expiry at the epoch or beyond the safe integer, and missing parts, are refused")
    void refusesInvalidParts() {
        AgentRelease release = fx.release(fx.codex, "1.2.3");

        assertThatThrownBy(() -> new AgentCliReceiptIntent(AgentCliAction.INSTALL, release, fx.installed, Instant.EPOCH)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AgentCliReceiptIntent(AgentCliAction.INSTALL, release, fx.installed, Instant.ofEpochMilli(9_007_199_254_740_992L)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AgentCliReceiptIntent(null, release, fx.installed, Instant.ofEpochMilli(1))).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new AgentCliReceiptIntent(AgentCliAction.INSTALL, null, fx.installed, Instant.ofEpochMilli(1))).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new AgentCliReceiptIntent(AgentCliAction.INSTALL, release, null, Instant.ofEpochMilli(1))).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new AgentCliReceiptIntent(AgentCliAction.INSTALL, release, fx.installed, null)).isInstanceOf(NullPointerException.class);
    }
}
