package io.github.jaeseongs95.portal.app.attachment;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.jaeseongs95.portal.app.maintenance.MaintenanceStore;
import io.github.jaeseongs95.portal.app.storage.ProtectionCheck;
import io.github.jaeseongs95.portal.app.storage.ReapGate;
import io.github.jaeseongs95.portal.app.storage.ReapPermission;
import io.github.jaeseongs95.portal.app.storage.ReapReport;
import io.github.jaeseongs95.portal.app.storage.StorageArea;
import io.github.jaeseongs95.portal.domain.attachment.AttachmentId;
import io.github.jaeseongs95.portal.domain.attachment.AttachmentRole;

/**
 * Unit tests for {@link AttachmentReaper}: ReapGate suspension, 1-hour grace window, referenced-attachment immunity (S4-AT-07), and quarantine fault isolation.
 */
class AttachmentReaperTest {

    private static final Instant NOW = Instant.parse("2026-11-26T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @Test
    @DisplayName("AttachmentReaper suspends without touching any files when maintenance is active or storage protection fails")
    void suspendsWhenReapGateDenies() {
        FakeAttachmentStore store = new FakeAttachmentStore();
        AttachmentId stale = new AttachmentId("1".repeat(48));
        store.disk.put(stale, NOW.minus(Duration.ofHours(3)));

        ReapGate maintenanceGate = new ReapGate(new StubMaintenance(true), ProtectionCheck.Protected::new);
        assertThat(new AttachmentReaper(store, maintenanceGate, CLOCK).reap())
            .isEqualTo(new ReapReport.Suspended(ReapPermission.SuspendReason.MAINTENANCE));
        assertThat(store.quarantined).isEmpty();

        ReapGate brokenStorageGate = new ReapGate(new StubMaintenance(false),
            () -> new ProtectionCheck.Unprotected(StorageArea.ATTACHMENTS, new IOException("unprotected")));
        assertThat(new AttachmentReaper(store, brokenStorageGate, CLOCK).reap())
            .isEqualTo(new ReapReport.Suspended(ReapPermission.SuspendReason.PROTECTION_FAILED));
        assertThat(store.quarantined).isEmpty();
    }

    @Test
    @DisplayName("S4-AT-07: AttachmentReaper never quarantines referenced files or files inside the 1-hour grace window, and isolates individual quarantine errors")
    void quarantinesOnlyStaleUnreferencedFilesAndIsolatesErrors() {
        FakeAttachmentStore store = new FakeAttachmentStore();
        AttachmentId referenced = new AttachmentId("a".repeat(48));
        AttachmentId recent = new AttachmentId("b".repeat(48));
        AttachmentId staleOk = new AttachmentId("c".repeat(48));
        AttachmentId staleFailing = new AttachmentId("d".repeat(48));

        store.disk.put(referenced, NOW.minus(Duration.ofHours(24)));
        store.referenced.add(referenced);
        store.disk.put(recent, NOW.minus(Duration.ofMinutes(59)));
        store.disk.put(staleOk, NOW.minus(Duration.ofHours(1)));
        store.disk.put(staleFailing, NOW.minus(Duration.ofHours(2)));
        store.failingQuarantine.add(staleFailing);

        ReapGate openGate = new ReapGate(new StubMaintenance(false), ProtectionCheck.Protected::new);
        ReapReport report = new AttachmentReaper(store, openGate, CLOCK).reap();

        assertThat(report).isEqualTo(new ReapReport.Reaped(1, 1));
        assertThat(store.quarantined).containsExactly(staleOk);
        assertThat(store.quarantined).doesNotContain(referenced, recent);
    }

    private static final class StubMaintenance implements MaintenanceStore {
        private final boolean occupied;

        StubMaintenance(boolean occupied) {
            this.occupied = occupied;
        }

        @Override
        public boolean occupied() {
            return occupied;
        }

        @Override
        public Optional<Instant> reservedAt() {
            return occupied ? Optional.of(NOW) : Optional.empty();
        }

        @Override
        public void reserve(Instant reservedAt) {
        }

        @Override
        public void release() {
        }
    }

    private static final class FakeAttachmentStore implements AttachmentStore {
        final Map<AttachmentId, Instant> disk = new HashMap<>();
        final Set<AttachmentId> referenced = new HashSet<>();
        final Set<AttachmentId> quarantined = new HashSet<>();
        final Set<AttachmentId> failingQuarantine = new HashSet<>();

        @Override
        public PreparedAttachment prepare(AttachmentRole role, byte[] plaintext) {
            throw new IllegalStateException("unused");
        }

        @Override
        public void recordMetadata(AttachmentMetadata metadata) {
            referenced.add(metadata.id());
        }

        @Override
        public void discardPrepared(AttachmentId id) {
            disk.remove(id);
        }

        @Override
        public Optional<AttachmentMetadata> findMetadata(AttachmentId id) {
            return Optional.empty();
        }

        @Override
        public Map<AttachmentId, AttachmentMetadata> findMetadata(Collection<AttachmentId> ids) {
            return Map.of();
        }

        @Override
        public byte[] decrypt(AttachmentMetadata metadata, AttachmentRole expectedRole) {
            return new byte[0];
        }

        @Override
        public Set<AttachmentId> storedFilesOlderThan(Instant cutoff) {
            Set<AttachmentId> result = new HashSet<>();
            for (Map.Entry<AttachmentId, Instant> entry : disk.entrySet()) {
                if (!entry.getValue().isAfter(cutoff)) {
                    result.add(entry.getKey());
                }
            }
            return Set.copyOf(result);
        }

        @Override
        public Set<AttachmentId> existingMetadataIds(Collection<AttachmentId> candidates) {
            Set<AttachmentId> result = new HashSet<>();
            for (AttachmentId candidate : candidates) {
                if (referenced.contains(candidate)) {
                    result.add(candidate);
                }
            }
            return Set.copyOf(result);
        }

        @Override
        public void quarantine(AttachmentId id) {
            if (failingQuarantine.contains(id)) {
                throw new IllegalStateException("simulated I/O failure");
            }
            quarantined.add(id);
            disk.remove(id);
        }
    }
}
