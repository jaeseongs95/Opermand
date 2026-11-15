package io.github.jaeseongs95.portal.store.migration;

import java.time.Clock;

import javax.sql.DataSource;

/**
 * The migrations of the schema, run over a connection of the product's one account. The scripts are in the jar ({@code db/expand}, {@code db/contract}) and
 * {@link MigrationRunner} applies them; expand and contract have their own folder and history table, so neither knows the other's applied scripts. Both live in the
 * {@code portal_meta} schema, which the runner creates.
 *
 * <p>3.0.0 ships expand scripts only. The contract phase exists so that the first contract script of a later release applies after an expand ran, and only when it is asked
 * for ({@link #contract()}), never at start-up.
 */
public final class Migrations {
    static final String SCHEMA = "portal_meta";

    private final Clock clock;
    private final DataSource dataSource;

    /**
     * @param clock the application's one Clock, which stamps the history rows
     * @param dataSource the product's data source ({@code spring.datasource.*}); the runner takes one connection of it for the length of a run
     */
    public Migrations(Clock clock, DataSource dataSource) {
        this.clock = clock;
        this.dataSource = dataSource;
    }

    /** Applies the pending expand scripts; the number applied (0 on a rerun). */
    public int expand() {
        return new MigrationRunner(clock, dataSource).run(MigrationRunner.Phase.EXPAND);
    }

    /** Applies the pending contract scripts; the number applied. Run by an explicit command, never at start-up, once one exists. */
    public int contract() {
        return new MigrationRunner(clock, dataSource).run(MigrationRunner.Phase.CONTRACT);
    }
}
