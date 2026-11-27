package io.github.jaeseongs95.opermand.store.journal;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import io.github.jaeseongs95.opermand.domain.operation.Elevation;

/** Table {@code operation_download}: the input of a download. The two body fields reference primary keys in {@code attachment}. */
@Table("operation_download")
public record OperationDownloadRow(@Id String operationId, String remotePath, Elevation elevation, String fileTargetId, String fileTargetRevision,
                                   String callerContextAttachmentId, String displayContextAttachmentId) {
}
