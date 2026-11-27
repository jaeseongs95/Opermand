package io.github.jaeseongs95.opermand.migrate.target;

import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * The context of a dry run: the store as it is, metadata-only and read only. No migration script is applied, no writer exists, and no attachment keyring or encrypted body
 * store is loaded or created, so a dry run cannot write or touch the keyring by construction.
 */
@Configuration(proxyBeanMethods = false)
@EnableAutoConfiguration
@Import(ReadOnlyStoreAccess.class)
public class ReadOnlyTargetConfiguration {

    @Bean
    TargetSchema targetSchema() {
        return new CurrentSchema();
    }
}
