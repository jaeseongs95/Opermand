package io.github.jaeseongs95.portal.ssh.sftp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.github.jaeseongs95.portal.app.remote.CancelSource;
import io.github.jaeseongs95.portal.app.remote.CommandKind;
import io.github.jaeseongs95.portal.app.remote.DownloadRequest;
import io.github.jaeseongs95.portal.app.remote.DownloadResult;
import io.github.jaeseongs95.portal.app.remote.ElevationPlan;
import io.github.jaeseongs95.portal.app.remote.ElevationRequest;
import io.github.jaeseongs95.portal.app.remote.ElevationResult;
import io.github.jaeseongs95.portal.app.remote.ExecReply;
import io.github.jaeseongs95.portal.app.remote.ExecutionControl;
import io.github.jaeseongs95.portal.app.remote.ExecutionLimits;
import io.github.jaeseongs95.portal.app.remote.FileAccess;
import io.github.jaeseongs95.portal.app.remote.FileDiagnostic;
import io.github.jaeseongs95.portal.app.remote.FileMode;
import io.github.jaeseongs95.portal.app.remote.FixedRemoteCommand;
import io.github.jaeseongs95.portal.app.remote.PathPolicyBinding;
import io.github.jaeseongs95.portal.app.remote.PathPolicyDecision;
import io.github.jaeseongs95.portal.app.remote.PermissionRequest;
import io.github.jaeseongs95.portal.app.remote.PermissionResult;
import io.github.jaeseongs95.portal.app.remote.PermissionSupport;
import io.github.jaeseongs95.portal.app.remote.PreparedCommand;
import io.github.jaeseongs95.portal.app.remote.RemoteExecution;
import io.github.jaeseongs95.portal.app.remote.RemoteTarget;
import io.github.jaeseongs95.portal.app.remote.ReplaceMethod;
import io.github.jaeseongs95.portal.app.remote.SinkResult;
import io.github.jaeseongs95.portal.app.remote.SshSettings;
import io.github.jaeseongs95.portal.app.remote.StdinBytes;
import io.github.jaeseongs95.portal.app.remote.TransferFailureCause;
import io.github.jaeseongs95.portal.app.remote.TransferLimits;
import io.github.jaeseongs95.portal.app.remote.UploadRequest;
import io.github.jaeseongs95.portal.app.remote.UploadResult;
import io.github.jaeseongs95.portal.app.target.TargetSource;
import io.github.jaeseongs95.portal.ssh.profile.TestFiles;
import io.github.jaeseongs95.portal.ssh.session.PoolSessionSource;
import io.github.jaeseongs95.portal.ssh.testkit.AdapterFixture;
import io.github.jaeseongs95.portal.ssh.testkit.EmbeddedSshServer;
import io.github.jaeseongs95.portal.domain.error.DispatchFailure;
import io.github.jaeseongs95.portal.domain.error.DispatchReason;
import io.github.jaeseongs95.portal.domain.error.DispatchRefusedException;
import io.github.jaeseongs95.portal.domain.error.DispatchStage;
import io.github.jaeseongs95.portal.domain.error.TransferErrorCode;
import io.github.jaeseongs95.portal.domain.id.Sha256Digester;
import io.github.jaeseongs95.portal.domain.id.TargetId;
import io.github.jaeseongs95.portal.domain.operation.CleanupState;
import io.github.jaeseongs95.portal.domain.operation.OverwriteMode;
import io.github.jaeseongs95.portal.domain.path.PosixRemotePath;
import io.github.jaeseongs95.portal.domain.path.RemotePath;
import io.github.jaeseongs95.portal.domain.target.AccessMode;
import io.github.jaeseongs95.portal.domain.target.ProfileAlias;
import io.github.jaeseongs95.portal.domain.target.TargetLookup;
import io.github.jaeseongs95.portal.domain.target.TargetRevision;
import java.io.IOException;
import java.nio.channels.ClosedChannelException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.common.channel.Channel;
import org.apache.sshd.common.channel.ChannelFactory;
import org.apache.sshd.common.session.Session;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.command.CommandFactory;
import org.apache.sshd.sftp.client.SftpClient;
import org.apache.sshd.sftp.common.SftpConstants;
import org.apache.sshd.sftp.server.SftpSubsystemFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * Elevated file work (S3-EL-01 to S3-EL-04, stage 3 design 6.1, decision Q8). A POSIX target is served by its SFTP server started with {@code sudo -n} and spoken to with
 * MINA's own client over an {@code exec} channel ({@link SudoSftpChannels}); the transfer code is the one of a plain transfer, so every check, replacement and result of the
 * other tests holds here too. A Windows target is served by the administrator probe and then the plain channel. Which of the two is chosen is a fact of the
 * {@link ElevationPlan} in the request, not of an operating-system test in the adapter. The test target stands in for {@code sudo -n} with its own SFTP server on the
 * command line {@code sudo -n /usr/lib/openssh/sftp-server}; whether a real {@code sudo} does the same is shown by the real-server tests (S3-RO-02).
 * The executor of the probes is a plain test class that runs nothing and records every command line.
 */
@Timeout(120)
class ElevatedTransferTest {

    private static final byte[] CONTENT = "the bytes of an elevated upload".getBytes(StandardCharsets.UTF_8);
    private static final ElevationPlan SUDO = new ElevationPlan.SudoNonInteractive(new FixedRemoteCommand("sudo-probe", "sudo -n true"));
    private static final ElevationPlan ADMINISTRATOR = new ElevationPlan.WindowsAdministratorCheck(new FixedRemoteCommand("admin-probe", "powershell.exe -NoLogo admin-probe"));

