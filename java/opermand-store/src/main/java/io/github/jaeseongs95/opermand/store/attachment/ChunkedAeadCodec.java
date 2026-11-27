package io.github.jaeseongs95.opermand.store.attachment;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.ReadableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

import org.springframework.stereotype.Component;

/**
 * Fixed-chunk framed AEAD codec ({@code PAE1}) for streaming, appendable, truncatable, and random-seekable payload files (artifacts and command outputs, B6-Crypto2).
 *
 * <p>Binary layout:
 * <ul>
 *   <li>File header: {@code [magic "PAE1" 4B][ver 1B (=1)][chunkSize 4B (=16384)][keyIdLen 1B][keyId UTF-8]}</li>
 *   <li>Frames {@code 0..N-1}: {@code [plainLen 4B big-endian][iv 12B][ciphertext(plainLen) + 16B GCM tag]}</li>
 * </ul>
 *
 * <p>Every non-final frame holds exactly {@value #CHUNK_PLAIN_BYTES} plaintext bytes ({@value #FULL_FRAME_BYTES} bytes on disk), enabling:
 * <ul>
 *   <li>O(1) plaintext length computation from header + file size;</li>
 *   <li>O(1) append (re-encrypting at most the one trailing partial chunk with a fresh random IV);</li>
 *   <li>O(1) truncate (re-encrypting at most the one new trailing partial chunk with a fresh random IV);</li>
 *   <li>O(span) random-seek range decryption without decrypting skipped chunks;</li>
 *   <li>streaming decryption without buffering large artifacts in memory.</li>
 * </ul>
 *
 * <p>AAD binds {@code ver | keyId | scope | fileId | chunkIndex | plainLen}, preventing chunk reordering, truncation forgery, and cross-file or cross-scope swapping.
 */
@Component
public class ChunkedAeadCodec {

    static final byte[] MAGIC = {'P', 'A', 'E', '1'};
    static final byte VERSION = 1;
    public static final int CHUNK_PLAIN_BYTES = 16 * 1024;
    static final int IV_BYTES = 12;
    static final int GCM_TAG_BITS = 128;
    static final int GCM_TAG_BYTES = GCM_TAG_BITS / 8;
    public static final int FRAME_OVERHEAD_BYTES = 4 + IV_BYTES + GCM_TAG_BYTES;
    public static final long FULL_FRAME_BYTES = (long) CHUNK_PLAIN_BYTES + FRAME_OVERHEAD_BYTES;
    private static final int FIXED_HEADER_PREFIX = MAGIC.length + 1 + 4 + 1;
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";

    /** Storage scope bound into AAD so files cannot be swapped across artifact, stream spool, and finished output formats. */
    public enum Scope {
        ARTIFACT,
        OUTPUT_STREAM,
        OUTPUT_OUT
    }

    private final AttachmentKeyring keyring;
    private final SecureRandom random;

    public ChunkedAeadCodec(AttachmentKeyring keyring, SecureRandom random) {
        this.keyring = Objects.requireNonNull(keyring, "keyring");
        this.random = Objects.requireNonNull(random, "random");
    }

    /** Computes the exact logical plaintext length of a {@code PAE1} file in O(1) without decrypting its body. */
    public long plaintextLength(FileChannel channel, Scope scope, String fileId) throws IOException {
        Objects.requireNonNull(channel, "channel");
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(fileId, "fileId");
        long fileSize = channel.size();
        if (fileSize == 0L) {
            return 0L;
        }
        Header header = readHeader(channel);
        return plaintextLength(channel, header, fileSize);
    }

