package io.github.jaeseongs95.portal.ssh.sftp;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jaeseongs95.portal.app.remote.CancelSource;
import io.github.jaeseongs95.portal.app.remote.DownloadRequest;
import io.github.jaeseongs95.portal.app.remote.DownloadResult;
import io.github.jaeseongs95.portal.app.remote.FileDiagnostic;
import io.github.jaeseongs95.portal.app.remote.PathPolicyDecision;
import io.github.jaeseongs95.portal.app.remote.PayloadSink;
import io.github.jaeseongs95.portal.app.remote.SinkResult;
import io.github.jaeseongs95.portal.app.remote.TransferFailureCause;
import io.github.jaeseongs95.portal.app.remote.TransferLimits;
import io.github.jaeseongs95.portal.domain.error.TransferErrorCode;
import io.github.jaeseongs95.portal.domain.operation.CompletionEvidence;
import io.github.jaeseongs95.portal.ssh.testkit.SftpFault;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.function.UnaryOperator;
import org.apache.sshd.sftp.client.SftpClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.util.unit.DataSize;

/**
 * The download over the embedded SFTP server (S3-SF-06 to S3-SF-09, S3-SF-14; defect D-0013): a download only reads, and a failed read is a failure with its reason,
 * never an unknown outcome. Each test also says what the server's directory holds afterwards: a download creates nothing there.
 */
@Timeout(120)
class DownloadTest {

    @TempDir
    Path root;

    private TransferBed bed;

    @AfterEach
    void closeBed() throws IOException {
        if (bed != null) {
            bed.close();
        }
    }

    private TransferBed start() throws IOException {
        bed = TransferBed.start(root, true);
        return bed;
    }

    private static byte[] pattern(int length) {
        byte[] bytes = new byte[length];
        for (int index = 0; index < length; index++) {
            bytes[index] = (byte) (index * 17 + 3);
        }
        return bytes;
    }

    private long entriesInRoot() throws IOException {
        try (var files = Files.list(root)) {
            return files.count();
        }
    }

    @Test
    @DisplayName("S3-SF-06 D-0013 A-159 a file of 850 bytes is fetched: the size and the digest are those of the bytes, the sink holds them, and the evidence is a verified read")
    void smallFile() throws IOException {
        TransferBed testBed = start();
        byte[] content = pattern(850);
        Files.write(root.resolve("small.bin"), content);
        TransferBed.Collecting sink = new TransferBed.Collecting();

        DownloadResult result = testBed.transfer().download(testBed.download("/small.bin"), sink, testBed.control());

        assertThat(result).isInstanceOf(DownloadResult.Fetched.class);
        DownloadResult.Fetched fetched = (DownloadResult.Fetched) result;
        assertThat(fetched.size()).isEqualTo(850);
        assertThat(fetched.sha256()).isEqualTo(testBed.sha256(content));
        assertThat(fetched.evidence()).isEqualTo(new CompletionEvidence.FileRead(true));
        assertThat(sink.bytes()).isEqualTo(content);
        assertThat(entriesInRoot()).as("a download creates nothing on the target").isEqualTo(1);
        assertThat(testBed.sessions().leases()).isEqualTo(1);
    }

    @Test
    @DisplayName("S-D0012-01 (adapter half): the size the target announced is told to the sink once, before the first block; a file that is too large announces nothing; a sink that cannot take the size ends the read before a byte")
    void announcesTheSizeBeforeTheFirstBlock() throws IOException {
        TransferBed testBed = start();
        Files.write(root.resolve("small.bin"), pattern(850));
        Files.write(root.resolve("empty.bin"), new byte[0]);
        Files.write(root.resolve("over.bin"), pattern(2_048));
        TransferBed.Collecting small = new TransferBed.Collecting();
        TransferBed.Collecting empty = new TransferBed.Collecting();
        TransferBed.Collecting over = new TransferBed.Collecting();
        TransferLimits limit = new TransferLimits(TransferLimits.DEFAULT_DEADLINE, DataSize.ofBytes(1024));
        int[] blocksTaken = {0};
        PayloadSink refusing = new PayloadSink() {
            @Override
            public SinkResult expect(long totalBytes) {
                return new SinkResult.Failed(new IllegalStateException("no room"));
            }

            @Override
            public SinkResult append(byte[] data) {
                blocksTaken[0]++;
                return new SinkResult.Accepted();
            }
        };

        testBed.transfer().download(testBed.download("/small.bin"), small, testBed.control());
        testBed.transfer().download(testBed.download("/empty.bin"), empty, testBed.control());
        DownloadResult tooLarge = testBed.transfer().download(testBed.download("/over.bin", limit), over, testBed.control());
        DownloadResult refused = testBed.transfer().download(testBed.download("/small.bin"), refusing, testBed.control());

        assertThat(small.announced()).isEqualTo(850);
        assertThat(small.announcements()).isEqualTo(1);
        assertThat(small.announcedBeforeTheFirstBlock()).isTrue();
        assertThat(empty.announced()).isZero();
        assertThat(tooLarge).isInstanceOf(DownloadResult.TooLarge.class);
        assertThat(over.announced()).as("a file that is too large is refused before the sink is asked").isEqualTo(-1L);
        assertThat(refused).isEqualTo(new DownloadResult.Failed(TransferFailureCause.LOCAL_IO_FAILED));
        assertThat(blocksTaken[0]).isZero();
    }

