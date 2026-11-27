package io.github.jaeseongs95.opermand.store.output;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import io.github.jaeseongs95.opermand.app.output.FormatSpan;
import io.github.jaeseongs95.opermand.app.output.OutputFormat;
import io.github.jaeseongs95.opermand.app.output.OutputFormats;
import io.github.jaeseongs95.opermand.app.output.OutputId;
import io.github.jaeseongs95.opermand.app.output.OutputPayloadStore;
import io.github.jaeseongs95.opermand.app.output.StreamFrame;
import io.github.jaeseongs95.opermand.app.output.StreamKind;
import io.github.jaeseongs95.opermand.app.storage.DiskSpace;
import io.github.jaeseongs95.opermand.app.storage.PayloadRead;
import io.github.jaeseongs95.opermand.domain.error.ExecutionErrorCode;
import io.github.jaeseongs95.opermand.domain.error.RequestRefusedException;
import io.github.jaeseongs95.opermand.domain.id.Digest;
import io.github.jaeseongs95.opermand.domain.id.Digester;
import io.github.jaeseongs95.opermand.store.attachment.ChunkedAeadCodec;
import io.github.jaeseongs95.opermand.store.attachment.CorruptedEncryptedPayloadException;
import io.github.jaeseongs95.opermand.store.output.OutputFiles.PayloadKind;

/**
 * The bytes of the command outputs, encrypted at rest with {@link ChunkedAeadCodec} ({@code PAE1}, 16 KiB fixed chunks with AES-256-GCM, B6-Crypto2).
 *
 * <ul>
 * <li>{@code <id>.stream}: encrypted spool of raw stdout/stderr frames, each one byte of {@link StreamKind} (0 stdout, 1 stderr), four bytes of length (big endian),
 * and the data. Logical plaintext size never exceeds {@link OutputLimits#maxOutputBytes()}.</li>
 * <li>{@code <id>.part}: temporary encrypted file written by {@code finish}.</li>
 * <li>{@code <id>.out}: encrypted JSON rendering followed by text rendering. {@code openFormat} performs O(span) random-seek decryption over only the intersecting
 * 16 KiB chunks and verifies both the GCM tags and the 16 KiB page SHA-256 digests + full rendering SHA-256 against the manifest.</li>
 * </ul>
 * Every method does file I/O and is called outside a database transaction (Principles 4.4).
 */
@Component
public class FileOutputPayloadStore implements OutputPayloadStore {

    /** The size of one page whose digest the manifest keeps (2.x {@code OUTPUT_PAGE_BYTES}). */
    public static final int PAGE_BYTES = 16 * 1024;
    /** One byte of kind and four bytes of length in front of the data of a frame. */
    public static final int FRAME_HEADER_BYTES = 5;

    private static final Logger LOG = LoggerFactory.getLogger(FileOutputPayloadStore.class);
    private static final byte STDOUT_CODE = 0;
    private static final byte STDERR_CODE = 1;

    private final OutputFiles files;
    private final Digester digester;
    private final OutputLimits limits;
    private final ChunkedAeadCodec codec;

    @Autowired
    public FileOutputPayloadStore(OutputFiles files, Digester digester, OutputLimits limits, ChunkedAeadCodec codec) {
        this.files = Objects.requireNonNull(files, "files");
        this.digester = Objects.requireNonNull(digester, "digester");
        this.limits = Objects.requireNonNull(limits, "limits");
        this.codec = Objects.requireNonNull(codec, "codec");
    }

    @Override
    public long append(OutputId id, StreamFrame frame) {
        if (files.exists(files.path(id, PayloadKind.OUT))) {
            // The output is finished: its spool was removed and must not be started again (2.x "Output stream closed").
            throw new RequestRefusedException(ExecutionErrorCode.OUTPUT_CAPTURE_FAILED);
        }
        byte[] data = frame.data();
        try (FileChannel spool = files.openReadWrite(files.path(id, PayloadKind.STREAM))) {
            long size = codec.plaintextLength(spool, ChunkedAeadCodec.Scope.OUTPUT_STREAM, id.value());
            int room = (int) Math.max(0L, Math.min(data.length, limits.maxOutputBytes() - size - FRAME_HEADER_BYTES));
            if (room == 0) {
                return size;
            }
            ByteBuffer bytes = ByteBuffer.allocate(FRAME_HEADER_BYTES + room);
            bytes.put(code(frame.kind())).putInt(room).put(data, 0, room);
            return codec.append(spool, ChunkedAeadCodec.Scope.OUTPUT_STREAM, id.value(), size, bytes.array());
        } catch (CorruptedEncryptedPayloadException | IOException e) {
            throw new RequestRefusedException(ExecutionErrorCode.OUTPUT_CAPTURE_FAILED, e);
        }
    }

