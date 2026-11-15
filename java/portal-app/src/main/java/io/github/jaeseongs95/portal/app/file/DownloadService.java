package io.github.jaeseongs95.portal.app.file;

import java.time.Clock;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import io.github.jaeseongs95.portal.app.artifact.ArtifactDestination;
import io.github.jaeseongs95.portal.app.artifact.ArtifactDirection;
import io.github.jaeseongs95.portal.app.artifact.ArtifactOrigin;
import io.github.jaeseongs95.portal.app.artifact.ArtifactPayloadStore;
import io.github.jaeseongs95.portal.app.artifact.ArtifactRecord;
import io.github.jaeseongs95.portal.app.artifact.ArtifactRefusal;
import io.github.jaeseongs95.portal.app.artifact.ArtifactRegistry;
import io.github.jaeseongs95.portal.app.artifact.ArtifactReservation;
import io.github.jaeseongs95.portal.app.artifact.ArtifactRetention;
import io.github.jaeseongs95.portal.app.artifact.ArtifactSink;
import io.github.jaeseongs95.portal.app.artifact.ArtifactSpec;
import io.github.jaeseongs95.portal.app.artifact.BindingKind;
import io.github.jaeseongs95.portal.app.artifact.ReleaseReason;
import io.github.jaeseongs95.portal.app.artifact.StagingTimes;
import io.github.jaeseongs95.portal.app.remote.DownloadRequest;
import io.github.jaeseongs95.portal.app.remote.DownloadResult;
import io.github.jaeseongs95.portal.app.remote.ExecutionControl;
import io.github.jaeseongs95.portal.app.remote.FileAccess;
import io.github.jaeseongs95.portal.app.remote.PathPolicyBinding;
import io.github.jaeseongs95.portal.app.remote.RemoteFileTransfer;
import io.github.jaeseongs95.portal.app.remote.RemoteTarget;
import io.github.jaeseongs95.portal.app.remote.SinkResult;
import io.github.jaeseongs95.portal.app.remote.TransferFailureCause;
import io.github.jaeseongs95.portal.app.remote.TransferLimits;
import io.github.jaeseongs95.portal.app.target.TargetPlatform;
import io.github.jaeseongs95.portal.domain.error.RequestRefusedException;
import io.github.jaeseongs95.portal.domain.file.FileBinding;
import io.github.jaeseongs95.portal.domain.file.FileName;
import io.github.jaeseongs95.portal.domain.id.Digest;
import io.github.jaeseongs95.portal.domain.id.Digester;
import io.github.jaeseongs95.portal.domain.operation.DownloadInput;
import io.github.jaeseongs95.portal.domain.operation.Elevation;
import io.github.jaeseongs95.portal.domain.operation.OverwriteMode;
import io.github.jaeseongs95.portal.domain.operation.UploadProtocol;
import io.github.jaeseongs95.portal.domain.target.AccessMode;
import io.github.jaeseongs95.portal.domain.target.ProfileAlias;
import io.github.jaeseongs95.portal.domain.id.ArtifactId;

/**
 * Reads a file from a target into a new artifact and says how it ended (design 4.1, defect D-0013, decisions D3-17 and D3-03). The order is the one 2.x had and design 5.2
 * fixes: an artifact row is reserved first with no bytes, the adapter looks at the file and announces its size, and the budget and the disk judge that size (D-0012), then the target is read into the
 * artifact, then the stored bytes are checked against what the adapter confirmed, then the artifact is {@code READY} with its real size.
 *
 * <p>Every way to end without a verified artifact gives the reserved bytes back and answers an outcome that is {@code NOT_DISPATCHED}, needs no reconciliation and is
 * {@code FAILED}: a download only reads, so the target cannot be in doubt (D3-17). A size that the budget or the disk refused (before the first byte is read) is the same outcome with the code
 * {@code ARTIFACT_QUOTA} or {@code ARTIFACT_STORAGE_FULL} and the numbers behind it, at stage {@code OPERATION} and {@code NOT_STARTED} (D-0012). A refusal of the artifact store at the reservation is the same outcome, with the store's own code and the sentence of
 * {@link TransferOutcomes#portalFailure}; a failure to write the artifact is the same outcome with the code {@code LOCAL_IO_FAILED} and the sentence of
 * {@link TransferOutcomes#localFailure}. A runtime exception (of the registry, or of the adapter: the dispatch guard's {@code DispatchRefusedException}, a
 * {@code RequestRefusedException} of a step of the adapter such as the Windows command wrapper of the link probe, or an error of the code) gives the reserved artifact back and
 * goes on to {@code DownloadOperationRunner}, which settles it once: a refusal as refused before dispatch, anything else as a local failure of the portal, never as an unknown
 * outcome, because the target was only read (defect D-0013, cases S-D0013-24 to S-D0013-26 and S-D0013-33).
 *
 * <p>The adapter hashes the bytes it read and the use case hashes the bytes it stored; a file whose two digests or sizes differ is not delivered (cases RC-A-182, RC-A-183).
 */
