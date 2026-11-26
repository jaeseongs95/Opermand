package io.github.jaeseongs95.portal.store.output;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.stream.Stream;

import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

import io.github.jaeseongs95.portal.app.storage.PrivateStorages;
import io.github.jaeseongs95.portal.domain.id.Digester;
import io.github.jaeseongs95.portal.domain.id.OperationIdGenerator;
import io.github.jaeseongs95.portal.domain.id.Sha256Digester;
import io.github.jaeseongs95.portal.store.StorageRoots;
import io.github.jaeseongs95.portal.store.TestStorage;
import io.github.jaeseongs95.portal.store.attachment.AttachmentKeyring;
import io.github.jaeseongs95.portal.store.attachment.ChunkedAeadCodec;

/**
 * The beans that portal-app ({@link Digester}, id generator) and portal-server ({@link StorageRoots}) provide in the product, supplied for a test that wires the output
 * adapters by Spring.
 */
@TestConfiguration(proxyBeanMethods = false)
class OutputWiringBeans {

    @Bean
    StorageRoots storageRoots() throws IOException {
        Path folder = Files.createTempDirectory("portal-output-wiring");
        return new StorageRoots(folder, folder);
    }

    @Bean
    Clock clock() {
        return Clock.fixed(Instant.parse("2026-11-12T00:00:00Z"), ZoneOffset.UTC);
    }

    @Bean
    SecureRandom secureRandom() {
        return new SecureRandom();
    }

    @Bean
    PrivateStorages privateStorages() {
        return TestStorage.rules();
    }

    @Bean
    AttachmentKeyring attachmentKeyring(StorageRoots roots, PrivateStorages storages, SecureRandom random, Clock clock) {
        return new AttachmentKeyring(roots.keyring().resolve(AttachmentKeyring.DEFAULT_FILE_NAME), storages, random, clock);
    }

    @Bean
    ChunkedAeadCodec chunkedAeadCodec(AttachmentKeyring keyring, SecureRandom random) {
        return new ChunkedAeadCodec(keyring, random);
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
    DisposableBean outputFolderCleanup(StorageRoots roots) {
        return () -> {
            try (Stream<Path> tree = Files.walk(roots.outputs())) {
                tree.sorted(Comparator.reverseOrder()).forEach(path -> {
                    try {
                        Files.delete(path);
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                });
            }
        };
    }
}
