package io.github.jaeseongs95.opermand.server.assembly;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.util.ClassUtils;

import io.github.jaeseongs95.opermand.app.lifecycle.StartupProtection;
import io.github.jaeseongs95.opermand.app.lifecycle.StartupStep;
import io.github.jaeseongs95.opermand.app.recovery.StartupRecovery;

/**
 * The start-up steps of the whole product in the order {@code StartupSequence} runs them (defect D-0159): the guard of the stores, then the reconciliation of the file stores
 * with their rows ({@link StorageStartup}), then the recovery of the journal, which opens the dispatcher. Before the order was declared, the recovery and with it the first
 * dispatch could come before the stores were checked, and a command could reserve its output before the outputs that the last run left pending were given up.
 */
@FullAssemblyTest
class StartupOrderTest {

    private final List<Class<?>> steps;

    StartupOrderTest(ObjectProvider<StartupStep> provider) {
        this.steps = provider.orderedStream().<Class<?>>map(ClassUtils::getUserClass).toList();
    }

    @Test
    @DisplayName("S-D0159-03 D-0159 in the product the guard of the stores runs first, the reconciliation of the stores second, and the recovery of the journal, which opens the dispatcher, last")
    void theProductStartsTheStoresBeforeItOpensTheDispatcher() {
        assertThat(steps).containsExactly(StartupProtection.class, StorageStartup.class, StartupRecovery.class);
    }
}
