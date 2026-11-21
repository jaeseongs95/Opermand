package io.github.jaeseongs95.portal.migrate.cli;

import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.support.GenericApplicationContext;

import io.github.jaeseongs95.portal.migrate.run.DryRunRunner;
import io.github.jaeseongs95.portal.migrate.run.ImportRunner;
import io.github.jaeseongs95.portal.migrate.run.MigrationInput;

/**
 * Puts the input of the run and its runner into the context before it starts, as beans: the runner then gets the store's adapters by constructor injection, and Boot runs it
 * once the context is up (the runners of the two modes differ in what they are given, so each context has exactly one of them).
 */
final class RunnerRegistration implements ApplicationContextInitializer<GenericApplicationContext> {

    private final MigrationInput input;
    private final boolean dryRun;

    RunnerRegistration(MigrationInput input, boolean dryRun) {
        this.input = input;
        this.dryRun = dryRun;
    }

    @Override
    public void initialize(GenericApplicationContext context) {
        context.registerBean(MigrationInput.class, () -> input);
        if (dryRun) {
            context.registerBean(DryRunRunner.class);
        } else {
            context.registerBean(ImportRunner.class);
        }
    }
}
