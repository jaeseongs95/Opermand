package io.github.jaeseongs95.portal.store.migration;

import java.time.Clock;

import javax.sql.DataSource;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The migration of the schema as the product wires it: the {@link Migrations} over the product's data source and the {@link ExpandOnStart} that applies the expand scripts at every
 * start. Found by the component scan of {@code StoreConfiguration}.
 */
@Configuration(proxyBeanMethods = false)
public class MigrationConfiguration {

    @Bean
    public Migrations migrations(Clock clock, DataSource dataSource) {
        return new Migrations(clock, dataSource);
    }

    @Bean
    public ExpandOnStart expandOnStart(Migrations migrations) {
        return new ExpandOnStart(migrations);
    }
}
