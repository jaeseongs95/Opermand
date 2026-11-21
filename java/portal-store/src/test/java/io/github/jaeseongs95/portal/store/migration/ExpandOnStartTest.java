package io.github.jaeseongs95.portal.store.migration;

import static io.github.jaeseongs95.portal.store.migration.MigrationTestSupport.freshUrl;
import static io.github.jaeseongs95.portal.store.migration.MigrationTestSupport.history;
import static io.github.jaeseongs95.portal.store.migration.MigrationTestSupport.tableExists;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;

import javax.sql.DataSource;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceInitializationAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.JdbcClientAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.JdbcTemplateAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;

import io.github.jaeseongs95.portal.store.migration.MigrationRunner.Phase;

/**
 * S-D0109-12 (start-up half), S-D0109-16 and S-D0109-17: the product has one database account (the {@code spring.datasource.*} of Boot), so the expand scripts are applied at
 * every start by the {@link ExpandOnStart} bean, before any bean that uses the database is initialised; the contract scripts are never applied at start. The context is the
 * one a Boot application gets: the auto-configured pool and {@link JdbcClient} and the configuration of the migration.
 */
class ExpandOnStartTest {

    /** Reads the schema in its own initialisation, like a start-up check does. It is registered before the migration configuration, so only the declared dependency can put the migration first. */
    @Configuration(proxyBeanMethods = false)
    static class ReaderBeforeMigration {
        @Bean
        PolicyRowsAtStart policyRowsAtStart(JdbcClient jdbc) {
            return new PolicyRowsAtStart(jdbc);
        }

        @Bean
        Clock clock() {
            return Clock.fixed(MigrationTestSupport.NOW, java.time.ZoneOffset.UTC);
        }
    }

    static final class PolicyRowsAtStart implements InitializingBean {
        private final JdbcClient jdbc;
        private long rows = -1;

        PolicyRowsAtStart(JdbcClient jdbc) {
            this.jdbc = jdbc;
        }

        @Override
        public void afterPropertiesSet() {
            rows = jdbc.sql("SELECT COUNT(*) FROM command_policy").query(Long.class).single();
        }
    }

    private static ApplicationContextRunner runnerOn(String url) {
        return new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(DataSourceAutoConfiguration.class, DataSourceInitializationAutoConfiguration.class, JdbcTemplateAutoConfiguration.class,
                JdbcClientAutoConfiguration.class))
            .withUserConfiguration(ReaderBeforeMigration.class, MigrationConfiguration.class)
            .withPropertyValues("spring.datasource.url=" + url);
    }

    @Test
    @DisplayName("S-D0109-16: every start applies the expand scripts of the jar (7 the first time, 0 on a start after that), over the one data source, and never makes a contract history")
    void everyStartAppliesExpand() {
        String url = freshUrl();

        runnerOn(url).run(first -> {
            assertThat(first).hasNotFailed();
            assertThat(first.getBean(ExpandOnStart.class).applied()).isEqualTo(8);
            JdbcTemplate jdbc = new JdbcTemplate(first.getBean(DataSource.class));
            assertThat(history(jdbc, Phase.EXPAND)).hasSize(8);
            assertThat(tableExists(jdbc, Migrations.SCHEMA, "contract_history")).as("a start never applies contract").isFalse();
        });

        runnerOn(url).run(second -> {
            assertThat(second).hasNotFailed();
            assertThat(second.getBean(ExpandOnStart.class).applied()).isZero();
            assertThat(history(new JdbcTemplate(second.getBean(DataSource.class)), Phase.EXPAND)).as("no row was added").hasSize(8);
        });
    }

    @Test
    @DisplayName("S-D0109-17: a bean that reads the schema while it is initialised finds it: the migration runs before the beans that use the database")
    void theMigrationRunsBeforeTheBeansThatUseTheDatabase() {
        runnerOn(freshUrl()).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(PolicyRowsAtStart.class).rows).as("the policy seed row of the schema").isEqualTo(1);
        });
    }
}
