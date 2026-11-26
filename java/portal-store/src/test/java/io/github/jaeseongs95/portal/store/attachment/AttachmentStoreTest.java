package io.github.jaeseongs95.portal.store.attachment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.FileSystemUtils;

import io.github.jaeseongs95.portal.app.attachment.AttachmentLimits;
import io.github.jaeseongs95.portal.app.attachment.AttachmentMetadata;
import io.github.jaeseongs95.portal.app.attachment.AttachmentReaper;
import io.github.jaeseongs95.portal.app.attachment.AttachmentUnreadableException;
import io.github.jaeseongs95.portal.app.attachment.PreparedAttachment;
import io.github.jaeseongs95.portal.app.maintenance.MaintenanceStore;
import io.github.jaeseongs95.portal.app.storage.PrivateStorages;
import io.github.jaeseongs95.portal.app.storage.ProtectionCheck;
import io.github.jaeseongs95.portal.app.storage.ReapGate;
import io.github.jaeseongs95.portal.app.storage.ReapReport;
import io.github.jaeseongs95.portal.domain.attachment.AttachmentId;
import io.github.jaeseongs95.portal.domain.attachment.AttachmentIdGenerator;
import io.github.jaeseongs95.portal.domain.attachment.AttachmentRole;
import io.github.jaeseongs95.portal.domain.id.Digest;
import io.github.jaeseongs95.portal.domain.id.Digester;
import io.github.jaeseongs95.portal.domain.id.Sha256Digester;
import io.github.jaeseongs95.portal.store.StorageRoots;
import io.github.jaeseongs95.portal.store.StoreTest;
import io.github.jaeseongs95.portal.store.TestStorage;

/**
 * S4-AT-01 through S4-AT-07: encrypted single-blob PAT1 attachment storage, schema invariants (no old body columns, no foreign keys on/to attachment, column bounds),
 * key rotation with permanent retired-key retention, tamper/missing detection, rollback orphan cleanup, and AttachmentReaper safety.
 */
@StoreTest
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import({AttachmentStoreTest.Beans.class, AttachmentKeyring.class, Pat1Envelope.class, AttachmentFiles.class, JdbcAttachmentStore.class})
class AttachmentStoreTest {

    private static final Instant T0 = Instant.parse("2026-11-26T00:00:00Z");

    @TestConfiguration(proxyBeanMethods = false)
    static class Beans {

        @Bean
        Clock clock() {
            return Clock.fixed(T0, ZoneOffset.UTC);
        }

        @Bean
        SecureRandom secureRandom() {
            return new SecureRandom();
        }

        @Bean
        Digester digester() {
            return new Sha256Digester();
        }

        @Bean
        AttachmentIdGenerator attachmentIdGenerator(SecureRandom random) {
            return new AttachmentIdGenerator(random);
        }

        @Bean
        PrivateStorages privateStorages() {
            return TestStorage.rules();
        }

        @Bean
        StorageRoots storageRoots() throws IOException {
            Path base = Files.createTempDirectory("portal-attachment-test");
            return new StorageRoots(base.resolve("artifacts"), base.resolve("outputs"));
        }
    }

    private final JdbcAttachmentStore store;
    private final AttachmentKeyring keyring;
    private final AttachmentFiles files;
    private final StorageRoots roots;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    AttachmentStoreTest(JdbcAttachmentStore store, AttachmentKeyring keyring, AttachmentFiles files, StorageRoots roots, JdbcTemplate jdbc,
                        PlatformTransactionManager transactionManager) {
        this.store = store;
        this.keyring = keyring;
        this.files = files;
        this.roots = roots;
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(transactionManager);
    }

    @AfterEach
    void cleanUp() throws IOException {
        jdbc.update("DELETE FROM attachment");
        FileSystemUtils.deleteRecursively(roots.attachments());
    }

