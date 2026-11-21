package io.github.jaeseongs95.portal.migrate.cli;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;

import org.springframework.boot.Banner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.json.JsonParserFactory;

import io.github.jaeseongs95.portal.domain.id.Sha256Digester;
import io.github.jaeseongs95.portal.migrate.plan.LegacyTranslator;
import io.github.jaeseongs95.portal.migrate.report.MigrationReport;
import io.github.jaeseongs95.portal.migrate.report.ReportPrinter;
import io.github.jaeseongs95.portal.migrate.run.MigrationInput;
import io.github.jaeseongs95.portal.migrate.run.PlannedRow;
import io.github.jaeseongs95.portal.migrate.run.SourceScan;
import io.github.jaeseongs95.portal.migrate.run.TargetCheck;
import io.github.jaeseongs95.portal.migrate.source.LegacyDirectory;
import io.github.jaeseongs95.portal.migrate.source.LegacySource;
import io.github.jaeseongs95.portal.migrate.source.SourceUnusableException;
import io.github.jaeseongs95.portal.migrate.target.EmptyHistory;
import io.github.jaeseongs95.portal.migrate.target.ReadOnlyTargetConfiguration;
import io.github.jaeseongs95.portal.migrate.target.WritableTargetConfiguration;

/**
 * The migration command: reads the 2.x data and checks it without any 3.0 store, and only then, if there is something to check or write, starts the store's context and lets
 * its runner do the rest. Everything the user needs is printed to the stream the command is given; the return value is the exit code (0 success, 1 error, 2 conflict).
 *
 * <p>The 2.x files are opened read only and are never changed; a real run writes the 3.0 store in one transaction, a dry run writes nothing at all (and does not even create a
 * store that does not exist yet).
 */
public final class MigrateCommand {

    private final PrintStream out;

    public MigrateCommand(PrintStream out) {
        this.out = out;
    }

    public int run(String[] args) {
        MigrateOptions options;
        try {
            options = new OptionsParser().parse(args);
        } catch (UsageException e) {
            out.println("error: " + e.getMessage());
            out.println(OptionsParser.USAGE);
            out.flush();
            return MigrationReport.Outcome.ERROR.exitCode();
        }
        MigrationReport report = new MigrationReport(options.dryRun() ? MigrationReport.Mode.DRY_RUN : MigrationReport.Mode.IMPORT);
        try {
            migrate(options, report, args);
        } catch (SourceUnusableException e) {
            report.error(e.getMessage());
        } catch (RuntimeException | IOException e) {
            report.error(e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        new ReportPrinter(out).print(report);
        return report.outcome().exitCode();
    }

    private void migrate(MigrateOptions options, MigrationReport report, String[] args) throws IOException {
        LegacyDirectory directory = new LegacyDirectory(options.source());
        report.inventory(directory.inventory());
        List<LegacySource> sources = directory.sources();
        if (sources.isEmpty()) {
            report.error("the 2.x data folder has no operation journal (operations.sqlite, command-automation.sqlite or agent-cli.sqlite): there is nothing to move");
            return;
        }
        Map<String, String> fingerprints = directory.fingerprints();
        fingerprints.forEach(report::fingerprint);
        LegacyTranslator translator = new LegacyTranslator(JsonParserFactory.getJsonParser(), new Sha256Digester());
        List<PlannedRow> planned = new SourceScan(translator, report).scan(sources);
        if (!directory.fingerprints().equals(fingerprints)) {
            report.error("a 2.x journal changed while it was read (is the 2.x service running?); nothing was written");
            return;
        }
        if (report.hasIssues()) {
            return;
        }
        MigrationInput input = new MigrationInput(directory, sources, translator, planned, fingerprints, options.unknownBudget(), report);
        if (options.dryRun() && !options.storeExists()) {
            new TargetCheck(new EmptyHistory(), report).check(input);
            return;
        }
        if (!options.dryRun() && !options.customDatabase()) {
            Files.createDirectories(options.target().toAbsolutePath().resolve("store"));
        }
        runInStoreContext(options, input, args);
    }

    private void runInStoreContext(MigrateOptions options, MigrationInput input, String[] args) {
        SpringApplication application = new SpringApplication(options.dryRun() ? ReadOnlyTargetConfiguration.class : WritableTargetConfiguration.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        application.setBannerMode(Banner.Mode.OFF);
        application.setLogStartupInfo(false);
        application.setDefaultProperties(options.springDefaults());
        application.addInitializers(new RunnerRegistration(input, options.dryRun()));
        // The runner of the context does the work while Boot starts it; closing the context releases the store (and its file lock) before the report is printed.
        application.run(args).close();
    }
}
