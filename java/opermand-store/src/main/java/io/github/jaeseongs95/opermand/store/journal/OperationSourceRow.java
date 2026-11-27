package io.github.jaeseongs95.opermand.store.journal;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import io.github.jaeseongs95.opermand.domain.operation.Elevation;

/** Table {@code operation_source}: the input of a source operation (the source text itself is never stored). The four body fields reference primary keys in {@code attachment}. */
@Table("operation_source")
public record OperationSourceRow(@Id String operationId, String planDigest, String sourceSha256, int sourceBytes, String cwd, Elevation elevation, String environmentRevision,
                                 String launcherCommandAttachmentId, String argvJsonAttachmentId, String callerContextAttachmentId, String displayContextAttachmentId) {
}
