package io.github.jaeseongs95.opermand.app.attachment;

import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

import io.github.jaeseongs95.opermand.domain.attachment.AttachmentRole;

/**
 * The set of encrypted attachments prepared outside a database transaction for one operation intake or settlement write. Tracks whether the write transaction consumed
 * them so the caller can clean up unneeded or rolled-back files outside the transaction.
 */
public final class PreparedOperationAttachments {

    private static final PreparedOperationAttachments EMPTY = new PreparedOperationAttachments(Map.of());

    private final Map<AttachmentRole, PreparedAttachment> entries;
    private final AtomicBoolean consumed = new AtomicBoolean(false);

    private PreparedOperationAttachments(Map<AttachmentRole, PreparedAttachment> entries) {
        if (entries.isEmpty()) {
            this.entries = Map.of();
        } else {
            EnumMap<AttachmentRole, PreparedAttachment> copy = new EnumMap<>(AttachmentRole.class);
            copy.putAll(entries);
            this.entries = Collections.unmodifiableMap(copy);
        }
    }

    public static PreparedOperationAttachments empty() {
        return EMPTY;
    }

    public static PreparedOperationAttachments of(Map<AttachmentRole, PreparedAttachment> entries) {
        Objects.requireNonNull(entries, "entries");
        return entries.isEmpty() ? EMPTY : new PreparedOperationAttachments(entries);
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }

    public Collection<PreparedAttachment> all() {
        return entries.values();
    }

    public PreparedAttachment require(AttachmentRole role) {
        Objects.requireNonNull(role, "role");
        PreparedAttachment prepared = entries.get(role);
        if (prepared == null) {
            throw new IllegalStateException("Missing prepared attachment for role " + role);
        }
        return prepared;
    }

    public boolean has(AttachmentRole role) {
        return entries.containsKey(Objects.requireNonNull(role, "role"));
    }

    public void markConsumed() {
        consumed.set(true);
    }

    public boolean isConsumed() {
        return consumed.get();
    }
}
