package io.github.jaeseongs95.opermand.archchecks.fixtures.s3.store;

import org.springframework.transaction.annotation.Transactional;

/** Red fixture for A-TX-BOUNDARY (D-0114): a store class annotated with {@code @Transactional}. */
public class TransactionalStore {

    @Transactional
    public void write() {
    }
}
