package io.github.jaeseongs95.portal.ssh.sftp;

import io.github.jaeseongs95.portal.app.remote.RemoteTarget;
import io.github.jaeseongs95.portal.domain.error.TransferErrorCode;
import java.io.EOFException;
import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.sftp.client.SftpClient;
import org.apache.sshd.sftp.common.SftpException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.stereotype.Component;

/**
 * Opens an SFTP channel with elevated rights on a POSIX target: the SFTP server of the target started with {@code sudo -n} on an {@code exec} channel and spoken to with
 * MINA's own client ({@link ExecSftpClient}; stage 3 design 6.1, decision Q8, shown to work on a real server by pilot P3-4). An elevated transfer is then the same adapter
 * code as a plain one over another channel: the same path checks, the same replacement, the same download, with the owner and group of a replaced file given back.
 *
 * <p>The server program is found over the plain SFTP channel: the first of the places {@link SftpServerPaths} names for the target (the path an administrator configured
 * for it, then the usual places of the distributions of the OpenSSH packages, {@link DefaultSftpServerPaths#USUAL_PLACES}) that holds a regular file. The path is written
 * into the command as one quoted shell word ({@link ShellWord}), so a configured path cannot become code. No second program is run for that, and {@code sudo -n} itself is
 * the only external program: it is the target's way
 * to give the rights, and Java cannot replace it (docs/external-programs). A target where no server program is found, or where {@code sudo -n} does not start it (it
 * needs a password, the account is not allowed) has no elevated file work: the caller refuses the request, and nothing was written.
 *
 * <p>What the opening came to keeps apart what is different (defect D-0013): a place that holds nothing (status "no such file") is the next place and then "no server";
 * a server that answers the look with another refusal is that refusal; a program whose channel closes before it speaks SFTP is {@link Opening.NotStarted};
 * a server that refuses to open a channel while the session lives (it reached its {@code MaxSessions}, or does not offer the subsystem), a program that does not
 * answer in time and a wait that is interrupted are all {@link Opening.ChannelFailed}: the session is fine and the request is not refused, the elevated channel is
 * not there, as on the plain path (the caller has no other use for telling them apart, so they are one result); only a session that is gone is {@link Opening.Lost}.
 * The failure to close the channel that looked is the channel's own and does not change what the look found.
 */
@Component
public class SudoSftpChannels {

    private static final Logger LOG = LoggerFactory.getLogger(SudoSftpChannels.class);

    /** What opening the elevated channel came to. */
    public sealed interface Opening permits Opening.Opened, Opening.NoServer, Opening.NotStarted, Opening.ChannelFailed, Opening.LookRefused,
        Opening.Lost {
        /**
         * The server program runs under {@code sudo -n}; the caller closes the client.
         *
         * @param client the SFTP client
         */
        record Opened(SftpClient client) implements Opening {
            public Opened {
                Objects.requireNonNull(client, "client");
            }
        }

        /** The target has no SFTP server program in the usual places. */
        record NoServer() implements Opening {
        }

        /**
         * The program was found and its channel closed before it answered the SFTP version request: {@code sudo -n} ended without starting it, as it does when the
         * account may not use it without a password.
         */
        record NotStarted() implements Opening {
        }

        /**
         * The session lives and there is no elevated channel, without a refusal of the request: the server refused to open a channel (the one that looks for the program
         * or the one that runs it), or the program was found and its SFTP initialization ended without an answer or was interrupted, so whether it started is not known.
         * Neither is a refusal nor a start; the caller treats both as a channel that could not be opened.
         */
        record ChannelFailed() implements Opening {
        }

        /**
         * The server answered the look for the program with a refusal (no permission to ask, a path it cannot use): the answer says nothing about the program.
         *
         * @param code why
         */
        record LookRefused(TransferErrorCode code) implements Opening {
            public LookRefused {
                Objects.requireNonNull(code, "code");
            }
        }

        /**
         * The connection failed.
         *
         * @param cause the exception that showed the loss
         */
        record Lost(IOException cause) implements Opening {
            public Lost {
                Objects.requireNonNull(cause, "cause");
            }
        }
    }

    private final SftpChannels plain;
    private final SftpFailureClassifier classifier;
    private final SftpServerPaths serverPaths;

    /**
     * @param plain opens the plain SFTP channel that looks for the server program
     * @param classifier the meaning of an SFTP failure
     * @param serverPaths the places to look for the server program of a target, in order
     */
    @Autowired
    public SudoSftpChannels(SftpChannels plain, SftpFailureClassifier classifier, SftpServerPaths serverPaths) {
        this.plain = Objects.requireNonNull(plain, "plain");
        this.classifier = Objects.requireNonNull(classifier, "classifier");
        this.serverPaths = Objects.requireNonNull(serverPaths, "serverPaths");
    }

    /**
     * The usual places of the distributions only.
     *
     * @param plain opens the plain SFTP channel that looks for the server program
     * @param classifier the meaning of an SFTP failure
     */
    public SudoSftpChannels(SftpChannels plain, SftpFailureClassifier classifier) {
        this(plain, classifier, new DefaultSftpServerPaths());
    }

