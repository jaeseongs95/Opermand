package io.github.jaeseongs95.portal.archchecks.fixtures.s3.store;

import org.springframework.transaction.support.TransactionTemplate;

/** Red fixture for A-TX-BOUNDARY (D-0114): a store class depending on {@code TransactionTemplate}. */
public class TemplateStore {

    private final TransactionTemplate template;

    public TemplateStore(TransactionTemplate template) {
        this.template = template;
    }

    public void write() {
        template.executeWithoutResult(status -> { });
    }
}
