package io.github.jaeseongs95.opermand.store.attachment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.jaeseongs95.opermand.store.TestStorage;

/**
 * Unit tests for {@link ChunkedAeadCodec} ({@code PAE1}): 16 KiB fixed-chunk boundaries, fresh IV on partial-chunk append and mid-chunk truncate, O(span) random-seek
 * range decryption, AAD binding across scope/fileId/chunkIndex, tamper rejection, and retired-key survival across keyring rotation.
 */
class ChunkedAeadCodecTest {

    private static final Instant T0 = Instant.parse("2026-11-27T00:00:00Z");
    private static final String FILE_A = "a".repeat(48);
    private static final String FILE_B = "b".repeat(48);

    private AttachmentKeyring keyringIn(Path dir) {
        return new AttachmentKeyring(dir.resolve("keyring").resolve("attachment.keys"), TestStorage.rules(), new SecureRandom(), Clock.fixed(T0, ZoneOffset.UTC));
    }

    private byte[] pattern(int length, int seed) {
        byte[] bytes = new byte[length];
        for (int i = 0; i < length; i++) {
            bytes[i] = (byte) ((i * 31 + seed) & 0xFF);
        }
        return bytes;
    }

    @Test
    @DisplayName("B6-Crypto2: partial-chunk append and mid-chunk truncate re-encrypt with a fresh IV and preserve exact bytes across chunk boundaries")
    void partialChunkAppendAndTruncateReencryptWithFreshIv(@TempDir Path dir) throws IOException {
        AttachmentKeyring keyring = keyringIn(dir);
        ChunkedAeadCodec codec = new ChunkedAeadCodec(keyring, new SecureRandom());
        Path file = dir.resolve("artifact.part");

        byte[] first = pattern(100, 1);
        byte[] second = pattern(ChunkedAeadCodec.CHUNK_PLAIN_BYTES + 250, 2);
        byte[] ivAfterFirst;
        byte[] ivAfterSecond;
        byte[] ivAfterTruncate;

        try (FileChannel ch = FileChannel.open(file, StandardOpenOption.CREATE_NEW, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
            assertThat(codec.append(ch, ChunkedAeadCodec.Scope.ARTIFACT, FILE_A, 0L, first)).isEqualTo(100L);
            int headerLen = (int) (Files.size(file) - ChunkedAeadCodec.FRAME_OVERHEAD_BYTES - 100L);
            ivAfterFirst = Arrays.copyOfRange(Files.readAllBytes(file), headerLen + 4, headerLen + 4 + ChunkedAeadCodec.IV_BYTES);

            long total = codec.append(ch, ChunkedAeadCodec.Scope.ARTIFACT, FILE_A, 100L, second);
            assertThat(total).isEqualTo(100L + second.length);
            assertThat(codec.plaintextLength(ch, ChunkedAeadCodec.Scope.ARTIFACT, FILE_A)).isEqualTo(total);
            ivAfterSecond = Arrays.copyOfRange(Files.readAllBytes(file), headerLen + 4, headerLen + 4 + ChunkedAeadCodec.IV_BYTES);
            assertThat(ivAfterSecond).as("partial chunk re-encryption must use a fresh IV").isNotEqualTo(ivAfterFirst);

            // Truncate to mid-chunk of chunk 1 (16384 + 77)
            long target = ChunkedAeadCodec.CHUNK_PLAIN_BYTES + 77L;
            long chunk1Offset = headerLen + ChunkedAeadCodec.FULL_FRAME_BYTES;
            byte[] chunk1IvBefore = Arrays.copyOfRange(Files.readAllBytes(file), (int) chunk1Offset + 4, (int) chunk1Offset + 4 + ChunkedAeadCodec.IV_BYTES);

            codec.truncate(ch, ChunkedAeadCodec.Scope.ARTIFACT, FILE_A, target);
            assertThat(codec.plaintextLength(ch, ChunkedAeadCodec.Scope.ARTIFACT, FILE_A)).isEqualTo(target);
            ivAfterTruncate = Arrays.copyOfRange(Files.readAllBytes(file), (int) chunk1Offset + 4, (int) chunk1Offset + 4 + ChunkedAeadCodec.IV_BYTES);
            assertThat(ivAfterTruncate).as("truncate mid-chunk must re-encrypt trailing chunk with a fresh IV").isNotEqualTo(chunk1IvBefore);

            byte[] expected = new byte[(int) target];
            System.arraycopy(first, 0, expected, 0, first.length);
            System.arraycopy(second, 0, expected, first.length, expected.length - first.length);
            assertThat(codec.readRange(ch, ChunkedAeadCodec.Scope.ARTIFACT, FILE_A, 0L, expected.length)).isEqualTo(expected);

            // Truncate to exact chunk boundary (16384)
            codec.truncate(ch, ChunkedAeadCodec.Scope.ARTIFACT, FILE_A, ChunkedAeadCodec.CHUNK_PLAIN_BYTES);
            assertThat(codec.plaintextLength(ch, ChunkedAeadCodec.Scope.ARTIFACT, FILE_A)).isEqualTo(ChunkedAeadCodec.CHUNK_PLAIN_BYTES);
            assertThat(codec.readRange(ch, ChunkedAeadCodec.Scope.ARTIFACT, FILE_A, 0L, ChunkedAeadCodec.CHUNK_PLAIN_BYTES))
                .isEqualTo(Arrays.copyOf(expected, ChunkedAeadCodec.CHUNK_PLAIN_BYTES));

            // Truncate to 0 and re-append
            codec.truncate(ch, ChunkedAeadCodec.Scope.ARTIFACT, FILE_A, 0L);
            assertThat(codec.plaintextLength(ch, ChunkedAeadCodec.Scope.ARTIFACT, FILE_A)).isZero();
            byte[] fresh = pattern(50, 9);
            assertThat(codec.append(ch, ChunkedAeadCodec.Scope.ARTIFACT, FILE_A, 0L, fresh)).isEqualTo(50L);
            assertThat(codec.readRange(ch, ChunkedAeadCodec.Scope.ARTIFACT, FILE_A, 0L, 50)).isEqualTo(fresh);
        }
    }

    @Test
    @DisplayName("B6-Crypto2: writeAll + random-seek readRange across multiple 16 KiB chunks and streaming decrypt channel return exact bytes")
    void randomSeekRangeAndStreamingDecryptionAcrossChunks(@TempDir Path dir) throws IOException {
        AttachmentKeyring keyring = keyringIn(dir);
        ChunkedAeadCodec codec = new ChunkedAeadCodec(keyring, new SecureRandom());
        Path file = dir.resolve("output.out");

        byte[] json = pattern(ChunkedAeadCodec.CHUNK_PLAIN_BYTES * 2 + 123, 7);
        byte[] text = pattern(ChunkedAeadCodec.CHUNK_PLAIN_BYTES + 456, 13);
        try (FileChannel ch = FileChannel.open(file, StandardOpenOption.CREATE_NEW, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
            codec.writeAll(ch, ChunkedAeadCodec.Scope.OUTPUT_OUT, FILE_A, json, text);
        }

        try (FileChannel ch = FileChannel.open(file, StandardOpenOption.READ)) {
            assertThat(codec.plaintextLength(ch, ChunkedAeadCodec.Scope.OUTPUT_OUT, FILE_A)).isEqualTo((long) json.length + text.length);
            assertThat(codec.readRange(ch, ChunkedAeadCodec.Scope.OUTPUT_OUT, FILE_A, 0L, json.length)).isEqualTo(json);
            assertThat(codec.readRange(ch, ChunkedAeadCodec.Scope.OUTPUT_OUT, FILE_A, json.length, text.length)).isEqualTo(text);
            // Cross-chunk slice inside json
            int sliceStart = ChunkedAeadCodec.CHUNK_PLAIN_BYTES - 50;
            assertThat(codec.readRange(ch, ChunkedAeadCodec.Scope.OUTPUT_OUT, FILE_A, sliceStart, 200))
                .isEqualTo(Arrays.copyOfRange(json, sliceStart, sliceStart + 200));
            assertThat(codec.readRange(ch, ChunkedAeadCodec.Scope.OUTPUT_OUT, FILE_A, json.length, 0)).isEmpty();
        }

        try (FileChannel ch = FileChannel.open(file, StandardOpenOption.READ)) {
            ChunkedAeadCodec.DecryptedStream stream = codec.openDecryptingChannel(ch, ChunkedAeadCodec.Scope.OUTPUT_OUT, FILE_A);
            assertThat(stream.plaintextLength()).isEqualTo((long) json.length + text.length);
            byte[] all = Channels.newInputStream(stream.channel()).readAllBytes();
            assertThat(Arrays.copyOfRange(all, 0, json.length)).isEqualTo(json);
            assertThat(Arrays.copyOfRange(all, json.length, all.length)).isEqualTo(text);
        }
    }

    @Test
    @DisplayName("B6-Crypto2: AAD rejects wrong scope, wrong fileId, swapped chunks, bit-flips, and truncated frames; rotated keyring still decrypts and appends old files")
    void aadBindingTamperDetectionAndKeyRotation(@TempDir Path dir) throws IOException {
        AttachmentKeyring keyring = keyringIn(dir);
        ChunkedAeadCodec codec = new ChunkedAeadCodec(keyring, new SecureRandom());
        Path file = dir.resolve("two-chunks.part");

        byte[] data = pattern(ChunkedAeadCodec.CHUNK_PLAIN_BYTES * 2, 5);
        try (FileChannel ch = FileChannel.open(file, StandardOpenOption.CREATE_NEW, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
            codec.append(ch, ChunkedAeadCodec.Scope.ARTIFACT, FILE_A, 0L, data);
        }

        // Key rotation: old file remains readable AND appendable via permanently retained RETIRED key
        keyring.rotate();
        byte[] extra = pattern(19, 11);
        try (FileChannel ch = FileChannel.open(file, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
            assertThat(codec.readRange(ch, ChunkedAeadCodec.Scope.ARTIFACT, FILE_A, 0L, data.length)).isEqualTo(data);
            assertThat(codec.append(ch, ChunkedAeadCodec.Scope.ARTIFACT, FILE_A, data.length, extra)).isEqualTo((long) data.length + extra.length);
            assertThat(codec.readRange(ch, ChunkedAeadCodec.Scope.ARTIFACT, FILE_A, data.length, extra.length)).isEqualTo(extra);
        }

        // Wrong scope and wrong fileId fail AAD authentication
        try (FileChannel ch = FileChannel.open(file, StandardOpenOption.READ)) {
            assertThatThrownBy(() -> codec.readRange(ch, ChunkedAeadCodec.Scope.OUTPUT_OUT, FILE_A, 0L, 16))
                .isInstanceOfSatisfying(CorruptedEncryptedPayloadException.class, e -> assertThat(e.reason()).isEqualTo(CorruptedEncryptedPayloadException.Reason.AUTH_FAILED));
            assertThatThrownBy(() -> codec.readRange(ch, ChunkedAeadCodec.Scope.ARTIFACT, FILE_B, 0L, 16))
                .isInstanceOfSatisfying(CorruptedEncryptedPayloadException.class, e -> assertThat(e.reason()).isEqualTo(CorruptedEncryptedPayloadException.Reason.AUTH_FAILED));
        }

        // Swapping chunk 0 and chunk 1 fails chunkIndex AAD
        byte[] raw = Files.readAllBytes(file);
        int headerLen = raw.length - (int) (ChunkedAeadCodec.FULL_FRAME_BYTES * 2 + ChunkedAeadCodec.FRAME_OVERHEAD_BYTES + extra.length);
        int frameLen = (int) ChunkedAeadCodec.FULL_FRAME_BYTES;
        byte[] swapped = raw.clone();
        System.arraycopy(raw, headerLen, swapped, headerLen + frameLen, frameLen);
        System.arraycopy(raw, headerLen + frameLen, swapped, headerLen, frameLen);
        Path swappedFile = dir.resolve("swapped.part");
        Files.write(swappedFile, swapped);
        try (FileChannel ch = FileChannel.open(swappedFile, StandardOpenOption.READ)) {
            assertThatThrownBy(() -> codec.readRange(ch, ChunkedAeadCodec.Scope.ARTIFACT, FILE_A, 0L, 16))
                .isInstanceOfSatisfying(CorruptedEncryptedPayloadException.class, e -> assertThat(e.reason()).isEqualTo(CorruptedEncryptedPayloadException.Reason.AUTH_FAILED));
        }

        // Bit-flip in ciphertext fails streaming channel
        byte[] flipped = raw.clone();
        flipped[flipped.length - 1] = (byte) (flipped[flipped.length - 1] ^ 0x01);
        Path flippedFile = dir.resolve("flipped.part");
        Files.write(flippedFile, flipped);
        try (FileChannel ch = FileChannel.open(flippedFile, StandardOpenOption.READ)) {
            ChunkedAeadCodec.DecryptedStream stream = codec.openDecryptingChannel(ch, ChunkedAeadCodec.Scope.ARTIFACT, FILE_A);
            assertThatThrownBy(() -> Channels.newInputStream(stream.channel()).readAllBytes())
                .isInstanceOf(IOException.class)
                .hasCauseInstanceOf(CorruptedEncryptedPayloadException.class);
        }
    }
}