    @Test
    @DisplayName("S3-SF-06 an empty file and a file of several blocks are fetched whole")
    void emptyAndLargerFiles() throws IOException {
        TransferBed testBed = start();
        byte[] large = pattern(700_000);
        Files.write(root.resolve("empty.bin"), new byte[0]);
        Files.write(root.resolve("large.bin"), large);
        TransferBed.Collecting emptySink = new TransferBed.Collecting();
        TransferBed.Collecting largeSink = new TransferBed.Collecting();

        DownloadResult empty = testBed.transfer().download(testBed.download("/empty.bin"), emptySink, testBed.control());
        DownloadResult whole = testBed.transfer().download(testBed.download("/large.bin"), largeSink, testBed.control());

        assertThat(empty).isEqualTo(new DownloadResult.Fetched(0, testBed.sha256(new byte[0])));
        assertThat(whole).isEqualTo(new DownloadResult.Fetched(700_000, testBed.sha256(large)));
        assertThat(largeSink.bytes()).isEqualTo(large);
    }

    @Test
    @DisplayName("S3-SF-07 a file exactly at the size limit is fetched; one byte more is TooLarge, found out before a byte is read")
    void sizeLimit() throws IOException {
        TransferBed testBed = start();
        Files.write(root.resolve("at.bin"), pattern(1024));
        Files.write(root.resolve("over.bin"), pattern(1025));
        TransferLimits limit = new TransferLimits(TransferLimits.DEFAULT_DEADLINE, DataSize.ofBytes(1024));
        TransferBed.Collecting overSink = new TransferBed.Collecting();

        DownloadResult at = testBed.transfer().download(testBed.download("/at.bin", limit), new TransferBed.Collecting(), testBed.control());
        DownloadResult over = testBed.transfer().download(testBed.download("/over.bin", limit), overSink, testBed.control());

        assertThat(at).isInstanceOf(DownloadResult.Fetched.class);
        assertThat(over).isEqualTo(new DownloadResult.TooLarge(1025, 1024));
        assertThat(overSink.bytes()).as("not a byte was read").isEmpty();
    }

    @Test
    @DisplayName("S3-SF-07 the contract's 64 MiB limit: one byte above it is TooLarge (a sparse file; only its size is asked)")
    void contractLimit() throws IOException {
        TransferBed testBed = start();
        long limit = 64L * 1024 * 1024;
        try (RandomAccessFile file = new RandomAccessFile(root.resolve("huge.bin").toFile(), "rw")) {
            file.setLength(limit + 1);
        }

        DownloadResult result = testBed.transfer().download(testBed.download("/huge.bin"), new TransferBed.Collecting(), testBed.control());

        assertThat(result).isEqualTo(new DownloadResult.TooLarge(limit + 1, limit));
    }

    @Test
    @DisplayName("S3-SF-08 D-0013 a server that refuses the read is Refused(PERMISSION_DENIED): not a transport error, the session is kept")
    void permissionDenied() throws IOException {
        TransferBed testBed = start();
        Files.write(root.resolve("secret.bin"), pattern(850));
        testBed.server().injectSftpFault(SftpFault.of(SftpFault.Operation.OPEN_FOR_READ, "secret.bin", SftpFault.Failure.PERMISSION_DENIED));

        DownloadResult result = testBed.transfer().download(testBed.download("/secret.bin"), new TransferBed.Collecting(), testBed.control());

        assertThat(result).isEqualTo(new DownloadResult.Refused(TransferErrorCode.PERMISSION_DENIED, new FileDiagnostic.SftpStatus(3)));
        assertThat(testBed.sessions().discards()).as("a refusal is an answer: the connection is fine").isZero();
    }

