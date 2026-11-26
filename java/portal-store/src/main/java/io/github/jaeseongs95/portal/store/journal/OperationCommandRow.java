package io.github.jaeseongs95.portal.store.journal;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

/** Table {@code operation_command}: the input of a command operation. The three body fields reference primary keys in the {@code attachment} table (no foreign key). */
@Table("operation_command")
public record OperationCommandRow(@Id String operationId, String commandTextAttachmentId, String callerContextAttachmentId, String displayContextAttachmentId) {
}
