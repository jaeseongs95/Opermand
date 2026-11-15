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
 * <p>The rule ({@link #choose(List)}, common format edition 3): a character large object type ({@code CLOB}, {@code LONGVARCHAR}) comes before any {@code VARCHAR}, and its
 * {@code CREATE_PARAMS} does not disqualify it: the driver only says that parameters may be given to the type (JDBC {@code getTypeInfo}: "parameters used in creating the type (may be
 * null)"), not that they are required. Where there is none, a {@code VARCHAR} type that needs no length (PostgreSQL's {@code text}), else the {@code VARCHAR} type of the largest
 * size. The type chosen is declared without a length when its {@code CREATE_PARAMS} is empty, and with the largest {@code PRECISION} the driver reports when it is not (a database
 * that takes the length as optional may give a small default to a type declared without it, for instance 1M characters for a Db2 {@code CLOB}).
 */
record LongTextType(String name, int sqlType, long precision, boolean needsLength) {

    /** How a column of this type is declared: with its largest size when the driver lists parameters for the type, else by name alone. */
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

    /** Best first: large object types before VARCHAR whatever they ask for, then a type that needs no length before one that does, then the larger size, then the name (so the choice is the same on every run). */
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
        return found;
    }
}
