package io.github.jaeseongs95.opermand.app.operation;

import org.springframework.stereotype.Component;

import io.github.jaeseongs95.opermand.app.maintenance.MaintenanceStore;
import io.github.jaeseongs95.opermand.domain.approval.MaintenanceState;

/** The maintenance reservation as the value the domain policies judge on. It reads the stored reservation each time, so a restart in the middle of maintenance stays in it. */
@Component
public class MaintenanceView {

    private final MaintenanceStore store;

    public MaintenanceView(MaintenanceStore store) {
        this.store = store;
    }

    public MaintenanceState state() {
        return store.occupied() ? new MaintenanceState.Reserved() : new MaintenanceState.Open();
    }
}
