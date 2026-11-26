package io.github.jaeseongs95.portal.migrate.cli;

import java.nio.file.Path;

import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.support.GenericApplicationContext;

import io.github.jaeseongs95.portal.migrate.run.DryRunRunner;
import io.github.jaeseongs95.portal.migrate.run.ImportRunner;
import io.github.jaeseongs95.portal.migrate.run.MigrationInput;
import io.github.jaeseongs95.portal.migrate.target.MigrationTarget;

/**
 * Puts the input of the run and its runner into the context before it starts, as beans: the runner then gets the store's adapters by constructor injection, and Boot runs it
 * once the context is up (the runners of the two modes differ in what they are given, so each context has exactly one of them; a dry run never registers {@link MigrationTarget}).
 */
final class RunnerRegistration implements ApplicationContextInitializer<GenericApplicationContext> {

    private final MigrationInput input;
    private final boolean dryRun;
    private final Path target;

    RunnerRegistration(MigrationInput input, boolean dryRun, Path target) {
        this.input = input;
        this.dryRun = dryRun;
        this.target = target;
    }

    @Override
    public void initialize(GenericApplicationContext context) {
        context.registerBean(MigrationInput.class, () -> input);
        if (dryRun) {
            context.registerBean(DryRunRunner.class);
        } else {
            context.registerBean(MigrationTarget.class, () -> new MigrationTarget(target));
            context.registerBean(ImportRunner.class);
        }
    }
}
