package io.github.jaeseongs95.portal.migrate.target;

import java.time.Clock;

import javax.sql.DataSource;

import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import io.github.jaeseongs95.portal.store.journal.JdbcClaimStore;
import io.github.jaeseongs95.portal.store.journal.JdbcOperationJournal;
import io.github.jaeseongs95.portal.store.migration.Migrations;

/**
 * The context of a real run: the store with its schema brought up to date by the store's own runner (when the run asks for it, after every check of the 2.x data has passed,
 * not at the start of the context, so that a 2.x folder that cannot be migrated never creates a 3.0 store), encrypted body attachments under {@link MigrationTarget}, and the
 * writer.
 */
@Configuration(proxyBeanMethods = false)
@EnableAutoConfiguration
@Import(StoreAccess.class)
public class WritableTargetConfiguration {

    @Bean
    Migrations migrations(Clock clock, DataSource dataSource) {
        return new Migrations(clock, dataSource);
    }

    @Bean
    TargetSchema targetSchema(Migrations migrations) {
        return new ExpandSchema(migrations);
    }

    @Bean
    HistoryWriter historyWriter(JdbcOperationJournal journal, JdbcClaimStore claims) {
        return new HistoryWriter(journal, claims);
    }
}