    /**
     * Opens the elevated channel of a transfer: as {@link #open(ClientSession, RemoteTarget)}, and a stop of the transfer that comes while a channel is being opened
     * (the one that looks for the program, or the one that runs it) cancels that open at once (defect SSH_PORTAL-D-0107).
     *
     * @param session an authenticated session
     * @param target the target of the session
     * @param watch the watch of the transfer
     * @return the open client, or why there is none
     */
    public Opening open(ClientSession session, RemoteTarget target, TransferWatch watch) {
        try {
            return GuardedSftpClient.whenOpening(opening -> watch.interruptWith(() -> opening.cancel()), () -> open(session, target));
        } catch (IOException e) {
            throw new IllegalStateException("The opening of an elevated channel reports its failures as a result, not as an exception", e);
        }
    }

    /**
     * Opens the elevated channel on the session.
     *
     * @param session an authenticated session
     * @param target the target of the session, whose places of the server program are looked in
     * @return the open client, or why there is none
     */
    public Opening open(ClientSession session, RemoteTarget target) {
        Objects.requireNonNull(target, "target");
        SftpClient look;
        try {
            look = plain.open(session);
        } catch (IOException e) {
            LOG.debug("The SFTP channel that looks for the server program could not be opened", e);
            return session.isOpen() ? new Opening.ChannelFailed() : new Opening.Lost(e);
        }
        Optional<String> server;
        try {
            server = serverProgram(look, serverPaths.candidates(target));
        } catch (IOException e) {
            LOG.debug("The look for the SFTP server program failed", e);
            return switch (classifier.verdict(e, SftpFailureClassifier.Site.TARGET_FILE)) {
                case SftpFailureClassifier.Verdict.Refusal refusal -> new Opening.LookRefused(refusal.code());
                case SftpFailureClassifier.Verdict.Lost lost -> new Opening.Lost(e);
            };
        } finally {
            plain.closeQuietly(look);
        }
        if (server.isEmpty()) {
            return new Opening.NoServer();
        }
        try {
            return new Opening.Opened(ExecSftpClient.open(session, "sudo -n " + new ShellWord(server.orElseThrow()).quoted()));
        } catch (IOException e) {
            LOG.debug("The SFTP server program did not start under sudo -n", e);
            return failedStart(session, e);
        }
    }

    /**
     * What the failure of the start of the server program comes to. MINA's client opens the channel and then waits for the answer of the program to its SFTP version
     * request (the wait is {@code SftpModuleProperties.SFTP_CHANNEL_OPEN_TIMEOUT}, 15 seconds by default), and ends the opening in one of these ways:
     * <ul>
     *   <li>the session is gone: {@link Opening.Lost};</li>
     *   <li>the channel closed before the version answer ({@code EOFException}, "Closing while await init message"): the program ended without speaking SFTP, which is
     *       what {@code sudo -n} does when it needs a password or the account may not use it: {@link Opening.NotStarted}, the only case that is a refusal;</li>
     *   <li>anything else with a live session: the server answered the request to open the channel with an open failure ({@code SshChannelOpenException}), the wait
     *       ended without an answer ({@code SocketTimeoutException}), or the thread was interrupted ({@code InterruptedIOException} that carries an
     *       {@code InterruptedException}; its interrupt status is set again here). Whether the program started is not known, and the caller does not need to tell these
     *       apart: {@link Opening.ChannelFailed}.</li>
     * </ul>
     */
    private Opening failedStart(ClientSession session, IOException failure) {
        // MINA's exceptions wrap what happened, so the most specific cause of the chain is the one to ask. Spring's NestedExceptionUtils is the standard tool for a chain
        // of causes (org.springframework.core.NestedExceptionUtils#getMostSpecificCause); Commons Lang's ExceptionUtils is not a dependency of the portal.
        Throwable cause = NestedExceptionUtils.getMostSpecificCause(failure);
        if (cause instanceof InterruptedException) {
            // A thread that is interrupted in the wait ends it with an InterruptedIOException that carries the InterruptedException, and the status is cleared by then. (A
            // SocketTimeoutException is an InterruptedIOException too, but nobody interrupted the thread: it is the wait that ended.)
            Thread.currentThread().interrupt();
        }
        if (!session.isOpen()) {
            return new Opening.Lost(failure);
        }
        if (failure instanceof EOFException) {
            return new Opening.NotStarted();
        }
        return new Opening.ChannelFailed();
    }

    /**
     * The first of the places that holds a regular file. A place the server says holds nothing (status {@code NO_SUCH_FILE} or {@code NO_SUCH_PATH}) is the next place;
     * every other failure of the look ends the search and is thrown, because it does not say the program is not there.
     */
    private Optional<String> serverProgram(SftpClient look, List<SftpServerPath> places) throws IOException {
        for (SftpServerPath place : places) {
            String path = place.value();
            try {
                if (look.stat(path).isRegularFile()) {
                    return Optional.of(path);
                }
            } catch (SftpException e) {
                if (classifier.classify(e).kind() != SftpFailure.Kind.NOT_FOUND) {
                    throw e;
                }
                LOG.debug("No SFTP server program at {} (status {})", path, e.getStatus(), e);
            }
        }
        return Optional.empty();
    }
}