    @Test
    @DisplayName("S4-AT-01: all six AttachmentRole values round-trip encrypted on disk as PAT1, owner-only, and remain decryptable after key rotation (RETIRED key retained)")
    void allSixRolesRoundTripAndSurviveKeyRotation() throws IOException {
        List<String> payloads = List.of(
            "echo 한글 명령 && printf 'ok\\n'",
            "caller context: 배포 점검",
            "",
            "powershell -NoProfile -Command -",
            "[\"arg1\",\"한글\",\"--flag\"]",
            "[\"ok\",\"줄바꿈\\n포함\"]");
        AttachmentRole[] roles = AttachmentRole.values();
        assertThat(roles).hasSize(6);

        AttachmentMetadata[] stored = new AttachmentMetadata[roles.length];
        for (int i = 0; i < roles.length; i++) {
            PreparedAttachment prepared = store.prepare(roles[i], payloads.get(i).getBytes(StandardCharsets.UTF_8));
            tx.executeWithoutResult(status -> store.recordMetadata(prepared.metadata()));
            stored[i] = store.findMetadata(prepared.metadata().id()).orElseThrow();

            Path blob = files.pathOf(stored[i].id());
            assertThat(blob).isRegularFile();
            TestStorage.assertOwnerOnly(blob);
            byte[] raw = Files.readAllBytes(blob);
            assertThat(new String(raw, 0, 4, StandardCharsets.UTF_8)).isEqualTo("PAT1");
            if (!payloads.get(i).isEmpty()) {
                assertThat(new String(raw, StandardCharsets.UTF_8)).doesNotContain(payloads.get(i));
            }
            assertThat(store.readUtf8(stored[i], roles[i])).isEqualTo(payloads.get(i));
        }

        TestStorage.assertOwnerOnly(keyring.keyFile());
        String oldKeyId = keyring.activeKey().keyId();
        String newKeyId = keyring.rotate().keyId();
        assertThat(newKeyId).isNotEqualTo(oldKeyId);
        assertThat(keyring.findKey(oldKeyId)).as("retired key is permanently retained").isPresent();

        for (int i = 0; i < roles.length; i++) {
            assertThat(store.readUtf8(stored[i], roles[i])).as("old attachment decrypts via retired key for " + roles[i]).isEqualTo(payloads.get(i));
        }
        PreparedAttachment afterRotate = store.prepare(AttachmentRole.COMMAND_TEXT, "after rotation".getBytes(StandardCharsets.UTF_8));
        tx.executeWithoutResult(status -> store.recordMetadata(afterRotate.metadata()));
        assertThat(store.readUtf8(afterRotate.metadata(), AttachmentRole.COMMAND_TEXT)).isEqualTo("after rotation");
    }

    @Test
    @DisplayName("S4-AT-01: explicit AttachmentKeyLocation override stores and loads keys outside the default keyring folder")
    void explicitKeyLocationOverrideWorks(@TempDir Path customDir) throws IOException {
        Path overrideFile = customDir.resolve("custom").resolve("override.keys");
        AttachmentKeyring customKeyring = new AttachmentKeyring(overrideFile, TestStorage.rules(), new SecureRandom(), Clock.fixed(T0, ZoneOffset.UTC));
        Pat1Envelope customEnvelope = new Pat1Envelope(customKeyring, new SecureRandom());
        AttachmentId id = new AttachmentId("1".repeat(48));

        byte[] ciphertext = customEnvelope.encrypt(id, AttachmentRole.COMMAND_TEXT, "secret".getBytes(StandardCharsets.UTF_8));
        assertThat(overrideFile).isRegularFile();
        TestStorage.assertOwnerOnly(overrideFile);
        assertThat(new String(customEnvelope.decrypt(id, AttachmentRole.COMMAND_TEXT, ciphertext), StandardCharsets.UTF_8)).isEqualTo("secret");
    }

