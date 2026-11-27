package io.github.jaeseongs95.opermand.migrate.target;

import java.util.Optional;

import io.github.jaeseongs95.opermand.domain.id.OperationId;
import io.github.jaeseongs95.opermand.domain.id.RequestKey;
import io.github.jaeseongs95.opermand.store.journal.OperationMetadataLookup;

/** What the 3.0 store already holds, read only from database metadata (no attachment decryption or keyring access): an operation by request key or id, and how many operations are in the unknown state. */
public interface ExistingHistory {

    /** {@code Optional} has the one meaning "no such operation". */
    Optional<OperationMetadataLookup.Entry> byRequestKey(RequestKey key);

    Optional<OperationMetadataLookup.Entry> byId(OperationId id);

    /** The operations in the unknown state: the count the 3.0 scheduler compares with its unknown budget before it starts a new operation. */
    int unknownOperations();
}
