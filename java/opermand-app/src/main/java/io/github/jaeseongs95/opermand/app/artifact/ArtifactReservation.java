package io.github.jaeseongs95.opermand.app.artifact;

import java.util.Objects;

import io.github.jaeseongs95.opermand.domain.id.ArtifactId;

/**
 * The answer of {@link ArtifactRegistry#reserve}. 2.x gave every refusal the one text "Staging budget exhausted" and so the code {@code ARTIFACT_QUOTA}
 * (defect D-0012: a small file was refused as over quota while the disk had room). Each refusal is its own variant now and carries the numbers behind it, and the
 * checks run in one fixed order: the size of the file against the per-file limit, then the budget, then the disk. The verdict of an earlier check never depends on a
 * later one: whether the budget refuses a file is decided by the budget alone, and a file that fits the budget is refused for the disk only when the disk's usable
 * space minus the file is below the margin. A disk that cannot be read is not a refusal at all: the registry throws {@code ARTIFACT_STORAGE_IO_ERROR}. Payloads in
 * quarantine are not looked at: they never refuse a file (see {@link ArtifactBudget}).
 */
public sealed interface ArtifactReservation permits ArtifactReservation.Reserved, ArtifactReservation.BudgetExceeded, ArtifactReservation.DiskSpaceInsufficient,
    ArtifactReservation.FileTooLarge {

    /** The row exists in state {@code STAGING}; the bytes may now be appended. */
    record Reserved(ArtifactId id) implements ArtifactReservation {

        public Reserved {
            Objects.requireNonNull(id, "id");
        }
    }

    /**
     * The application's staging budget cannot take the file.
     *
     * @param requestedBytes the declared size of the file
     * @param liveBytes what the rows of the artifacts charge (reservations in flight, staged and consumed files); the bytes in quarantine are not part of it (defect D-0012)
     * @param budgetBytes the budget
     */
    record BudgetExceeded(long requestedBytes, long liveBytes, long budgetBytes) implements ArtifactReservation {

        public BudgetExceeded {
            if (liveBytes < 0) {
                throw new IllegalArgumentException("The live bytes must not be negative");
            }
        }
    }

    /**
     * The disk has less usable space than the file needs plus the margin that must stay free.
     *
     * @param requestedBytes the declared size of the file
     * @param usableBytes the usable space the disk reported
     * @param marginBytes the space that must remain after the file's bytes are set aside
     */
    record DiskSpaceInsufficient(long requestedBytes, long usableBytes, long marginBytes) implements ArtifactReservation {
    }

    /**
     * The file is larger than the per-file limit.
     *
     * @param requestedBytes the declared size of the file
     * @param limitBytes the limit
     */
    record FileTooLarge(long requestedBytes, long limitBytes) implements ArtifactReservation {
    }
}
