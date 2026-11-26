package io.github.jaeseongs95.portal.store.artifact;

import java.io.IOException;
import java.security.SecureRandom;
import java.time.Clock;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

import io.github.jaeseongs95.portal.app.storage.PrivateStorages;
import io.github.jaeseongs95.portal.domain.id.ArtifactIdGenerator;
import io.github.jaeseongs95.portal.domain.id.Digester;
import io.github.jaeseongs95.portal.domain.id.Sha256Digester;
import io.github.jaeseongs95.portal.store.StorageRoots;
import io.github.jaeseongs95.portal.store.TestStorage;
import io.github.jaeseongs95.portal.store.attachment.AttachmentKeyring;
import io.github.jaeseongs95.portal.store.attachment.ChunkedAeadCodec;

/**
 * The beans an artifact test slice needs besides the adapters under test: a private temporary storage root (removed with the context), the id generator, digest,
 * keyring, and chunked AEAD codec.
 */
@TestConfiguration(proxyBeanMethods = false)
class ArtifactTestStorage {

    @Bean
    TemporaryStorage temporaryStorage() throws IOException {
        return new TemporaryStorage();
    }

    @Bean
    StorageRoots storageRoots(TemporaryStorage storage) {
        return new StorageRoots(storage.folder().resolve("artifacts"), storage.folder().resolve("outputs"));
    }

    @Bean
    PrivateStorages privateStorages() {
        return TestStorage.rules();
    }

    @Bean
    SecureRandom secureRandom() {
        return new SecureRandom();
    }

    @Bean
    AttachmentKeyring attachmentKeyring(StorageRoots roots, PrivateStorages storages, SecureRandom random) {
        return new AttachmentKeyring(roots.keyring().resolve(AttachmentKeyring.DEFAULT_FILE_NAME), storages, random, Clock.systemUTC());
    }

    @Bean
    ChunkedAeadCodec chunkedAeadCodec(AttachmentKeyring keyring, SecureRandom random) {
        return new ChunkedAeadCodec(keyring, random);
    }

    @Bean
    ArtifactIdGenerator artifactIdGenerator(SecureRandom random) {
        return new ArtifactIdGenerator(random);
    }

    @Bean
    Digester digester() {
        return new Sha256Digester();
    }
}
