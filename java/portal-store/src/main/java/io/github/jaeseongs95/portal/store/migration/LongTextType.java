package io.github.jaeseongs95.portal.store.migration;

import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import io.github.jaeseongs95.portal.store.migration.MigrationException.Reason;

/**
 * One character type of a database as its driver reports it in {@link DatabaseMetaData#getTypeInfo()}: its name, its JDBC type, its largest size and whether a column of it is
 * declared with a length. The migration scripts write the database's long text type as {@code ${long_text}}. SQL has no one name for it (CLOB, TEXT, LONGTEXT, VARCHAR(max) ...),
 * so the runner asks the driver which type to use. No list of DBMS names is kept here: the choice is made from what the driver says.
 *
 * <p>The rule ({@link #choose(List)}): a character large object type ({@code CLOB}, {@code LONGVARCHAR}) that needs no length, the one of the largest size; where there is none,
 * a {@code VARCHAR} type that needs no length (PostgreSQL's {@code text}), else the {@code VARCHAR} type of the largest size written with that size.
 */
record LongTextType(String name, int sqlType, long precision, boolean needsLength) {

    /** How a column of this type is declared. */
    String declaration() {
        return needsLength ? name + "(" + precision + ")" : name;
    }

    /** The long text type of the database behind the metadata. */
    static LongTextType choose(DatabaseMetaData metaData) {
        try {
            return choose(candidates(metaData));
        } catch (SQLException failure) {
            throw new MigrationException(Reason.MIGRATION_FAILED, "The database's long text type could not be read from its driver.", failure);
        }
    }

    static LongTextType choose(List<LongTextType> candidates) {
        return candidates.stream().min(RANK)
                .orElseThrow(() -> new MigrationException(Reason.MIGRATION_FAILED, "The database reports no character type that holds a long text."));
    }

    /** Best first: large object types before VARCHAR, a type that needs no length before one that does, then the larger size, then the name (so the choice is the same on every run). */
    private static final Comparator<LongTextType> RANK = Comparator
            .comparingInt((LongTextType type) -> type.sqlType() == Types.VARCHAR ? 1 : 0)
            .thenComparing(LongTextType::needsLength)
            .thenComparing(Comparator.comparingLong(LongTextType::precision).reversed())
            .thenComparing(LongTextType::name);

    private static List<LongTextType> candidates(DatabaseMetaData metaData) throws SQLException {
        List<LongTextType> found = new ArrayList<>();
        try (ResultSet rows = metaData.getTypeInfo()) {
            while (rows.next()) {
                int sqlType = rows.getInt("DATA_TYPE");
                if (sqlType != Types.CLOB && sqlType != Types.LONGVARCHAR && sqlType != Types.VARCHAR) {
                    continue;
                }
                String params = rows.getString("CREATE_PARAMS");
                found.add(new LongTextType(rows.getString("TYPE_NAME"), sqlType, rows.getLong("PRECISION"), params != null && !params.isBlank()));
            }
        }
        // A large object type that asks for a length is no long text type; a VARCHAR that does is, with its largest size.
        return found.stream().filter(type -> type.sqlType() == Types.VARCHAR || !type.needsLength()).toList();
    }
}