    @TempDir
    Path root;

    private TransferBed bed;
    private Logger transferLog;
    private ListAppender<ILoggingEvent> transferAppender;

    @BeforeEach
    void listenToTheTransfer() {
        transferLog = (Logger) LoggerFactory.getLogger(MinaRemoteFileTransfer.class);
        transferAppender = new ListAppender<>();
        transferAppender.start();
        transferLog.addAppender(transferAppender);
    }

    @AfterEach
    void closeBed() throws IOException {
        transferLog.detachAppender(transferAppender);
        transferAppender.stop();
        if (bed != null) {
            bed.close();
        }
    }

    private TransferBed start(TransferBed.Sudo sudo, ScriptedExecutor executor) throws IOException {
        bed = TransferBed.start(root, sudo, executor);
        return bed;
    }

    private UploadRequest elevatedUpload(String path, OverwriteMode overwrite, ElevationPlan plan) {
        return bed.upload(new PosixRemotePath(path), CONTENT, overwrite, new FileAccess.Elevated(plan), TransferLimits.defaults());
    }

    private DownloadRequest elevatedDownload(String path) {
        return new DownloadRequest(TransferBed.TARGET, new PosixRemotePath(path), new FileAccess.Elevated(SUDO), new PathPolicyBinding.NoRootPolicy(), TransferLimits.defaults());
    }

    // ---- S3-EL-01: the SFTP server under sudo -n ----

    @Test
    @DisplayName("S3-EL-01 an elevated upload on a POSIX target runs the same code over the SFTP server started with sudo -n: Published, the bytes, no temporary file, no command of the executor")
    void elevatedUpload() throws IOException {
        ScriptedExecutor executor = ScriptedExecutor.everythingSucceeds();
        TransferBed testBed = start(TransferBed.Sudo.WORKS, executor);

        UploadResult result = testBed.transfer().upload(elevatedUpload("/elevated.bin", OverwriteMode.FAIL_IF_EXISTS, SUDO), TransferBed.source(CONTENT), testBed.control());

        assertThat(result).isInstanceOf(UploadResult.Published.class);
        assertThat(((UploadResult.Published) result).sha256()).isEqualTo(testBed.sha256(CONTENT));
        assertThat(Files.readAllBytes(root.resolve("elevated.bin"))).isEqualTo(CONTENT);
        try (var files = Files.list(root)) {
            assertThat(files.map(file -> file.getFileName().toString()).filter(name -> name.startsWith(".ssh-approval-"))).isEmpty();
        }
        assertThat(executor.commandLines()).as("the file work needs no remote command but the start of the server").isEmpty();
        assertThat(testBed.sessions().leases()).isEqualTo(1);
    }

    @Test
    @DisplayName("S3-EL-01 the replacement of an existing file is the adapter's own: posix-rename in one step, and a file that must not be replaced is DESTINATION_EXISTS")
    void elevatedReplacement() throws IOException {
        TransferBed testBed = start(TransferBed.Sudo.WORKS, ScriptedExecutor.everythingSucceeds());
        Files.write(root.resolve("existing.bin"), "old".getBytes(StandardCharsets.UTF_8));

        UploadResult kept = testBed.transfer().upload(elevatedUpload("/existing.bin", OverwriteMode.FAIL_IF_EXISTS, SUDO), TransferBed.source(CONTENT), testBed.control());
        UploadResult replaced = testBed.transfer().upload(elevatedUpload("/existing.bin", OverwriteMode.REPLACE, SUDO), TransferBed.source(CONTENT), testBed.control());

        assertThat(kept).isEqualTo(new UploadResult.Refused(TransferErrorCode.DESTINATION_EXISTS, new FileDiagnostic.None(), CleanupState.COMPLETE));
        assertThat(replaced).isInstanceOf(UploadResult.Published.class);
        assertThat(((UploadResult.Published) replaced).method()).isEqualTo(ReplaceMethod.POSIX_RENAME);
        assertThat(Files.readAllBytes(root.resolve("existing.bin"))).isEqualTo(CONTENT);
    }

    @Test
    @DisplayName("S3-EL-01 an elevated upload meets the same path checks: a directory where the file should be is UNSAFE_PATH, a missing directory is PARENT_NOT_FOUND, and the file size is not held in memory")
    void elevatedPathChecks() throws IOException {
        TransferBed testBed = start(TransferBed.Sudo.WORKS, ScriptedExecutor.everythingSucceeds());
        Files.createDirectory(root.resolve("folder"));

        UploadResult directory = testBed.transfer().upload(elevatedUpload("/folder", OverwriteMode.REPLACE, SUDO), TransferBed.source(CONTENT), testBed.control());
        UploadResult missing = testBed.transfer().upload(elevatedUpload("/nowhere/x.bin", OverwriteMode.REPLACE, SUDO), TransferBed.source(CONTENT), testBed.control());

        assertThat(directory).isEqualTo(new UploadResult.Refused(TransferErrorCode.UNSAFE_PATH, new FileDiagnostic.None(), CleanupState.COMPLETE));
        assertThat(((UploadResult.Refused) missing).code()).isEqualTo(TransferErrorCode.PARENT_NOT_FOUND);
    }

