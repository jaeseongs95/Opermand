package io.github.jaeseongs95.opermand.migrate.target;

import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.ComponentScans;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;
import org.springframework.data.jdbc.repository.config.EnableJdbcRepositories;

import io.github.jaeseongs95.opermand.domain.attachment.AttachmentIdGenerator;
import io.github.jaeseongs95.opermand.domain.id.Digester;
import io.github.jaeseongs95.opermand.domain.id.OperationIdGenerator;
import io.github.jaeseongs95.opermand.domain.id.Sha256Digester;
import io.github.jaeseongs95.opermand.store.StorageRoots;
import io.github.jaeseongs95.opermand.store.journal.OperationMetadataLookup;

/**
 * The full journal and encrypted-attachment store wiring used by a real migration run (and full store inspection in tests): wires {@code portal.store.journal},
 * {@code portal.store.attachment}, UTC/quoting JDBC helpers, and the storage/attachment-preparation helpers via component scan while keeping {@code opermand-migrate}'s
 * compiled classes free of direct {@code opermand-app} references (rule A-DIR).
 */
@Configuration(proxyBeanMethods = false)
@ComponentScans({
    @ComponentScan(basePackages = "io.github.jaeseongs95.opermand.store.journal"),
    @ComponentScan(basePackages = "io.github.jaeseongs95.opermand.store.attachment"),
    @ComponentScan(basePackages = "io.github.jaeseongs95.opermand.store", useDefaultFilters = false,
        includeFilters = @ComponentScan.Filter(type = FilterType.REGEX, pattern = "io\\.github\\.jaeseongs95\\.opermand\\.store\\.(IdentifierQuoting|UtcTimestamps|UtcJdbcConversions)")),
    @ComponentScan(basePackages = "io.github.jaeseongs95.opermand.app.storage", useDefaultFilters = false,
        includeFilters = @ComponentScan.Filter(type = FilterType.REGEX, pattern = "io\\.github\\.jaeseongs95\\.opermand\\.app\\.storage\\.(PrivateStorages|PosixPrivateStorage|AclPrivateStorage)")),
    @ComponentScan(basePackages = "io.github.jaeseongs95.opermand.app.attachment",
        excludeFilters = @ComponentScan.Filter(type = FilterType.REGEX, pattern = "io\\.github\\.jaeseongs95\\.opermand\\.app\\.attachment\\.AttachmentReaper"))
})
@EnableJdbcRepositories(basePackages = {"io.github.jaeseongs95.opermand.store.journal", "io.github.jaeseongs95.opermand.store.attachment"})
class StoreAccess {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    SecureRandom secureRandom() {
        return new SecureRandom();
    }

    @Bean
    Digester digester() {
        return new Sha256Digester();
    }

    @Bean
    OperationIdGenerator operationIdGenerator(SecureRandom random) {
        return new OperationIdGenerator(random);
    }

    @Bean
    AttachmentIdGenerator attachmentIdGenerator(SecureRandom random) {
        return new AttachmentIdGenerator(random);
    }

    @Bean
    StorageRoots storageRoots(MigrationTarget target) {
        Path base = target.dataDir();
        return new StorageRoots(base.resolve("artifacts"), base.resolve("outputs"), base.resolve("attachments"), base.resolve("keyring"));
    }

    @Bean
    ExistingHistory existingHistory(OperationMetadataLookup lookup) {
        return new StoredHistory(lookup);
    }
}