    @Override
    public OutputFormats finish(OutputId id, byte[] json, byte[] text) {
        long total = (long) json.length + text.length;
        if (total > limits.maxOutputBytes()) {
            // 2.x "Output snapshot exceeds storage reservation": nothing is written, so the reservation is released without bytes to account for.
            throw new RequestRefusedException(ExecutionErrorCode.OUTPUT_CAPACITY);
        }
        Path out = files.path(id, PayloadKind.OUT);
        if (files.exists(out)) {
            throw new RequestRefusedException(ExecutionErrorCode.OUTPUT_CAPTURE_FAILED);
        }
        Map<OutputFormat, FormatSpan> spans = new EnumMap<>(OutputFormat.class);
        spans.put(OutputFormat.JSON, span(0L, json));
        spans.put(OutputFormat.TEXT, span(json.length, text));
        Path part = files.path(id, PayloadKind.PART);
        try (FileChannel channel = files.openFreshReadWrite(part)) {
            codec.writeAll(channel, ChunkedAeadCodec.Scope.OUTPUT_OUT, id.value(), json, text);
        } catch (CorruptedEncryptedPayloadException | IOException e) {
            throw new RequestRefusedException(ExecutionErrorCode.OUTPUT_CAPTURE_FAILED, e);
        }
        files.moveIntoPlace(part, out);
        removeSpool(id);
        return new OutputFormats(spans);
    }

    @Override
    public PayloadRead openFormat(OutputId id, OutputFormat format, FormatSpan span) {
        if (span.sizeBytes() > limits.maxOutputBytes()) {
            throw new RequestRefusedException(ExecutionErrorCode.OUTPUT_CAPTURE_FAILED);
        }
        int size = (int) span.sizeBytes();
        byte[] bytes;
        try (FileChannel out = files.openRead(files.path(id, PayloadKind.OUT))) {
            bytes = codec.readRange(out, ChunkedAeadCodec.Scope.OUTPUT_OUT, id.value(), span.offsetBytes(), size);
        } catch (CorruptedEncryptedPayloadException e) {
            throw damaged(id, format, e.getMessage());
        } catch (IOException e) {
            throw new RequestRefusedException(ExecutionErrorCode.OUTPUT_CAPTURE_FAILED, e);
        }
        verify(id, format, span, bytes);
        return new PayloadRead(Channels.newChannel(new ByteArrayInputStream(bytes)), size);
    }

    /** Opens the encrypted spool as a verified decrypting stream; fails closed on tampering or missing spool. */
    @Override
    public PayloadRead openStream(OutputId id) {
        Path spool = files.path(id, PayloadKind.STREAM);
        if (!files.exists(spool)) {
            throw new RequestRefusedException(ExecutionErrorCode.OUTPUT_CAPTURE_FAILED);
        }
        FileChannel channel = files.openRead(spool);
        try {
            ChunkedAeadCodec.DecryptedStream stream = codec.openDecryptingChannel(channel, ChunkedAeadCodec.Scope.OUTPUT_STREAM, id.value());
            return new PayloadRead(stream.channel(), stream.plaintextLength());
        } catch (CorruptedEncryptedPayloadException | IOException e) {
            throw new RequestRefusedException(ExecutionErrorCode.OUTPUT_CAPTURE_FAILED, e);
        }
    }

