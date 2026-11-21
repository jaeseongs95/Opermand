package io.github.jaeseongs95.portal.store;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

import org.springframework.stereotype.Component;

/**
 * The one rule by which the store keeps a moment in a {@code TIMESTAMP} column: the column holds the UTC date and time of the {@link Instant}, whatever the time zone
 * of the JVM is (defect D-0161). A {@code TIMESTAMP} has no zone; the driver turns an {@code Instant} or a {@code java.sql.Timestamp} into its date and time in the
 * default zone of the JVM, so two moments an hour apart were stored as the same value in the hour that daylight saving time repeats, and every stored moment moved by the
 * difference of the offsets when the host (or the JVM) changed its zone. The adapters write the {@link LocalDateTime} this class makes and read it back with the same
 * rule: the Spring Data JDBC rows through {@link UtcJdbcConversions}, the statements written by hand through this class.
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

    /**
     * The moment of a column value a driver answers as a {@link Timestamp} (Spring Data JDBC reads a {@code TIMESTAMP} column with {@code getObject}, which H2 answers with a
     * {@code Timestamp}). The date and time fields of the {@code Timestamp} are the column's, so the rule is the one of {@link #read(LocalDateTime)}. A {@code Timestamp}
     * made by the driver from a date and time that the JVM's zone skips (the hour that daylight saving time leaves out) has the fields an hour later; only a statement that
     * reads the column as a {@link LocalDateTime} avoids that, which the statements written by hand do.
     */
    public Instant read(Timestamp column) {
        return read(column.toLocalDateTime());
    }
}
