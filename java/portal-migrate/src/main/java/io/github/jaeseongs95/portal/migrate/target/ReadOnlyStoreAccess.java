package io.github.jaeseongs95.portal.migrate.target;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.ComponentScans;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;
import org.springframework.data.jdbc.repository.config.EnableJdbcRepositories;

import io.github.jaeseongs95.portal.store.journal.OperationMetadataLookup;

/**
 * Metadata-only store access for {@code --dry-run}: loads only {@link OperationMetadataLookup} and the UTC/quoting JDBC helpers so a dry run checks collisions and the
 * unknown budget against the {@code operation} table without creating, opening, or reading the attachment keyring or any encrypted body files.
 */
@Configuration(proxyBeanMethods = false)
@ComponentScans({
    @ComponentScan(basePackages = "io.github.jaeseongs95.portal.store.journal", useDefaultFilters = false,
        includeFilters = @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = OperationMetadataLookup.class)),
    @ComponentScan(basePackages = "io.github.jaeseongs95.portal.store", useDefaultFilters = false,
        includeFilters = @ComponentScan.Filter(type = FilterType.REGEX, pattern = "io\\.github\\.jaeseongs95\\.portal\\.store\\.(IdentifierQuoting|UtcTimestamps|UtcJdbcConversions)"))
})
@EnableJdbcRepositories(basePackages = "io.github.jaeseongs95.portal.store.journal")
class ReadOnlyStoreAccess {

    @Bean
    ExistingHistory existingHistory(OperationMetadataLookup lookup) {
        return new StoredHistory(lookup);
    }
}