@Service
public class DownloadService {

    private static final Logger LOG = LoggerFactory.getLogger(DownloadService.class);

    private final ArtifactRegistry registry;
    private final ArtifactPayloadStore payloads;
    private final RemoteFileTransfer transfer;
    private final TransferSupport support;
    private final TransferOutcomes outcomes;
    private final TransferLimits limits;
    private final StagingTimes times;
    private final Digester digester;
    private final Clock clock;

    public DownloadService(ArtifactRegistry registry, ArtifactPayloadStore payloads, RemoteFileTransfer transfer, TransferSupport support, TransferOutcomes outcomes,
                           TransferLimits limits, StagingTimes times, Digester digester, Clock clock) {
        this.registry = registry;
        this.payloads = payloads;
        this.transfer = transfer;
        this.support = support;
        this.outcomes = outcomes;
        this.limits = limits;
        this.times = times;
        this.digester = digester;
        this.clock = clock;
    }

    public DownloadExecution download(DownloadJob job, ExecutionControl control) {
        Objects.requireNonNull(job, "job");
        Objects.requireNonNull(control, "control");
        DownloadInput input = job.input();
        TargetPlatform platform = support.platformOf(job.target());
        FileAccess access = support.accessOf(platform, input.elevation());
        // A download reads with the read profile; an elevated one needs the profile that may write (2.x transfers.mjs download).
        AccessMode mode = input.elevation() == Elevation.SUDO ? AccessMode.READ_WRITE : AccessMode.READ_ONLY;
        ProfileAlias profile = mode == AccessMode.READ_ONLY ? job.target().target().readProfile() : job.target().target().writeProfile();
        RemoteTarget remote = new RemoteTarget(job.target().target().id(), job.approvedRevision(), profile, mode);
        PathPolicyBinding pathPolicy = platform.pathPolicy(job.target().target());

        ArtifactReservation reservation;
        try {
            reservation = registry.reserve(reservationOf(job), clock.instant());
        } catch (RequestRefusedException e) {
            // The artifact store could not be read (ARTIFACT_STORAGE_IO_ERROR) or refused the reservation outright: nothing was sent to the target. A runtime exception of the
            // store is not caught here: it goes on to DownloadOperationRunner, which settles every such exception once (audit F4).
            return new DownloadExecution.NotStored(outcomes.portalFailure(e));
        }
        if (!(reservation instanceof ArtifactReservation.Reserved reserved)) {
            return new DownloadExecution.NotStored(outcomes.reservationRefused(ArtifactRefusal.of(reservation)));
        }

        ArtifactSink sink = new ArtifactSink(reserved.id(), payloads, registry, digester);
        DownloadResult result;
        try {
            result = transfer.download(new DownloadRequest(remote, input.remotePath(), access, pathPolicy, limits), sink, control);
        } catch (RuntimeException e) {
            // The artifact is given back and the exception goes on to DownloadOperationRunner, which settles it: a DispatchRefusedException of the dispatch guard, or a
            // RequestRefusedException of a step of the adapter before it touches the target (the Windows command wrapper in the link probe), as refused before dispatch; any other
            // exception as a local failure of the portal.
            discard(reserved.id());
            throw e;
        }
        if (result instanceof DownloadResult.Fetched fetched) {
            return stored(reserved.id(), sink, fetched);
        }
        discard(reserved.id());
        if (sink.refusal().isPresent()) {
            // The target announced a size that the budget or the disk cannot take: the same answer as a refused reservation, now that the size is known (defect D-0012).
            return new DownloadExecution.NotStored(outcomes.reservationRefused(ArtifactRefusal.of(sink.refusal().orElseThrow())));
        }
        if (result instanceof DownloadResult.Failed failed && failed.cause() == TransferFailureCause.LOCAL_IO_FAILED && sink.status() instanceof SinkResult.Failed sinkFailed) {
            // The adapter stopped because the sink failed; the use case, which owns the sink, knows the cause.
            return new DownloadExecution.NotStored(outcomes.localFailure(sinkFailed.cause()));
        }
        return new DownloadExecution.NotStored(outcomes.download(result, limits.deadline()));
    }

