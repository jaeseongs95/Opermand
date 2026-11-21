package io.github.jaeseongs95.portal.app.lifecycle;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.stereotype.Component;

/**
 * Runs the {@link StartupStep}s of the application once, in the order they declare ({@link org.springframework.core.annotation.Order}), when every singleton exists. It is the
 * one {@link SmartInitializingSingleton} of the start-up work: Spring calls such callbacks in the order the beans were registered, which no step may depend on (defect D-0159).
 * A step that throws stops the start and the later steps are not run.
 *
 * <p>It belongs to the factory that owns it, like the callbacks it replaces, so the steps run once per application and not once per child context.
 */
@Component
public class StartupSequence implements SmartInitializingSingleton {

    private final ObjectProvider<StartupStep> steps;

    public StartupSequence(ObjectProvider<StartupStep> steps) {
        this.steps = steps;
    }

    @Override
    public void afterSingletonsInstantiated() {
        steps.orderedStream().forEach(StartupStep::run);
    }
}
