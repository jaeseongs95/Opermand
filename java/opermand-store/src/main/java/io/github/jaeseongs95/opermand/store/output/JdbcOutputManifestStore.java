package io.github.jaeseongs95.opermand.store.output;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.springframework.data.jdbc.core.JdbcAggregateOperations;
import org.springframework.stereotype.Repository;

import io.github.jaeseongs95.opermand.app.output.OutputBinding;
import io.github.jaeseongs95.opermand.app.output.OutputBudget;
import io.github.jaeseongs95.opermand.app.output.OutputFormats;
import io.github.jaeseongs95.opermand.app.output.OutputId;
import io.github.jaeseongs95.opermand.app.output.OutputLookup;
import io.github.jaeseongs95.opermand.app.output.OutputManifest;
import io.github.jaeseongs95.opermand.app.output.OutputManifestStore;
import io.github.jaeseongs95.opermand.app.output.OutputReservation;
import io.github.jaeseongs95.opermand.app.output.OutputState;
import io.github.jaeseongs95.opermand.app.output.RetainedBytes;
import io.github.jaeseongs95.opermand.app.output.StreamInfo;
import io.github.jaeseongs95.opermand.app.storage.DiskSpace;
import io.github.jaeseongs95.opermand.domain.error.ExecutionErrorCode;
import io.github.jaeseongs95.opermand.domain.error.OperationErrorCode;
import io.github.jaeseongs95.opermand.domain.error.RequestRefusedException;
import io.github.jaeseongs95.opermand.domain.id.OperationIdGenerator;
import io.github.jaeseongs95.opermand.store.storage.StorageBudgetRow;
import io.github.jaeseongs95.opermand.store.storage.StorageBudgetRowRepository;

/**
 * {@link OutputManifestStore} over {@code command_output}, {@code command_output_format}, {@code command_output_page}, and {@code storage_budget} (SSH_PORTAL-D-0058).
 * Transaction boundaries belong to the calling service (Principles 4.4); this repository opens no transactions and performs no file I/O.
 * Every quota-changing mutation locks {@code storage_budget} first (Lock Order #1) before mutating {@code command_output}.
 */
@Repository
public class JdbcOutputManifestStore implements OutputManifestStore {

    private static final String SCOPE = StorageBudgetRowRepository.OUTPUT_SCOPE;

    private final CommandOutputRowRepository outputs;
    private final CommandOutputFormatRowRepository formats;
    private final CommandOutputPageRowRepository pages;
    private final StorageBudgetRowRepository budgets;
    private final JdbcAggregateOperations aggregates;
    private final OutputMapper mapper;
    private final OperationIdGenerator ids;
    private final OutputLimits limits;
    private final Clock clock;

    public JdbcOutputManifestStore(CommandOutputRowRepository outputs, CommandOutputFormatRowRepository formats, CommandOutputPageRowRepository pages,
                                   StorageBudgetRowRepository budgets, JdbcAggregateOperations aggregates, OutputMapper mapper, OperationIdGenerator ids,
                                   OutputLimits limits, Clock clock) {
        this.outputs = outputs;
        this.formats = formats;
        this.pages = pages;
        this.budgets = budgets;
        this.aggregates = aggregates;
        this.mapper = mapper;
        this.ids = ids;
        this.limits = limits;
        this.clock = clock;
    }

    @Override
    public OutputLookup find(OutputId id, Instant now) {
        return outputs.findById(id.value()).map(row -> lookup(row, now)).orElseGet(OutputLookup.Missing::new);
    }

    @Override
    public OutputReservation reserve(OutputBinding binding, long expectedBytes, Instant at, DiskSpace disk) {
        Objects.requireNonNull(binding, "binding");
        Objects.requireNonNull(at, "at");
        Objects.requireNonNull(disk, "disk");
        if (expectedBytes < 0) {
            throw new IllegalArgumentException("expectedBytes must not be negative: " + expectedBytes);
        }
        if (expectedBytes > limits.maxOutputBytes()) {
            return new OutputReservation.TooLarge(limits.maxOutputBytes());
        }
        if (expectedBytes > limits.budgetBytes()) {
            StorageBudgetRow row = budgetRow();
            if (row.itemCount() >= limits.maxCount()) {
                return new OutputReservation.CountExceeded(limits.maxCount());
            }
            return new OutputReservation.BudgetExceeded(expectedBytes, row.chargedBytes(), limits.budgetBytes());
        }
        if (budgets.reserveBytesAndCount(SCOPE, expectedBytes, limits.budgetBytes() - expectedBytes, limits.maxCount()) != 1) {
            StorageBudgetRow row = budgetRow();
            if (row.itemCount() >= limits.maxCount()) {
                return new OutputReservation.CountExceeded(limits.maxCount());
            }
            return new OutputReservation.BudgetExceeded(expectedBytes, row.chargedBytes(), limits.budgetBytes());
        }
        if (disk.usableBytes() < limits.diskRequiredBytes()) {
            budgets.adjust(SCOPE, -expectedBytes, -1L);
            return new OutputReservation.DiskSpaceInsufficient(limits.diskRequiredBytes(), disk.usableBytes());
        }
        OutputId id = new OutputId(ids.next().value());
        aggregates.insert(mapper.newRow(id, binding, at.plus(limits.ttl()), expectedBytes));
        return new OutputReservation.Reserved(id);
    }

