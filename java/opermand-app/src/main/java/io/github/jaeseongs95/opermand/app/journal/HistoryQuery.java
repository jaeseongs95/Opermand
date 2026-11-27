package io.github.jaeseongs95.opermand.app.journal;

import java.util.Optional;
import java.util.function.Supplier;

import io.github.jaeseongs95.opermand.domain.id.OperationId;
import io.github.jaeseongs95.opermand.domain.operation.Operation;

/**
 * The read-only history (2.x {@code history-query.mjs}): public metadata only, newest first, at most 10,000 rows are scanned. The page size (20) and the response
 * limit (256 KiB) belong to the use case of stage 3.
 */
public interface HistoryQuery {

    HistoryPage page(HistoryFilter filter, HistoryCursor after, int pageSize);

    /**
     * Fetches database rows only (safe inside a short read-only transaction) and returns a supplier that decrypts body attachments outside the transaction with per-row
     * masked fallback (Principles 4.4, stage 4 design B-15).
     */
    default Supplier<HistoryPage> fetchPage(HistoryFilter filter, HistoryCursor after, int pageSize) {
        HistoryPage page = page(filter, after, pageSize);
        return () -> page;
    }

    /** {@code Optional} here has the one meaning "no such operation". */
    Optional<Operation> publicMetadata(OperationId id);

    /**
     * Fetches one operation's database rows only (safe inside a short read-only transaction) and returns a supplier that decrypts body attachments outside the
     * transaction with masked fallback.
     */
    default Supplier<Optional<Operation>> fetchPublicMetadata(OperationId id) {
        Optional<Operation> found = publicMetadata(id);
        return () -> found;
    }
}
