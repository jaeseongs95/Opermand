package io.github.jaeseongs95.opermand.store.migration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;

/**
 * Applies the expand scripts when the bean is initialised, which is before any bean that uses the database ({@link MigrationInitializerDetector} tells Boot so). The product has one
 * database account, so it can apply the scripts itself at every start; a product with a separate DDL account would refuse to start and ask for a command instead (common
 * format, 5). A failure stops the start: the context does not come up on a schema that is not the jar's.
 */
public final class ExpandOnStart implements InitializingBean {
    private static final Logger LOG = LoggerFactory.getLogger(ExpandOnStart.class);

    private final Migrations migrations;
    private volatile int applied = -1;

    public ExpandOnStart(Migrations migrations) {
        this.migrations = migrations;
    }

    @Override
    public void afterPropertiesSet() {
        applied = migrations.expand();
        LOG.info("The expand migrations were applied: {} script(s) this start.", applied);
    }

    /** The number of scripts this start applied (0 when the schema was up to date); -1 before the start has run. */
    public int applied() {
        return applied;
    }
}