    /** The SHA-256 of the verified decrypted spool; a tampered spool fails closed with {@code OUTPUT_CAPTURE_FAILED}. */
    @Override
    public Digest streamDigest(OutputId id) {
        try (PayloadRead read = openStream(id)) {
            if (read.size() > limits.maxOutputBytes()) {
                throw damaged(id, "the spool is larger than an output may be");
            }
            ByteBuffer bytes = ByteBuffer.allocate((int) read.size());
            while (bytes.hasRemaining()) {
                if (read.channel().read(bytes) < 0) {
                    throw damaged(id, "the spool ended before its size");
                }
            }
            return digester.digest(bytes.array());
        } catch (IOException e) {
            throw new RequestRefusedException(ExecutionErrorCode.OUTPUT_CAPTURE_FAILED, e);
        }
    }

    @Override
    public void delete(OutputId id) {
        for (PayloadKind kind : PayloadKind.values()) {
            files.delete(files.path(id, kind));
        }
    }

    @Override
    public long retainedBytes(OutputId id) {
        long bytes = 0L;
        for (PayloadKind kind : PayloadKind.values()) {
            Path file = files.path(id, kind);
            if (files.exists(file)) {
                files.requireRegular(file);
                try {
                    bytes += codec.plaintextLengthOrRawSize(file, scopeOf(kind), id.value());
                } catch (IOException e) {
                    throw new RequestRefusedException(ExecutionErrorCode.OUTPUT_CAPTURE_FAILED, e);
                }
            }
        }
        return bytes;
    }

    @Override
    public void quarantine(OutputId id) {
        for (PayloadKind kind : PayloadKind.values()) {
            Path file = files.path(id, kind);
            if (files.exists(file)) {
                files.quarantine(file);
            }
        }
    }

    @Override
    public Set<OutputId> stored() {
        return files.storedIds();
    }

    @Override
    public DiskSpace usable() {
        return new DiskSpace(files.usableBytes());
    }

    private ChunkedAeadCodec.Scope scopeOf(PayloadKind kind) {
        return switch (kind) {
            case STREAM -> ChunkedAeadCodec.Scope.OUTPUT_STREAM;
            case PART, OUT -> ChunkedAeadCodec.Scope.OUTPUT_OUT;
        };
    }

    private FormatSpan span(long offset, byte[] bytes) {
        List<Digest> pages = new ArrayList<>();
        for (int from = 0; from < bytes.length; from += PAGE_BYTES) {
            pages.add(digester.digest(Arrays.copyOfRange(bytes, from, Math.min(bytes.length, from + PAGE_BYTES))));
        }
        return new FormatSpan(offset, bytes.length, digester.digest(bytes), pages);
    }

    private void verify(OutputId id, OutputFormat format, FormatSpan span, byte[] bytes) {
        int pages = (bytes.length + PAGE_BYTES - 1) / PAGE_BYTES;
        if (span.pageDigests().size() != pages) {
            throw damaged(id, format, "the span has " + span.pageDigests().size() + " page digests for " + pages + " pages");
        }
        for (int page = 0; page < pages; page++) {
            int from = page * PAGE_BYTES;
            if (!span.pageDigests().get(page).equals(digester.digest(Arrays.copyOfRange(bytes, from, Math.min(bytes.length, from + PAGE_BYTES))))) {
                throw damaged(id, format, "page " + page + " does not match its digest");
            }
        }
        if (!span.sha256().equals(digester.digest(bytes))) {
            throw damaged(id, format, "the rendering does not match its digest");
        }
    }

    /** The spool of a finished output is not needed any more; a spool that cannot be removed is left for the next delete or the start-up check. */
    private void removeSpool(OutputId id) {
        try {
            files.delete(files.path(id, PayloadKind.STREAM));
        } catch (RequestRefusedException e) {
            LOG.warn("The spool of the finished output {} could not be removed", id.value(), e);
        }
    }

    private RequestRefusedException damaged(OutputId id, OutputFormat format, String reason) {
        LOG.warn("The {} rendering of the output {} failed verification: {}", format, id.value(), reason);
        return new RequestRefusedException(ExecutionErrorCode.OUTPUT_CAPTURE_FAILED);
    }

    private RequestRefusedException damaged(OutputId id, String reason) {
        LOG.warn("The spool of the output {} cannot be read: {}", id.value(), reason);
        return new RequestRefusedException(ExecutionErrorCode.OUTPUT_CAPTURE_FAILED);
    }

    private byte code(StreamKind kind) {
        return switch (kind) {
            case STDOUT -> STDOUT_CODE;
            case STDERR -> STDERR_CODE;
        };
    }
}
