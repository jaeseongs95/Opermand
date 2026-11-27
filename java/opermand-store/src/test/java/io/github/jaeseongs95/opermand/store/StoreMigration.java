package io.github.jaeseongs95.opermand.store;

import java.time.Clock;

import javax.sql.DataSource;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

import io.github.jaeseongs95.opermand.store.migration.ExpandOnStart;
import io.github.jaeseongs95.opermand.store.migration.Migrations;

/**
 * The migration of the storage slice: what {@code MigrationConfiguration} does in the product (the expand scripts at the start, before the beans that use the database), with a
 * clock of its own. A test slice does not scan {@code @Configuration} classes, and the slice has no {@code Clock} bean that every test could share (the tests that need a fixed
 * time declare their own), so the slice makes the one {@link ExpandOnStart} here; the history stamps are not what these tests are about.
 */
@TestConfiguration(proxyBeanMethods = false)
class StoreMigration {

    @Bean
    ExpandOnStart expandOnStart(DataSource dataSource) {
        return new ExpandOnStart(new Migrations(Clock.systemUTC(), dataSource));
    }
}
