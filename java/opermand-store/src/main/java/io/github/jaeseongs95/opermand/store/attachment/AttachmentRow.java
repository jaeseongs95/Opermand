package io.github.jaeseongs95.opermand.store.attachment;

import java.time.Instant;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import io.github.jaeseongs95.opermand.domain.attachment.AttachmentRole;

/** Table {@code attachment}: metadata of one encrypted operation-body blob (no path column, no foreign keys). */
@Table("attachment")
public record AttachmentRow(@Id String id, AttachmentRole role, long sizeBytes, String sha256, Instant createdAt) {
}