    @Test
    @DisplayName("S3-SF-09 D-0013 a connection that is cut while the file is read is Failed(TRANSPORT_LOST), the session is discarded and the read is not tried again")
    void connectionLostWhileReading() throws IOException {
        TransferBed testBed = start();
        Files.write(root.resolve("cut.bin"), pattern(1024 * 1024));
        testBed.server().injectSftpFault(SftpFault.of(SftpFault.Operation.OPEN_FOR_READ, "cut.bin", SftpFault.Failure.CONNECTION_DROPPED).afterBytes(100_000));

        DownloadResult result = testBed.transfer().download(testBed.download("/cut.bin"), new TransferBed.Collecting(), testBed.control());

        assertThat(result).isEqualTo(new DownloadResult.Failed(TransferFailureCause.TRANSPORT_LOST));
        assertThat(testBed.sessions().discards()).isEqualTo(1);
        assertThat(testBed.sessions().leases()).as("no automatic second read").isEqualTo(1);
    }

    @Test
    @DisplayName("S-D0013-01 D-0013 a file read to its last byte, with its size and SHA-256 right, is Fetched when closing the read stream then fails: a close error does not turn a verified read into TRANSPORT_LOST")
    void closeFailureAfterACompleteReadKeepsTheFetchedResult() throws IOException {
        TransferBed testBed = startWith(client -> new SftpDoubles().failingToCloseWhenReading(client, ".bin"));
        byte[] content = pattern(850);
        Files.write(root.resolve("small.bin"), content);
        TransferBed.Collecting sink = new TransferBed.Collecting();

        DownloadResult result = testBed.transfer().download(testBed.download("/small.bin"), sink, testBed.control());

        assertThat(result).as("the 850 bytes arrived and match; only closing the stream failed: %s", result).isInstanceOf(DownloadResult.Fetched.class);
        DownloadResult.Fetched fetched = (DownloadResult.Fetched) result;
        assertThat(fetched.size()).isEqualTo(850);
        assertThat(fetched.sha256()).isEqualTo(testBed.sha256(content));
        assertThat(sink.bytes()).isEqualTo(content);
        assertThat(testBed.sessions().discards()).as("the connection is fine: the session is kept").isZero();
        assertThat(entriesInRoot()).as("a download creates nothing on the target").isEqualTo(1);
    }

    @Test
    @DisplayName("S-D0013-03 D-0013 a close failure that comes with the end of the channel after the last byte is still Failed(TRANSPORT_LOST): the session is discarded and the read is not tried again")
    void closeFailureWithAChannelThatEndedIsTransportLost() throws IOException {
        TransferBed testBed = startWith(client -> new SftpDoubles().channelEndingAfterTheRead(client, ".bin", true, () -> { }));
        Files.write(root.resolve("small.bin"), pattern(850));

        DownloadResult result = testBed.transfer().download(testBed.download("/small.bin"), new TransferBed.Collecting(), testBed.control());

        assertThat(result).isEqualTo(new DownloadResult.Failed(TransferFailureCause.TRANSPORT_LOST));
        assertThat(testBed.sessions().discards()).isEqualTo(1);
        assertThat(testBed.sessions().leases()).as("no automatic second read").isEqualTo(1);
    }

    @Test
    @DisplayName("S-D0013-04 D-0013 a stop, a file that changed and a sink that failed keep their own result when closing the read stream fails as well")
    void closeFailureDoesNotReplaceTheResultOfAnEarlyEnd() throws IOException {
        TransferBed testBed = startWith(client -> new SftpDoubles().failingToCloseWhenReading(client, ".bin"));
        Path moving = root.resolve("moving.bin");
        Files.write(moving, pattern(1024 * 1024));
        Files.write(root.resolve("a.bin"), pattern(1024 * 1024));
        Files.write(root.resolve("sunk.bin"), pattern(500_000));
        CancelSource cancel = new CancelSource();

        DownloadResult stopped = testBed.transfer().download(testBed.download("/a.bin"), new TransferBed.Collecting(cancel::cancel), testBed.control(cancel));
        DownloadResult changed = testBed.transfer().download(testBed.download("/moving.bin"), new TransferBed.Collecting(() -> appendQuietly(moving)), testBed.control());
        DownloadResult sinkFailed = testBed.transfer().download(testBed.download("/sunk.bin"),
            data -> new SinkResult.Failed(new IOException("the artifact store is full")), testBed.control());

        assertThat(stopped).as("a cancel in the middle").isEqualTo(new DownloadResult.Failed(TransferFailureCause.CANCELLED));
        assertThat(changed).as("a file that changed while it was read").isEqualTo(new DownloadResult.Refused(TransferErrorCode.FILE_CHANGED, new FileDiagnostic.None()));
        assertThat(sinkFailed).as("a sink that failed").isEqualTo(new DownloadResult.Failed(TransferFailureCause.LOCAL_IO_FAILED));
        assertThat(testBed.sessions().discards()).as("none of the three lost the connection").isZero();
    }

