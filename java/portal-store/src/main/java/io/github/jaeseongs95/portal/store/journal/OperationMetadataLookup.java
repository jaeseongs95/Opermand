package io.github.jaeseongs95.portal.store.journal;

import java.util.Objects;
import java.util.Optional;

import org.springframework.stereotype.Component;

import io.github.jaeseongs95.portal.domain.id.Digest;
import io.github.jaeseongs95.portal.domain.id.OperationId;
import io.github.jaeseongs95.portal.domain.id.RequestKey;

/**
 * Read-only metadata lookup over the {@code operation} table. Reads only database columns ({@code id}, {@code contract_digest}, {@code state}) and never touches
 * encrypted body attachments or the attachment keyring.
 */
@Component
public class OperationMetadataLookup {

    /** Identifying metadata of an existing operation row without decrypting any body attachments. */
    public record Entry(OperationId id, Digest contractDigest) {
        public Entry {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(contractDigest, "contractDigest");
        }

        public boolean hasContract(Digest expected) {
            return contractDigest.equals(expected);
        }
    }

    private final OperationRowRepository operations;

    public OperationMetadataLookup(OperationRowRepository operations) {
        this.operations = Objects.requireNonNull(operations, "operations");
    }

    public Optional<Entry> findByRequestKey(RequestKey key) {
        Objects.requireNonNull(key, "key");
        return operations.findByRequestKey(key.value()).map(OperationMetadataLookup::toEntry);
    }

    public Optional<Entry> findById(OperationId id) {
        Objects.requireNonNull(id, "id");
        return operations.findById(id.value()).map(OperationMetadataLookup::toEntry);
    }

    public int unknownOperations() {
        return Math.toIntExact(operations.countLiveUnknown());
    }

    private static Entry toEntry(OperationRow row) {
        return new Entry(new OperationId(row.id()), new Digest(row.contractDigest()));
    }
}
