package io.github.jaeseongs95.opermand.ssh.sftp;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jaeseongs95.opermand.app.remote.CancelSource;
import io.github.jaeseongs95.opermand.app.remote.DownloadRequest;
import io.github.jaeseongs95.opermand.app.remote.DownloadResult;
import io.github.jaeseongs95.opermand.app.remote.FileAccess;
import io.github.jaeseongs95.opermand.app.remote.FileDiagnostic;
import io.github.jaeseongs95.opermand.app.remote.FileMode;
import io.github.jaeseongs95.opermand.app.remote.FilePathPolicy;
import io.github.jaeseongs95.opermand.app.remote.PathPolicyBinding;
import io.github.jaeseongs95.opermand.app.remote.PathPolicyDecision;
import io.github.jaeseongs95.opermand.app.remote.PermissionRequest;
import io.github.jaeseongs95.opermand.app.remote.PermissionResult;
import io.github.jaeseongs95.opermand.app.remote.PermissionSupport;
import io.github.jaeseongs95.opermand.app.remote.TransferFailureCause;
import io.github.jaeseongs95.opermand.app.remote.TransferLimits;
import io.github.jaeseongs95.opermand.app.remote.UnsupportedReason;
import io.github.jaeseongs95.opermand.app.remote.UploadRequest;
import io.github.jaeseongs95.opermand.app.remote.UploadResult;
import io.github.jaeseongs95.opermand.ssh.profile.TestFiles;
import io.github.jaeseongs95.opermand.domain.error.TransferErrorCode;
import io.github.jaeseongs95.opermand.domain.id.Sha256Digester;
import io.github.jaeseongs95.opermand.domain.operation.CleanupState;
import io.github.jaeseongs95.opermand.domain.operation.OverwriteMode;
import io.github.jaeseongs95.opermand.domain.path.PathSyntax;
import io.github.jaeseongs95.opermand.domain.path.PosixRemotePath;
import io.github.jaeseongs95.opermand.domain.path.RemotePath;
import io.github.jaeseongs95.opermand.domain.path.WindowsRemotePath;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.UnaryOperator;
import org.apache.sshd.sftp.client.SftpClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * The allowed-root rule (S3-SF-15), the permission bits (S3-SF-11 and S3-SF-12), and the answer of a target that cannot set them. The rule is judged before any server
 * is asked: a refused path leases no session.
 */
@Timeout(120)
class PolicyAndPermissionTest {

    private static final byte[] CONTENT = "twelve bytes".getBytes(java.nio.charset.StandardCharsets.UTF_8);

    @TempDir
    Path root;

    private TransferBed bed;

    @AfterEach
    void closeBed() throws IOException {
        if (bed != null) {
            bed.close();
        }
    }

    /** A policy that answers what the test says and counts how often it was asked. */
    private static final class CountingPolicy implements FilePathPolicy {
        private final PathPolicyDecision answer;
        private final AtomicInteger asked = new AtomicInteger();

        CountingPolicy(PathPolicyDecision answer) {
            this.answer = answer;
        }

        @Override
        public PathPolicyDecision decide(io.github.jaeseongs95.opermand.domain.id.TargetId target, RemotePath path) {
            asked.incrementAndGet();
            return answer;
        }

        int asked() {
            return asked.get();
        }
    }

    private TransferBed startWith(FilePathPolicy policy) throws IOException {
        bed = TransferBed.start(root, true, new SftpChannels(), ScriptedExecutor.everythingSucceeds(), policy);
        return bed;
    }

    private UploadRequest windowsUpload(String path, PathPolicyBinding binding) {
        return new UploadRequest(TransferBed.TARGET, new WindowsRemotePath(path), CONTENT.length, new Sha256Digester().digest(CONTENT), OverwriteMode.REPLACE, new FileAccess.Direct(),
            binding, TransferLimits.defaults());
    }

    private static final PathPolicyBinding WINDOWS_ROOT = new PathPolicyBinding.RootPolicy(PathSyntax.WINDOWS);

    // ---- S3-SF-15: the allowed root ----

