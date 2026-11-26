package io.github.jaeseongs95.portal.store.attachment;

import org.springframework.data.repository.CrudRepository;

/** Spring Data JDBC repository for the {@code attachment} metadata table. */
public interface AttachmentRowRepository extends CrudRepository<AttachmentRow, String> {
}
