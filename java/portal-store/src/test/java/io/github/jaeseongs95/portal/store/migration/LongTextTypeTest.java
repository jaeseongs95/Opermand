package io.github.jaeseongs95.portal.store.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Types;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.jaeseongs95.portal.store.migration.MigrationException.Reason;

/**
 * S-D0109-13 (a): the choice of the long text type from what a driver reports in {@code getTypeInfo()}. The lists are what the drivers report in the shape of each family;
 * no DBMS name is in the rule: a character large object type comes first (whatever parameters it lists), then a VARCHAR that needs no length, then the VARCHAR of the largest size;
 * the type is declared with its largest size when it lists parameters, and by its name alone when it does not.
 */
class LongTextTypeTest {

    @Test
    @DisplayName("S-D0109-13: the H2 shape (a CLOB that lists CREATE_PARAMS) gets that type, declared with its largest size, whatever VARCHAR it also has")
    void aLargeObjectTypeThatListsParametersComesFirstWithItsSize() {
        LongTextType chosen = LongTextType.choose(List.of(new LongTextType("CHARACTER VARYING", Types.VARCHAR, 1_000_000_000L, true),
            new LongTextType("CHARACTER LARGE OBJECT", Types.CLOB, 2_147_483_647L, true), new LongTextType("CHAR", Types.CHAR, 1, true)));

        assertThat(chosen.declaration()).isEqualTo("CHARACTER LARGE OBJECT(2147483647)");
    }

    @Test
    @DisplayName("S-D0109-13: the Db2 shape (CLOB with a length, a smaller VARCHAR) gets CLOB with its largest size")
    void theDb2ShapeGetsClobWithItsSize() {
        LongTextType chosen = LongTextType.choose(List.of(new LongTextType("VARCHAR", Types.VARCHAR, 32_672L, true), new LongTextType("CLOB", Types.CLOB, 2_147_483_647L, true)));

        assertThat(chosen.declaration()).isEqualTo("CLOB(2147483647)");
    }

    @Test
    @DisplayName("S-D0109-13: a large object type that lists no parameters is declared by its name alone, and comes before a VARCHAR that needs no length")
    void aLargeObjectTypeWithoutParametersIsDeclaredByName() {
        LongTextType chosen = LongTextType.choose(List.of(new LongTextType("text", Types.VARCHAR, 2_147_483_647L, false), new LongTextType("CLOB", Types.CLOB, 4_294_967_295L, false)));

        assertThat(chosen.declaration()).isEqualTo("CLOB");
    }

    @Test
    @DisplayName("S-D0109-13: a large object type that asks for a length is not left out: it still comes before a VARCHAR that needs none")
    void aLargeObjectTypeIsNotLeftOutForItsParameters() {
        LongTextType chosen = LongTextType.choose(List.of(new LongTextType("text", Types.VARCHAR, 2_147_483_647L, false), new LongTextType("CLOB", Types.CLOB, 1_000_000L, true)));

        assertThat(chosen.declaration()).isEqualTo("CLOB(1000000)");
    }

    @Test
    @DisplayName("S-D0109-13: a database whose long text is a VARCHAR without a length (the PostgreSQL shape) gets that type")
    void aVarcharWithoutLengthIsTheNextChoice() {
        LongTextType chosen = LongTextType.choose(List.of(new LongTextType("varchar", Types.VARCHAR, 10_485_760L, true), new LongTextType("text", Types.VARCHAR, 2_147_483_647L, false)));

        assertThat(chosen.declaration()).isEqualTo("text");
    }

    @Test
    @DisplayName("S-D0109-13: of several types without a length the largest wins (the MySQL and MariaDB shape: LONGTEXT before MEDIUMTEXT before TEXT)")
    void theLargestTypeWins() {
        LongTextType chosen = LongTextType.choose(List.of(new LongTextType("TEXT", Types.LONGVARCHAR, 65_535L, false), new LongTextType("MEDIUMTEXT", Types.LONGVARCHAR, 16_777_215L, false),
            new LongTextType("LONGTEXT", Types.LONGVARCHAR, 4_294_967_295L, false), new LongTextType("VARCHAR", Types.VARCHAR, 65_535L, true)));

        assertThat(chosen.declaration()).isEqualTo("LONGTEXT");
    }

    @Test
    @DisplayName("S-D0109-13: a database with only VARCHAR types that need a length gets the largest one, written with its size")
    void aSizedVarcharIsTheLastResort() {
        LongTextType chosen = LongTextType.choose(List.of(new LongTextType("varchar", Types.VARCHAR, 4_000L, true), new LongTextType("varchar2", Types.VARCHAR, 32_767L, true)));

        assertThat(chosen.declaration()).isEqualTo("varchar2(32767)");
    }

    @Test
    @DisplayName("S-D0109-13: the choice is the same on every run: equal size and kind are ordered by name")
    void theChoiceIsStable() {
        LongTextType a = new LongTextType("A_TEXT", Types.LONGVARCHAR, 100L, false);
        LongTextType b = new LongTextType("B_TEXT", Types.LONGVARCHAR, 100L, false);

        assertThat(LongTextType.choose(List.of(b, a))).isEqualTo(a);
        assertThat(LongTextType.choose(List.of(a, b))).isEqualTo(a);
    }

    @Test
    @DisplayName("S-D0109-13: a database that reports no character type is refused with MIGRATION_FAILED")
    void noCandidateIsRefused() {
        assertThatThrownBy(() -> LongTextType.choose(List.of())).isInstanceOfSatisfying(MigrationException.class,
            failure -> assertThat(failure.reason()).isEqualTo(Reason.MIGRATION_FAILED));
    }
}
