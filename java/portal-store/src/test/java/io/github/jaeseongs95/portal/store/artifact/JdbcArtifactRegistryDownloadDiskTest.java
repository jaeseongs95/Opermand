package io.github.jaeseongs95.portal.store.artifact;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

import io.github.jaeseongs95.portal.app.artifact.ArtifactRegistry;
import io.github.jaeseongs95.portal.app.artifact.ArtifactReservation;
import io.github.jaeseongs95.portal.domain.id.ArtifactId;
import io.github.jaeseongs95.portal.store.StoreTest;

/**
 * The size a target announced for a download, judged by the disk (S-D0012-15, store half). The margin is the usable space of the temporary folder minus 32 MiB: the empty reservation of a
 * download is accepted, and a file of the largest size (64 MiB) leaves less than the margin, while the budget is the default one with plenty of room, so only the disk can refuse it.
 */
@StoreTest
@Import({JdbcArtifactRegistry.class, ArtifactMapper.class, FileArtifactPayloadStore.class, ArtifactTestStorage.class})
class JdbcArtifactRegistryDownloadDiskTest {

    @TestConfiguration(proxyBeanMethods = false)
    static class Limits {
        @Bean
        ArtifactLimits artifactLimits() throws java.io.IOException {
            long usable = java.nio.file.Files.getFileStore(java.nio.file.Path.of(System.getProperty("java.io.tmpdir"))).getUsableSpace();
            return new ArtifactLimits(ArtifactLimits.DEFAULT_BUDGET_BYTES, ArtifactLimits.MAX_FILE_BYTES, ArtifactLimits.CHUNK_BYTES, ArtifactLimits.UPLOAD_TTL,
                Math.max(0L, usable - 32L * 1024 * 1024));
        }
    }

    private final ArtifactFixtures fixtures = new ArtifactFixtures();
    private final ArtifactRegistry registry;

    JdbcArtifactRegistryDownloadDiskTest(ArtifactRegistry registry) {
        this.registry = registry;
    }

    @Test
    @DisplayName("S-D0012-15 (store half): an announced size that the disk cannot take is DiskSpaceInsufficient with the usable space, not a budget refusal, and the row keeps its empty reservation")
    void anAnnouncedSizeTheDiskCannotTakeIsADiskRefusal() {
        ArtifactId download = fixtures.reservedId(registry.reserve(fixtures.download(0), fixtures.at()));

        ArtifactReservation refused = registry.sizeDownload(download, ArtifactLimits.MAX_FILE_BYTES);

        assertThat(refused).isInstanceOfSatisfying(ArtifactReservation.DiskSpaceInsufficient.class, disk -> {
            assertThat(disk.requestedBytes()).isEqualTo(ArtifactLimits.MAX_FILE_BYTES);
            assertThat(disk.usableBytes()).isPositive();
            assertThat(disk.marginBytes()).isPositive();
        });
        assertThat(registry.find(download).orElseThrow().spec().sizeBytes()).isZero();
        assertThat(registry.budget().chargedBytes()).isEqualTo(1);
    }
}