    @Test
    @DisplayName("S-D0013-05 D-0013 a cancel that comes after the last byte and ends the channel before the look at the file stays Failed(CANCELLED), not TRANSPORT_LOST")
    void cancelAfterTheLastByteKeepsItsCause() throws IOException {
        CancelSource cancel = new CancelSource();
        TransferBed testBed = startWith(client -> new SftpDoubles().channelEndingAfterTheRead(client, ".bin", false, cancel::cancel));
        Files.write(root.resolve("small.bin"), pattern(850));

        DownloadResult result = testBed.transfer().download(testBed.download("/small.bin"), new TransferBed.Collecting(), testBed.control(cancel));

        assertThat(result).as("the channel ended because of the cancel: %s", result).isEqualTo(new DownloadResult.Failed(TransferFailureCause.CANCELLED));
    }

    private TransferBed startWith(UnaryOperator<SftpClient> fault) throws IOException {
        bed = TransferBed.start(root, true, new SftpDoubles.FaultyChannels(fault), ScriptedExecutor.everythingSucceeds(), (target, path) -> new PathPolicyDecision.NotApplicable());
        return bed;
    }

    @Test
    @DisplayName("S-D0013-06 D-0013 a cancel that comes while the path is first looked at and ends the channel stays Failed(CANCELLED), not TRANSPORT_LOST: the live session is kept")
    void cancelDuringTheFirstLookKeepsItsCause() throws IOException {
        CancelSource cancel = new CancelSource();
        TransferBed testBed = startWith(client -> new SftpDoubles().channelEndingAtTheFirstLook(client, cancel::cancel));
        Files.write(root.resolve("small.bin"), pattern(850));
        TransferBed.Collecting sink = new TransferBed.Collecting();

        DownloadResult result = testBed.transfer().download(testBed.download("/small.bin"), sink, testBed.control(cancel));

        assertThat(result).as("the channel ended because of the cancel: %s", result).isEqualTo(new DownloadResult.Failed(TransferFailureCause.CANCELLED));
        assertThat(testBed.sessions().discards()).as("the connection is fine; only the transfer was stopped: the session is kept").isZero();
        assertThat(sink.bytes()).as("not a byte was read").isEmpty();
    }

    @Test
    @DisplayName("S-D0013-08 D-0013 a channel that ends at the first look with nobody having stopped the transfer is still Failed(TRANSPORT_LOST) and the session is discarded")
    void lossAtTheFirstLookWithoutACauseIsTransportLost() throws IOException {
        TransferBed testBed = startWith(client -> new SftpDoubles().channelEndingAtTheFirstLook(client, () -> { }));
        Files.write(root.resolve("small.bin"), pattern(850));

        DownloadResult result = testBed.transfer().download(testBed.download("/small.bin"), new TransferBed.Collecting(), testBed.control());

        assertThat(result).isEqualTo(new DownloadResult.Failed(TransferFailureCause.TRANSPORT_LOST));
        assertThat(testBed.sessions().discards()).isEqualTo(1);
        assertThat(testBed.sessions().leases()).as("no automatic second read").isEqualTo(1);
    }

