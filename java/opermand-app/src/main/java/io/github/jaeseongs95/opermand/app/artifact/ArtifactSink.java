package io.github.jaeseongs95.opermand.app.artifact;

import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;

import org.springframework.transaction.support.TransactionOperations;

import io.github.jaeseongs95.opermand.app.remote.PayloadSink;
import io.github.jaeseongs95.opermand.app.remote.SinkResult;
import io.github.jaeseongs95.opermand.app.storage.DiskCheck;
import io.github.jaeseongs95.opermand.app.storage.RunningDigest;
import io.github.jaeseongs95.opermand.domain.id.ArtifactId;
import io.github.jaeseongs95.opermand.domain.id.Digest;
import io.github.jaeseongs95.opermand.domain.id.Digester;

/**
 * Where the bytes of one artifact go as they arrive (a download read from a target, a stream imported, a staged file copied). It is one transfer's state: the offset
 * and the chunk number it has reached, the digest of everything written, and the first failure. It is made for the transfer and dropped with it, so it is not a bean.
 *
 * <p>Every piece is written to the payload store first and acknowledged to the registry second (design 8.5: the bytes are on disk before the metadata says so), in chunks
 * of at most {@link ChunkDigest#MAX_CHUNK_BYTES}. The failure of either step is the sink's state, not an exception: the adapter that feeds the sink keeps draining its
 * channel and answers {@code Failed}, and the owner of the sink, which knows the cause, discards the artifact. A sink that failed takes nothing more.
 */
public final class ArtifactSink implements PayloadSink {

    private final ArtifactId id;
    private final ArtifactPayloadStore payloads;
    private final ArtifactRegistry registry;
    private final Digester digester;
    private final TransactionOperations writeTx;
    private final RunningDigest whole = new RunningDigest();

    private long announced = -1L;
    private ArtifactReservation refusal;
    private long written;
    private int nextChunk;
    private SinkResult state = new SinkResult.Accepted();

    /** A sink of a download: its size is not known yet, and {@link #append} takes no byte until {@link #expect} announced it. */
    public ArtifactSink(ArtifactId id, ArtifactPayloadStore payloads, ArtifactRegistry registry, Digester digester) {
        this(id, payloads, registry, digester, TransactionOperations.withoutTransaction());
    }

    public ArtifactSink(ArtifactId id, ArtifactPayloadStore payloads, ArtifactRegistry registry, Digester digester, TransactionOperations writeTx) {
        this.id = Objects.requireNonNull(id, "id");
        this.payloads = Objects.requireNonNull(payloads, "payloads");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.digester = Objects.requireNonNull(digester, "digester");
        this.writeTx = Objects.requireNonNull(writeTx, "writeTx");
    }

    /**
     * A sink of an artifact whose size was declared when it was reserved (an import, a copy): the reservation holds that size already, and the registry refuses bytes beyond it,
     * so no announcement is needed.
     */
    public static ArtifactSink declared(ArtifactId id, ArtifactPayloadStore payloads, ArtifactRegistry registry, Digester digester) {
        return declared(id, payloads, registry, digester, TransactionOperations.withoutTransaction());
    }

    public static ArtifactSink declared(ArtifactId id, ArtifactPayloadStore payloads, ArtifactRegistry registry, Digester digester, TransactionOperations writeTx) {
        ArtifactSink sink = new ArtifactSink(id, payloads, registry, digester, writeTx);
        sink.announced = Long.MAX_VALUE;
        return sink;
    }

    /**
     * The target announced the size of the file, before the first byte: measures free disk space outside any transaction, then updates the reservation in a short write transaction.
     */
    @Override
    public SinkResult expect(long totalBytes) {
        if (state instanceof SinkResult.Failed) {
            return state;
        }
        try {
            if (announced >= 0) {
                throw new IllegalStateException("The size of the file was announced twice");
            }
            DiskCheck disk = DiskCheck.measure(payloads::usable);
            ArtifactReservation answer = Objects.requireNonNull(writeTx.execute(status -> registry.sizeDownload(id, totalBytes, disk)), "reservation");
            if (answer instanceof ArtifactReservation.Reserved) {
                announced = totalBytes;
            } else {
                refusal = answer;
                state = new SinkResult.Failed(ArtifactRefusal.of(answer).toException());
            }
        } catch (RuntimeException e) {
            state = new SinkResult.Failed(e);
        }
        return state;
    }

    /** The refusal of the announced size by the budget, the disk or the file limit, when that is why the sink failed; the use case answers it as it answers a refused reservation. */
    public Optional<ArtifactReservation> refusal() {
        return Optional.ofNullable(refusal);
    }

    @Override
    public SinkResult append(byte[] data) {
        Objects.requireNonNull(data, "data");
        if (state instanceof SinkResult.Failed) {
            return state;
        }
        if (announced < 0 || written + data.length > announced) {
            // Without a size the budget cannot be kept, and bytes beyond the announced size were never reserved (defect D-0012).
            state = new SinkResult.Failed(new IllegalStateException(announced < 0 ? "The size of the file was not announced before its bytes" : "The bytes exceed the announced size"));
            return state;
        }
        try {
            int from = 0;
            while (from < data.length) {
                int size = Math.min(ChunkDigest.MAX_CHUNK_BYTES, data.length - from);
                byte[] piece = size == data.length ? data : Arrays.copyOfRange(data, from, from + size);
                payloads.append(id, written, piece);
                ChunkDigest chunk = new ChunkDigest(size, digester.digest(piece));
                int chunkNo = nextChunk;
                writeTx.executeWithoutResult(status -> registry.append(id, chunkNo, chunk));
                whole.update(piece);
                written += size;
                nextChunk++;
                from += size;
            }
        } catch (RuntimeException e) {
            state = new SinkResult.Failed(e);
        }
        return state;
    }

    /** The bytes written and acknowledged so far. */
    public long writtenBytes() {
        return written;
    }

    /** The SHA-256 of every byte written; read it once, when the transfer ends. */
    public Digest digest() {
        return whole.finish();
    }

    /** {@link SinkResult.Accepted} while every piece was taken, {@link SinkResult.Failed} with the first failure after that. */
    public SinkResult status() {
        return state;
    }
}