    @Override
    public OutputManifest recordStream(OutputId id, StreamInfo stream) {
        if (stream instanceof StreamInfo.NoStream) {
            throw new IllegalArgumentException("Record an open or a complete stream; no stream is the state before the first record");
        }
        CommandOutputRow row = requirePending(id);
        if (row.streamState() == StreamState.COMPLETE) {
            throw new RequestRefusedException(ExecutionErrorCode.OUTPUT_CAPTURE_FAILED);
        }
        CommandOutputRow next = mapper.withStream(claimVersion(row), stream);
        aggregates.update(next);
        return mapper.toManifest(next, List.of(), List.of());
    }

    @Override
    public OutputManifest markReady(OutputId id, OutputFormats formatSpans, long sizeBytes) {
        if (sizeBytes < 0) {
            throw new IllegalArgumentException("sizeBytes must not be negative: " + sizeBytes);
        }
        lockBudget();
        CommandOutputRow row = requirePending(id);
        List<CommandOutputFormatRow> formatRows = mapper.formatRows(id, formatSpans);
        List<CommandOutputPageRow> pageRows = mapper.pageRows(id, formatSpans);
        CommandOutputRow next = claimVersion(row).withReady(sizeBytes, clock.instant().plus(limits.ttl()));
        long delta = sizeBytes - row.chargeBytes();
        if (delta != 0L) {
            budgets.adjust(SCOPE, delta, 0L);
        }
        aggregates.update(next);
        aggregates.insertAll(formatRows);
        aggregates.insertAll(pageRows);
        return mapper.toManifest(next, formatRows, pageRows);
    }

    @Override
    public void markUnavailable(OutputId id, Instant expiresAt, RetainedBytes retained) {
        Objects.requireNonNull(expiresAt, "expiresAt");
        Objects.requireNonNull(retained, "retained");
        lockBudget();
        CommandOutputRow row = requireRow(id);
        if (row.state() == OutputState.READY) {
            throw new RequestRefusedException(ExecutionErrorCode.OUTPUT_CAPTURE_FAILED);
        }
        long charge = switch (retained) {
            case RetainedBytes.Measured measured -> measured.bytes();
            case RetainedBytes.Unmeasured unmeasured -> Math.max(row.chargeBytes(), limits.maxOutputBytes());
        };
        if (row.state() == OutputState.UNAVAILABLE && row.expiresAt().equals(expiresAt) && row.chargeBytes() == charge) {
            return;
        }
        CommandOutputRow next = claimVersion(row).withUnavailable(expiresAt, charge);
        long delta = charge - row.chargeBytes();
        if (delta != 0L) {
            budgets.adjust(SCOPE, delta, 0L);
        }
        aggregates.update(next);
    }

    @Override
    public OutputBudget budget() {
        StorageBudgetRow row = budgetRow();
        return new OutputBudget(limits.budgetBytes(), row.chargedBytes(), limits.maxCount(), Math.toIntExact(row.itemCount()));
    }

    @Override
    public List<OutputManifest> expired(Instant now) {
        return outputs.findByStateNotAndExpiresAtLessThanEqual(OutputState.PENDING, now).stream().map(this::manifest).toList();
    }

    @Override
    public void delete(OutputId id) {
        lockBudget();
        Optional<CommandOutputRow> found = outputs.findById(id.value());
        if (found.isEmpty()) {
            return;
        }
        pages.removeByOutputId(id.value());
        formats.removeByOutputId(id.value());
        if (outputs.removeById(id.value()) == 1) {
            budgets.adjust(SCOPE, -found.orElseThrow().chargeBytes(), -1L);
        }
    }

    @Override
    public List<OutputManifest> inState(OutputState state) {
        return outputs.findByState(state).stream().map(this::manifest).toList();
    }

    @Override
    public void reconcileBudget() {
        if (budgets.reconcileOutput() != 1) {
            throw new IllegalStateException("The storage_budget row for OUTPUT is missing: the migration V8 was not applied");
        }
    }

    private OutputLookup lookup(CommandOutputRow row, Instant now) {
        if (!now.isBefore(row.expiresAt())) {
            return new OutputLookup.Expired();
        }
        return switch (row.state()) {
            case PENDING -> new OutputLookup.Pending();
            case UNAVAILABLE -> new OutputLookup.Unavailable();
            case READY -> new OutputLookup.Ready(manifest(row));
        };
    }

    private OutputManifest manifest(CommandOutputRow row) {
        return mapper.toManifest(row, formats.findByOutputId(row.id()), pages.findByOutputIdOrderByFormatAscPageNoAsc(row.id()));
    }

    private CommandOutputRow requireRow(OutputId id) {
        return outputs.findById(id.value()).orElseThrow(() -> new RequestRefusedException(ExecutionErrorCode.OUTPUT_CAPTURE_FAILED));
    }

    private CommandOutputRow requirePending(OutputId id) {
        CommandOutputRow row = requireRow(id);
        if (row.state() != OutputState.PENDING) {
            throw new RequestRefusedException(ExecutionErrorCode.OUTPUT_CAPTURE_FAILED);
        }
        return row;
    }

    private CommandOutputRow claimVersion(CommandOutputRow row) {
        long next = row.version() + 1;
        if (outputs.bumpVersion(row.id(), row.version(), next) != 1) {
            throw new RequestRefusedException(OperationErrorCode.OUTPUT_VERSION_CONFLICT);
        }
        return row.withVersion(next);
    }

    private void lockBudget() {
        if (budgets.adjust(SCOPE, 0L, 0L) != 1) {
            throw new IllegalStateException("The storage_budget row for OUTPUT is missing: the migration V8 was not applied");
        }
    }

    private StorageBudgetRow budgetRow() {
        return budgets.findById(SCOPE)
            .orElseThrow(() -> new IllegalStateException("The storage_budget row for OUTPUT is missing: the migration V8 was not applied"));
    }
}
