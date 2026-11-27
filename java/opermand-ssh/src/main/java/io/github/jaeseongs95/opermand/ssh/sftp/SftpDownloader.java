package io.github.jaeseongs95.opermand.ssh.sftp;

import io.github.jaeseongs95.opermand.app.remote.DownloadRequest;
import io.github.jaeseongs95.opermand.app.remote.DownloadResult;
import io.github.jaeseongs95.opermand.app.remote.FileDiagnostic;
import io.github.jaeseongs95.opermand.app.remote.PayloadSink;
import io.github.jaeseongs95.opermand.app.remote.SinkResult;
import io.github.jaeseongs95.opermand.app.remote.TransferFailureCause;
import io.github.jaeseongs95.opermand.domain.error.TransferErrorCode;
import io.github.jaeseongs95.opermand.domain.path.WindowsRemotePath;
import java.io.IOException;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Optional;
import org.apache.sshd.sftp.client.SftpClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The download over an open SFTP channel (stage 3 design 4.1 and 4.3). It only reads: it creates, renames and removes nothing on the target (the 2.x helper copied the
 * file into a staging folder first, which 3.0 does not have). So no download result leaves the target changed or unknown on its side, and a download that is cut short
 * is a failed read, never an {@code UNKNOWN} outcome (decision D3-17, defect D-0013: an 850-byte file that fails to arrive is a failure with its reason).
 *
 * <p>The order: the path is looked at ({@link RemotePathChecker}: no link, a regular file); the size comes from that look and above the limit the answer is
 * {@code TooLarge} before a byte is read; the size is told to the sink ({@link PayloadSink#expect}) before the first block; the bytes flow to the {@link PayloadSink} in blocks while the SHA-256 and the size are counted; the file is looked at again
 * afterwards. A size read that is not the size announced, or a file whose size, modification time or mode changed meanwhile, is {@code FILE_CHANGED} (the 2.x helper also
 * compared the inode and the change time, which SFTP attributes do not carry). A sink that fails ends the read at once.
 *
 * <p>What the failures come to: a status the server answered with (permission denied, no such file) is {@code Refused} with its own code; a lost connection is
 * {@code Failed(TRANSPORT_LOST)}; the time limit and the cancel are {@code Failed(DEADLINE)} and {@code Failed(CANCELLED)}. The adapter never reads the file again. A failure of {@code close()} on the read stream is only logged: it comes after the
 * last byte and does not change what was read (size and SHA-256) or what the second look at the file found. A verdict of {@code TRANSPORT_LOST} is logged at WARN with the
 * exception that caused it (defect D-0013), because the result does not carry it; a stop by the time limit or the cancel is not a lost connection and is not logged as one.
 */
@Component
public class SftpDownloader {

    private static final Logger LOG = LoggerFactory.getLogger(SftpDownloader.class);
    /** 32 KiB per SFTP packet: fits every SFTP v3 server's maximum packet payload while reading over a verified open handle. */
    private static final int BLOCK_BYTES = 32 * 1024;

    private final RemotePathChecker checker;
    private final SftpFailureClassifier classifier;
    private final SftpChannels channels;

    /**
     * @param checker looks at the path before and after the read
     * @param classifier the meaning of an SFTP failure
     * @param channels closes the stream of the read without changing what the read came to
     */
    public SftpDownloader(RemotePathChecker checker, SftpFailureClassifier classifier, SftpChannels channels) {
        this.checker = Objects.requireNonNull(checker, "checker");
        this.classifier = Objects.requireNonNull(classifier, "classifier");
        this.channels = Objects.requireNonNull(channels, "channels");
    }

    /**
     * Reads the file into the sink.
     *
     * @param client the SFTP client of the leased session
     * @param lease the lease; it is discarded when the connection is lost
     * @param watch the time limit and the cancel of this transfer
     * @param request the file to read
     * @param sink where the bytes go
     * @return how the download ended
     */
    DownloadResult download(SftpClient client, SessionLease lease, TransferWatch watch, DownloadRequest request, PayloadSink sink) {
        SftpLocation location = new SftpLocation(request.path());
        PathInspection before = checker.inspect(client, location);
        return switch (before) {
            case PathInspection.Lost lost -> {
                // A time limit or a cancel that fires during the first look closes the channel, and the look then fails: the cause stays what stopped the transfer and the
                // live session is kept, as in the look after the read and in failedReading. Only a loss that nobody caused is a lost connection (case S-D0013-06).
                yield watch.fired().<DownloadResult>map(DownloadResult.Failed::new).orElseGet(() -> connectionLost(lease, lost.cause()));
            }
            case PathInspection.Refused refused -> new DownloadResult.Refused(refused.code(), refused.diagnostic());
            case PathInspection.Absent absent -> new DownloadResult.Refused(TransferErrorCode.FILE_NOT_FOUND, new FileDiagnostic.None());
            case PathInspection.Present present -> read(client, lease, watch, request, sink, location, present.state());
        };
    }

    private DownloadResult read(SftpClient client, SessionLease lease, TransferWatch watch, DownloadRequest request, PayloadSink sink, SftpLocation location,
                                LeafState announced) {
        long limit = request.limits().maxFileSize().toBytes();
        if (announced.size() > limit) {
            return new DownloadResult.TooLarge(announced.size(), limit);
        }
        return watch.stopRequested().<DownloadResult>map(DownloadResult.Failed::new)
            .orElseGet(() -> readUnlessStopped(client, lease, watch, request, sink, location, announced));
    }

    private DownloadResult readUnlessStopped(SftpClient client, SessionLease lease, TransferWatch watch, DownloadRequest request, PayloadSink sink, SftpLocation location,
                                             LeafState announced) {
        long limit = request.limits().maxFileSize().toBytes();
        StreamDigest digest = StreamDigest.sha256();
        DownloadResult ended;
        try {
            // Open the read handle and verify its descriptor with fstat before announcing to the sink or reading a single byte (defect SSH_PORTAL-D-0082): if the path was
            // swapped to another file or symlink target between lstat and open, fstat catches the mismatch before any sensitive bytes leave the server. The handle is closed
            // via closeQuietly so a close error after a verified read does not replace Fetched (defect D-0013, case S-D0013-01).
            SftpClient.CloseableHandle handle = client.open(location.file(), EnumSet.of(SftpClient.OpenMode.Read));
            try {
                SftpClient.Attributes opened = client.stat(handle);
                if (FileKind.of(opened) != FileKind.REGULAR) {
                    return new DownloadResult.Refused(TransferErrorCode.UNSAFE_PATH, new FileDiagnostic.None());
                }
                if (opened.getSize() > limit) {
                    return new DownloadResult.TooLarge(opened.getSize(), limit);
                }
                LeafState openedState = LeafState.of(opened);
                if (!sameFile(announced, openedState, request.path() instanceof WindowsRemotePath)) {
                    LOG.debug("Download handle fstat disagreed with lstat: announced={}, opened={}", announced, openedState);
                    return changed();
                }
                // The size the target announced goes to the sink before the first block, so the use case can hold only that much of its budget (defect D-0012).
                if (sink.expect(announced.size()) instanceof SinkResult.Failed) {
                    return new DownloadResult.Failed(TransferFailureCause.LOCAL_IO_FAILED);
                }
                ended = pump(client, handle, watch, announced, sink, digest);
                if (ended == null) {
                    if (digest.count() != announced.size()) {
                        ended = changed();
                    } else {
                        SftpClient.Attributes afterRead = client.stat(handle);
                        if (FileKind.of(afterRead) != FileKind.REGULAR || !LeafState.of(afterRead).equals(openedState)) {
                            ended = changed();
                        }
                    }
                }
            } finally {
                channels.closeQuietly(handle);
            }
        } catch (IOException e) {
            return failedReading(lease, watch, e);
        }
        if (ended != null) {
            return ended;
        }
        PathInspection after = checker.inspect(client, location);
        return switch (after) {
            case PathInspection.Present present -> present.state().equals(announced)
                ? new DownloadResult.Fetched(digest.count(), digest.finishSha256())
                : changed();
            case PathInspection.Absent absent -> changed();
            case PathInspection.Refused refused -> new DownloadResult.Refused(refused.code(), refused.diagnostic());
            case PathInspection.Lost lost -> {
                // A time limit or a cancel that came after the last byte closes the channel, and the look at the file then fails: the cause stays what stopped the transfer
                // (the same order as in failedReading), and only a loss that nobody caused is a lost connection (case S-D0013-05).
                yield watch.fired().<DownloadResult>map(DownloadResult.Failed::new).orElseGet(() -> connectionLost(lease, lost.cause()));
            }
        };
    }

    /**
     * Reads the pinned handle to its end into the sink and the digest.
     *
     * @return the result when the read ended before the end of the file (a stop, a changed file, a failing sink), or {@code null} when the whole file was read
     * @throws IOException when a read of the handle fails
     */
    private DownloadResult pump(SftpClient client, SftpClient.Handle handle, TransferWatch watch, LeafState announced, PayloadSink sink, StreamDigest digest)
        throws IOException {
        byte[] block = new byte[BLOCK_BYTES];
        long offset = 0;
        while (true) {
            int read = client.read(handle, offset, block, 0, block.length);
            if (read < 0) {
                break;
            }
            Optional<TransferFailureCause> stopped = watch.stopRequested();
            if (stopped.isPresent()) {
                return new DownloadResult.Failed(stopped.orElseThrow());
            }
            offset += read;
            digest.update(block, 0, read);
            if (digest.count() > announced.size()) {
                return changed(); // the file grew while it was read
            }
            if (sink.append(Arrays.copyOf(block, read)) instanceof SinkResult.Failed) {
                return new DownloadResult.Failed(TransferFailureCause.LOCAL_IO_FAILED);
            }
        }
        return null;
    }

    private DownloadResult failedReading(SessionLease lease, TransferWatch watch, IOException cause) {
        return watch.fired().<DownloadResult>map(DownloadResult.Failed::new).orElseGet(() -> switch (classifier.verdict(cause, SftpFailureClassifier.Site.TARGET_FILE)) {
            case SftpFailureClassifier.Verdict.Lost lost -> connectionLost(lease, cause);
            case SftpFailureClassifier.Verdict.Refusal refusal -> new DownloadResult.Refused(refusal.code(), refusal.diagnostic());
        });
    }

    /**
     * The one place where a download is judged to have lost its connection: the session is discarded, and the exception that showed the loss is logged with its stack and kind,
     * because the result ({@code Failed(TRANSPORT_LOST)}) carries no cause and the next loss of unknown origin could not be diagnosed otherwise (defect D-0013, cases S-D0013-27
     * to S-D0013-29). The message names no remote path, which the result never repeats either, and no local path or credential value (D-0137).
     */
    private DownloadResult connectionLost(SessionLease lease, IOException cause) {
        LOG.warn("A download was judged a lost connection (TRANSPORT_LOST); the target was only read and the session is discarded", cause);
        lease.discard();
        return new DownloadResult.Failed(TransferFailureCause.TRANSPORT_LOST);
    }

    /**
     * Compares pre-open {@code lstat} metadata with opened-handle {@code fstat} metadata. On POSIX servers every field matches identically on the same inode; on Windows
     * targets ({@link WindowsRemotePath}), Win32-OpenSSH synthesizes POSIX mode/uid/gid differently between {@code w32_lstat} and {@code w32_fstat}, so size and
     * modification time are compared across the two calls while same-syscall comparisons ({@code fstat}/{@code fstat} and {@code lstat}/{@code lstat}) still compare all fields.
     */
    private static boolean sameFile(LeafState announced, LeafState opened, boolean windowsPath) {
        if (windowsPath) {
            return opened.size() == announced.size() && opened.modifiedMillis() == announced.modifiedMillis();
        }
        return opened.equals(announced);
    }

    private DownloadResult changed() {
        return new DownloadResult.Refused(TransferErrorCode.FILE_CHANGED, new FileDiagnostic.None());
    }
}
