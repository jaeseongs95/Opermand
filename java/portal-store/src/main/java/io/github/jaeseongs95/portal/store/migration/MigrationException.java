package io.github.jaeseongs95.portal.store.migration;

import java.io.Serial;
import java.util.Objects;

/**
 * The schema cannot be migrated: a script that was applied has changed or is missing, the numbers are not consecutive, a script failed (or the runner that held its number
 * failed), the database's encoding is not UTF-8, or the driver reports no long text type. These are start-up failures, not refusals of a request, so this is not a
 * {@code PortalException} and adds no code to the public contract (the same reasoning as {@code DatabaseProductException} and {@code ArtifactStoreCorruptedException}). The
 * message holds the {@link Reason} and fixed names (script files of the jar, numbers); the caught exception is the {@code cause} only.
 */
public final class MigrationException extends IllegalStateException {

    /** Why the migration stopped. The meanings are those of the common format (section 4); the names are those of the AGS runner's codes. */
    public enum Reason {
        /** The digest of an applied script differs from the history: the database was made by other text than the jar holds. */
        MIGRATION_CHANGED,
        /** The history has a number whose script is not in the jar. */
        MIGRATION_MISSING,
        /** The numbers are not consecutive from 1, or a script lies below the highest number applied. */
        MIGRATION_OUT_OF_ORDER,
        /** A script failed, the runner that held its number failed, or the database could not be reached or read for the migration. */
        MIGRATION_FAILED,
        /** The database does not take or read UTF-8 text. */
        DB_ENCODING_MISMATCH
    }

    @Serial
    private static final long serialVersionUID = 1L;

    private final Reason reason;

    MigrationException(Reason reason, String message, Throwable cause) {
        super(reason + ": " + Objects.requireNonNull(message, "message"), cause);
        this.reason = Objects.requireNonNull(reason, "reason");
    }

    MigrationException(Reason reason, String message) {
        this(reason, message, null);
    }

    public Reason reason() {
        return reason;
    }
}
