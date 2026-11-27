package io.github.jaeseongs95.opermand.ssh.sftp;

import io.github.jaeseongs95.opermand.app.remote.FileDiagnostic;
import io.github.jaeseongs95.opermand.app.remote.PayloadSource;
import io.github.jaeseongs95.opermand.app.remote.TransferFailureCause;
import io.github.jaeseongs95.opermand.app.remote.UnconfirmedCause;
import io.github.jaeseongs95.opermand.app.remote.UploadRequest;
import io.github.jaeseongs95.opermand.app.remote.UploadResult;
import io.github.jaeseongs95.opermand.app.storage.PayloadRead;
import io.github.jaeseongs95.opermand.domain.error.TransferErrorCode;
import io.github.jaeseongs95.opermand.domain.id.Digest;
import io.github.jaeseongs95.opermand.domain.operation.CleanupState;
import io.github.jaeseongs95.opermand.domain.operation.OverwriteMode;
import io.github.jaeseongs95.opermand.domain.path.WindowsRemotePath;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Optional;
import org.apache.sshd.sftp.client.SftpClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The upload over an open SFTP channel (stage 3 design 4.2 steps 1 to 3; the replacement is {@link AtomicReplacer}'s). The order is what makes the result honest:
 * <ol>
 *   <li>the target is looked at ({@link RemotePathChecker}): a link, a directory, a missing parent or a file that must not be replaced end the upload here, with the
 *       target untouched;</li>
 *   <li>the temporary file {@code .opermand-<hex>.part} is made next to the target with an exclusive create, so it can never be somebody else's file, and gets
 *       the mode {@code 0600} (a path in Windows syntax has no mode: its SFTP server does not support {@code chmod});</li>
 *   <li>the bytes flow from the {@link PayloadSource} to the server in blocks, and the SHA-256 and the size are counted on the way: a stream that is not the announced
 *       file ends the upload ({@code FILE_CHANGED}), the temporary file is removed;</li>
 *   <li>the temporary file is read back and hashed again (decision D3-11, on by default: the files are at most 64 MiB), so a byte changed on the way or on the
 *       server's disk is found before the file is put in place;</li>
 *   <li>the {@link AtomicReplacer} puts it in place and reads it back.</li>
 * </ol>
 * A stop from outside (the time limit, the owner's cancel) before the replacement removes the temporary file with a channel of its own and is reported as
 * {@code NotDispatched} when that is confirmed, and as {@code Unconfirmed(CleanupUnconfirmed)} when it is not. A lost connection after the temporary file was created is
 * {@code Unconfirmed(TransportLost)} and the session is discarded: nothing is sent again.
 */
@Component
public class SftpUploader {

    private static final Logger LOG = LoggerFactory.getLogger(SftpUploader.class);
    /** 32 KiB per SFTP packet: fits every SFTP v3 server's maximum packet payload while streaming directly over an open handle. */
    private static final int BLOCK_BYTES = 32 * 1024;
    /** {@code 0600}: read and write for the owner, nothing for anybody else. */
    private static final int OWNER_ONLY = 0600;

    private final RemotePathChecker checker;
    private final AtomicReplacer replacer;
    private final SftpFailureClassifier classifier;
    private final SftpChannels channels;
    private final SpaceProbe spaceProbe;

    /**
     * @param checker looks at the path before anything is written
     * @param replacer puts the temporary file in place
     * @param classifier the meaning of an SFTP failure
     * @param channels opens a channel of its own for the clean-up after a stop
     * @param spaceProbe tells a full disk from a plain failure
     */
    public SftpUploader(RemotePathChecker checker, AtomicReplacer replacer, SftpFailureClassifier classifier, SftpChannels channels, SpaceProbe spaceProbe) {
        this.checker = Objects.requireNonNull(checker, "checker");
        this.replacer = Objects.requireNonNull(replacer, "replacer");
        this.classifier = Objects.requireNonNull(classifier, "classifier");
        this.channels = Objects.requireNonNull(channels, "channels");
        this.spaceProbe = Objects.requireNonNull(spaceProbe, "spaceProbe");
    }

    /** What copying the bytes (or reading them back) came to. */
    private sealed interface Copy permits Copy.Copied, Copy.Mismatch, Copy.Stopped, Copy.LocalFailure, Copy.RemoteFailure {
        record Copied(Digest sha256, long bytes) implements Copy {
        }

        /** The stream is not the announced file: another size than announced. */
        record Mismatch() implements Copy {
        }

        record Stopped(TransferFailureCause cause) implements Copy {
        }

        /** The local side failed: the artifact cannot be read. */
        record LocalFailure() implements Copy {
        }

        record RemoteFailure(IOException cause) implements Copy {
        }
    }

    /**
     * Writes the file and puts it in place.
     *
     * @param client the SFTP client of the leased session
     * @param lease the lease; it is discarded when the connection is lost
     * @param watch the time limit and the cancel of this transfer
     * @param request the file to publish
     * @param source the bytes
     * @return how the upload ended
     */
    UploadResult upload(SftpClient client, SessionLease lease, TransferWatch watch, UploadRequest request, PayloadSource source) {
        SftpLocation location = new SftpLocation(request.path());
        PathInspection original = checker.inspect(client, location);
        return switch (original) {
            case PathInspection.Lost lost -> {
                // Nothing was written yet. A time limit or a cancel that fires while the channel is opened or the path is first looked at closes the channel, and the look
                // then fails as a lost connection: the cause stays what stopped the upload and the live session is kept. Only a loss that nobody caused is a lost connection
                // (cases S-D0013-10 and S-D0013-11).
                if (watch.fired().isPresent()) {
                    yield new UploadResult.NotDispatched(watch.fired().orElseThrow());
                }
                LOG.warn("An upload was judged a lost connection before anything was written (TRANSPORT_LOST); the session is discarded", lost.cause());
                lease.discard();
                yield new UploadResult.NotDispatched(TransferFailureCause.TRANSPORT_LOST);
            }
            case PathInspection.Refused refused -> new UploadResult.Refused(refused.code(), refused.diagnostic(), CleanupState.COMPLETE);
            case PathInspection.Absent absent -> write(client, lease, watch, request, source, location, original);
            case PathInspection.Present present -> request.overwrite() == OverwriteMode.FAIL_IF_EXISTS
                ? new UploadResult.Refused(TransferErrorCode.DESTINATION_EXISTS, new FileDiagnostic.None(), CleanupState.COMPLETE)
                : write(client, lease, watch, request, source, location, original);
        };
    }

    /** Outcome of staging the temporary file over a single pinned handle (SSH_PORTAL-D-0082), evaluated after the handle is closed. */
    private sealed interface Staging permits Staging.Ready, Staging.CopyIssue, Staging.RemoteError {
        record Ready() implements Staging {
        }

        record CopyIssue(Copy copy) implements Staging {
        }

        record RemoteError(IOException cause, SftpFailureClassifier.Site site) implements Staging {
        }
    }

    private UploadResult write(SftpClient client, SessionLease lease, TransferWatch watch, UploadRequest request, PayloadSource source, SftpLocation location,
                               PathInspection original) {
        Optional<TransferFailureCause> stopped = watch.stopRequested();
        if (stopped.isPresent()) {
            return new UploadResult.NotDispatched(stopped.orElseThrow());
        }
        String tempName = replacer.newTempName();
        String tempPath = location.sibling(tempName);
        Staging staged = stageOverSingleHandle(client, watch, request, source, tempPath, original);
        switch (staged) {
            case Staging.Ready ready -> {
                // Handle is closed (required before rename on Windows) and every temporary-file step ran on that one FD.
            }
            case Staging.CopyIssue issue -> {
                return check(client, lease, watch, location, tempName, tempPath, request, issue.copy())
                    .orElseThrow(() -> new IllegalStateException("Expected staging copy issue to produce an upload result"));
            }
            case Staging.RemoteError error -> {
                return failedRemotely(client, lease, watch, location, tempName, tempPath, error.cause(), error.site());
            }
        }
        // From here the replacement runs to its end: a cancel or the time limit no longer stops it half way.
        watch.disarm();
        return replacer.replace(new ReplaceJob(client, location, tempName, original, request.overwrite(), request.sizeBytes(), request.sha256()));
    }

    // ---- the temporary file ----

    /**
     * Creates, protects ({@code 0600}), writes, reads back, and applies inherited mode/owner on one exclusively created SFTP handle ({@code O_RDWR|O_CREAT|O_EXCL}),
     * closing the handle before returning. Never re-opens {@code tempPath} by path and never invokes path-based {@code chown}/{@code chmod} (defect SSH_PORTAL-D-0082).
     */
    private Staging stageOverSingleHandle(SftpClient client, TransferWatch watch, UploadRequest request, PayloadSource source, String tempPath,
                                          PathInspection original) {
        SftpClient.CloseableHandle handle;
        try {
            handle = client.open(tempPath,
                EnumSet.of(SftpClient.OpenMode.Read, SftpClient.OpenMode.Write, SftpClient.OpenMode.Create, SftpClient.OpenMode.Exclusive));
        } catch (IOException e) {
            return new Staging.RemoteError(e, SftpFailureClassifier.Site.NEW_FILE);
        }
        try {
            if (!(request.path() instanceof WindowsRemotePath)) {
                try {
                    protect(client, handle);
                } catch (IOException e) {
                    return new Staging.RemoteError(e, SftpFailureClassifier.Site.NEW_FILE);
                }
            }
            Copy written = copy(client, handle, watch, source, request);
            if (!matchesExpected(written, request)) {
                return new Staging.CopyIssue(written);
            }
            Copy verified = readBack(client, handle, watch);
            if (!matchesExpected(verified, request)) {
                return new Staging.CopyIssue(verified);
            }
            Optional<IOException> modeFailure = inheritMode(client, handle, request, original);
            if (modeFailure.isPresent()) {
                return new Staging.RemoteError(modeFailure.orElseThrow(), SftpFailureClassifier.Site.OWN_FILE);
            }
            return new Staging.Ready();
        } finally {
            channels.closeQuietly(handle);
        }
    }

    private boolean matchesExpected(Copy copy, UploadRequest request) {
        return copy instanceof Copy.Copied copied && copied.bytes() == request.sizeBytes() && copied.sha256().equals(request.sha256());
    }

    /**
     * Sets the mode of a new temporary file to {@code 0600}. Every failure ends the upload (defect SSH_PORTAL-D-0056): a file that other accounts may read must not
     * receive the content, and a server that could not set the mode of the file it just made gives no proof that the file is private. (This method used to go on after the
     * plain failure and the unknown status, with the mode the umask of the server gives, and only a debug line said so.) The temporary file is removed by the caller's
     * clean-up and the result is a refusal that names the cause of the server's answer.
     */
    private void protect(SftpClient client, SftpClient.Handle handle) throws IOException {
        client.setStat(handle, new SftpClient.Attributes().perms(OWNER_ONLY));
    }

    /**
     * A file that is replaced keeps the permission bits (and under elevation, numeric owner/group) it had: applied on the pinned temporary handle via {@code SSH_FXP_FSETSTAT}
     * before the file goes in place (defect SSH_PORTAL-D-0082). A path in Windows syntax has no POSIX mode.
     */
    private Optional<IOException> inheritMode(SftpClient client, SftpClient.Handle handle, UploadRequest request, PathInspection original) {
        if (!(original instanceof PathInspection.Present present) || request.path() instanceof WindowsRemotePath) {
            return Optional.empty();
        }
        try {
            SftpClient.Attributes attributes = new SftpClient.Attributes().perms(present.state().permissions() & 0777);
            if (request.access() instanceof io.github.jaeseongs95.opermand.app.remote.FileAccess.Elevated) {
                // An elevated server (sudo sftp-server) is root, whose new file belongs to root: the owner and group the file had are given back on the handle (only root may).
                attributes.owner(present.state().userId(), present.state().groupId());
            }
            client.setStat(handle, attributes);
            return Optional.empty();
        } catch (IOException e) {
            SftpFailure.Kind kind = classifier.classify(e).kind();
            if (kind == SftpFailure.Kind.PLAIN_FAILURE || kind == SftpFailure.Kind.OTHER) {
                // The new file is already private (protect ran first), so what is lost is that the replaced file's wider mode is not given back; the cause is logged, not hidden.
                LOG.warn("The server did not give the mode (and owner) of the replaced file to the new file; the new file keeps the private mode it was made with", e);
                return Optional.empty();
            }
            return Optional.of(e);
        }
    }

    // ---- copying ----

    private Copy copy(SftpClient client, SftpClient.Handle handle, TransferWatch watch, PayloadSource source, UploadRequest request) {
        StreamDigest digest = StreamDigest.sha256();
        byte[] block = new byte[BLOCK_BYTES];
        long offset = 0;
        try (PayloadRead payload = source.open()) {
            if (payload.size() != request.sizeBytes()) {
                return new Copy.Mismatch();
            }
            ByteBuffer buffer = ByteBuffer.wrap(block);
            while (true) {
                buffer.clear();
                int read;
                try {
                    read = payload.channel().read(buffer);
                } catch (IOException e) {
                    LOG.warn("The stored file of an upload could not be read", e);
                    return new Copy.LocalFailure();
                }
                if (read < 0) {
                    break;
                }
                Optional<TransferFailureCause> stopped = watch.stopRequested();
                if (stopped.isPresent()) {
                    return new Copy.Stopped(stopped.orElseThrow());
                }
                digest.update(block, 0, read);
                if (digest.count() > request.sizeBytes()) {
                    return new Copy.Mismatch();
                }
                client.write(handle, offset, block, 0, read);
                offset += read;
            }
            return new Copy.Copied(digest.finishSha256(), digest.count());
        } catch (IOException e) {
            return new Copy.RemoteFailure(e);
        }
    }

    private Copy readBack(SftpClient client, SftpClient.Handle handle, TransferWatch watch) {
        StreamDigest digest = StreamDigest.sha256();
        byte[] block = new byte[BLOCK_BYTES];
        long offset = 0;
        try {
            while (true) {
                int read = client.read(handle, offset, block, 0, block.length);
                if (read < 0) {
                    break;
                }
                Optional<TransferFailureCause> stopped = watch.stopRequested();
                if (stopped.isPresent()) {
                    return new Copy.Stopped(stopped.orElseThrow());
                }
                digest.update(block, 0, read);
                offset += read;
            }
            return new Copy.Copied(digest.finishSha256(), digest.count());
        } catch (IOException e) {
            return new Copy.RemoteFailure(e);
        }
    }

    // ---- what the copy came to ----

    /** Turns a finished copy into the end of the upload, or says that the upload goes on (empty). */
    private Optional<UploadResult> check(SftpClient client, SessionLease lease, TransferWatch watch, SftpLocation location, String tempName, String tempPath,
                                         UploadRequest request, Copy copy) {
        return switch (copy) {
            case Copy.Copied copied -> copied.bytes() == request.sizeBytes() && copied.sha256().equals(request.sha256())
                ? Optional.empty()
                : Optional.of(cleanedRefusal(client, tempName, tempPath, TransferErrorCode.FILE_CHANGED, new FileDiagnostic.None()));
            case Copy.Mismatch mismatch -> Optional.of(cleanedRefusal(client, tempName, tempPath, TransferErrorCode.FILE_CHANGED, new FileDiagnostic.None()));
            case Copy.Stopped stopped -> Optional.of(stopped(lease, tempName, tempPath, stopped.cause()));
            case Copy.LocalFailure local -> Optional.of(stopped(lease, tempName, tempPath, TransferFailureCause.LOCAL_IO_FAILED));
            case Copy.RemoteFailure remote -> Optional.of(failedRemotely(client, lease, watch, location, tempName, tempPath, remote.cause(), SftpFailureClassifier.Site.OWN_FILE));
        };
    }

    /**
     * A request the server refused or a connection that broke while the temporary file was being made, written or read. A time limit or a cancel that fired closed the channel
     * and made the request fail: that is the stop, not a lost connection, so the cause stays what stopped the upload, the live session is kept and the temporary file is
     * removed on a channel of its own (defect SSH_PORTAL-D-0089; the stage of the temporary file and of the mode used to report {@code Unconfirmed(TransportLost)} and
     * to discard the session).
     */
    private UploadResult failedRemotely(SftpClient client, SessionLease lease, TransferWatch watch, SftpLocation location, String tempName, String tempPath, IOException cause,
                                        SftpFailureClassifier.Site site) {
        if (watch.fired().isPresent()) {
            LOG.debug("The upload was stopped while its temporary file was being made or written", cause);
            return stopped(lease, tempName, tempPath, watch.fired().orElseThrow());
        }
        SftpFailure failure = classifier.classify(cause);
        return switch (classifier.verdict(failure, site)) {
            case SftpFailureClassifier.Verdict.Lost lost -> {
                LOG.warn("An upload was judged a lost connection after its temporary file was begun (TRANSPORT_LOST, outcome unconfirmed); the session is discarded", cause);
                lease.discard();
                yield new UploadResult.Unconfirmed(new UnconfirmedCause.TransportLost(), CleanupState.INCOMPLETE);
            }
            case SftpFailureClassifier.Verdict.Refusal refusal -> {
                boolean noSpace = failure.kind() == SftpFailure.Kind.PLAIN_FAILURE && spaceProbe.exhausted(client, location.directory());
                yield noSpace
                    ? cleanedRefusal(client, tempName, tempPath, TransferErrorCode.STAGING_UNAVAILABLE, new FileDiagnostic.NoSpace())
                    : cleanedRefusal(client, tempName, tempPath, refusal.code(), refusal.diagnostic());
            }
        };
    }

    /** The upload is refused and the target is unchanged: the temporary file is removed, and the result says whether that is confirmed. */
    private UploadResult cleanedRefusal(SftpClient client, String tempName, String tempPath, TransferErrorCode code, FileDiagnostic diagnostic) {
        if (replacer.remove(client, tempPath) == CleanupState.COMPLETE) {
            return new UploadResult.Refused(code, diagnostic, CleanupState.COMPLETE);
        }
        return new UploadResult.Unconfirmed(new UnconfirmedCause.CleanupUnconfirmed(tempName), CleanupState.INCOMPLETE);
    }

    /** A stop from outside: the temporary file is removed with a channel of its own, because the one the upload used was closed to stop it. */
    private UploadResult stopped(SessionLease lease, String tempName, String tempPath, TransferFailureCause cause) {
        CleanupState cleanup = removeWithFreshChannel(lease, tempPath);
        return cleanup == CleanupState.COMPLETE
            ? new UploadResult.NotDispatched(cause)
            : new UploadResult.Unconfirmed(new UnconfirmedCause.CleanupUnconfirmed(tempName), CleanupState.INCOMPLETE);
    }

    private CleanupState removeWithFreshChannel(SessionLease lease, String tempPath) {
        SftpClient fresh;
        try {
            fresh = channels.open(lease.session());
        } catch (IOException e) {
            LOG.warn("A channel for removing the temporary file of a stopped upload could not be opened; the file may remain", e);
            return CleanupState.INCOMPLETE;
        }
        try {
            return replacer.remove(fresh, tempPath);
        } finally {
            channels.closeQuietly(fresh);
        }
    }
}