    @Test
    @DisplayName("S3-SF-15 a Windows path outside the allowed root is PATH_OUTSIDE_ALLOWED_ROOT before any session is leased; inside it, in any case, the rule lets it through")
    void enforcedRoot() throws IOException {
        TransferBed testBed = startWith(new CountingPolicy(new PathPolicyDecision.Enforce(new WindowsRemotePath("C:\\Work"))));

        UploadResult outside = testBed.transfer().upload(windowsUpload("C:\\Other\\a.bin", WINDOWS_ROOT), TransferBed.source(CONTENT), testBed.control());
        UploadResult sibling = testBed.transfer().upload(windowsUpload("C:\\Work2\\a.bin", WINDOWS_ROOT), TransferBed.source(CONTENT), testBed.control());

        assertThat(outside).isEqualTo(new UploadResult.Refused(TransferErrorCode.PATH_OUTSIDE_ALLOWED_ROOT, new FileDiagnostic.None(), CleanupState.COMPLETE));
        assertThat(sibling).isEqualTo(new UploadResult.Refused(TransferErrorCode.PATH_OUTSIDE_ALLOWED_ROOT, new FileDiagnostic.None(), CleanupState.COMPLETE));
        assertThat(testBed.sessions().leases()).isZero();
    }

    @Test
    @DisplayName("S3-SF-15 inside the root the rule does not refuse (the case of letters and the separator do not matter on Windows); the answer then comes from the server")
    void insideTheRootPassesTheRule() throws IOException {
        TransferBed testBed = startWith(new CountingPolicy(new PathPolicyDecision.Enforce(new WindowsRemotePath("C:\\Work"))));

        UploadResult inside = testBed.transfer().upload(windowsUpload("c:/WORK/deep/a.bin", WINDOWS_ROOT), TransferBed.source(CONTENT), testBed.control());

        // The embedded server has no C: drive, so the upload ends at the server with a refusal of its own. That the server was asked at all is the proof that the rule
        // let the path through; what the server answers to a Windows path depends on the file system it runs on and is not the subject here.
        assertThat(testBed.sessions().leases()).isEqualTo(1);
        assertThat(inside).isNotEqualTo(new UploadResult.Refused(TransferErrorCode.PATH_OUTSIDE_ALLOWED_ROOT, new FileDiagnostic.None(), CleanupState.COMPLETE));
        assertThat(inside).isNotInstanceOf(UploadResult.Published.class);
    }

    @Test
    @DisplayName("S3-SF-15 a test server with the root rule lifted (Bypass) and a target without the rule (NotApplicable) are not refused by it")
    void bypassAndNotApplicable() throws IOException {
        for (PathPolicyDecision decision : new PathPolicyDecision[] {new PathPolicyDecision.Bypass(), new PathPolicyDecision.NotApplicable()}) {
            try (TransferBed testBed = TransferBed.start(Files.createTempDirectory(root, "p-"), true, new SftpChannels(), ScriptedExecutor.everythingSucceeds(),
                new CountingPolicy(decision))) {
                UploadResult result = testBed.transfer().upload(windowsUpload("C:\\Anywhere\\a.bin", WINDOWS_ROOT), TransferBed.source(CONTENT), testBed.control());

                assertThat(testBed.sessions().leases()).as("%s: the server was asked", decision.getClass().getSimpleName()).isEqualTo(1);
                assertThat(result).isNotEqualTo(new UploadResult.Refused(TransferErrorCode.PATH_OUTSIDE_ALLOWED_ROOT, new FileDiagnostic.None(), CleanupState.COMPLETE));
            }
        }
    }

    @Test
    @DisplayName("S3-SF-15 a policy that cannot be established is PATH_POLICY_UNSUPPORTED: a refusal, never an allowance")
    void unconfirmedPolicy() throws IOException {
        TransferBed testBed = startWith(new CountingPolicy(new PathPolicyDecision.Unconfirmed()));

        UploadResult result = testBed.transfer().upload(windowsUpload("C:\\Work\\a.bin", WINDOWS_ROOT), TransferBed.source(CONTENT), testBed.control());

        assertThat(result).isEqualTo(new UploadResult.Refused(TransferErrorCode.PATH_POLICY_UNSUPPORTED, new FileDiagnostic.None(), CleanupState.COMPLETE));
        assertThat(testBed.sessions().leases()).isZero();
    }

