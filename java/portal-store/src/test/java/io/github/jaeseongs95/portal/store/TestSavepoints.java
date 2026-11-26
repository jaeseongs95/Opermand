package io.github.jaeseongs95.portal.store;

import java.util.Objects;
import java.util.function.Supplier;

import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Test-only savepoint helper imported exclusively by {@link StoreTest} so schema constraint tests can assert multiple rejected SQL statements inside one rolled-back
 * {@code @StoreTest} transaction under {@link PgAbortedTransactionDataSource} without keeping any savepoint helper in production {@code src/main}.
 */
@Component
public class TestSavepoints {

    private final TransactionTemplate nested;

    public TestSavepoints(PlatformTransactionManager transactionManager) {
        this.nested = new TransactionTemplate(Objects.requireNonNull(transactionManager, "transactionManager"));
        this.nested.setPropagationBehavior(TransactionDefinition.PROPAGATION_NESTED);
    }

    public void run(Runnable body) {
        Objects.requireNonNull(body, "body");
        nested.executeWithoutResult(status -> body.run());
    }

    public <T> T call(Supplier<T> body) {
        Objects.requireNonNull(body, "body");
        return nested.execute(status -> body.get());
    }
}
