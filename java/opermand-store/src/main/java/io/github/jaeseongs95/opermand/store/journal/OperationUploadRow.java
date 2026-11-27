package io.github.jaeseongs95.opermand.store.journal;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import io.github.jaeseongs95.opermand.domain.operation.Elevation;
import io.github.jaeseongs95.opermand.domain.operation.OverwriteMode;
import io.github.jaeseongs95.opermand.domain.operation.UploadProtocol;

/** Table {@code operation_upload}: the input of an upload. There is no foreign key to the artifact: an artifact is released before the history row (A-495). */
@Table("operation_upload")
public record OperationUploadRow(@Id String operationId, String artifactId, String remotePath, String fileName, long sizeBytes, String sha256, OverwriteMode overwriteMode,
                                 Elevation elevation, String fileTargetId, String fileTargetRevision, UploadProtocol uploadProtocol) {
}
