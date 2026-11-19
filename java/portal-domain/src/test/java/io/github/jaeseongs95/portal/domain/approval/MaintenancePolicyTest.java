package io.github.jaeseongs95.portal.domain.approval;

import static io.github.jaeseongs95.portal.domain.operation.OperationFixtures.T0;
import static io.github.jaeseongs95.portal.domain.operation.OperationFixtures.queued;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.jaeseongs95.portal.domain.error.DispatchFailure;
import io.github.jaeseongs95.portal.domain.error.DispatchReason;
import io.github.jaeseongs95.portal.domain.error.DispatchStage;
import io.github.jaeseongs95.portal.domain.operation.Operation;
import io.github.jaeseongs95.portal.domain.operation.OperationState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class MaintenancePolicyTest {

    private final MaintenancePolicy policy = new MaintenancePolicy();

    @Test
    @DisplayName("S2-ST-01 a queued operation that waits when maintenance begins is refused before dispatch for maintenance, and nothing ran")
    void queuedOperationIsClosed() {
        DispatchFailure failure = policy.failureForQueuedOperation();
        assertThat(failure).isEqualTo(DispatchFailure.refused(DispatchReason.MAINTENANCE, DispatchStage.MAINTENANCE));
        Operation closed = queued().refuseBeforeDispatch(failure, T0.plusSeconds(3));
        assertThat(closed.state()).isEqualTo(OperationState.FAILED);
    }
}
