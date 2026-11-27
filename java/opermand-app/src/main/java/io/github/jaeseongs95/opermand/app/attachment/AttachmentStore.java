package io.github.jaeseongs95.opermand.app.attachment;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import io.github.jaeseongs95.opermand.domain.attachment.AttachmentId;
import io.github.jaeseongs95.opermand.domain.attachment.AttachmentRole;

/**
 * Storage port for encrypted operation-body attachments (D-0092, stage 4 design B-15).
 *
 * <p>File/crypto methods ({@link #prepare}, {@link #discardPrepared}, {@link #decrypt}, {@link #storedFilesOlderThan}, {@link #quarantine}) perform zero database calls
 * and must run outside database transactions (Principles 4.4). Metadata methods ({@link #recordMetadata}, {@link #findMetadata}, {@link #existingMetadataIds}) perform
 * database operations only and join the caller's transaction.
 */
public interface AttachmentStore {

    /** Encrypts {@code plaintext} into the PAT1 envelope, writes and {@code fsync}s the file outside any DB transaction, and returns its prepared metadata. */
    PreparedAttachment prepare(AttachmentRole role, byte[] plaintext);

    /** Inserts the {@code attachment} metadata row inside the caller's write transaction (zero file/crypto I/O). */
    void recordMetadata(AttachmentMetadata metadata);

    /** Best-effort file deletion for a prepared attachment whose DB transaction rolled back or did not need it (zero DB I/O). */
    void discardPrepared(AttachmentId id);

    /** Reads one metadata row from the {@code attachment} table (DB-only). */
    Optional<AttachmentMetadata> findMetadata(AttachmentId id);

    /** Reads metadata rows for the given ids from the {@code attachment} table (DB-only). */
    Map<AttachmentId, AttachmentMetadata> findMetadata(Collection<AttachmentId> ids);

    /**
     * Reads and decrypts the attachment file for {@code metadata}, verifying role, PAT1 AEAD tag + AAD, plaintext length, and plaintext SHA-256 (file/crypto only).
     *
     * @throws AttachmentUnreadableException if missing, unreadable, unknown key, tampered, or mismatched in role/size/digest
     */
    byte[] decrypt(AttachmentMetadata metadata, AttachmentRole expectedRole);

    /** Convenience wrapper decoding the verified plaintext as UTF-8. */
    default String readUtf8(AttachmentMetadata metadata, AttachmentRole expectedRole) {
        return new String(decrypt(metadata, expectedRole), StandardCharsets.UTF_8);
    }

    /** Returns attachment ids present on disk whose last-modified instant is at or before {@code cutoff} (file-only). */
    Set<AttachmentId> storedFilesOlderThan(Instant cutoff);

    /** Returns the subset of {@code candidates} that currently have a row in the {@code attachment} table (DB-only). */
    Set<AttachmentId> existingMetadataIds(Collection<AttachmentId> candidates);

    /** Moves an unreferenced orphan file into the attachments quarantine folder (file-only, never permanently deletes). */
    void quarantine(AttachmentId id);
}
