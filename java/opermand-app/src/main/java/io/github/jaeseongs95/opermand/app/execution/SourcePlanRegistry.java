package io.github.jaeseongs95.opermand.app.execution;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import io.github.jaeseongs95.opermand.app.operation.TerminalOperationListener;
import io.github.jaeseongs95.opermand.domain.approval.ApprovalPolicy;
import io.github.jaeseongs95.opermand.domain.error.CapacityErrorCode;
import io.github.jaeseongs95.opermand.domain.error.RequestRefusedException;
import io.github.jaeseongs95.opermand.domain.id.OperationId;
import io.github.jaeseongs95.opermand.domain.operation.Operation;
import io.github.jaeseongs95.opermand.domain.text.SourceText;

/**
 * In-memory registry of private {@code SOURCE} script bodies keyed by {@link OperationId} (stage 4 design B-3, defect SSH_PORTAL-D-0170).
 *
 * <p>Source text is never written to the database or disk: it lives only here for the lifetime of an open operation, is consumed once when
 * {@link SourceOperationRunner} dispatches the operation, and is removed automatically when an operation reaches any terminal state (expired, denied,
 * cancelled, settled). Total memory is strictly bounded to {@code requestCapacity * SourceText.MAX_BYTES} (request capacity x 16 KiB).
 */
@Component
public class SourcePlanRegistry implements TerminalOperationListener {

    private final Object lock = new Object();
    private final Map<OperationId, SourceText> plans = new HashMap<>();
    private final int maxEntries;
    private final long maxBytes;
    private long totalBytes;

    @Autowired
    public SourcePlanRegistry(ApprovalPolicy policy) {
        this(Objects.requireNonNull(policy, "policy").requestCapacity().value());
    }

    public SourcePlanRegistry(int maxEntries) {
        if (maxEntries < 1) {
            throw new IllegalArgumentException("Source plan capacity must be at least 1");
        }
        this.maxEntries = maxEntries;
        this.maxBytes = Math.multiplyExact((long) maxEntries, SourceText.MAX_BYTES);
    }

    /**
     * Stores {@code source} in memory for {@code id}. Refuses with {@link CapacityErrorCode#REQUEST_CAPACITY_EXCEEDED} if the registry is full.
     */
    public void register(OperationId id, SourceText source) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(source, "source");
        long bytes = utf8Length(source);
        synchronized (lock) {
            SourceText previous = plans.get(id);
            long previousBytes = previous == null ? 0L : utf8Length(previous);
            int nextCount = previous == null ? plans.size() + 1 : plans.size();
            long nextBytes = totalBytes - previousBytes + bytes;
            if (nextCount > maxEntries || nextBytes > maxBytes) {
                throw new RequestRefusedException(CapacityErrorCode.REQUEST_CAPACITY_EXCEEDED);
            }
            plans.put(id, source);
            totalBytes = nextBytes;
        }
    }

    /** Atomically removes and returns the registered source text for {@code id}, or empty if absent (e.g., after restart). */
    public Optional<SourceText> take(OperationId id) {
        Objects.requireNonNull(id, "id");
        synchronized (lock) {
            SourceText removed = plans.remove(id);
            if (removed != null) {
                totalBytes -= utf8Length(removed);
            }
            return Optional.ofNullable(removed);
        }
    }

    /** Removes any registered source text for {@code id}; idempotent. */
    public void remove(OperationId id) {
        Objects.requireNonNull(id, "id");
        synchronized (lock) {
            SourceText removed = plans.remove(id);
            if (removed != null) {
                totalBytes -= utf8Length(removed);
            }
        }
    }

    @Override
    public void onTerminal(Operation operation) {
        Objects.requireNonNull(operation, "operation");
        remove(operation.id());
    }

    public int size() {
        synchronized (lock) {
            return plans.size();
        }
    }

    public long totalBytes() {
        synchronized (lock) {
            return totalBytes;
        }
    }

    private static long utf8Length(SourceText source) {
        return source.value().getBytes(StandardCharsets.UTF_8).length;
    }
}