    @Test
    @DisplayName("S4-AT-02: after V1..V8 none of the 10 old long-text columns exist, all 10 *_attachment_id CHAR(48) NOT NULL exist, and attachment has no path column")
    void schemaMatchesAttachmentContract() {
        List<String> oldColumns = List.of(
            "operation_command.command_text", "operation_command.caller_context", "operation_command.display_context",
            "operation_source.launcher_command", "operation_source.argv_json", "operation_source.caller_context", "operation_source.display_context",
            "operation_download.caller_context", "operation_download.display_context",
            "operation_result.result_texts");
        List<String> actualColumns = jdbc.queryForList(
            "SELECT LOWER(TABLE_NAME) || '.' || LOWER(COLUMN_NAME) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = 'PUBLIC'", String.class);
        assertThat(actualColumns).doesNotContainAnyElementsOf(oldColumns);

        List<String> newAttachmentColumns = List.of(
            "operation_command.command_text_attachment_id", "operation_command.caller_context_attachment_id", "operation_command.display_context_attachment_id",
            "operation_source.launcher_command_attachment_id", "operation_source.argv_json_attachment_id",
            "operation_source.caller_context_attachment_id", "operation_source.display_context_attachment_id",
            "operation_download.caller_context_attachment_id", "operation_download.display_context_attachment_id",
            "operation_result.result_texts_attachment_id");
        for (String full : newAttachmentColumns) {
            String[] parts = full.split("\\.");
            String spec = jdbc.queryForObject(
                "SELECT DATA_TYPE || '/' || CHARACTER_MAXIMUM_LENGTH || '/' || IS_NULLABLE FROM INFORMATION_SCHEMA.COLUMNS "
                    + "WHERE TABLE_SCHEMA = 'PUBLIC' AND LOWER(TABLE_NAME) = ? AND LOWER(COLUMN_NAME) = ?",
                String.class, parts[0], parts[1]);
            assertThat(spec).as(full).isEqualTo("CHARACTER/48/NO");
        }

        List<String> attachmentCols = jdbc.queryForList(
            "SELECT LOWER(COLUMN_NAME) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = 'PUBLIC' AND LOWER(TABLE_NAME) = 'attachment' ORDER BY ORDINAL_POSITION",
            String.class);
        assertThat(attachmentCols).containsExactly("id", "role", "size_bytes", "sha256", "created_at");
    }

    @Test
    @DisplayName("S4-AT-03: attachment table has zero outgoing foreign keys and zero incoming foreign keys from any other table")
    void attachmentHasZeroForeignKeys() {
        Long outgoing = jdbc.queryForObject(
            "SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS "
                + "WHERE TABLE_SCHEMA = 'PUBLIC' AND LOWER(TABLE_NAME) = 'attachment' AND CONSTRAINT_TYPE = 'FOREIGN KEY'", Long.class);
        Long incoming = jdbc.queryForObject(
            "SELECT COUNT(*) FROM INFORMATION_SCHEMA.REFERENTIAL_CONSTRAINTS rc "
                + "JOIN INFORMATION_SCHEMA.TABLE_CONSTRAINTS uc ON rc.UNIQUE_CONSTRAINT_NAME = uc.CONSTRAINT_NAME AND rc.UNIQUE_CONSTRAINT_SCHEMA = uc.CONSTRAINT_SCHEMA "
                + "WHERE uc.TABLE_SCHEMA = 'PUBLIC' AND LOWER(uc.TABLE_NAME) = 'attachment'", Long.class);
        assertThat(outgoing).isZero();
        assertThat(incoming).isZero();
    }

