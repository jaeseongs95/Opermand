package io.github.jaeseongs95.opermand.store;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.TimeZone;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.jdbc.core.JdbcAggregateOperations;
import org.springframework.jdbc.core.JdbcTemplate;

import io.github.jaeseongs95.opermand.domain.operation.ApprovalMode;
import io.github.jaeseongs95.opermand.domain.operation.AuditState;
import io.github.jaeseongs95.opermand.domain.operation.OperationKind;
import io.github.jaeseongs95.opermand.domain.operation.OperationState;
import io.github.jaeseongs95.opermand.domain.operation.RecoveryReason;
import io.github.jaeseongs95.opermand.store.journal.OperationRow;

/**
 * Regression tests for SSH_PORTAL-D-0161: UTC timestamps falling on local DST spring-forward nonexistent wall-clock times (e.g. {@code 2026-03-08T02:30:00Z} in
 * {@code America/Los_Angeles} / {@code America/New_York}) and fall-back ambiguous times must survive round-trips through Spring Data JDBC and {@link JdbcTemplate}
 * without shifting by one hour via {@code java.sql.Timestamp}.
 */
@StoreTest
class UtcTimestampsDstTest {

    private static final String HEX = "0".repeat(64);
    private static final Instant SPRING_FORWARD_GAP = Instant.parse("2026-03-08T02:30:00.123456Z");
    private static final Instant FALL_BACK_OVERLAP = Instant.parse("2026-11-01T01:30:00.654321Z");

    private final JdbcAggregateOperations aggregates;
    private final JdbcTemplate jdbc;

    UtcTimestampsDstTest(JdbcAggregateOperations aggregates, JdbcTemplate jdbc) {
        this.aggregates = aggregates;
        this.jdbc = jdbc;
    }

    @Test
    @DisplayName("D-0161: UTC Instant at US DST spring-forward gap (02:30) and fall-back overlap (01:30) round-trips identically in America/Los_Angeles and America/New_York")
    void dstSpringForwardAndFallBackInstantsRoundTripWithoutShift() {
        TimeZone original = TimeZone.getDefault();
        try {
            int seq = 0;
            for (String zoneId : List.of("America/Los_Angeles", "America/New_York", "UTC")) {
                TimeZone.setDefault(TimeZone.getTimeZone(zoneId));
                for (Instant instant : List.of(SPRING_FORWARD_GAP, FALL_BACK_OVERLAP)) {
                    seq++;
                    String id = String.format("%048x", seq);
                    OperationRow inserted = new OperationRow(id, "dst-key-" + seq, "owner", "alpha", HEX, OperationKind.COMMAND, OperationState.PENDING, 1L,
                        ApprovalMode.NOT_DECIDED, "default", null, HEX, null, null, instant, instant, null, null, null, null, null, null, null,
                        RecoveryReason.NONE, AuditState.NONE);
                    aggregates.insert(inserted);

                    OperationRow loaded = aggregates.findById(id, OperationRow.class);
                    assertThat(loaded).as("zone=%s instant=%s", zoneId, instant).isNotNull();
                    assertThat(loaded.createdAt()).as("Spring Data JDBC round-trip in %s", zoneId).isEqualTo(instant);
                    assertThat(loaded.updatedAt()).as("Spring Data JDBC updatedAt in %s", zoneId).isEqualTo(instant);

                    Object rawObject = jdbc.queryForObject("SELECT created_at FROM operation WHERE id = ?", Object.class, id);
                    assertThat(rawObject).as("untyped ResultSet.getObject returns LocalDateTime, never java.sql.Timestamp in %s", zoneId)
                        .isEqualTo(LocalDateTime.ofInstant(instant, ZoneOffset.UTC));
                }
            }
        } finally {
            TimeZone.setDefault(original);
        }
    }
}