    @Test
    @DisplayName("S3-EL-01 an elevated download reads the file over the same channel and checks it like a plain one")
    void elevatedDownload() throws IOException {
        TransferBed testBed = start(TransferBed.Sudo.WORKS, ScriptedExecutor.everythingSucceeds());
        Files.write(root.resolve("secret.txt"), CONTENT);
        TransferBed.Collecting sink = new TransferBed.Collecting();

        DownloadResult result = testBed.transfer().download(elevatedDownload("/secret.txt"), sink, testBed.control());
        DownloadResult missing = testBed.transfer().download(elevatedDownload("/missing.txt"), new TransferBed.Collecting(), testBed.control());

        assertThat(result).isEqualTo(new DownloadResult.Fetched(CONTENT.length, testBed.sha256(CONTENT)));
        assertThat(sink.bytes()).isEqualTo(CONTENT);
        assertThat(missing).isEqualTo(new DownloadResult.Refused(TransferErrorCode.FILE_NOT_FOUND, new FileDiagnostic.None()));
    }

    @Test
    @DisplayName("S3-SF-12 elevated permission bits are set and read back by the same code; where the file system has no POSIX modes the read-back says so")
    void elevatedPermissions() throws IOException {
        TransferBed testBed = start(TransferBed.Sudo.WORKS, ScriptedExecutor.everythingSucceeds());
        Path file = root.resolve("app.conf");
        Files.write(file, CONTENT);
        PermissionRequest request = new PermissionRequest(TransferBed.TARGET, new PosixRemotePath("/app.conf"), new FileMode(0640), new PermissionSupport.Supported(),
            new FileAccess.Elevated(SUDO), new PathPolicyBinding.NoRootPolicy(), TransferLimits.defaults());

        PermissionResult result = testBed.transfer().applyPermissions(request, testBed.control());

        if (TestFiles.hasAbility("posix", Files.getFileStore(root).supportsFileAttributeView(PosixFileAttributeView.class))) {
            assertThat(result).isEqualTo(new PermissionResult.Applied());
            assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(file))).isEqualTo("rw-r-----");
        } else {
            assertThat(result).isEqualTo(new PermissionResult.Refused(TransferErrorCode.INVALID_REQUEST, new FileDiagnostic.None()));
        }
    }

    // ---- S3-EL-02: no elevation ----

    @Test
    @DisplayName("S3-EL-02 sudo that cannot start the server (a password is needed) refuses the request before anything is written: PERMISSION_DENIED")
    void sudoDenied() throws IOException {
        TransferBed testBed = start(TransferBed.Sudo.DENIED, ScriptedExecutor.everythingSucceeds());

        UploadResult upload = testBed.transfer().upload(elevatedUpload("/x.bin", OverwriteMode.REPLACE, SUDO), TransferBed.source(CONTENT), testBed.control());
        DownloadResult download = testBed.transfer().download(elevatedDownload("/x.bin"), new TransferBed.Collecting(), testBed.control());

        assertThat(upload).isEqualTo(new UploadResult.Refused(TransferErrorCode.PERMISSION_DENIED, new FileDiagnostic.None(), CleanupState.COMPLETE));
        assertThat(download).isEqualTo(new DownloadResult.Refused(TransferErrorCode.PERMISSION_DENIED, new FileDiagnostic.None()));
        assertThat(root.resolve("x.bin")).doesNotExist();
    }

    @Test
    @DisplayName("S3-EL-02 a target without an SFTP server program in the usual places has no elevated file work: the request is refused as invalid, nothing is written")
    void noServerProgram() throws IOException {
        TransferBed testBed = start(TransferBed.Sudo.NO_SERVER, ScriptedExecutor.everythingSucceeds());

        UploadResult result = testBed.transfer().upload(elevatedUpload("/x.bin", OverwriteMode.REPLACE, SUDO), TransferBed.source(CONTENT), testBed.control());

        assertThat(result).isEqualTo(new UploadResult.Refused(TransferErrorCode.INVALID_REQUEST, new FileDiagnostic.None(), CleanupState.COMPLETE));
        assertThat(root.resolve("x.bin")).doesNotExist();
    }

    // ---- D-0013 (re-audit C2, R3): what the opening of the elevated channel came to ----

    private TransferBed start(TransferBed.Sudo sudo, SftpChannels channels, Consumer<SshServer> serverFault) throws IOException {
        bed = TransferBed.start(root, sudo, channels, ScriptedExecutor.everythingSucceeds(), serverFault);
        return bed;
    }

    @Test
    @DisplayName("S-D0013-13 D-0013 a server that refuses to open the SFTP channel while the session lives is CHANNEL_OPEN_FAILED, as on the plain path: the session is kept")
    void serverRefusesTheLookingChannel() throws IOException {
        TransferBed testBed = start(TransferBed.Sudo.WORKS, new SftpChannels(), TransferBed.refusingSessionChannels(opening -> true));

        DownloadResult download = testBed.transfer().download(elevatedDownload("/x.bin"), new TransferBed.Collecting(), testBed.control());
        UploadResult upload = testBed.transfer().upload(elevatedUpload("/x.bin", OverwriteMode.REPLACE, SUDO), TransferBed.source(CONTENT), testBed.control());

        assertThat(download).isEqualTo(new DownloadResult.Failed(TransferFailureCause.CHANNEL_OPEN_FAILED));
        assertThat(upload).isEqualTo(new UploadResult.NotDispatched(TransferFailureCause.CHANNEL_OPEN_FAILED));
        assertThat(testBed.sessions().discards()).as("the connection is fine; the server only refused a channel").isZero();
        assertThat(testBed.sessions().openRejections()).isEqualTo(2);
        assertThat(root.resolve("x.bin")).doesNotExist();
    }

    @Test
    @DisplayName("S-D0013-14 D-0013 a server that refuses to open the channel of sudo -n while the session lives is CHANNEL_OPEN_FAILED, not PERMISSION_DENIED: the session is kept")
    void serverRefusesTheSudoChannel() throws IOException {
        TransferBed testBed = start(TransferBed.Sudo.WORKS, new SftpChannels(), TransferBed.refusingSessionChannels(opening -> opening == 2));

        DownloadResult download = testBed.transfer().download(elevatedDownload("/x.bin"), new TransferBed.Collecting(), testBed.control());

        assertThat(download).isEqualTo(new DownloadResult.Failed(TransferFailureCause.CHANNEL_OPEN_FAILED));
        assertThat(testBed.sessions().discards()).isZero();
        assertThat(testBed.sessions().openRejections()).isEqualTo(1);
    }

    @Test
    @DisplayName("S-D0013-15 D-0013 a close of the channel that looked for the server program fails: the elevated download is still Fetched")
    void closeFailureOfTheLookDoesNotCoverTheResult() throws IOException {
        TransferBed testBed = start(TransferBed.Sudo.WORKS, new SftpDoubles.FaultyChannels(client -> new SftpDoubles().failingToClose(client)), sshd -> { });
        Files.write(root.resolve("secret.txt"), CONTENT);
        TransferBed.Collecting sink = new TransferBed.Collecting();

        DownloadResult result = testBed.transfer().download(elevatedDownload("/secret.txt"), sink, testBed.control());

        assertThat(result).isEqualTo(new DownloadResult.Fetched(CONTENT.length, testBed.sha256(CONTENT)));
        assertThat(sink.bytes()).isEqualTo(CONTENT);
        assertThat(testBed.sessions().discards()).isZero();
    }

    @Test
    @DisplayName("S-D0013-16 D-0013 a refusal of the permission to look for the server program is PERMISSION_DENIED, not a missing program: the session is kept")
    void refusedLookIsNotAMissingProgram() throws IOException {
        TransferBed testBed = start(TransferBed.Sudo.WORKS,
            new SftpDoubles.FaultyChannels(client -> new SftpDoubles().refusingToStat(client, "sftp-server", SftpConstants.SSH_FX_PERMISSION_DENIED)), sshd -> { });

        DownloadResult result = testBed.transfer().download(elevatedDownload("/x.bin"), new TransferBed.Collecting(), testBed.control());

        assertThat(result).isEqualTo(new DownloadResult.Refused(TransferErrorCode.PERMISSION_DENIED, new FileDiagnostic.None()));
        assertThat(testBed.sessions().discards()).isZero();
    }

    @Test
    @DisplayName("S-D0013-17 D-0013 a usual place that holds no program (status no such file) does not end the search: the next place serves the elevated download")
    void missingFirstPlaceGoesOn() throws IOException {
        TransferBed testBed = start(TransferBed.Sudo.WORKS, ScriptedExecutor.everythingSucceeds());
        Files.write(root.resolve("secret.txt"), CONTENT);
        TransferBed.Collecting sink = new TransferBed.Collecting();

        DownloadResult result = testBed.transfer().download(elevatedDownload("/secret.txt"), sink, testBed.control());

        assertThat(root.resolve("usr/libexec")).as("the first place of the search is empty").doesNotExist();
        assertThat(result).isEqualTo(new DownloadResult.Fetched(CONTENT.length, testBed.sha256(CONTENT)));
        assertThat(sink.bytes()).isEqualTo(CONTENT);
    }

    // ---- D-0013 (re-audit condition 1): the SFTP initialization of the program that sudo -n started ----

    /** How long the client waits for the version answer of a program that never gives it: short, so the tests that wait for it are short. */
    private static final Duration SHORT_WAIT = Duration.ofMillis(300);
    /** The wait of the tests that stop the transfer while it waits: much longer than the stop, which comes from an event (the server started the command). */
    private static final Duration LONG_WAIT = Duration.ofSeconds(1);

    @Test
    @DisplayName("S-D0013-18 D-0013 a program that sudo -n started and that never answers the SFTP version request ends the wait: CHANNEL_OPEN_FAILED, not PERMISSION_DENIED; the session is kept")
    void initializationThatTimesOutIsNotARefusal() throws IOException {
        TransferBed testBed = start(TransferBed.Sudo.HANGS, ScriptedExecutor.everythingSucceeds());
        testBed.sessions().sftpInitializationWait(SHORT_WAIT);

        DownloadResult download = testBed.transfer().download(elevatedDownload("/x.bin"), new TransferBed.Collecting(), testBed.control());
        UploadResult upload = testBed.transfer().upload(elevatedUpload("/x.bin", OverwriteMode.REPLACE, SUDO), TransferBed.source(CONTENT), testBed.control());

        assertThat(download).isEqualTo(new DownloadResult.Failed(TransferFailureCause.CHANNEL_OPEN_FAILED));
        assertThat(upload).isEqualTo(new UploadResult.NotDispatched(TransferFailureCause.CHANNEL_OPEN_FAILED));
        assertThat(testBed.sessions().discards()).as("the connection is fine; the program did not answer").isZero();
        assertThat(root.resolve("x.bin")).doesNotExist();
    }

    @Test
    @DisplayName("S-D0013-19 D-0013 the time limit that is reached while the version answer is awaited stays DEADLINE, not PERMISSION_DENIED")
    void deadlineDuringTheInitializationKeepsItsCause() throws IOException {
        TransferBed testBed = start(TransferBed.Sudo.HANGS, ScriptedExecutor.everythingSucceeds());
        testBed.sessions().sftpInitializationWait(LONG_WAIT);
        // The time limit is the default (it does not run by itself); it runs when the server has started the command, which happens after the channel was opened and so
        // always while the version answer is awaited, however long the steps before it took.
        testBed.server().startedCommands().whenAtLeast(1).thenRun(testBed::expireDeadline);

        DownloadResult download = testBed.transfer().download(elevatedDownload("/x.bin"), new TransferBed.Collecting(), testBed.control());

        assertThat(download).isEqualTo(new DownloadResult.Failed(TransferFailureCause.DEADLINE));
        assertThat(testBed.sessions().discards()).isZero();
    }

    @Test
    @DisplayName("S-D0013-20 D-0013 a cancel that comes while the version answer is awaited stays CANCELLED, not PERMISSION_DENIED")
    void cancelDuringTheInitializationKeepsItsCause() throws IOException {
        TransferBed testBed = start(TransferBed.Sudo.HANGS, ScriptedExecutor.everythingSucceeds());
        testBed.sessions().sftpInitializationWait(LONG_WAIT);
        CancelSource cancel = new CancelSource();
        testBed.server().startedCommands().whenAtLeast(1).thenRun(cancel::cancel);

        DownloadResult download = testBed.transfer().download(elevatedDownload("/x.bin"), new TransferBed.Collecting(), testBed.control(cancel));

        assertThat(download).isEqualTo(new DownloadResult.Failed(TransferFailureCause.CANCELLED));
        assertThat(testBed.sessions().discards()).isZero();
    }

    /** What a transfer on another thread came to, and whether that thread was left interrupted. */
    private record Interrupted(DownloadResult result, boolean interruptedAfterwards) {
    }

    @Test
    @DisplayName("S-D0013-21 D-0013 a transfer thread that is interrupted while the version answer is awaited gets CHANNEL_OPEN_FAILED, not PERMISSION_DENIED; its interrupt status is kept")
    void interruptDuringTheInitializationIsNotARefusal() throws Exception {
        TransferBed testBed = start(TransferBed.Sudo.HANGS, ScriptedExecutor.everythingSucceeds());
        testBed.sessions().sftpInitializationWait(LONG_WAIT);
        AtomicReference<Thread> worker = new AtomicReference<>();
        testBed.server().startedCommands().whenAtLeast(1).thenRun(() -> worker.get().interrupt());
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Interrupted outcome = executor.submit(() -> {
                worker.set(Thread.currentThread());
                DownloadResult result = testBed.transfer().download(elevatedDownload("/x.bin"), new TransferBed.Collecting(), testBed.control());
                return new Interrupted(result, Thread.currentThread().isInterrupted());
            }).get(60, TimeUnit.SECONDS);

            assertThat(outcome.result()).isEqualTo(new DownloadResult.Failed(TransferFailureCause.CHANNEL_OPEN_FAILED));
            assertThat(outcome.interruptedAfterwards()).as("the thread was interrupted: that stays known to its owner").isTrue();
            assertThat(testBed.sessions().discards()).isZero();
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    @DisplayName("S-D0013-22 D-0013 a program whose channel closes before the version answer (sudo -n that ends at once) is still PERMISSION_DENIED")
    void closedBeforeTheVersionAnswerIsStillARefusal() throws IOException {
        TransferBed testBed = start(TransferBed.Sudo.CLOSES, ScriptedExecutor.everythingSucceeds());

        DownloadResult download = testBed.transfer().download(elevatedDownload("/x.bin"), new TransferBed.Collecting(), testBed.control());

        assertThat(download).isEqualTo(new DownloadResult.Refused(TransferErrorCode.PERMISSION_DENIED, new FileDiagnostic.None()));
        assertThat(testBed.sessions().discards()).isZero();
    }

    @Test
    @DisplayName("S-D0013-36 D-0013 a dead session during sudo -n channel opening (Opening.Lost) logs the cause at WARN with the throwable; a cancel before the loss logs no WARN")
    void lostSessionDuringElevatedOpeningLogsTheCauseUnlessStopped() throws IOException {
        TransferBed lostBed = start(TransferBed.Sudo.WORKS, new SftpChannels() {
            @Override
            public SftpClient open(ClientSession session) throws IOException {
                session.close(true).await(Duration.ofSeconds(5));
                throw new ClosedChannelException();
            }
        }, sshd -> { });

        DownloadResult lost = lostBed.transfer().download(elevatedDownload("/x.bin"), new TransferBed.Collecting(), lostBed.control());

        List<ILoggingEvent> warnings = transferAppender.list.stream().filter(event -> event.getLevel().isGreaterOrEqual(Level.WARN)).toList();
        assertThat(lost).isEqualTo(new DownloadResult.Failed(TransferFailureCause.TRANSPORT_LOST));
        assertThat(lostBed.sessions().discards()).isEqualTo(1);
        assertThat(warnings).hasSize(1);
        assertThat(warnings.get(0).getThrowableProxy()).isNotNull();
        assertThat(warnings.get(0).getThrowableProxy().getClassName()).isEqualTo(ClosedChannelException.class.getName());
        assertThat(warnings.get(0).getFormattedMessage()).doesNotContain("x.bin");
        lostBed.close();
        transferAppender.list.clear();

        CancelSource cancel = new CancelSource();
        TransferBed cancelledBed = start(TransferBed.Sudo.WORKS, new SftpChannels() {
            @Override
            public SftpClient open(ClientSession session) throws IOException {
                cancel.cancel();
                session.close(true).await(Duration.ofSeconds(5));
                throw new ClosedChannelException();
            }
        }, sshd -> { });

        DownloadResult cancelled = cancelledBed.transfer().download(elevatedDownload("/x.bin"), new TransferBed.Collecting(), cancelledBed.control(cancel));

        List<ILoggingEvent> cancelWarnings = transferAppender.list.stream().filter(event -> event.getLevel().isGreaterOrEqual(Level.WARN)).toList();
        assertThat(cancelled).isEqualTo(new DownloadResult.Failed(TransferFailureCause.CANCELLED));
        assertThat(cancelWarnings).as("a stop by the owner is not logged as a lost connection").isEmpty();
    }

    // ---- S3-EL-03, S3-EL-04: the plan decides ----

    @Test
    @DisplayName("S3-EL-03 S3-EL-04 RC-B-616 a Windows connection that is the administrator's is granted and the file work is the plain SFTP work: the probe is the only command")
    void administratorGranted() throws IOException {
        ScriptedExecutor executor = ScriptedExecutor.everythingSucceeds();
        TransferBed testBed = start(TransferBed.Sudo.NO_SERVER, executor);

        UploadResult result = testBed.transfer().upload(elevatedUpload("/admin.bin", OverwriteMode.FAIL_IF_EXISTS, ADMINISTRATOR), TransferBed.source(CONTENT), testBed.control());

        assertThat(result).isInstanceOf(UploadResult.Published.class);
        assertThat(Files.readAllBytes(root.resolve("admin.bin"))).isEqualTo(CONTENT);
        assertThat(executor.commandLines()).containsExactly("powershell.exe -NoLogo admin-probe");
    }

    @Test
    @DisplayName("S3-EL-03 RC-B-616 a connection that is not the administrator's is refused before the transfer: PERMISSION_DENIED, nothing written, no SFTP session")
    void administratorDenied() throws IOException {
        TransferBed testBed = start(TransferBed.Sudo.NO_SERVER, new ScriptedExecutor(commandLine -> new ScriptedExecutor.Answer.Exits(1, "")));

        UploadResult result = testBed.transfer().upload(elevatedUpload("/admin.bin", OverwriteMode.FAIL_IF_EXISTS, ADMINISTRATOR), TransferBed.source(CONTENT), testBed.control());

        assertThat(result).isEqualTo(new UploadResult.Refused(TransferErrorCode.PERMISSION_DENIED, new FileDiagnostic.None(), CleanupState.COMPLETE));
        assertThat(root.resolve("admin.bin")).doesNotExist();
        assertThat(testBed.sessions().leases()).isZero();
    }

    @Test
    @DisplayName("S3-EL-04 the plan decides the route: a sudo plan never runs the Windows probe, a Windows plan never starts a sudo server")
    void planDecidesTheRoute() throws IOException {
        ScriptedExecutor executor = ScriptedExecutor.everythingSucceeds();
        TransferBed testBed = start(TransferBed.Sudo.WORKS, executor);

        testBed.transfer().upload(elevatedUpload("/a.bin", OverwriteMode.FAIL_IF_EXISTS, SUDO), TransferBed.source(CONTENT), testBed.control());
        assertThat(executor.commandLines()).isEmpty();

        testBed.transfer().upload(elevatedUpload("/b.bin", OverwriteMode.FAIL_IF_EXISTS, ADMINISTRATOR), TransferBed.source(CONTENT), testBed.control());
        assertThat(executor.commandLines()).containsExactly("powershell.exe -NoLogo admin-probe");
        assertThat(Files.readAllBytes(root.resolve("b.bin"))).isEqualTo(CONTENT);
    }

    @Test
    @DisplayName("S3-EL-03 S3-EL-04 the probe's exit code is the whole answer: 0 grants, another code denies, a probe that does not end is Unavailable, a plan without elevation has no target here")
    void checkElevation() throws IOException {
        TransferBed granted = start(TransferBed.Sudo.NO_SERVER, ScriptedExecutor.everythingSucceeds());
        ElevationRequest sudoRequest = new ElevationRequest(TransferBed.TARGET, SUDO, ExecutionLimits.defaults());
        assertThat(granted.transfer().checkElevation(sudoRequest, granted.control())).isEqualTo(new ElevationResult.Granted());
        granted.close();

        TransferBed denied = start(TransferBed.Sudo.NO_SERVER, new ScriptedExecutor(commandLine -> new ScriptedExecutor.Answer.Exits(1, "")));
        assertThat(denied.transfer().checkElevation(sudoRequest, denied.control())).isEqualTo(new ElevationResult.Denied());
        denied.close();

        TransferBed lost = start(TransferBed.Sudo.NO_SERVER, new ScriptedExecutor(commandLine -> new ScriptedExecutor.Answer.Lost()));
        assertThat(lost.transfer().checkElevation(new ElevationRequest(TransferBed.TARGET, ADMINISTRATOR, ExecutionLimits.defaults()), lost.control()))
            .isEqualTo(new ElevationResult.Unavailable(TransferFailureCause.TRANSPORT_LOST));
        lost.close();

        TransferBed none = start(TransferBed.Sudo.NO_SERVER, ScriptedExecutor.everythingSucceeds());
        assertThat(none.transfer().checkElevation(new ElevationRequest(TransferBed.TARGET, new ElevationPlan.NotSupported(), ExecutionLimits.defaults()), none.control()))
            .isEqualTo(new ElevationResult.Unavailable(TransferFailureCause.CONNECT_FAILED));
        assertThat(none.sessions().leases()).isZero();
    }

    @Test
    @DisplayName("S-D0149-03 D-0149 an upload with a link probe and Windows administrator elevation invokes the dispatch guard at each of its three remote steps and sends nothing when the first refuses")
    void linkProbeAndAdministratorUploadCheckTheGuardAtEachStep() throws IOException {
        ScriptedExecutor linkExecutor = ScriptedExecutor.everythingSucceeds();
        HiddenLinkProbe linkProbe = new HiddenLinkProbe() {
            @Override
            public boolean applies(RemotePath path) {
                return true;
            }

            @Override
            public Verdict check(RemoteTarget target, RemotePath path, TransferLimits limits, ExecutionControl control) {
                PreparedCommand command = new PreparedCommand("powershell.exe -NoLogo link-probe", StdinBytes.none(), CommandKind.COMMAND);
                linkExecutor.run(new RemoteExecution(target, command, ExecutionLimits.defaults(), ExecReply.NOT_AWAITED), frame -> new SinkResult.Accepted(), control);
                return new HiddenLinkProbe.Clear();
            }
        };
        bed = TransferBed.start(root, List.of(linkProbe));
        AtomicInteger guardCalls = new AtomicInteger();
        ExecutionControl counting = new ExecutionControl(new CancelSource(), guardCalls::incrementAndGet);

        UploadResult published = bed.transfer().upload(elevatedUpload("/guarded.bin", OverwriteMode.FAIL_IF_EXISTS, ADMINISTRATOR), TransferBed.source(CONTENT), counting);

        assertThat(published).isInstanceOf(UploadResult.Published.class);
        assertThat(guardCalls).hasValue(3);
        assertThat(linkExecutor.commandLines()).containsExactly("powershell.exe -NoLogo link-probe");
        assertThat(Files.readAllBytes(root.resolve("guarded.bin"))).isEqualTo(CONTENT);
        assertThat(bed.sessions().leases()).isEqualTo(1);

        DispatchRefusedException targetChanged = new DispatchRefusedException(DispatchFailure.refused(DispatchReason.TARGET_CHANGED, DispatchStage.TARGET_GUARD));
        ExecutionControl refusing = new ExecutionControl(new CancelSource(), () -> {
            throw targetChanged;
        });

        assertThatThrownBy(() -> bed.transfer().upload(elevatedUpload("/refused.bin", OverwriteMode.FAIL_IF_EXISTS, ADMINISTRATOR), TransferBed.source(CONTENT), refusing))
            .isSameAs(targetChanged);
        assertThat(linkExecutor.commandLines()).as("the first guard refused before the link probe sent a command").hasSize(1);
        assertThat(bed.sessions().leases()).as("no second SFTP session was leased").isEqualTo(1);
        assertThat(root.resolve("refused.bin")).doesNotExist();
    }

    @Test
    @DisplayName("SudoSftpChannels awaits the lookup channel close before opening sudo -n so elevated transfers succeed on a MaxSessions=1 server over one pooled session")
    void elevatedTransferSucceedsUnderMaxSessionsOne() throws IOException {
        Files.createDirectories(root.resolve("usr/lib/openssh"));
        Files.writeString(root.resolve("usr/lib/openssh/sftp-server"), "placeholder");
        AtomicReference<Channel> activeSessionChannel = new AtomicReference<>();
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setDaemon(true);
        scheduler.initialize();
        try (EmbeddedSshServer pooledServer = EmbeddedSshServer.builder()
                .password(TestSessions.USER, TestSessions.PASSWORD)
                .sftp(root)
                .customize(sshd -> {
                    CommandFactory original = sshd.getCommandFactory();
                    SftpSubsystemFactory subsystem = new SftpSubsystemFactory.Builder().build();
                    sshd.setCommandFactory((channel, command) -> TransferBed.SUDO_COMMAND.equals(command)
                        ? subsystem.createSubsystem(channel) : original.createCommand(channel, command));
                    sshd.setChannelFactories(sshd.getChannelFactories().stream().map(factory -> "session".equals(factory.getName())
                        ? (ChannelFactory) new ChannelFactory() {
                            @Override
                            public String getName() {
                                return factory.getName();
                            }

                            @Override
                            public Channel createChannel(Session session) throws IOException {
                                Channel current = activeSessionChannel.get();
                                if (current != null && !current.isClosing() && !current.isClosed()) {
                                    return null;
                                }
                                Channel created = factory.createChannel(session);
                                if (created == null) {
                                    return null;
                                }
                                if (!activeSessionChannel.compareAndSet(current, created)) {
                                    created.close(true);
                                    return null;
                                }
                                created.addCloseFutureListener(future -> activeSessionChannel.compareAndSet(created, null));
                                return created;
                            }
                        }
                        : factory).toList());
                })
                .start();
             AdapterFixture fixture = AdapterFixture.start(AdapterFixture.defaultSettings())) {
            fixture.profiles().put("writer", AdapterFixture.profileFor(pooledServer, TestSessions.USER));
            fixture.credentials().password("writer", TestSessions.PASSWORD);
            MinaRemoteFileTransfer pooledTransfer = pooledTransfer(fixture, scheduler);
            RemoteTarget target = new RemoteTarget(new TargetId("files"), TargetRevision.of("a".repeat(64)), new ProfileAlias("writer"), AccessMode.READ_WRITE);
            Sha256Digester digester = new Sha256Digester();

            UploadRequest uploadRequest = new UploadRequest(target, new PosixRemotePath("/max1.bin"), CONTENT.length, digester.digest(CONTENT),
                OverwriteMode.FAIL_IF_EXISTS, new FileAccess.Elevated(SUDO), new PathPolicyBinding.NoRootPolicy(), TransferLimits.defaults());
            UploadResult uploaded = pooledTransfer.upload(uploadRequest, TransferBed.source(CONTENT), AdapterFixture.control());

            assertThat(uploaded).isInstanceOf(UploadResult.Published.class);
            assertThat(Files.readAllBytes(root.resolve("max1.bin"))).isEqualTo(CONTENT);

            TransferBed.Collecting sink = new TransferBed.Collecting();
            DownloadRequest downloadRequest = new DownloadRequest(target, new PosixRemotePath("/max1.bin"), new FileAccess.Elevated(SUDO),
                new PathPolicyBinding.NoRootPolicy(), TransferLimits.defaults());
            DownloadResult downloaded = pooledTransfer.download(downloadRequest, sink, AdapterFixture.control());

            assertThat(downloaded).isEqualTo(new DownloadResult.Fetched(CONTENT.length, digester.digest(CONTENT)));
            assertThat(sink.bytes()).isEqualTo(CONTENT);
            assertThat(pooledServer.acceptedConnections().size()).as("both elevated transfers reused the single pooled session").isEqualTo(1);
        } finally {
            scheduler.shutdown();
        }
    }

    @Test
    @DisplayName("Three consecutive SFTP channel-open refusals signal openRejected() to SshSessionPool and evict the pooled session")
    void threeConsecutiveSftpChannelOpenRefusalsEvictThePooledSession() throws IOException {
        Files.write(root.resolve("probe.txt"), CONTENT);
        SshSettings defaults = AdapterFixture.defaultSettings();
        SshSettings settings = new SshSettings(defaults.connectTimeout(), defaults.heartbeatInterval(), defaults.heartbeatNoReplyMax(), defaults.idleProbeAfter(),
            defaults.execReplyTimeout(), defaults.channelPermits(), 3, defaults.hostKeyMode(), defaults.knownHosts());
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setDaemon(true);
        scheduler.initialize();
        try (EmbeddedSshServer pooledServer = EmbeddedSshServer.builder()
                .password(TestSessions.USER, TestSessions.PASSWORD)
                .sftp(root)
                .start();
             AdapterFixture fixture = AdapterFixture.start(settings)) {
            fixture.profiles().put("writer", AdapterFixture.profileFor(pooledServer, TestSessions.USER));
            fixture.credentials().password("writer", TestSessions.PASSWORD);
            MinaRemoteFileTransfer pooledTransfer = pooledTransfer(fixture, scheduler);
            RemoteTarget target = new RemoteTarget(new TargetId("files"), TargetRevision.of("a".repeat(64)), new ProfileAlias("writer"), AccessMode.READ_WRITE);
            DownloadRequest plainDownload = new DownloadRequest(target, new PosixRemotePath("/probe.txt"), new FileAccess.Direct(),
                new PathPolicyBinding.NoRootPolicy(), TransferLimits.defaults());

            assertThat(pooledTransfer.download(plainDownload, new TransferBed.Collecting(), AdapterFixture.control()))
                .isInstanceOf(DownloadResult.Fetched.class);
            assertThat(pooledServer.acceptedConnections().size()).isEqualTo(1);

            pooledServer.refuseNewChannels(true);
            for (int i = 0; i < 3; i++) {
                assertThat(pooledTransfer.download(plainDownload, new TransferBed.Collecting(), AdapterFixture.control()))
                    .isEqualTo(new DownloadResult.Failed(TransferFailureCause.CHANNEL_OPEN_FAILED));
            }
            pooledServer.refuseNewChannels(false);

            TransferBed.Collecting sink = new TransferBed.Collecting();
            assertThat(pooledTransfer.download(plainDownload, sink, AdapterFixture.control()))
                .isInstanceOf(DownloadResult.Fetched.class);
            assertThat(sink.bytes()).isEqualTo(CONTENT);
            assertThat(pooledServer.acceptedConnections().size())
                .as("three consecutive SFTP channel-open refusals evicted the old session and opened a fresh connection")
                .isEqualTo(2);
        } finally {
            scheduler.shutdown();
        }
    }

    private static MinaRemoteFileTransfer pooledTransfer(AdapterFixture fixture, ThreadPoolTaskScheduler scheduler) {
        SftpChannels channels = new SftpChannels();
        SftpFailureClassifier classifier = new SftpFailureClassifier();
        RemotePathChecker checker = new RemotePathChecker((target, path) -> new PathPolicyDecision.NotApplicable(), classifier);
        AtomicReplacer replacer = new AtomicReplacer(checker, classifier, new SecureRandom(), Clock.systemUTC());
        SftpUploader uploader = new SftpUploader(checker, replacer, classifier, channels, new SpaceProbe());
        SftpDownloader downloader = new SftpDownloader(checker, classifier, channels);
        TargetSource noTargets = id -> new TargetLookup.NotRegistered(id);
        ElevationProbe probe = new ElevationProbe(ScriptedExecutor.everythingSucceeds(), noTargets);
        SudoSftpChannels sudoChannels = new SudoSftpChannels(channels, classifier,
            target -> List.of(new SftpServerPath(TransferBed.EMPTY_PLACE), new SftpServerPath(TransferBed.SUDO_SERVER)));
        return new MinaRemoteFileTransfer(new PoolSessionSource(fixture.pool()), channels, checker, uploader, downloader, classifier, sudoChannels, probe, scheduler,
            Clock.systemUTC(), List.of());
    }
}
