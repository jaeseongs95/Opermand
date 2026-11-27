package io.github.jaeseongs95.opermand.ssh.sftp;

import io.github.jaeseongs95.opermand.app.remote.RemoteTarget;
import java.util.List;

/**
 * Where {@link SudoSftpChannels} looks for the SFTP server program of a target, in order (defect SSH_PORTAL-D-0171). The places depend on the target, not on this server: a
 * distribution or a hand-made installation may put the program anywhere. The adapter owns this interface and asks it; what answers it, the usual places of the OpenSSH
 * packages ({@link DefaultSftpServerPaths}) or the path an administrator configured for one target, is the assembly's choice (a bean of this type that is {@code @Primary}
 * replaces the default).
 */
@FunctionalInterface
public interface SftpServerPaths {

    /**
     * @param target the target the elevated channel is for
     * @return the places to look, first to last; never empty
     */
    List<SftpServerPath> candidates(RemoteTarget target);
}
