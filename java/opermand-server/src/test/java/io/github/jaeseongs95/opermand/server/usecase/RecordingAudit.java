package io.github.jaeseongs95.opermand.server.usecase;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import io.github.jaeseongs95.opermand.app.audit.AuditAction;
import io.github.jaeseongs95.opermand.app.audit.AuditEvent;
import io.github.jaeseongs95.opermand.app.audit.AuditFailedException;
import io.github.jaeseongs95.opermand.app.audit.AuditLog;
import io.github.jaeseongs95.opermand.domain.id.OperationId;

/**
 * The audit port of the use case tests: it remembers every line that "became durable" and fails, with the text of the 2.x tests, for the actions a test chose. The JSONL
 * file adapter has its own tests in opermand-store; what is tested here is what the use cases do with the answer of the port, which needs a port that can say "no".
 */
final class RecordingAudit implements AuditLog {

    private final List<AuditEvent> written = new CopyOnWriteArrayList<>();
    private final Set<AuditAction> failing = ConcurrentHashMap.newKeySet();
    private volatile Consumer<AuditEvent> whenWritten = event -> { };

    @Override
    public void append(AuditEvent event) {
        if (failing.contains(event.action())) {
            throw new AuditFailedException("audit unavailable: SECRET-AUDIT-DETAIL");
        }
        whenWritten.accept(event);
        written.add(event);
    }

    /** Runs {@code observer} at the moment each line becomes durable, before it is remembered: what the journal holds at that moment can be looked at. */
    void whenWritten(Consumer<AuditEvent> observer) {
        whenWritten = observer;
    }

    /** From now on the lines of this action do not become durable. */
    void failOn(AuditAction action) {
        failing.add(action);
    }

    void failNothing() {
        failing.clear();
        whenWritten = event -> { };
    }

    /** The actions written for the operation, in the order they were written. */
    List<AuditAction> actionsOf(OperationId id) {
        return written.stream().filter(event -> event.operationId().equals(id)).map(AuditEvent::action).toList();
    }

    List<AuditEvent> all() {
        return List.copyOf(written);
    }
}
