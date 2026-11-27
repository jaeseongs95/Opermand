package io.github.jaeseongs95.opermand.app.lifecycle;

import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import io.github.jaeseongs95.opermand.app.storage.ProtectionCheck;
import io.github.jaeseongs95.opermand.app.storage.StorageProtection;

/**
 * Refuses to start the application when the guard of the file stores fails (case RC-B-004). 2.x opened its output store and the maintenance state in the constructor of the
 * application and let a damaged guard fail the whole start, with the expired output bytes untouched; here the check runs once every singleton exists and before the context
 * is started, so a failure stops the start and no scheduled reaper ever runs. The reapers check the same guard before each run as well ({@code ReapGate}), so a folder whose
 * protection is lost <em>after</em> the start also keeps its bytes.
 *
 * <p>It is the first {@link StartupStep} ({@link StartupSequence} runs it when every singleton exists) and not Boot's {@code ApplicationRunner}: opermand-app depends on Spring
 * Framework only, and the failure has to be in the refresh of the context to be a refused start.
 */
@Component
@Order(StartupStep.PROTECTION)
public class StartupProtection implements StartupStep {

    private final StorageProtection protection;

    public StartupProtection(StorageProtection protection) {
        this.protection = protection;
    }

    @Override
    public void run() {
        verify();
    }

    /** Throws {@link IllegalStateException} naming the folder and carrying the cause when a folder cannot be used (it is not a folder, or the file store cannot make it private to its owner). */
    public void verify() {
        if (protection.check() instanceof ProtectionCheck.Unprotected unprotected) {
            throw new IllegalStateException("The " + unprotected.area() + " folder cannot be used; the application does not start and deletes nothing", unprotected.cause());
        }
    }
}