    @Test
    @DisplayName("S4-AT-04: mid-write DB failure rolls back metadata leaving only an orphan file on disk until quarantined after grace window")
    void midWriteFailureLeavesNoMetadataAndOrphanIsQuarantinedAfterGrace() throws IOException {
        PreparedAttachment prepared = store.prepare(AttachmentRole.COMMAND_TEXT, "orphan body".getBytes(StandardCharsets.UTF_8));
        AttachmentId orphanId = prepared.metadata().id();
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            store.recordMetadata(prepared.metadata());
            throw new IllegalStateException("simulated mid-transaction crash");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(store.findMetadata(orphanId)).isEmpty();
        assertThat(files.pathOf(orphanId)).isRegularFile();

        // Simulate file older than 1 hour and verify reaper quarantines it (does not permanently delete).
        Instant twoHoursAgo = T0.minus(Duration.ofHours(2));
        Files.setLastModifiedTime(files.pathOf(orphanId), FileTime.from(twoHoursAgo));
        AttachmentReaper reaper = new AttachmentReaper(store, new ReapGate(new FreeMaintenance(), ProtectionCheck.Protected::new), Clock.fixed(T0, ZoneOffset.UTC));
        assertThat(reaper.reap()).isEqualTo(new ReapReport.Reaped(1, 0));
        assertThat(files.pathOf(orphanId)).doesNotExist();
        assertThat(files.quarantinePathOf(orphanId)).isRegularFile();
    }

    @Test
    @DisplayName("S4-AT-05: tampered ciphertext, swapped file (AAD mismatch), wrong role, wrong metadata sha256, missing file, and unknown key all throw AttachmentUnreadableException")
    void unreadableAndTamperedAttachmentsFailClosed() throws IOException {
        PreparedAttachment first = store.prepare(AttachmentRole.COMMAND_TEXT, "alpha command".getBytes(StandardCharsets.UTF_8));
        PreparedAttachment second = store.prepare(AttachmentRole.CALLER_CONTEXT, "beta context".getBytes(StandardCharsets.UTF_8));
        tx.executeWithoutResult(status -> {
            store.recordMetadata(first.metadata());
            store.recordMetadata(second.metadata());
        });

        // Wrong expected role
        assertThatThrownBy(() -> store.decrypt(first.metadata(), AttachmentRole.CALLER_CONTEXT))
            .isInstanceOfSatisfying(AttachmentUnreadableException.class, e -> assertThat(e.reason()).isEqualTo(AttachmentUnreadableException.Reason.ROLE_MISMATCH));

        // Metadata SHA-256 mismatch (even when GCM tag is valid)
        AttachmentMetadata wrongDigest = new AttachmentMetadata(first.metadata().id(), AttachmentRole.COMMAND_TEXT, first.metadata().sizeBytes(),
            new Digest("0".repeat(64)), first.metadata().createdAt());
        assertThatThrownBy(() -> store.decrypt(wrongDigest, AttachmentRole.COMMAND_TEXT))
            .isInstanceOfSatisfying(AttachmentUnreadableException.class, e -> assertThat(e.reason()).isEqualTo(AttachmentUnreadableException.Reason.DIGEST_MISMATCH));

        // Swapped ciphertext file (AAD binds attachmentId + role)
        byte[] secondBytes = Files.readAllBytes(files.pathOf(second.metadata().id()));
        byte[] originalFirst = Files.readAllBytes(files.pathOf(first.metadata().id()));
        Files.write(files.pathOf(first.metadata().id()), secondBytes);
        assertThatThrownBy(() -> store.decrypt(first.metadata(), AttachmentRole.COMMAND_TEXT))
            .isInstanceOfSatisfying(AttachmentUnreadableException.class, e -> assertThat(e.reason()).isEqualTo(AttachmentUnreadableException.Reason.AUTH_FAILED));

        // Bit-flipped ciphertext
        byte[] flipped = originalFirst.clone();
        flipped[flipped.length - 1] ^= 0x01;
        Files.write(files.pathOf(first.metadata().id()), flipped);
        assertThatThrownBy(() -> store.decrypt(first.metadata(), AttachmentRole.COMMAND_TEXT))
            .isInstanceOfSatisfying(AttachmentUnreadableException.class, e -> assertThat(e.reason()).isEqualTo(AttachmentUnreadableException.Reason.AUTH_FAILED));

        // Missing key id in header
        byte[] missingKey = originalFirst.clone();
        missingKey[6] = (byte) (missingKey[6] == 'x' ? 'y' : 'x');
        Files.write(files.pathOf(first.metadata().id()), missingKey);
        assertThatThrownBy(() -> store.decrypt(first.metadata(), AttachmentRole.COMMAND_TEXT))
            .isInstanceOfSatisfying(AttachmentUnreadableException.class, e -> assertThat(e.reason()).isEqualTo(AttachmentUnreadableException.Reason.UNKNOWN_KEY));

        // Missing file
        Files.delete(files.pathOf(first.metadata().id()));
        assertThatThrownBy(() -> store.decrypt(first.metadata(), AttachmentRole.COMMAND_TEXT))
            .isInstanceOfSatisfying(AttachmentUnreadableException.class, e -> assertThat(e.reason()).isEqualTo(AttachmentUnreadableException.Reason.MISSING_FILE));
    }

