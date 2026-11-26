package io.github.jaeseongs95.portal.migrate.cli;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.DefaultApplicationArguments;

import io.github.jaeseongs95.portal.domain.schedule.ConcurrencyLimits;

/**
 * Reads the command line with Spring Boot's own argument parser ({@code --name=value}). Only the options of the migration are accepted, and the {@code spring.*} options, which
 * go to the Spring context as they are (the way to point the migration at a 3.0 store that is not the embedded one).
 */
final class OptionsParser {

    static final String USAGE = String.join("\n",
        "usage: portal-migrate --source=<2.x data folder> --target=<3.0 data folder> [--dry-run] [--unknown-budget=<n>] [--env-file=<2.x environment file> --env-out=<new file>]",
        "  --source          the folder the 2.x service kept its state in (stop the 2.x service first)",
        "  --target          the 3.0 data folder (BRIDGE_DATA_DIR of the server); holds the embedded store (portal) and encrypted attachments/keyring. Required for real history imports even when --spring.datasource.url names the database",
        "  --dry-run         write nothing: report what would be moved, what collides and what is not moved",
        "  --unknown-budget  the unknown budget the 3.0 server runs with (default " + ConcurrencyLimits.DEFAULT_UNKNOWN_BUDGET + "); the migration stops if the unknown operations would use it up",
        "  --env-file        a 2.x environment file (BRIDGE_* settings, secrets included); it is only read. Needs --env-out",
        "  --env-out         the file the converted 3.0 settings are written to, owner only; it is never overwritten and must not be the --env-file. Needs --env-file",
        "exit codes: 0 moved (or nothing to do, or a dry run that found no problem), 1 error, 2 conflict (nothing was written)");

    private static final Set<String> OPTIONS = Set.of("source", "target", "dry-run", "unknown-budget", "env-file", "env-out");

    MigrateOptions parse(String[] args) {
        ApplicationArguments arguments = new DefaultApplicationArguments(args);
        if (!arguments.getNonOptionArgs().isEmpty()) {
            throw new UsageException("unexpected argument: " + arguments.getNonOptionArgs().getFirst() + " (options are written --name=value)");
        }
        for (String name : arguments.getOptionNames()) {
            if (!OPTIONS.contains(name) && !name.startsWith("spring.")) {
                throw new UsageException("unknown option --" + name);
            }
        }
        String source = single(arguments, "source");
        if (source == null) {
            throw new UsageException("--source is required");
        }
        boolean custom = arguments.containsOption("spring.datasource.url");
        String target = single(arguments, "target");
        if (target == null && !custom) {
            throw new UsageException("--target is required (or --spring.datasource.url to name the 3.0 store)");
        }
        Path envFile = path(arguments, "env-file");
        Path envOut = path(arguments, "env-out");
        if ((envFile == null) != (envOut == null)) {
            throw new UsageException("--env-file and --env-out go together: the 2.x file is only read, the converted settings need a file of their own");
        }
        if (envFile != null && sameFile(envFile, envOut)) {
            throw new UsageException("--env-out is the 2.x environment file itself; the 2.x file is never changed, name a new file");
        }
        return new MigrateOptions(Path.of(source).toAbsolutePath(), target == null ? null : Path.of(target).toAbsolutePath(), flag(arguments, "dry-run"), budget(arguments), custom,
            envFile, envOut);
    }

    private Path path(ApplicationArguments arguments, String name) {
        String text = single(arguments, name);
        return text == null ? null : Path.of(text).toAbsolutePath();
    }

    /** The same path, or two names of one existing file (a link, a different spelling): the file system decides, not the text. */
    private boolean sameFile(Path first, Path second) {
        if (first.normalize().equals(second.normalize())) {
            return true;
        }
        try {
            return Files.exists(first) && Files.exists(second) && Files.isSameFile(first, second);
        } catch (IOException e) {
            throw new UsageException("the paths --env-file and --env-out cannot be compared: " + e.getClass().getSimpleName());
        }
    }

    private String single(ApplicationArguments arguments, String name) {
        List<String> values = arguments.getOptionValues(name);
        if (values == null) {
            return null;
        }
        if (values.size() != 1 || values.getFirst().isEmpty()) {
            throw new UsageException("--" + name + " needs exactly one value");
        }
        return values.getFirst();
    }

    private boolean flag(ApplicationArguments arguments, String name) {
        List<String> values = arguments.getOptionValues(name);
        if (values == null) {
            return false;
        }
        if (values.isEmpty()) {
            return true;
        }
        if (values.size() == 1 && ("true".equals(values.getFirst()) || "false".equals(values.getFirst()))) {
            return Boolean.parseBoolean(values.getFirst());
        }
        throw new UsageException("--" + name + " is a flag (or =true, =false)");
    }

    private int budget(ApplicationArguments arguments) {
        String text = single(arguments, "unknown-budget");
        if (text == null) {
            return ConcurrencyLimits.DEFAULT_UNKNOWN_BUDGET;
        }
        try {
            int value = Integer.parseInt(text);
            if (value < 1) {
                throw new UsageException("--unknown-budget is a whole number of at least 1");
            }
            return value;
        } catch (NumberFormatException e) {
            throw new UsageException("--unknown-budget is a whole number of at least 1");
        }
    }
}
