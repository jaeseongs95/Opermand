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
import io.github.jaeseongs95.portal.migrate.settings.SettingsPlan;
import io.github.jaeseongs95.portal.migrate.settings.SettingsPlanner;
import io.github.jaeseongs95.portal.migrate.settings.SettingsWriter;
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
 * store that does not exist yet). When a 2.x environment file is given, its settings are converted in the same run: read and checked before anything is written, and written
 * to a file of their own only after the history has been moved without a problem (a run that is stopped by a conflict writes no settings file either).
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
            ReportPrinter.line(out, "error: " + e.getMessage());
            ReportPrinter.line(out, OptionsParser.USAGE);
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
        SettingsPlan settings = planSettings(options, report);
        List<LegacySource> sources = directory.sources();
        if (sources.isEmpty() && settings == null) {
            report.error("the 2.x data folder has no operation journal (operations.sqlite, command-automation.sqlite or agent-cli.sqlite): there is nothing to move");
            return;
        }
        if (!sources.isEmpty()) {
            moveHistory(options, directory, sources, report, args);
        }
        if (settings != null) {
            finishSettings(options, settings, report);
        }
    }

    private void moveHistory(MigrateOptions options, LegacyDirectory directory, List<LegacySource> sources, MigrationReport report, String[] args) throws IOException {
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
        if (!options.storeExists()) {
            // A store that does not exist yet has nothing the 2.x history could collide with; what can still stop the move (the unknown budget) is known now, so a move that
            // cannot be made leaves no store behind (and a dry run needs none).
            new TargetCheck(new EmptyHistory(), report).check(input);
            if (options.dryRun() || report.hasIssues()) {
                return;
            }
        }
        if (!options.dryRun() && !options.customDatabase()) {
            Files.createDirectories(options.target());
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

    /** The first half of the settings: the 2.x environment file read and converted, nothing written. Null when the run has no environment file. */
    private SettingsPlan planSettings(MigrateOptions options, MigrationReport report) {
        if (options.envFile() == null) {
            return null;
        }
        SettingsPlan plan = new SettingsPlanner().plan(options.envFile(), options.envOut(), options.target());
        plan.problems().forEach(problem -> report.issue(problem.code(), problem.reference(), problem.message()));
        report.settings(plan.notes(), plan.actions());
        return plan;
    }

    /** The second half: the settings file is written when the history part ended without a problem, and said so when it was not. */
    private void finishSettings(MigrateOptions options, SettingsPlan plan, MigrationReport report) {
        if (plan.hasProblems()) {
            return;
        }
        if (report.hasIssues() || report.errorMessage() != null) {
            report.settingsResult("settings file: not written, this run found a problem (see below); nothing of this run is written");
            return;
        }
        SettingsWriter writer = new SettingsWriter();
        try {
            SettingsWriter.Result result = options.dryRun() ? writer.probe(plan) : writer.write(plan);
            report.settingsResult("settings file: " + describe(result, options.dryRun()));
        } catch (IOException e) {
            report.error("the settings file could not be written (" + e.getClass().getSimpleName() + ": " + e.getMessage()
                + "); the history part of this run is done, run the command again once the cause is fixed");
        }
    }

    private String describe(SettingsWriter.Result result, boolean dryRun) {
        return switch (result) {
            case WRITTEN -> dryRun ? "would be written, owner only" : "written, owner only";
            case ALREADY_THERE -> "already there with exactly these settings, left as it is";
            case LEFT_AS_IT_IS -> "there with other content (completed by hand?), left as it is; delete it to have this conversion written again";
        };
    }
}
