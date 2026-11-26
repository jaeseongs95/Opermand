package io.github.jaeseongs95.portal.migrate.target;

import java.util.Optional;

import io.github.jaeseongs95.portal.domain.id.OperationId;
import io.github.jaeseongs95.portal.domain.id.RequestKey;
import io.github.jaeseongs95.portal.store.journal.OperationMetadataLookup;

/** The history metadata of an existing 3.0 store, read directly from the {@code operation} table without reading or creating a keyring. */
final class StoredHistory implements ExistingHistory {

    private final OperationMetadataLookup lookup;

    StoredHistory(OperationMetadataLookup lookup) {
        this.lookup = lookup;
    }

    @Override
    public Optional<OperationMetadataLookup.Entry> byRequestKey(RequestKey key) {
        return lookup.findByRequestKey(key);
    }

    @Override
    public Optional<OperationMetadataLookup.Entry> byId(OperationId id) {
        return lookup.findById(id);
    }

    @Override
    public int unknownOperations() {
        return lookup.unknownOperations();
    }
}
