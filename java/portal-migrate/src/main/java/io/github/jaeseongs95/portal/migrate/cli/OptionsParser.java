package io.github.jaeseongs95.portal.migrate.cli;

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
        "usage: portal-migrate --source=<2.x data folder> --target=<3.0 data folder> [--dry-run] [--unknown-budget=<n>]",
        "  --source          the folder the 2.x service kept its state in (stop the 2.x service first)",
        "  --target          the 3.0 data folder; the embedded store is created below it. Not needed when --spring.datasource.url names the store",
        "  --dry-run         write nothing: report what would be moved, what collides and what is not moved",
        "  --unknown-budget  the unknown budget the 3.0 server runs with (default " + ConcurrencyLimits.DEFAULT_UNKNOWN_BUDGET + "); the migration stops if the unknown operations would use it up",
        "exit codes: 0 moved (or nothing to do, or a dry run that found no problem), 1 error, 2 conflict (nothing was written)");

    private static final Set<String> OPTIONS = Set.of("source", "target", "dry-run", "unknown-budget");

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
        return new MigrateOptions(Path.of(source).toAbsolutePath(), target == null ? null : Path.of(target).toAbsolutePath(), flag(arguments, "dry-run"), budget(arguments), custom);
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
