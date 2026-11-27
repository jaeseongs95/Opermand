package io.github.jaeseongs95.opermand.store.artifact;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

import io.github.jaeseongs95.opermand.app.artifact.ArtifactRecord;
import io.github.jaeseongs95.opermand.app.artifact.ArtifactRegistry;
import io.github.jaeseongs95.opermand.app.artifact.ArtifactReservation;
import io.github.jaeseongs95.opermand.app.artifact.ConsumeReason;
import io.github.jaeseongs95.opermand.app.artifact.ReleaseReason;
import io.github.jaeseongs95.opermand.domain.id.ArtifactId;
import io.github.jaeseongs95.opermand.domain.id.TargetId;
import io.github.jaeseongs95.opermand.domain.path.PosixRemotePath;
import io.github.jaeseongs95.opermand.store.StoreTest;

/**
 * The registry with a small budget (128 bytes, 64 per file, no disk margin), where the three refusals of {@code reserve} can be provoked with a few rows. The
 * disk is never short here, so a refusal is always the budget's or the per-file limit's (defect D-0012 asked for exactly that separation).
 */
@StoreTest
@Import({JdbcArtifactRegistry.class, ArtifactMapper.class, FileArtifactPayloadStore.class, ArtifactTestStorage.class})
class JdbcArtifactRegistryBudgetTest {

    @TestConfiguration(proxyBeanMethods = false)
    static class Limits {
        @Bean
        ArtifactLimits artifactLimits() {
            return new ArtifactLimits(128L, 64L, ArtifactLimits.CHUNK_BYTES, ArtifactLimits.UPLOAD_TTL, 0L);
        }
    }

    private final ArtifactFixtures fixtures = new ArtifactFixtures();
    private final ArtifactRegistry registry;

    JdbcArtifactRegistryBudgetTest(ArtifactRegistry registry) {
        this.registry = registry;
    }

    private ArtifactReservation reserve(io.github.jaeseongs95.opermand.app.artifact.ArtifactSpec spec) {
        return registry.reserve(spec, fixtures.at(), ArtifactFixtures.UNLIMITED_DISK);
    }

    @Test
    @DisplayName("RC-A-160 / S3-AR-02 (store half): when the budget is used up even a 1 byte file is refused as BudgetExceeded, with the numbers (the live rows), and no row is created")
    void aSmallFileIsRefusedByTheBudgetAndNothingIsStored() {
        for (int i = 0; i < 4; i++) {
            fixtures.reservedId(reserve(fixtures.upload(32)));
        }
        List<ArtifactRecord> before = registry.holding();

        ArtifactReservation refused = reserve(fixtures.upload(1));

        assertThat(refused).isEqualTo(new ArtifactReservation.BudgetExceeded(1, 128, 128));
        assertThat(registry.holding()).containsExactlyInAnyOrderElementsOf(before);
        assertThat(registry.budget().chargedBytes()).isEqualTo(128);
    }

    @Test
    @DisplayName("S-D0012-11 (store half): a row in DELETING charges nothing, so a file that cannot be deleted does not refuse new transfers; the same reservation is refused while the row is live")
    void aRowBeingDeletedChargesNothing() {
        ArtifactId dying = fixtures.reservedId(reserve(fixtures.upload(64)));
        fixtures.reservedId(reserve(fixtures.upload(32)));
        assertThat(reserve(fixtures.upload(64))).isEqualTo(new ArtifactReservation.BudgetExceeded(64, 96, 128));

        registry.beginDelete(dying);

        assertThat(registry.budget().chargedBytes()).isEqualTo(32);
        assertThat(reserve(fixtures.upload(64))).isInstanceOf(ArtifactReservation.Reserved.class);
        assertThat(registry.deleting()).extracting(ArtifactRecord::id).containsExactly(dying);
    }

    @Test
    @DisplayName("S-D0012-18 (store half): a row in CONSUMING charges nothing (its bytes are being moved out or removed), the tombstone after it charges one byte")
    void aRowInUseChargesNothing() {
        ArtifactId file = fixtures.readyArtifact(registry, fixtures.stagedFile(64), fixtures.bytes(64, 1));
        assertThat(registry.budget().chargedBytes()).isEqualTo(64);

        registry.beginConsume(file, ConsumeReason.MOVED);

        assertThat(registry.budget().chargedBytes()).isZero();
        assertThat(registry.consuming()).extracting(ArtifactRecord::id).containsExactly(file);
        registry.completeConsume(file, ConsumeReason.MOVED);
        assertThat(registry.budget().chargedBytes()).isEqualTo(1);
    }

