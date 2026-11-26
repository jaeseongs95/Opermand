package io.github.jaeseongs95.portal.store.output;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.security.SecureRandom;

import org.springframework.data.jdbc.core.JdbcAggregateOperations;

import io.github.jaeseongs95.portal.app.output.OutputBinding;
import io.github.jaeseongs95.portal.app.output.OutputId;
import io.github.jaeseongs95.portal.app.output.OutputReservation;
import io.github.jaeseongs95.portal.app.output.RetainedBytes;
import io.github.jaeseongs95.portal.app.output.StreamFrame;
import io.github.jaeseongs95.portal.app.output.StreamKind;
import io.github.jaeseongs95.portal.domain.id.OperationIdGenerator;
import io.github.jaeseongs95.portal.domain.id.Sha256Digester;
import io.github.jaeseongs95.portal.store.StorageRoots;
import io.github.jaeseongs95.portal.store.TestStorage;
import io.github.jaeseongs95.portal.store.output.OutputFiles.PayloadKind;
import io.github.jaeseongs95.portal.store.storage.StorageBudgetRowRepository;

/**
 * The output store as a test builds it: the manifest store over the test's database, the payload store over a temporary folder, and the start-up check over both.
 * A second world over the same folder and the same database is a restart of the portal (fresh objects, same bytes and rows).
 */
final class OutputWorld {

    final OutputFiles files;
    final FileOutputPayloadStore payloads;
    final JdbcOutputManifestStore manifests;
    final OutputStartupCheck startup;
    final CommandOutputRowRepository rows;
    final CommandOutputFormatRowRepository formats;
    final CommandOutputPageRowRepository pages;
    private final JdbcAggregateOperations aggregates;

    OutputWorld(Path folder, OutputLimits limits, Clock clock, CommandOutputRowRepository rows, CommandOutputFormatRowRepository formats, CommandOutputPageRowRepository pages,
                StorageBudgetRowRepository budgets, JdbcAggregateOperations aggregates, OutputMapper mapper) {
        this.rows = rows;
        this.formats = formats;
        this.pages = pages;
        this.aggregates = aggregates;
        this.files = new OutputFiles(new StorageRoots(folder, folder), TestStorage.rules());
        this.payloads = new FileOutputPayloadStore(files, new Sha256Digester(), limits);
        this.manifests = new JdbcOutputManifestStore(rows, formats, pages, budgets, aggregates, mapper, new OperationIdGenerator(new SecureRandom()), limits, clock);
        this.startup = new OutputStartupCheck(manifests, payloads);
    }

    Path file(OutputId id, PayloadKind kind) {
        return files.path(id, kind);
    }

    /** Reserves and insists that the reservation was accepted. */
    OutputId reserve(OutputBinding binding, long expectedBytes, Instant at) {
        OutputReservation reservation = manifests.reserve(binding, expectedBytes, at, payloads.usable());
        if (reservation instanceof OutputReservation.Reserved reserved) {
            return reserved.id();
        }
        throw new AssertionError("The reservation was refused: " + reservation);
    }

    /** Measures retained bytes outside any DB write and marks the output unavailable. */
    void markUnavailable(OutputId id, Instant expiresAt) {
        manifests.markUnavailable(id, expiresAt, new RetainedBytes.Measured(payloads.retainedBytes(id)));
    }

    CommandOutputRow row(OutputId id) {
        return rows.findById(id.value()).orElseThrow();
    }

    long charge(OutputId id) {
        return row(id).chargeBytes();
    }

    /** Appends a frame of the text and returns the size of the spool. */
    long append(OutputId id, StreamKind kind, String text) {
        return payloads.append(id, new StreamFrame(kind, text.getBytes(StandardCharsets.UTF_8)));
    }

    /** Puts bytes in a payload file the way the store would (owner only, no links), replacing what the file held. */
    void plant(Path file, byte[] content) throws IOException {
        try (FileChannel channel = files.openFresh(file)) {
            channel.write(ByteBuffer.wrap(content));
        }
    }

    /** Sets a charge directly, as an older version of the store would have left it, and reconciles the budget ledger to match. */
    void forceCharge(OutputId id, long chargeBytes) {
        aggregates.update(row(id).withChargeBytes(chargeBytes));
        manifests.reconcileBudget();
    }
}