    @Test
    @DisplayName("S3-SF-15 a target without a root rule is not asked at all, and a path of the other syntax than the rule's is INVALID_REQUEST")
    void noRootPolicyAndWrongSyntax() throws IOException {
        CountingPolicy policy = new CountingPolicy(new PathPolicyDecision.Unconfirmed());
        TransferBed testBed = startWith(policy);
        byte[] content = CONTENT;
        UploadRequest posixWithoutRule = new UploadRequest(TransferBed.TARGET, new PosixRemotePath("/a.bin"), content.length, testBed.sha256(content), OverwriteMode.FAIL_IF_EXISTS,
            new FileAccess.Direct(), new PathPolicyBinding.NoRootPolicy(), TransferLimits.defaults());
        UploadRequest posixUnderWindowsRule = new UploadRequest(TransferBed.TARGET, new PosixRemotePath("/a.bin"), content.length, testBed.sha256(content),
            OverwriteMode.FAIL_IF_EXISTS, new FileAccess.Direct(), WINDOWS_ROOT, TransferLimits.defaults());

        UploadResult withoutRule = testBed.transfer().upload(posixWithoutRule, TransferBed.source(content), testBed.control());
        UploadResult wrongSyntax = testBed.transfer().upload(posixUnderWindowsRule, TransferBed.source(content), testBed.control());

        assertThat(withoutRule).isInstanceOf(UploadResult.Published.class);
        assertThat(policy.asked()).as("the policy is asked only for a target that has the rule, and not for the wrong syntax").isZero();
        assertThat(((UploadResult.Refused) wrongSyntax).code()).isEqualTo(TransferErrorCode.INVALID_REQUEST);
    }

    @Test
    @DisplayName("S3-SF-15 the rule holds for a download and for the permission bits as it does for an upload")
    void ruleHoldsForEveryOperation() throws IOException {
        TransferBed testBed = startWith(new CountingPolicy(new PathPolicyDecision.Enforce(new WindowsRemotePath("C:\\Work"))));
        DownloadRequest download = new DownloadRequest(TransferBed.TARGET, new WindowsRemotePath("C:\\Other\\a.bin"), new FileAccess.Direct(), WINDOWS_ROOT, TransferLimits.defaults());
        PermissionRequest permission = new PermissionRequest(TransferBed.TARGET, new WindowsRemotePath("C:\\Other\\a.bin"), new FileMode(0600), new PermissionSupport.Supported(),
            new FileAccess.Direct(), WINDOWS_ROOT, TransferLimits.defaults());

        DownloadResult downloaded = testBed.transfer().download(download, new TransferBed.Collecting(), testBed.control());
        PermissionResult changed = testBed.transfer().applyPermissions(permission, testBed.control());

        assertThat(downloaded).isEqualTo(new DownloadResult.Refused(TransferErrorCode.PATH_OUTSIDE_ALLOWED_ROOT, new FileDiagnostic.None()));
        assertThat(changed).isEqualTo(new PermissionResult.Refused(TransferErrorCode.PATH_OUTSIDE_ALLOWED_ROOT, new FileDiagnostic.None()));
        assertThat(testBed.sessions().leases()).isZero();
    }

    // ---- S3-SF-11, S3-SF-12: permission bits ----

    private PermissionRequest permission(String path, int bits, PermissionSupport support) {
        return new PermissionRequest(TransferBed.TARGET, new PosixRemotePath(path), new FileMode(bits), support, new FileAccess.Direct(), new PathPolicyBinding.NoRootPolicy(),
            TransferLimits.defaults());
    }

    @Test
    @DisplayName("S3-SF-11 a target that cannot set permission bits (Windows SFTP has no chmod) answers Unsupported with the reason, calls SFTP zero times and throws nothing")
    void windowsCannotChmod() throws IOException {
        TransferBed testBed = startWith((target, path) -> new PathPolicyDecision.NotApplicable());
        Files.write(root.resolve("a.bin"), CONTENT);

        PermissionResult result = testBed.transfer().applyPermissions(permission("/a.bin", 0600, new PermissionSupport.Unsupported(UnsupportedReason.WINDOWS_SFTP_CHMOD)),
            testBed.control());

        assertThat(result).isEqualTo(new PermissionResult.Unsupported(UnsupportedReason.WINDOWS_SFTP_CHMOD));
        assertThat(testBed.sessions().leases()).as("SFTP is not called").isZero();
    }

