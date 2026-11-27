package io.github.jaeseongs95.opermand.ssh.sftp;

import java.io.IOException;
import java.util.Objects;
import org.apache.sshd.sftp.client.SftpClient;
import org.apache.sshd.sftp.client.extensions.openssh.OpenSSHStatExtensionInfo;
import org.apache.sshd.sftp.client.extensions.openssh.OpenSSHStatPathExtension;
import org.apache.sshd.sftp.common.extensions.openssh.StatVfsExtensionParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Tells whether the file system of a directory has no space left, with {@code statvfs@openssh.com}. OpenSSH's {@code sftp-server} speaks SFTP version 3, which has no
 * status for a full disk: it answers the plain {@code FAILURE} (status 4) that every other refusal without a name gets. A write that fails that way is a full disk or
 * something else, and only a question to the file system tells which; the answer decides whether the result says {@code NoSpace} or only the status (defect D-0034: a
 * full disk must not read as a permission problem or a lost connection). A server without the extension, or one that does not answer, gives no proof, and the result
 * keeps the plain status.
 *
 * <p>Win32-OpenSSH offers the extension and answers it: its {@code sftp-server.c} advertises {@code statvfs@openssh.com} (only {@code fstatvfs@openssh.com} is left out on
 * Windows, {@code #ifndef WINDOWS}) and its {@code statvfs()} in {@code contrib/win32/win32compat/misc.c} answers from {@code GetDiskFreeSpaceW} (the free clusters are both the
 * free and the available blocks; a quota of the account is not part of that answer, so a full quota is not recognized and keeps the plain status). Checked against the
 * {@code latestw_all} branch of github.com/PowerShell/openssh-portable on 2026-11-21 and by {@code RealSftpTest} on a Windows server (defect SSH_PORTAL-D-0173).
 */
@Component
public class SpaceProbe {

    private static final Logger LOG = LoggerFactory.getLogger(SpaceProbe.class);

    /**
     * Whether the file system of the directory has no block left for an unprivileged user.
     *
     * @param client the SFTP client of the session
     * @param directory the SFTP path of the directory the file was being written to
     * @return true only when the server offers the extension, answers, and reports no available block
     */
    public boolean exhausted(SftpClient client, String directory) {
        Objects.requireNonNull(client, "client");
        Objects.requireNonNull(directory, "directory");
        if (!client.getServerExtensions().containsKey(StatVfsExtensionParser.NAME)) {
            LOG.debug("The server does not offer {}: whether the disk is full cannot be asked, and the plain status stands", StatVfsExtensionParser.NAME);
            return false;
        }
        try {
            OpenSSHStatExtensionInfo information = client.getExtension(OpenSSHStatPathExtension.class).stat(directory);
            return information.f_bavail == 0;
        } catch (IOException e) {
            LOG.debug("The server did not answer {} for the directory of the file: the plain status stands", StatVfsExtensionParser.NAME, e);
            return false;
        }
    }
}
