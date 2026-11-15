package io.github.jaeseongs95.portal.store.migration;

import static io.github.jaeseongs95.portal.store.migration.MigrationTestSupport.CLOCK;
import static io.github.jaeseongs95.portal.store.migration.MigrationTestSupport.freshDatabase;
import static io.github.jaeseongs95.portal.store.migration.MigrationTestSupport.schemaExists;
import static io.github.jaeseongs95.portal.store.migration.MigrationTestSupport.script;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.SQLException;
import java.util.List;

import javax.sql.DataSource;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import io.github.jaeseongs95.portal.store.migration.MigrationException.Reason;
import io.github.jaeseongs95.portal.store.migration.MigrationRunner.EncodingProbe;
import io.github.jaeseongs95.portal.store.migration.MigrationRunner.Phase;

/**
 * S-D0109-30 and S-D0109-31: the judgment of the encoding probe and the path where the probe's query fails. The embedded H2 database is always UTF-8, so a real database that
 * counts bytes or loses the text in a code page cannot be made here and other DBMS are not tested (development rule 7.3): the judgment is tested on the values such a database
 * would answer, and the failing query by an error injected at the driver call ({@link InterceptingDataSource}; every other call is the real H2's).
 */
class MigrationEncodingProbeTest {

    @Test
    @DisplayName("S-D0109-30: two characters and the text itself are accepted; a count of bytes, a lost value ('??') and no value are refused with DB_ENCODING_MISMATCH")
    void bothTheCountAndTheValueMustBeTheTextsOwn() {
        assertThatCode(() -> MigrationRunner.judgeEncoding(new EncodingProbe(2, "한글"))).doesNotThrowAnyException();

        assertThatThrownBy(() -> MigrationRunner.judgeEncoding(new EncodingProbe(6, "한글"))).isInstanceOfSatisfying(MigrationException.class, failure -> {
            assertThat(failure.reason()).isEqualTo(Reason.DB_ENCODING_MISMATCH);
            assertThat(failure.getMessage()).contains("count as 6");
        });
        assertThatThrownBy(() -> MigrationRunner.judgeEncoding(new EncodingProbe(2, "??"))).isInstanceOfSatisfying(MigrationException.class, failure -> {
            assertThat(failure.reason()).isEqualTo(Reason.DB_ENCODING_MISMATCH);
            assertThat(failure.getMessage()).contains("'??'");
        });
        assertThatThrownBy(() -> MigrationRunner.judgeEncoding(new EncodingProbe(2, null))).isInstanceOfSatisfying(MigrationException.class,
            failure -> assertThat(failure.reason()).isEqualTo(Reason.DB_ENCODING_MISMATCH));
    }

    @Test
    @DisplayName("S-D0109-31: a probe query that fails stops the run with DB_ENCODING_MISMATCH and the injected cause, before the schema or the history table is made")
    void aFailingProbeStopsBeforeAnythingIsMade() {
        DataSource database = freshDatabase();
        DataSource failingProbe = new InterceptingDataSource(database, (method, arguments) -> {
            if (method.equals("prepareStatement") && arguments[0] instanceof String sql && sql.startsWith("SELECT CHAR_LENGTH(")) {
                throw new SQLException("the database cannot take the text");
            }
        });

        assertThatThrownBy(() -> new MigrationRunner(CLOCK, failingProbe).run(Phase.EXPAND, List.of(script(1, "CREATE TABLE t1 (id INTEGER NOT NULL)")))).isInstanceOfSatisfying(
            MigrationException.class, failure -> {
                assertThat(failure.reason()).isEqualTo(Reason.DB_ENCODING_MISMATCH);
                assertThat(failure.getCause()).isInstanceOf(SQLException.class).hasMessage("the database cannot take the text");
            });
        assertThat(schemaExists(new JdbcTemplate(database), Migrations.SCHEMA)).as("nothing was made").isFalse();
    }
}
