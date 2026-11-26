package io.github.jaeseongs95.portal.migrate.target;

import java.security.SecureRandom;
import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.ComponentScans;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;
import org.springframework.data.jdbc.repository.config.EnableJdbcRepositories;

import io.github.jaeseongs95.portal.domain.id.Digester;
import io.github.jaeseongs95.portal.domain.id.OperationIdGenerator;
import io.github.jaeseongs95.portal.domain.id.Sha256Digester;
import io.github.jaeseongs95.portal.store.journal.JdbcOperationJournal;
import io.github.jaeseongs95.portal.store.journal.JdbcOperationQueueView;

/**
 * The part of the 3.0 store that the migration uses: the operation journal and the claim store of {@code portal-store} (found by name below, the same classes the server's
 * assembly uses, over the same tables), the mapping settings the store needs, and the helper beans the journal asks for. Nothing else of the store is started: the artifact,
 * output, audit and policy adapters are not needed to write a history. It is imported by the two roots, {@link ReadOnlyTargetConfiguration} and
 * {@link WritableTargetConfiguration}.
 */
@Configuration(proxyBeanMethods = false)
@ComponentScans({
    @ComponentScan(basePackages = "io.github.jaeseongs95.portal.store.journal"),
    // The two classes of the store's root package that the journal adapters depend on (the name quoting of the mapping context, the savepoints); the filter names them
    // because the quoting class is package private and the rest of that package belongs to adapters this tool does not start.
    @ComponentScan(basePackages = "io.github.jaeseongs95.portal.store", useDefaultFilters = false,
        includeFilters = @ComponentScan.Filter(type = FilterType.REGEX, pattern = "io\\.github\\.jaeseongs95\\.portal\\.store\\.(IdentifierQuoting|Savepoints)"))
})
@EnableJdbcRepositories(basePackages = "io.github.jaeseongs95.portal.store.journal")
class StoreAccess {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    Digester digester() {
        return new Sha256Digester();
    }

    @Bean
    OperationIdGenerator operationIdGenerator() {
        return new OperationIdGenerator(new SecureRandom());
    }

    @Bean
    ExistingHistory existingHistory(JdbcOperationJournal journal, JdbcOperationQueueView queue) {
        return new StoredHistory(journal, queue);
    }
}
