package io.github.jaeseongs95.portal.store;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

import org.springframework.stereotype.Component;

/**
 * The one rule by which the store keeps a moment in a {@code TIMESTAMP} column: the column holds the UTC date and time of the {@link Instant}, whatever the time zone
 * of the JVM is (defect D-0161). A {@code TIMESTAMP} has no zone; the driver turns an {@code Instant} or a {@code java.sql.Timestamp} into its date and time in the
 * default zone of the JVM, which shifts spring-forward DST gap hours and collapses fall-back repeated hours.
 *
 * <p>The adapters write {@link LocalDateTime} via {@link #write(Instant)} and read {@code TIMESTAMP} columns directly as {@link LocalDateTime} via
 * {@code ResultSet.getObject(col, LocalDateTime.class)} (enforced globally for Spring Data JDBC by {@link UtcJdbcConversions}), never via {@code java.sql.Timestamp}.
 */
@Component
public final class UtcTimestamps {

    /** The date and time the column holds for {@code moment}. */
    public LocalDateTime write(Instant moment) {
        return LocalDateTime.ofInstant(moment, ZoneOffset.UTC);
    }

    /** The moment of a column value that was written by {@link #write}. */
    public Instant read(LocalDateTime column) {
        return column.toInstant(ZoneOffset.UTC);
    }
}