    @Test
    @DisplayName("S-D0012-14 (store half): the announced size of a download is judged by the budget without the download's own charge: a 1 KB-like small file fits where the largest file would not, a size that does not fit is BudgetExceeded and changes nothing")
    void anAnnouncedSizeIsJudgedByTheBudget() {
        ArtifactId download = fixtures.reservedId(reserve(fixtures.download(0)));
        fixtures.reservedId(reserve(fixtures.upload(60)));
        fixtures.reservedId(reserve(fixtures.upload(40)));

        assertThat(registry.sizeDownload(download, 28, ArtifactFixtures.UNLIMITED_DISK)).as("100 + 28 = 128 fits, although a whole file (64) would not").isEqualTo(new ArtifactReservation.Reserved(download));
        assertThat(registry.budget().chargedBytes()).isEqualTo(128);
        assertThat(registry.sizeDownload(download, 29, ArtifactFixtures.UNLIMITED_DISK)).as("the own charge of 28 is not counted twice; 29 does not fit").isEqualTo(new ArtifactReservation.BudgetExceeded(29, 100, 128));
        assertThat(registry.find(download).orElseThrow().spec().sizeBytes()).as("a refusal changes nothing").isEqualTo(28L);
        assertThat(registry.budget().chargedBytes()).isEqualTo(128);
    }

    @Test
    @DisplayName("RC-B-063 (budget half): two 64 byte files fill a 128 byte budget across targets, the third is refused, a release lets it in, releasing all returns the budget to zero")
    void theBudgetIsGlobalAcrossTargets() {
        ArtifactId alpha = fixtures.reservedId(reserve(fixtures.upload(64, new PosixRemotePath("/tmp/a.bin"), new TargetId("alpha"))));
        ArtifactId beta = fixtures.reservedId(reserve(fixtures.upload(64, new PosixRemotePath("/tmp/b.bin"), new TargetId("beta"))));

        assertThat(reserve(fixtures.upload(64, new PosixRemotePath("/tmp/c.bin"), new TargetId("gamma"))))
            .isEqualTo(new ArtifactReservation.BudgetExceeded(64, 128, 128));
        assertThat(registry.holding()).hasSize(2);

        registry.release(alpha, ReleaseReason.REFUSED);
        ArtifactId gamma = fixtures.reservedId(reserve(fixtures.upload(64, new PosixRemotePath("/tmp/c.bin"), new TargetId("gamma"))));
        registry.release(beta, ReleaseReason.REFUSED);
        registry.release(gamma, ReleaseReason.REFUSED);
        assertThat(registry.budget().chargedBytes()).isZero();
        assertThat(registry.budget().remainingBytes()).isEqualTo(128);
    }

    @Test
    @DisplayName("RC-B-068 (store half): a refused reservation changes nothing, a released one gives its bytes back at once, and the remaining budget is what the next request sees")
    void aRefusedReservationLeavesTheBudgetAlone() {
        ArtifactId first = fixtures.reservedId(reserve(fixtures.upload(64)));
        ArtifactId second = fixtures.reservedId(reserve(fixtures.upload(64)));
        long charged = registry.budget().chargedBytes();

        assertThat(reserve(fixtures.upload(1))).isInstanceOf(ArtifactReservation.BudgetExceeded.class);
        assertThat(registry.budget().chargedBytes()).isEqualTo(charged);

        registry.release(first, ReleaseReason.REFUSED);
        assertThat(registry.budget().remainingBytes()).isEqualTo(64);
        assertThat(fixtures.reservedId(reserve(fixtures.upload(64)))).isNotEqualTo(second);
    }

    @Test
    @DisplayName("RC-A-172: items are not counted, bytes are: 101 empty files fit, the budget stops at 128 empty files and the 129th is refused")
    void emptyFilesChargeOneByteEach() {
        List<ArtifactId> ids = new ArrayList<>();
        for (int i = 0; i < 128; i++) {
            ids.add(fixtures.reservedId(reserve(fixtures.upload(0))));
        }

        assertThat(ids).hasSize(128);
        assertThat(registry.budget().chargedBytes()).isEqualTo(128);
        assertThat(reserve(fixtures.upload(0))).isEqualTo(new ArtifactReservation.BudgetExceeded(0, 128, 128));
    }

    @Test
    @DisplayName("RC-A-180: a file over the per-file limit is refused as FileTooLarge with the limit, even when the budget has room")
    void aFileOverTheLimitIsRefused() {
        assertThat(reserve(fixtures.upload(65))).isEqualTo(new ArtifactReservation.FileTooLarge(65, 64));
        assertThat(registry.holding()).isEmpty();
        assertThat(fixtures.reservedId(reserve(fixtures.upload(64)))).isNotNull();
    }

    @Test
    @DisplayName("RC-A-181: with the budget full a further begin is refused; once the expired partial upload is released the capacity is free again")
    void releasingAnExpiredUploadFreesTheCapacity() {
        ArtifactId filler = fixtures.reservedId(reserve(fixtures.upload(64)));
        ArtifactId partial = fixtures.reservedId(reserve(fixtures.upload(64)));
        assertThat(reserve(fixtures.upload(4))).isInstanceOf(ArtifactReservation.BudgetExceeded.class);

        List<ArtifactRecord> expired = registry.expired(ArtifactFixtures.T0.plus(Duration.ofMinutes(10)));
        assertThat(expired).extracting(ArtifactRecord::id).containsExactlyInAnyOrder(filler, partial);
        expired.forEach(record -> registry.release(record.id(), ReleaseReason.EXPIRED));

        assertThat(registry.budget().chargedBytes()).isZero();
        assertThat(reserve(fixtures.upload(4))).isInstanceOf(ArtifactReservation.Reserved.class);
    }
}
