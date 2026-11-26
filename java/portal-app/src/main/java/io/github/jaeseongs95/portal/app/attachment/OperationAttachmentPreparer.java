package io.github.jaeseongs95.portal.app.attachment;

import java.nio.charset.StandardCharsets;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import io.github.jaeseongs95.portal.domain.attachment.AttachmentRole;
import io.github.jaeseongs95.portal.domain.operation.AgentCliInput;
import io.github.jaeseongs95.portal.domain.operation.CommandInput;
import io.github.jaeseongs95.portal.domain.operation.DownloadInput;
import io.github.jaeseongs95.portal.domain.operation.OperationInput;
import io.github.jaeseongs95.portal.domain.operation.RecordedResult;
import io.github.jaeseongs95.portal.domain.operation.SourceInput;
import io.github.jaeseongs95.portal.domain.operation.UploadInput;
import tools.jackson.databind.json.JsonMapper;

/**
 * Encrypts and writes operation body attachments outside any database transaction before {@code OperationIntake} or {@code OperationWrites} opens its write transaction,
 * and cleans up prepared files if the transaction rolls back or does not consume them (Principles 4.4, stage 4 design B-15).
 */
@Component
public class OperationAttachmentPreparer {

    private static final Logger LOG = LoggerFactory.getLogger(OperationAttachmentPreparer.class);
    private static final OperationAttachmentPreparer NONE = new OperationAttachmentPreparer();

    private final AttachmentStore store;
    private final JsonMapper mapper;

    @Autowired
    public OperationAttachmentPreparer(AttachmentStore store, JsonMapper mapper) {
        this.store = Objects.requireNonNull(store, "store");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    private OperationAttachmentPreparer() {
        this.store = null;
        this.mapper = null;
    }

    /** No-op preparer for pure in-memory unit tests that stub {@code OperationJournal} without an {@link AttachmentStore}. */
    public static OperationAttachmentPreparer none() {
        return NONE;
    }

    /** Prepares all encrypted input body files for {@code input} outside any database transaction. Cleans up partial files on failure. */
    public PreparedOperationAttachments prepareInput(OperationInput input) {
        Objects.requireNonNull(input, "input");
        if (store == null) {
            return PreparedOperationAttachments.empty();
        }
        Map<AttachmentRole, PreparedAttachment> prepared = new EnumMap<>(AttachmentRole.class);
        try {
            switch (input) {
                case CommandInput command -> {
                    putUtf8(prepared, AttachmentRole.COMMAND_TEXT, command.command().value());
                    putUtf8(prepared, AttachmentRole.CALLER_CONTEXT, command.callerContext().value());
                    putUtf8(prepared, AttachmentRole.DISPLAY_CONTEXT, command.displayContext().value());
                }
                case SourceInput source -> {
                    putUtf8(prepared, AttachmentRole.LAUNCHER_COMMAND, source.launcher().value());
                    putUtf8(prepared, AttachmentRole.ARGV_JSON, source.argvJson().value());
                    putUtf8(prepared, AttachmentRole.CALLER_CONTEXT, source.callerContext().value());
                    putUtf8(prepared, AttachmentRole.DISPLAY_CONTEXT, source.displayContext().value());
                }
                case DownloadInput download -> {
                    putUtf8(prepared, AttachmentRole.CALLER_CONTEXT, download.callerContext().value());
                    putUtf8(prepared, AttachmentRole.DISPLAY_CONTEXT, download.displayContext().value());
                }
                case UploadInput upload -> {
                    // UploadInput has no long body text columns.
                }
                case AgentCliInput agentCli -> {
                    // AgentCliInput stores only its intent digest on the operation row.
                }
            }
            return PreparedOperationAttachments.of(prepared);
        } catch (RuntimeException e) {
            discardAll(prepared.values(), e);
            throw e;
        }
    }

    /** Prepares the encrypted {@code RESULT_TEXTS} attachment for a settled result outside any database transaction. */
    public PreparedOperationAttachments prepareResult(RecordedResult result) {
        Objects.requireNonNull(result, "result");
        if (store == null) {
            return PreparedOperationAttachments.empty();
        }
        List<String> texts = switch (result) {
            case RecordedResult.NoResult noResult -> null;
            case RecordedResult.Executed executed -> executed.outcome().texts();
            case RecordedResult.Refused refused -> List.of();
        };
        if (texts == null) {
            return PreparedOperationAttachments.empty();
        }
        String json = mapper.writeValueAsString(texts);
        PreparedAttachment attachment = store.prepare(AttachmentRole.RESULT_TEXTS, json.getBytes(StandardCharsets.UTF_8));
        return PreparedOperationAttachments.of(Map.of(AttachmentRole.RESULT_TEXTS, attachment));
    }

    /** Best-effort deletion of prepared files whose DB transaction failed or did not consume them. */
    public void discard(PreparedOperationAttachments prepared) {
        if (store == null || prepared == null || prepared.isEmpty()) {
            return;
        }
        for (PreparedAttachment attachment : prepared.all()) {
            try {
                store.discardPrepared(attachment.metadata().id());
            } catch (RuntimeException e) {
                LOG.warn("Prepared attachment {} could not be discarded after rollback; the orphan reaper will quarantine it after the grace period",
                    attachment.metadata().id().value(), e);
            }
        }
    }

    private void putUtf8(Map<AttachmentRole, PreparedAttachment> target, AttachmentRole role, String text) {
        target.put(role, store.prepare(role, text.getBytes(StandardCharsets.UTF_8)));
    }

    private void discardAll(Iterable<PreparedAttachment> attachments, RuntimeException primary) {
        for (PreparedAttachment attachment : attachments) {
            try {
                store.discardPrepared(attachment.metadata().id());
            } catch (RuntimeException cleanup) {
                primary.addSuppressed(cleanup);
            }
        }
    }
}