    /**
     * Returns the logical plaintext length for a valid {@code PAE1} file, or falls back to the raw file size on disk when a quarantined/interrupted file is not a valid
     * {@code PAE1} envelope.
     */
    public long plaintextLengthOrRawSize(Path file, Scope scope, String fileId) throws IOException {
        long rawSize = Files.size(file);
        if (rawSize == 0L) {
            return 0L;
        }
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
            return plaintextLength(channel, scope, fileId);
        } catch (CorruptedEncryptedPayloadException e) {
            return rawSize;
        }
    }

    /**
     * Appends {@code data} at {@code expectedPlainOffset}, re-encrypting any trailing partial chunk with a fresh random IV, and calls {@code channel.force(true)}.
     *
     * @return the new logical plaintext length
     */
    public long append(FileChannel channel, Scope scope, String fileId, long expectedPlainOffset, byte[] data) throws IOException {
        Objects.requireNonNull(channel, "channel");
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(fileId, "fileId");
        Objects.requireNonNull(data, "data");
        if (expectedPlainOffset < 0L) {
            throw new IllegalArgumentException("expectedPlainOffset must not be negative");
        }
        Header header;
        long currentPlain;
        long fileSize = channel.size();
        if (fileSize == 0L) {
            if (expectedPlainOffset != 0L) {
                throw new IllegalStateException("Expected plaintext offset " + expectedPlainOffset + " does not match empty file");
            }
            header = writeHeader(channel);
            currentPlain = 0L;
        } else {
            header = readHeader(channel);
            currentPlain = plaintextLength(channel, header, fileSize);
            if (currentPlain != expectedPlainOffset) {
                throw new IllegalStateException("Expected plaintext offset " + expectedPlainOffset + " does not match current " + currentPlain);
            }
        }
        if (data.length == 0) {
            channel.force(true);
            return currentPlain;
        }
        long chunkIndex = currentPlain / CHUNK_PLAIN_BYTES;
        int inChunk = (int) (currentPlain % CHUNK_PLAIN_BYTES);
        int srcPos = 0;
        if (inChunk > 0) {
            byte[] existing = decryptChunk(channel, header, scope, fileId, chunkIndex, inChunk);
            int take = Math.min(CHUNK_PLAIN_BYTES - inChunk, data.length);
            byte[] combined = new byte[inChunk + take];
            System.arraycopy(existing, 0, combined, 0, inChunk);
            System.arraycopy(data, 0, combined, inChunk, take);
            writeChunk(channel, header, scope, fileId, chunkIndex, combined);
            srcPos += take;
            chunkIndex++;
        }
        while (srcPos < data.length) {
            int take = Math.min(CHUNK_PLAIN_BYTES, data.length - srcPos);
            byte[] slice = Arrays.copyOfRange(data, srcPos, srcPos + take);
            writeChunk(channel, header, scope, fileId, chunkIndex, slice);
            srcPos += take;
            chunkIndex++;
        }
        channel.force(true);
        return currentPlain + data.length;
    }

    /** Writes a complete {@code PAE1} file containing {@code first} immediately followed by {@code second}, calling {@code channel.force(true)}. */
    public void writeAll(FileChannel channel, Scope scope, String fileId, byte[] first, byte[] second) throws IOException {
        Objects.requireNonNull(channel, "channel");
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(fileId, "fileId");
        Objects.requireNonNull(first, "first");
        Objects.requireNonNull(second, "second");
        channel.truncate(0L);
        Header header = writeHeader(channel);
        long chunkIndex = 0L;
        byte[] pending = new byte[CHUNK_PLAIN_BYTES];
        int pendingLen = 0;
        for (byte[] part : new byte[][] {first, second}) {
            int pos = 0;
            while (pos < part.length) {
                int take = Math.min(CHUNK_PLAIN_BYTES - pendingLen, part.length - pos);
                System.arraycopy(part, pos, pending, pendingLen, take);
                pendingLen += take;
                pos += take;
                if (pendingLen == CHUNK_PLAIN_BYTES) {
                    writeChunk(channel, header, scope, fileId, chunkIndex++, pending);
                    pendingLen = 0;
                }
            }
        }
        if (pendingLen > 0) {
            writeChunk(channel, header, scope, fileId, chunkIndex, Arrays.copyOf(pending, pendingLen));
        }
        channel.force(true);
    }

    /**
     * Truncates a {@code PAE1} file to {@code targetPlainLength}. If {@code targetPlainLength} lands inside a chunk, that trailing chunk is decrypted, trimmed, and
     * re-encrypted with a fresh random IV before truncating the underlying file and calling {@code channel.force(true)}.
     */
    public void truncate(FileChannel channel, Scope scope, String fileId, long targetPlainLength) throws IOException {
        Objects.requireNonNull(channel, "channel");
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(fileId, "fileId");
        if (targetPlainLength < 0L) {
            throw new IllegalArgumentException("targetPlainLength must not be negative");
        }
        long fileSize = channel.size();
        if (fileSize == 0L) {
            if (targetPlainLength == 0L) {
                return;
            }
            throw new IllegalArgumentException("Cannot truncate empty file to " + targetPlainLength);
        }
        Header header = readHeader(channel);
        long currentPlain = plaintextLength(channel, header, fileSize);
        if (targetPlainLength > currentPlain) {
            throw new IllegalArgumentException("Target length " + targetPlainLength + " exceeds current length " + currentPlain);
        }
        if (targetPlainLength == currentPlain) {
            return;
        }
        if (targetPlainLength == 0L) {
            channel.truncate(header.headerLength());
            channel.force(true);
            return;
        }
        long fullChunks = targetPlainLength / CHUNK_PLAIN_BYTES;
        int tailBytes = (int) (targetPlainLength % CHUNK_PLAIN_BYTES);
        if (tailBytes == 0) {
            channel.truncate(frameOffset(header, fullChunks));
            channel.force(true);
            return;
        }
        int existingChunkLen = chunkPlainLength(currentPlain, fullChunks);
        byte[] existing = decryptChunk(channel, header, scope, fileId, fullChunks, existingChunkLen);
        byte[] trimmed = Arrays.copyOf(existing, tailBytes);
        writeChunk(channel, header, scope, fileId, fullChunks, trimmed);
        channel.truncate(frameOffset(header, fullChunks) + FRAME_OVERHEAD_BYTES + tailBytes);
        channel.force(true);
    }

    /** Random-seek decrypts exactly {@code plainLength} bytes starting at {@code plainOffset}, touching only the intersecting 16 KiB chunks. */
    public byte[] readRange(FileChannel channel, Scope scope, String fileId, long plainOffset, int plainLength) throws IOException {
        Objects.requireNonNull(channel, "channel");
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(fileId, "fileId");
        if (plainOffset < 0L || plainLength < 0) {
            throw new CorruptedEncryptedPayloadException(CorruptedEncryptedPayloadException.Reason.TRUNCATED_FRAME, "Negative range requested");
        }
        long fileSize = channel.size();
        if (fileSize == 0L) {
            if (plainOffset == 0L && plainLength == 0) {
                return new byte[0];
            }
            throw new CorruptedEncryptedPayloadException(CorruptedEncryptedPayloadException.Reason.TRUNCATED_FRAME, "Encrypted file is empty");
        }
        Header header = readHeader(channel);
        long totalPlain = plaintextLength(channel, header, fileSize);
        if (plainOffset + plainLength > totalPlain) {
            throw new CorruptedEncryptedPayloadException(CorruptedEncryptedPayloadException.Reason.TRUNCATED_FRAME,
                "Requested range [" + plainOffset + ", " + (plainOffset + plainLength) + ") exceeds plaintext size " + totalPlain);
        }
        if (plainLength == 0) {
            return new byte[0];
        }
        byte[] result = new byte[plainLength];
        int dstPos = 0;
        long chunkIndex = plainOffset / CHUNK_PLAIN_BYTES;
        int skip = (int) (plainOffset % CHUNK_PLAIN_BYTES);
        while (dstPos < plainLength) {
            int expectedChunkLen = chunkPlainLength(totalPlain, chunkIndex);
            byte[] chunkPlain = decryptChunk(channel, header, scope, fileId, chunkIndex, expectedChunkLen);
            int take = Math.min(chunkPlain.length - skip, plainLength - dstPos);
            System.arraycopy(chunkPlain, skip, result, dstPos, take);
            dstPos += take;
            skip = 0;
            chunkIndex++;
        }
        return result;
    }

    /**
     * Opens a streaming decrypting {@link ReadableByteChannel} over {@code channel}. Validates header and framing immediately; closes {@code channel} when closed (or if
     * opening fails).
     */
    public DecryptedStream openDecryptingChannel(FileChannel channel, Scope scope, String fileId) throws IOException {
        Objects.requireNonNull(channel, "channel");
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(fileId, "fileId");
        try {
            long fileSize = channel.size();
            if (fileSize == 0L) {
                return new DecryptedStream(new StreamingDecryptChannel(channel, null, scope, fileId, 0L), 0L);
            }
            Header header = readHeader(channel);
            long totalPlain = plaintextLength(channel, header, fileSize);
            return new DecryptedStream(new StreamingDecryptChannel(channel, header, scope, fileId, totalPlain), totalPlain);
        } catch (IOException | RuntimeException e) {
            try {
                channel.close();
            } catch (IOException closing) {
                e.addSuppressed(closing);
            }
            throw e;
        }
    }

    /** Opened streaming channel together with its verified logical plaintext length. */
    public record DecryptedStream(ReadableByteChannel channel, long plaintextLength) {
    }

    private Header writeHeader(FileChannel channel) throws IOException {
        AttachmentKeyring.KeyEntry active = keyring.activeKey();
        byte[] keyIdBytes = active.keyId().getBytes(StandardCharsets.UTF_8);
        if (keyIdBytes.length == 0 || keyIdBytes.length > 255) {
            throw new IllegalStateException("Key id length out of range: " + keyIdBytes.length);
        }
        int headerLength = FIXED_HEADER_PREFIX + keyIdBytes.length;
        ByteBuffer buf = ByteBuffer.allocate(headerLength);
        buf.put(MAGIC).put(VERSION).putInt(CHUNK_PLAIN_BYTES).put((byte) keyIdBytes.length).put(keyIdBytes).flip();
        writeFullyAt(channel, 0L, buf);
        return new Header(active.keyId(), active.secretKey(), headerLength);
    }

    private Header readHeader(FileChannel channel) throws IOException {
        long fileSize = channel.size();
        if (fileSize < FIXED_HEADER_PREFIX + 1L) {
            throw new CorruptedEncryptedPayloadException(CorruptedEncryptedPayloadException.Reason.INVALID_HEADER,
                "Encrypted file header is truncated (" + fileSize + " bytes)");
        }
        ByteBuffer prefix = ByteBuffer.allocate(FIXED_HEADER_PREFIX);
        readFullyAt(channel, 0L, prefix);
        prefix.flip();
        for (int i = 0; i < MAGIC.length; i++) {
            if (prefix.get() != MAGIC[i]) {
                throw new CorruptedEncryptedPayloadException(CorruptedEncryptedPayloadException.Reason.INVALID_HEADER, "Invalid PAE1 magic header");
            }
        }
        byte ver = prefix.get();
        if (ver != VERSION) {
            throw new CorruptedEncryptedPayloadException(CorruptedEncryptedPayloadException.Reason.INVALID_HEADER, "Unsupported PAE1 version: " + ver);
        }
        int chunkSize = prefix.getInt();
        if (chunkSize != CHUNK_PLAIN_BYTES) {
            throw new CorruptedEncryptedPayloadException(CorruptedEncryptedPayloadException.Reason.INVALID_HEADER, "Unsupported PAE1 chunk size: " + chunkSize);
        }
        int keyIdLen = Byte.toUnsignedInt(prefix.get());
        int headerLength = FIXED_HEADER_PREFIX + keyIdLen;
        if (keyIdLen == 0 || fileSize < headerLength) {
            throw new CorruptedEncryptedPayloadException(CorruptedEncryptedPayloadException.Reason.INVALID_HEADER, "Malformed PAE1 key id header");
        }
        ByteBuffer keyIdBuf = ByteBuffer.allocate(keyIdLen);
        readFullyAt(channel, FIXED_HEADER_PREFIX, keyIdBuf);
        String keyId = new String(keyIdBuf.array(), StandardCharsets.UTF_8);
        Optional<SecretKey> key = keyring.findKey(keyId);
        if (key.isEmpty()) {
            throw new CorruptedEncryptedPayloadException(CorruptedEncryptedPayloadException.Reason.UNKNOWN_KEY, "Key " + keyId + " not found in keyring");
        }
        return new Header(keyId, key.orElseThrow(), headerLength);
    }

    private long plaintextLength(FileChannel channel, Header header, long fileSize) throws IOException {
        long bodyBytes = fileSize - header.headerLength();
        if (bodyBytes < 0L) {
            throw new CorruptedEncryptedPayloadException(CorruptedEncryptedPayloadException.Reason.INVALID_HEADER, "File shorter than PAE1 header");
        }
        if (bodyBytes == 0L) {
            return 0L;
        }
        long fullFrames = bodyBytes / FULL_FRAME_BYTES;
        long rem = bodyBytes % FULL_FRAME_BYTES;
        if (rem == 0L) {
            int lastPlain = readFramePlainLength(channel, frameOffset(header, fullFrames - 1));
            if (lastPlain != CHUNK_PLAIN_BYTES) {
                throw new CorruptedEncryptedPayloadException(CorruptedEncryptedPayloadException.Reason.TRUNCATED_FRAME,
                    "Full frame declared plainLen " + lastPlain + " instead of " + CHUNK_PLAIN_BYTES);
            }
            return fullFrames * CHUNK_PLAIN_BYTES;
        }
        if (rem <= FRAME_OVERHEAD_BYTES) {
            throw new CorruptedEncryptedPayloadException(CorruptedEncryptedPayloadException.Reason.TRUNCATED_FRAME,
                "Trailing frame truncated (" + rem + " bytes)");
        }
        int expectedLastPlain = (int) (rem - FRAME_OVERHEAD_BYTES);
        int declared = readFramePlainLength(channel, frameOffset(header, fullFrames));
        if (declared != expectedLastPlain) {
            throw new CorruptedEncryptedPayloadException(CorruptedEncryptedPayloadException.Reason.TRUNCATED_FRAME,
                "Trailing frame declared plainLen " + declared + " but file holds " + expectedLastPlain);
        }
        return fullFrames * CHUNK_PLAIN_BYTES + expectedLastPlain;
    }

    private int readFramePlainLength(FileChannel channel, long offset) throws IOException {
        ByteBuffer lenBuf = ByteBuffer.allocate(4);
        readFullyAt(channel, offset, lenBuf);
        lenBuf.flip();
        return lenBuf.getInt();
    }

    private void writeChunk(FileChannel channel, Header header, Scope scope, String fileId, long chunkIndex, byte[] plaintext) throws IOException {
        byte[] iv = new byte[IV_BYTES];
        random.nextBytes(iv);
        byte[] sealed;
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, header.secretKey(), new GCMParameterSpec(GCM_TAG_BITS, iv));
            cipher.updateAAD(aad(header.keyId(), scope, fileId, chunkIndex, plaintext.length));
            sealed = cipher.doFinal(plaintext);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Failed to encrypt PAE1 chunk " + chunkIndex + " for " + fileId, e);
        }
        ByteBuffer frame = ByteBuffer.allocate(4 + IV_BYTES + sealed.length);
        frame.putInt(plaintext.length).put(iv).put(sealed).flip();
        writeFullyAt(channel, frameOffset(header, chunkIndex), frame);
    }

    private byte[] decryptChunk(FileChannel channel, Header header, Scope scope, String fileId, long chunkIndex, int expectedPlainLen) throws IOException {
        int frameBytes = FRAME_OVERHEAD_BYTES + expectedPlainLen;
        ByteBuffer frame = ByteBuffer.allocate(frameBytes);
        readFullyAt(channel, frameOffset(header, chunkIndex), frame);
        frame.flip();
        int declaredPlain = frame.getInt();
        if (declaredPlain != expectedPlainLen) {
            throw new CorruptedEncryptedPayloadException(CorruptedEncryptedPayloadException.Reason.TRUNCATED_FRAME,
                "Chunk " + chunkIndex + " declared length " + declaredPlain + ", expected " + expectedPlainLen);
        }
        byte[] iv = new byte[IV_BYTES];
        frame.get(iv);
        byte[] sealed = new byte[expectedPlainLen + GCM_TAG_BYTES];
        frame.get(sealed);
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, header.secretKey(), new GCMParameterSpec(GCM_TAG_BITS, iv));
            cipher.updateAAD(aad(header.keyId(), scope, fileId, chunkIndex, declaredPlain));
            return cipher.doFinal(sealed);
        } catch (AEADBadTagException e) {
            throw new CorruptedEncryptedPayloadException(CorruptedEncryptedPayloadException.Reason.AUTH_FAILED,
                "PAE1 chunk " + chunkIndex + " authentication failed for " + fileId, e);
        } catch (GeneralSecurityException e) {
            throw new CorruptedEncryptedPayloadException(CorruptedEncryptedPayloadException.Reason.INVALID_HEADER,
                "PAE1 chunk " + chunkIndex + " decryption failed for " + fileId, e);
        }
    }

    private static long frameOffset(Header header, long chunkIndex) {
        return header.headerLength() + chunkIndex * FULL_FRAME_BYTES;
    }

    private static int chunkPlainLength(long totalPlain, long chunkIndex) {
        long remaining = totalPlain - chunkIndex * CHUNK_PLAIN_BYTES;
        return (int) Math.min(CHUNK_PLAIN_BYTES, remaining);
    }

    private static byte[] aad(String keyId, Scope scope, String fileId, long chunkIndex, int plainLen) {
        return ("PAE1|" + VERSION + "|" + keyId + "|" + scope.name() + "|" + fileId + "|" + chunkIndex + "|" + plainLen).getBytes(StandardCharsets.UTF_8);
    }

    private static void readFullyAt(FileChannel channel, long position, ByteBuffer dst) throws IOException {
        long pos = position;
        while (dst.hasRemaining()) {
            int n = channel.read(dst, pos);
            if (n < 0) {
                throw new CorruptedEncryptedPayloadException(CorruptedEncryptedPayloadException.Reason.TRUNCATED_FRAME,
                    "Unexpected EOF reading PAE1 file at offset " + pos);
            }
            pos += n;
        }
    }

    private static void writeFullyAt(FileChannel channel, long position, ByteBuffer src) throws IOException {
        long pos = position;
        while (src.hasRemaining()) {
            pos += channel.write(src, pos);
        }
    }

    private record Header(String keyId, SecretKey secretKey, int headerLength) {
    }

    private final class StreamingDecryptChannel implements ReadableByteChannel {

        private final FileChannel channel;
        private final Header header;
        private final Scope scope;
        private final String fileId;
        private final long totalPlain;

        private long nextChunkIndex;
        private long deliveredPlain;
        private ByteBuffer current = ByteBuffer.allocate(0);
        private boolean open = true;

        private StreamingDecryptChannel(FileChannel channel, Header header, Scope scope, String fileId, long totalPlain) {
            this.channel = channel;
            this.header = header;
            this.scope = scope;
            this.fileId = fileId;
            this.totalPlain = totalPlain;
        }

        @Override
        public int read(ByteBuffer dst) throws IOException {
            if (!open) {
                throw new java.nio.channels.ClosedChannelException();
            }
            if (!dst.hasRemaining()) {
                return 0;
            }
            if (!current.hasRemaining()) {
                if (deliveredPlain >= totalPlain) {
                    return -1;
                }
                int expectedLen = chunkPlainLength(totalPlain, nextChunkIndex);
                byte[] plain;
                try {
                    plain = decryptChunk(channel, header, scope, fileId, nextChunkIndex, expectedLen);
                } catch (CorruptedEncryptedPayloadException e) {
                    throw new IOException(e.getMessage(), e);
                }
                nextChunkIndex++;
                current = ByteBuffer.wrap(plain);
            }
            int take = Math.min(dst.remaining(), current.remaining());
            int savedLimit = current.limit();
            current.limit(current.position() + take);
            dst.put(current);
            current.limit(savedLimit);
            deliveredPlain += take;
            return take;
        }

        @Override
        public boolean isOpen() {
            return open;
        }

        @Override
        public void close() throws IOException {
            open = false;
            channel.close();
        }
    }
}
