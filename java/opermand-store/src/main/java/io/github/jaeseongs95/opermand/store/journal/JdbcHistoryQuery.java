package io.github.jaeseongs95.opermand.store.journal;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;

import io.github.jaeseongs95.opermand.app.journal.HistoryCursor;
import io.github.jaeseongs95.opermand.app.journal.HistoryFilter;
import io.github.jaeseongs95.opermand.app.journal.HistoryPage;
import io.github.jaeseongs95.opermand.app.journal.HistoryQuery;
import io.github.jaeseongs95.opermand.app.journal.TargetFilter;
import io.github.jaeseongs95.opermand.domain.error.HistoryErrorCode;
import io.github.jaeseongs95.opermand.domain.error.RequestRefusedException;
import io.github.jaeseongs95.opermand.domain.id.OperationId;
import io.github.jaeseongs95.opermand.domain.operation.Operation;
import io.github.jaeseongs95.opermand.domain.operation.OperationState;

/**
 * {@link HistoryQuery} over the journal, newest first with keyset paging on {@code (created_at, id)}.
 *
 * <p>{@link #fetchPage} and {@link #fetchPublicMetadata} perform SQL queries only (inside the caller's read-only transaction) and return a supplier that decrypts
 * operation body attachments outside the transaction with per-row masked fallback on unreadable bodies (stage 4 design B-15, Principles 4.4).
 */
@Repository
public class JdbcHistoryQuery implements HistoryQuery {

    private final OperationRowRepository operations;
    private final OperationStorage storage;

    public JdbcHistoryQuery(OperationRowRepository operations, OperationStorage storage) {
        this.operations = operations;
        this.storage = storage;
    }

    @Override
    public HistoryPage page(HistoryFilter filter, HistoryCursor after, int pageSize) {
        return fetchPage(filter, after, pageSize).get();
    }

    @Override
    public Supplier<HistoryPage> fetchPage(HistoryFilter filter, HistoryCursor after, int pageSize) {
        Collection<OperationState> states = filter.states().isEmpty() ? EnumSet.allOf(OperationState.class) : filter.states();
        int fetch = pageSize + 1;
        List<OperationRow> rows = switch (after) {
            case HistoryCursor.FirstPage first -> firstPage(filter.target(), states, fetch);
            case HistoryCursor.After position -> following(filter.target(), states, position, fetch);
            case HistoryCursor.End end -> throw new RequestRefusedException(HistoryErrorCode.HISTORY_INVALID_CURSOR);
        };
        boolean more = rows.size() > pageSize;
        List<OperationRow> shown = more ? rows.subList(0, pageSize) : rows;
        List<OperationStorage.LoadedOperation> loaded = shown.stream().map(storage::fetchRaw).toList();
        HistoryCursor next = more ? new HistoryCursor.After(shown.get(shown.size() - 1).createdAt(), new OperationId(shown.get(shown.size() - 1).id())) : new HistoryCursor.End();
        return () -> new HistoryPage(loaded.stream().map(storage::hydrateForHistory).toList(), next);
    }

    @Override
    public Optional<Operation> publicMetadata(OperationId id) {
        return fetchPublicMetadata(id).get();
    }

    @Override
    public Supplier<Optional<Operation>> fetchPublicMetadata(OperationId id) {
        Optional<OperationStorage.LoadedOperation> raw = operations.findById(id.value()).map(storage::fetchRaw);
        return () -> raw.map(storage::hydrateForHistory);
    }

    private List<OperationRow> firstPage(TargetFilter target, Collection<OperationState> states, int fetch) {
        return switch (target) {
            case TargetFilter.AnyTarget any -> operations.findByStateInOrderByCreatedAtDescIdDesc(states, PageRequest.ofSize(fetch));
            case TargetFilter.OnlyTarget only -> operations.findByTargetIdAndStateInOrderByCreatedAtDescIdDesc(only.targetId().value(), states, PageRequest.ofSize(fetch));
        };
    }

    private List<OperationRow> following(TargetFilter target, Collection<OperationState> states, HistoryCursor.After position, int fetch) {
        List<OperationRow> rows = new ArrayList<>(switch (target) {
            case TargetFilter.AnyTarget any -> operations.findByStateInAndCreatedAtAndIdLessThanOrderByIdDesc(states, position.createdAt(), position.id().value(),
                PageRequest.ofSize(fetch));
            case TargetFilter.OnlyTarget only -> operations.findByTargetIdAndStateInAndCreatedAtAndIdLessThanOrderByIdDesc(only.targetId().value(), states,
                position.createdAt(), position.id().value(), PageRequest.ofSize(fetch));
        });
        if (rows.size() < fetch) {
            int rest = fetch - rows.size();
            rows.addAll(switch (target) {
                case TargetFilter.AnyTarget any -> operations.findByStateInAndCreatedAtBeforeOrderByCreatedAtDescIdDesc(states, position.createdAt(), PageRequest.ofSize(rest));
                case TargetFilter.OnlyTarget only -> operations.findByTargetIdAndStateInAndCreatedAtBeforeOrderByCreatedAtDescIdDesc(only.targetId().value(), states,
                    position.createdAt(), PageRequest.ofSize(rest));
            });
        }
        return rows;
    }
}
