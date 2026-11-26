package io.github.jaeseongs95.portal.migrate.target;

import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * The context of a dry run: the store as it is, read only. No migration script is applied and no writer exists, so the run cannot write by construction. (The data source
 * is opened read only by the settings the command gives for an embedded store.)
 */
@Configuration(proxyBeanMethods = false)
@EnableAutoConfiguration
@Import(StoreAccess.class)
public class ReadOnlyTargetConfiguration {

    @Bean
    TargetSchema targetSchema() {
        return new CurrentSchema();
    }
}
