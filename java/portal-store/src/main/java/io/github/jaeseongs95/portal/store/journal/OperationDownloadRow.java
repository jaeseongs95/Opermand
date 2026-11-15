package io.github.jaeseongs95.portal.store.journal;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import io.github.jaeseongs95.portal.domain.operation.Elevation;

/** Table {@code operation_download}: the input of a download. The two texts are long-text columns (${long_text}) of V1. */
@Table("operation_download")
public record OperationDownloadRow(@Id String operationId, String remotePath, Elevation elevation, String fileTargetId, String fileTargetRevision, String callerContext,
                                   String displayContext) {
}