    @Test
    @DisplayName("S4-AT-06: AttachmentStore rejects bodies exceeding AttachmentLimits.maxBytes without writing a file")
    void bodyExceedingMaxBytesIsRefused() {
        byte[] oversized = new byte[(int) AttachmentLimits.DEFAULT_MAX_BYTES + 1];
        assertThatThrownBy(() -> store.prepare(AttachmentRole.COMMAND_TEXT, oversized))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("exceeds max size");
        assertThat(files.storedFilesOlderThan(T0.plus(Duration.ofDays(1)))).isEmpty();
    }

    @Test
    @DisplayName("S4-AT-07: AttachmentReaper never deletes or quarantines files that have a row in attachment, protects <1h orphans, and quarantines >1h orphans")
    void reaperNeverTouchesReferencedAttachmentsAndProtectsGraceWindow() throws IOException {
        PreparedAttachment referenced = store.prepare(AttachmentRole.COMMAND_TEXT, "keep forever".getBytes(StandardCharsets.UTF_8));
        tx.executeWithoutResult(status -> store.recordMetadata(referenced.metadata()));
        PreparedAttachment recentOrphan = store.prepare(AttachmentRole.CALLER_CONTEXT, "in-flight orphan".getBytes(StandardCharsets.UTF_8));
        PreparedAttachment staleOrphan = store.prepare(AttachmentRole.DISPLAY_CONTEXT, "stale orphan".getBytes(StandardCharsets.UTF_8));

        Files.setLastModifiedTime(files.pathOf(referenced.metadata().id()), FileTime.from(T0.minus(Duration.ofDays(30))));
        Files.setLastModifiedTime(files.pathOf(recentOrphan.metadata().id()), FileTime.from(T0.minus(Duration.ofMinutes(30))));
        Files.setLastModifiedTime(files.pathOf(staleOrphan.metadata().id()), FileTime.from(T0.minus(Duration.ofMinutes(61))));

        AttachmentReaper reaper = new AttachmentReaper(store, new ReapGate(new FreeMaintenance(), ProtectionCheck.Protected::new), Clock.fixed(T0, ZoneOffset.UTC));
        assertThat(reaper.reap()).isEqualTo(new ReapReport.Reaped(1, 0));

        assertThat(files.pathOf(referenced.metadata().id())).as("referenced file is never touched").isRegularFile();
        assertThat(store.readUtf8(referenced.metadata(), AttachmentRole.COMMAND_TEXT)).isEqualTo("keep forever");
        assertThat(files.pathOf(recentOrphan.metadata().id())).as("in-flight file under 1h grace window is preserved").isRegularFile();
        assertThat(files.pathOf(staleOrphan.metadata().id())).as("stale orphan moved out of active bucket").doesNotExist();
        assertThat(files.quarantinePathOf(staleOrphan.metadata().id())).as("stale orphan preserved in quarantine").isRegularFile();
    }

    private static final class FreeMaintenance implements MaintenanceStore {
        private Instant reserved;

        @Override
        public boolean occupied() {
            return reserved != null;
        }

        @Override
        public Optional<Instant> reservedAt() {
            return Optional.ofNullable(reserved);
        }

        @Override
        public void reserve(Instant reservedAt) {
            this.reserved = reservedAt;
        }

        @Override
        public void release() {
            this.reserved = null;
        }
    }
}
