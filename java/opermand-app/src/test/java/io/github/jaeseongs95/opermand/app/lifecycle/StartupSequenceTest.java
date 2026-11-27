package io.github.jaeseongs95.opermand.app.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.annotation.Order;

import io.github.jaeseongs95.opermand.app.recovery.StartupRecovery;

/**
 * The start-up steps run in the order they declare, not in the order their beans were registered (defect D-0159). The first test registers the steps in the wrong order on
 * purpose; the second reads the declared places of the real steps of this module.
 */
class StartupSequenceTest {

    private static final List<String> RAN = new ArrayList<>();

    @Test
    @DisplayName("S-D0159-01 D-0159 the steps run by their @Order whatever the order of their registration, and a step that fails stops the ones after it")
    void stepsRunByTheirDeclaredOrder() {
        RAN.clear();
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.registerBean("last", Last.class);
            context.registerBean("first", First.class);
            context.registerBean("middle", Middle.class);
            context.registerBean(StartupSequence.class);
            context.refresh();
        }
        assertThat(RAN).containsExactly("first", "middle", "last");

        RAN.clear();
        AnnotationConfigApplicationContext failing = new AnnotationConfigApplicationContext();
        failing.registerBean("last", Last.class);
        failing.registerBean("broken", Broken.class);
        failing.registerBean(StartupSequence.class);
        assertThatThrownBy(failing::refresh).hasMessageContaining("the step failed");
        failing.close();
        assertThat(RAN).as("the step after the broken one did not run").containsExactly("broken");
    }

    @Test
    @DisplayName("S-D0159-02 D-0159 the protection of the stores comes before the stores are reconciled, and both before the journal is recovered and the dispatcher opened")
    void theRealStepsDeclareTheirPlaces() {
        assertThat(StartupStep.PROTECTION).isLessThan(StartupStep.STORAGE);
        assertThat(StartupStep.STORAGE).isLessThan(StartupStep.JOURNAL);
        assertThat(StartupProtection.class.getAnnotation(Order.class).value()).isEqualTo(StartupStep.PROTECTION);
        assertThat(StartupRecovery.class.getAnnotation(Order.class).value()).isEqualTo(StartupStep.JOURNAL);
    }

    @Order(100)
    static final class First implements StartupStep {

        @Override
        public void run() {
            RAN.add("first");
        }
    }

    @Order(200)
    static final class Middle implements StartupStep {

        @Override
        public void run() {
            RAN.add("middle");
        }
    }

    @Order(300)
    static final class Last implements StartupStep {

        @Override
        public void run() {
            RAN.add("last");
        }
    }

    @Order(50)
    static final class Broken implements StartupStep {

        @Override
        public void run() {
            RAN.add("broken");
            throw new IllegalStateException("the step failed");
        }
    }
}