    @Test
    @DisplayName("RC-B-517 a file that is not there is FILE_NOT_FOUND, a missing directory is PARENT_NOT_FOUND, a directory is UNSAFE_PATH")
    void missingAndWrongKinds() throws IOException {
        TransferBed testBed = start();
        Files.createDirectory(root.resolve("folder"));

        DownloadResult missing = testBed.transfer().download(testBed.download("/missing.bin"), new TransferBed.Collecting(), testBed.control());
        DownloadResult noFolder = testBed.transfer().download(testBed.download("/nowhere/x.bin"), new TransferBed.Collecting(), testBed.control());
        DownloadResult directory = testBed.transfer().download(testBed.download("/folder"), new TransferBed.Collecting(), testBed.control());

        assertThat(missing).isEqualTo(new DownloadResult.Refused(TransferErrorCode.FILE_NOT_FOUND, new FileDiagnostic.None()));
        assertThat(noFolder).isInstanceOf(DownloadResult.Refused.class);
        assertThat(((DownloadResult.Refused) noFolder).code()).isEqualTo(TransferErrorCode.PARENT_NOT_FOUND);
        assertThat(directory).isEqualTo(new DownloadResult.Refused(TransferErrorCode.UNSAFE_PATH, new FileDiagnostic.None()));
    }

    @Test
    @DisplayName("S3-SF-14 a file that changes while it is read is FILE_CHANGED; what the sink took is the caller's to delete")
    void changedWhileReading() throws IOException {
        TransferBed testBed = start();
        Path file = root.resolve("moving.bin");
        Files.write(file, pattern(1024 * 1024));
        TransferBed.Collecting sink = new TransferBed.Collecting(() -> appendQuietly(file));

        DownloadResult result = testBed.transfer().download(testBed.download("/moving.bin"), sink, testBed.control());

        assertThat(result).isEqualTo(new DownloadResult.Refused(TransferErrorCode.FILE_CHANGED, new FileDiagnostic.None()));
    }

    private static void appendQuietly(Path file) {
        try {
            Files.write(file, "more bytes".getBytes(StandardCharsets.UTF_8), StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new IllegalStateException("the test could not change the file", e);
        }
    }

    @Test
    @DisplayName("S3-AG-09 a sink that fails ends the read at once: Failed(LOCAL_IO_FAILED), the server's file is untouched")
    void sinkFails() throws IOException {
        TransferBed testBed = start();
        Files.write(root.resolve("a.bin"), pattern(500_000));

        DownloadResult result = testBed.transfer().download(testBed.download("/a.bin"), data -> new SinkResult.Failed(new IOException("the artifact store is full")), testBed.control());

        assertThat(result).isEqualTo(new DownloadResult.Failed(TransferFailureCause.LOCAL_IO_FAILED));
        assertThat(Files.size(root.resolve("a.bin"))).isEqualTo(500_000);
    }

    @Test
    @DisplayName("S3-EX-03 a cancel asked before the read starts is Failed(CANCELLED); a cancel in the middle ends the read with the same answer")
    void cancel() throws IOException {
        TransferBed testBed = start();
        Files.write(root.resolve("a.bin"), pattern(1024 * 1024));
        CancelSource before = new CancelSource();
        before.cancel();
        CancelSource during = new CancelSource();

        DownloadResult beforeStart = testBed.transfer().download(testBed.download("/a.bin"), new TransferBed.Collecting(), testBed.control(before));
        DownloadResult inTheMiddle = testBed.transfer().download(testBed.download("/a.bin"), new TransferBed.Collecting(during::cancel), testBed.control(during));

        assertThat(beforeStart).isEqualTo(new DownloadResult.Failed(TransferFailureCause.CANCELLED));
        assertThat(inTheMiddle).isEqualTo(new DownloadResult.Failed(TransferFailureCause.CANCELLED));
    }

    @Test
    @DisplayName("S3-SS-02 a session that cannot be had is Failed(CONNECT_FAILED), and a download over a dead server is no unknown outcome")
    void noSession() throws IOException {
        TransferBed testBed = start();
        testBed.server().close();

        DownloadResult result = testBed.transfer().download(testBed.download("/a.bin"), new TransferBed.Collecting(), testBed.control());

        assertThat(result).isEqualTo(new DownloadResult.Failed(TransferFailureCause.CONNECT_FAILED));
    }

    @Test
    @DisplayName("S3-SF-13 a download of a request the type rejects cannot be built: a path with a dot part is no path")
    void downloadRequestNeedsALegalPath() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new DownloadRequest(TransferBed.TARGET, new io.github.jaeseongs95.portal.domain.path.PosixRemotePath("/a/../b"),
            new io.github.jaeseongs95.portal.app.remote.FileAccess.Direct(), new io.github.jaeseongs95.portal.app.remote.PathPolicyBinding.NoRootPolicy(), TransferLimits.defaults()))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