    @Test
    @DisplayName("S3-SF-12 a POSIX target sets the bits and reads them back; where the file system has no POSIX modes the server ignores the request and the read-back says so")
    void linuxChmod() throws IOException {
        TransferBed testBed = startWith((target, path) -> new PathPolicyDecision.NotApplicable());
        Path file = root.resolve("a.bin");
        Files.write(file, CONTENT);

        PermissionResult result = testBed.transfer().applyPermissions(permission("/a.bin", 0640, new PermissionSupport.Supported()), testBed.control());

        if (TestFiles.hasAbility("posix", Files.getFileStore(root).supportsFileAttributeView(PosixFileAttributeView.class))) {
            assertThat(result).isEqualTo(new PermissionResult.Applied());
            assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(file))).isEqualTo("rw-r-----");
        } else {
            // NTFS on the developer's PC has no POSIX modes; the adapter does not claim a change it could not read back.
            assertThat(result).isEqualTo(new PermissionResult.Refused(TransferErrorCode.INVALID_REQUEST, new FileDiagnostic.None()));
        }
    }

    @Test
    @DisplayName("SSH_PORTAL-D-0085 a cancel that comes while the mode is being changed does not close the channel half way: the live session is kept and the result is not a lost transport")
    void aCancelDuringTheModeChangeKeepsTheSession() throws IOException {
        CancelSource cancel = new CancelSource();
        TransferBed testBed = startWithFault(client -> new SftpDoubles().runningWhenTheModeIsChanged(client, cancel::cancel));
        Path file = root.resolve("a.bin");
        Files.write(file, CONTENT);

        PermissionResult result = testBed.transfer().applyPermissions(permission("/a.bin", 0640, new PermissionSupport.Supported()), testBed.control(cancel));

        assertThat(result).as("the change is one request that runs to its end: %s", result).isIn(new PermissionResult.Applied(),
            new PermissionResult.Refused(TransferErrorCode.INVALID_REQUEST, new FileDiagnostic.None()));
        assertThat(testBed.sessions().discards()).as("the connection is fine; the session is kept").isZero();
    }

    @Test
    @DisplayName("SSH_PORTAL-D-0085 a cancel that came before the change was sent sends nothing: NotDispatched(CANCELLED)")
    void aCancelBeforeTheModeChangeSendsNothing() throws IOException {
        CancelSource cancel = new CancelSource();
        TransferBed testBed = startWith((target, path) -> new PathPolicyDecision.NotApplicable());
        Path file = root.resolve("a.bin");
        Files.write(file, CONTENT);
        cancel.cancel();

        PermissionResult result = testBed.transfer().applyPermissions(permission("/a.bin", 0640, new PermissionSupport.Supported()), testBed.control(cancel));

        assertThat(result).isEqualTo(new PermissionResult.NotDispatched(TransferFailureCause.CANCELLED));
    }

    @Test
    @DisplayName("SSH_PORTAL-D-0086 a mode that was set and cannot be read back because the file is gone is FILE_NOT_FOUND, answered by a live server: the session is kept")
    void aFileThatVanishedAfterTheModeWasSet() throws IOException {
        TransferBed testBed = startWithFault(client -> new SftpDoubles().refusingToLookAfterTheModeIsSet(client, org.apache.sshd.sftp.common.SftpConstants.SSH_FX_NO_SUCH_FILE));
        Files.write(root.resolve("a.bin"), CONTENT);

        PermissionResult result = testBed.transfer().applyPermissions(permission("/a.bin", 0640, new PermissionSupport.Supported()), testBed.control());

        assertThat(result).isInstanceOf(PermissionResult.Refused.class);
        assertThat(((PermissionResult.Refused) result).code()).isEqualTo(TransferErrorCode.FILE_NOT_FOUND);
        assertThat(testBed.sessions().discards()).as("a status of a live server is not a lost connection").isZero();
    }

    @Test
    @DisplayName("SSH_PORTAL-D-0086 any other answer to the read-back look is unconfirmed, and the live session is kept (it used to be discarded as a lost connection)")
    void aRefusedReadBackKeepsTheSession() throws IOException {
        TransferBed testBed = startWithFault(client -> new SftpDoubles().refusingToLookAfterTheModeIsSet(client, org.apache.sshd.sftp.common.SftpConstants.SSH_FX_PERMISSION_DENIED));
        Files.write(root.resolve("a.bin"), CONTENT);

        PermissionResult result = testBed.transfer().applyPermissions(permission("/a.bin", 0640, new PermissionSupport.Supported()), testBed.control());

        assertThat(result).isInstanceOf(PermissionResult.Unconfirmed.class);
        assertThat(testBed.sessions().discards()).isZero();
    }

    private TransferBed startWithFault(UnaryOperator<SftpClient> fault) throws IOException {
        bed = TransferBed.start(root, true, new SftpDoubles.FaultyChannels(fault), ScriptedExecutor.everythingSucceeds(), (target, path) -> new PathPolicyDecision.NotApplicable());
        return bed;
    }

    @Test
    @DisplayName("S-D0013-07 D-0013 a cancel that comes while the path is first looked at and ends the channel is NotDispatched(CANCELLED), not TRANSPORT_LOST: the live session is kept")
    void cancelDuringTheFirstLookKeepsItsCause() throws IOException {
        CancelSource cancel = new CancelSource();
        TransferBed testBed = startWithFault(client -> new SftpDoubles().channelEndingAtTheFirstLook(client, cancel::cancel));
        Path file = root.resolve("a.bin");
        Files.write(file, CONTENT);
        Set<PosixFilePermission> before = posixPermissionsOrNull(file);

        PermissionResult result = testBed.transfer().applyPermissions(permission("/a.bin", 0640, new PermissionSupport.Supported()), testBed.control(cancel));

        assertThat(result).as("the channel ended because of the cancel: %s", result).isEqualTo(new PermissionResult.NotDispatched(TransferFailureCause.CANCELLED));
        assertThat(testBed.sessions().discards()).as("the connection is fine; only the request was stopped: the session is kept").isZero();
        assertThat(posixPermissionsOrNull(file)).as("nothing was sent: the bits are as they were").isEqualTo(before);
    }

    @Test
    @DisplayName("S-D0013-09 D-0013 a channel that ends at the first look with nobody having stopped the request is still NotDispatched(TRANSPORT_LOST) and the session is discarded")
    void lossAtTheFirstLookWithoutACauseIsTransportLost() throws IOException {
        TransferBed testBed = startWithFault(client -> new SftpDoubles().channelEndingAtTheFirstLook(client, () -> { }));
        Files.write(root.resolve("a.bin"), CONTENT);

        PermissionResult result = testBed.transfer().applyPermissions(permission("/a.bin", 0640, new PermissionSupport.Supported()), testBed.control());

        assertThat(result).isEqualTo(new PermissionResult.NotDispatched(TransferFailureCause.TRANSPORT_LOST));
        assertThat(testBed.sessions().discards()).isEqualTo(1);
        assertThat(testBed.sessions().leases()).as("no automatic second try").isEqualTo(1);
    }

    /** The POSIX permissions of the file, or {@code null} on a file system that has none (the test then compares {@code null} with {@code null}). */
    private static Set<PosixFilePermission> posixPermissionsOrNull(Path file) throws IOException {
        return TestFiles.hasAbility("posix", Files.getFileStore(file).supportsFileAttributeView(PosixFileAttributeView.class)) ? Files.getPosixFilePermissions(file) : null;
    }

    @Test
    @DisplayName("S3-SF-12 permission bits of a file that is not there are FILE_NOT_FOUND, of a directory UNSAFE_PATH")
    void chmodOfMissingAndDirectory() throws IOException {
        TransferBed testBed = startWith((target, path) -> new PathPolicyDecision.NotApplicable());
        Files.createDirectory(root.resolve("folder"));

        PermissionResult missing = testBed.transfer().applyPermissions(permission("/missing.bin", 0600, new PermissionSupport.Supported()), testBed.control());
        PermissionResult directory = testBed.transfer().applyPermissions(permission("/folder", 0600, new PermissionSupport.Supported()), testBed.control());

        assertThat(missing).isEqualTo(new PermissionResult.Refused(TransferErrorCode.FILE_NOT_FOUND, new FileDiagnostic.None()));
        assertThat(directory).isEqualTo(new PermissionResult.Refused(TransferErrorCode.UNSAFE_PATH, new FileDiagnostic.None()));
    }
}