    /** The adapter says the file was read to the end; the use case checks what it stored before the artifact is delivered. */
    private DownloadExecution stored(ArtifactId id, ArtifactSink sink, DownloadResult.Fetched fetched) {
        if (sink.status() instanceof SinkResult.Failed failed) {
            discard(id);
            return new DownloadExecution.NotStored(outcomes.localFailure(failed.cause()));
        }
        Digest actual = sink.digest();
        if (sink.writtenBytes() != fetched.size() || !actual.equals(fetched.sha256())) {
            discard(id);
            return new DownloadExecution.NotStored(outcomes.downloadNotStored("FILE_CHANGED", "The stored bytes are not what the target confirmed; nothing was delivered."));
        }
        try {
            ArtifactRecord ready = registry.completeDownload(id, actual);
            return new DownloadExecution.Stored(outcomes.downloaded(), ready);
        } catch (RequestRefusedException e) {
            discard(id);
            return new DownloadExecution.NotStored(outcomes.portalFailure(e));
        } catch (RuntimeException e) {
            discard(id);
            throw e;
        }
    }

    /**
     * The reservation: no bytes, because the size is not known before the target is looked at, bound to the target the operation was approved for; the name is the last part of the
     * remote path. The adapter announces the size of the file before its first block and the {@link ArtifactSink} sets the reservation to it, so the budget and the disk judge the real
     * size (defect D-0012: reserving the largest file first refused a 1 KB download whenever less than a whole file was left). A target whose size cannot be seen is never read: the
     * sink takes no byte before the size (a file without a size cannot be kept inside the budget).
     */
    private ArtifactSpec reservationOf(DownloadJob job) {
        DownloadInput input = job.input();
        BindingKind binding = input.binding() instanceof FileBinding.Bound ? BindingKind.BOUND : BindingKind.UNBOUND;
        return new ArtifactSpec(ArtifactDirection.DOWNLOAD, fileNameOf(input), 0L, new ArtifactOrigin.Transfer(),
            new ArtifactDestination.Assigned(input.remotePath(), job.target().target().id(), job.approvedRevision(), binding), OverwriteMode.REPLACE, input.elevation(),
            UploadProtocol.UNSPECIFIED, ArtifactRetention.RELEASABLE, times.download());
    }

    private FileName fileNameOf(DownloadInput input) {
        try {
            return new FileName(input.remotePath().basename());
        } catch (IllegalArgumentException e) {
            // A last path part that is no file name (it cannot be a file the target would serve): the artifact still needs one.
            return new FileName("download.bin");
        }
    }

    /** Removes the bytes and the row, and so the charge. A failure here is logged. When it came after the row was set to {@code DELETING}, a later reap removes the row at once and the start-up check finishes it; when the row could not be set to {@code DELETING}, it stays and a reap removes it when it expires. */
    private void discard(ArtifactId id) {
        try {
            registry.beginDelete(id);
            payloads.delete(id);
            registry.release(id, ReleaseReason.REFUSED);
        } catch (RuntimeException e) {
            LOG.warn("The download artifact " + id.value() + " could not be discarded; a later reap or the start-up check removes it (at once when the removal had begun, otherwise at the expiry of the artifact)", e);
        }
    }
}
