package io.github.jaeseongs95.portal.migrate.cli;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The command line of the migration.
 *
 * @param source the folder the 2.x service kept its state in
 * @param target the 3.0 data folder ({@code BRIDGE_DATA_DIR} of the server: the embedded H2 store is the file {@code portal} in it); null when the data source is given by
 *     {@code --spring.datasource.*}
 * @param dryRun true to write nothing and only report
 * @param unknownBudget the unknown budget the 3.0 server will run with; the migration stops when the unknown operations would use it up
 * @param customDatabase true when the 3.0 store is on a data source the command line names ({@code --spring.datasource.url}) instead of the embedded H2 store in the target
 * @param envFile the 2.x environment file whose settings are converted ({@code BRIDGE_*}), or null when settings are not part of this run
 * @param envOut the file the converted settings are written to (never the 2.x file), or null exactly when {@code envFile} is
 */
public record MigrateOptions(Path source, Path target, boolean dryRun, int unknownBudget, boolean customDatabase, Path envFile, Path envOut) {

    /** The name of the embedded store in the data folder: the server opens {@code jdbc:h2:file:<BRIDGE_DATA_DIR>/portal}, which H2 keeps in {@code portal.mv.db}. */
    private static final String STORE_NAME = "portal";

    /** True when the embedded store already exists; a data source named on the command line is taken to exist. */
    boolean storeExists() {
        return customDatabase || Files.exists(target.resolve(STORE_NAME + ".mv.db"));
    }

    /**
     * The settings the command gives the Spring context under whatever the command line says: no web server, no banner, no script-based database initialization, and, unless a
     * data source is named, the embedded H2 store in the target folder. A dry run opens an existing embedded store read only and refuses to create one.
     */
    Map<String, Object> springDefaults() {
        Map<String, Object> defaults = new LinkedHashMap<>();
        defaults.put("spring.main.web-application-type", "none");
        defaults.put("spring.main.banner-mode", "off");
        defaults.put("spring.sql.init.mode", "never");
        defaults.put("logging.level.root", "WARN");
        if (!customDatabase) {
            String location = target.resolve(STORE_NAME).toString().replace('\\', '/');
            defaults.put("spring.datasource.url", "jdbc:h2:file:" + location + (dryRun ? ";ACCESS_MODE_DATA=r;IFEXISTS=TRUE" : ""));
            defaults.put("spring.datasource.username", "sa");
            defaults.put("spring.datasource.password", "");
        }
        return defaults;
    }
}
