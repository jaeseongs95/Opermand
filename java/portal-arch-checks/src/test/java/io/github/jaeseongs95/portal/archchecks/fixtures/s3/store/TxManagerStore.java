package io.github.jaeseongs95.portal.archchecks.fixtures.s3.store;

import org.springframework.transaction.PlatformTransactionManager;

/** Red fixture for A-TX-BOUNDARY (D-0114): a store class injecting {@code PlatformTransactionManager}. */
public class TxManagerStore {

    private final PlatformTransactionManager manager;

    public TxManagerStore(PlatformTransactionManager manager) {
        this.manager = manager;
    }

    public PlatformTransactionManager manager() {
        return manager;
    }
}
