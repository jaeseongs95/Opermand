package io.github.jaeseongs95.portal.store.journal;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

import io.github.jaeseongs95.portal.app.storage.PrivateStorages;
import io.github.jaeseongs95.portal.domain.attachment.AttachmentIdGenerator;
import io.github.jaeseongs95.portal.domain.id.Digester;
import io.github.jaeseongs95.portal.domain.id.OperationIdGenerator;
import io.github.jaeseongs95.portal.domain.id.Sha256Digester;
import io.github.jaeseongs95.portal.store.StorageRoots;
import io.github.jaeseongs95.portal.store.TestStorage;
import tools.jackson.databind.json.JsonMapper;

/** The helper beans the journal and attachment adapters need, with a fixed {@link Clock} so that every time in a test is a value the test chose. */
@TestConfiguration(proxyBeanMethods = false)
class JournalTestBeans {

    static final Instant NOW = Instant.parse("2026-11-12T00:00:00Z");

    @Bean
    Clock clock() {
        return Clock.fixed(NOW, ZoneOffset.UTC);
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
    JsonMapper jsonMapper() {
        return JsonMapper.builder().build();
    }

    @Bean
    PrivateStorages privateStorages() {
        return TestStorage.rules();
    }

    @Bean
    StorageRoots storageRoots() throws IOException {
        Path base = Files.createTempDirectory("portal-journal-test");
        return new StorageRoots(base.resolve("artifacts"), base.resolve("outputs"));
    }
}
