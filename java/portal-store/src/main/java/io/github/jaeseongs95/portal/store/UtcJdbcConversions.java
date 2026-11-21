package io.github.jaeseongs95.portal.store;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.convert.converter.Converter;
import org.springframework.data.convert.ReadingConverter;
import org.springframework.data.convert.WritingConverter;
import org.springframework.data.jdbc.core.convert.JdbcCustomConversions;
import org.springframework.data.jdbc.core.dialect.JdbcDialect;

/**
 * Registers {@link UtcTimestamps} as the conversion of every {@link Instant} that a Spring Data JDBC repository writes (a property, a derived query's parameter or a
 * {@code @Query} parameter) and reads (defect D-0161). The conversions of the database dialect stay: {@link JdbcCustomConversions#of} adds ours to them, and the bean is
 * {@link Primary} so that Boot's own conversions bean, which has none of ours, is not the one the mapping context takes.
 */
@Configuration(proxyBeanMethods = false)
class UtcJdbcConversions {

    @Bean
    @Primary
    JdbcCustomConversions utcJdbcCustomConversions(JdbcDialect dialect, UtcTimestamps timestamps) {
        return JdbcCustomConversions.of(dialect, List.of(new InstantToColumn(timestamps), new ColumnToInstant(timestamps), new TimestampToInstant(timestamps)));
    }

    @WritingConverter
    private static final class InstantToColumn implements Converter<Instant, LocalDateTime> {

        private final UtcTimestamps timestamps;

        InstantToColumn(UtcTimestamps timestamps) {
            this.timestamps = timestamps;
        }

        @Override
        public LocalDateTime convert(Instant source) {
            return timestamps.write(source);
        }
    }

    /** For a driver that answers a {@code TIMESTAMP} column as a {@link LocalDateTime}. */
    @ReadingConverter
    private static final class ColumnToInstant implements Converter<LocalDateTime, Instant> {

        private final UtcTimestamps timestamps;

        ColumnToInstant(UtcTimestamps timestamps) {
            this.timestamps = timestamps;
        }

        @Override
        public Instant convert(LocalDateTime source) {
            return timestamps.read(source);
        }
    }

    /** For a driver that answers a {@code TIMESTAMP} column as a {@link Timestamp}, which is what H2 does. */
    @ReadingConverter
    private static final class TimestampToInstant implements Converter<Timestamp, Instant> {

        private final UtcTimestamps timestamps;

        TimestampToInstant(UtcTimestamps timestamps) {
            this.timestamps = timestamps;
        }

        @Override
        public Instant convert(Timestamp source) {
            return timestamps.read(source);
        }
    }
}
