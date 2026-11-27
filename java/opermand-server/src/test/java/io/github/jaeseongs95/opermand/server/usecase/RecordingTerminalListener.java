package io.github.jaeseongs95.opermand.server.usecase;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import io.github.jaeseongs95.opermand.app.operation.TerminalOperationListener;
import io.github.jaeseongs95.opermand.domain.id.OperationId;
import io.github.jaeseongs95.opermand.domain.operation.Operation;

/** A listener of terminal operations that remembers which operations it was told about, so a test can count the notifications of one operation. */
final class RecordingTerminalListener implements TerminalOperationListener {

    private final List<OperationId> told = new CopyOnWriteArrayList<>();

    @Override
    public void onTerminal(Operation operation) {
        told.add(operation.id());
    }

    /** How many times the operation was announced as ended. */
    long timesTold(OperationId id) {
        return told.stream().filter(id::equals).count();
    }
}
