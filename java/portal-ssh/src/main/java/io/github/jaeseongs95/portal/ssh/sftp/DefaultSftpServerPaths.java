package io.github.jaeseongs95.portal.ssh.sftp;

import io.github.jaeseongs95.portal.app.remote.RemoteTarget;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Component;

/**
 * The places of the SFTP server program that no configuration changes: the usual ones of the OpenSSH packages (Debian and Ubuntu, Red Hat and Fedora, Arch, and BSD-like
 * systems), for every target, after the places an administrator named (those come first, so a target whose program is elsewhere is found at once).
 */
@Component
public final class DefaultSftpServerPaths implements SftpServerPaths {

    /** Where the packages of OpenSSH put the SFTP server. */
    public static final List<SftpServerPath> USUAL_PLACES = List.of(new SftpServerPath("/usr/lib/openssh/sftp-server"), new SftpServerPath("/usr/libexec/openssh/sftp-server"),
        new SftpServerPath("/usr/lib/ssh/sftp-server"), new SftpServerPath("/usr/libexec/sftp-server"));

    private final List<SftpServerPath> named;

    /** The usual places only. */
    public DefaultSftpServerPaths() {
        this(List.of());
    }

    /**
     * @param named the places the administrator named, tried before the usual ones
     */
    public DefaultSftpServerPaths(List<SftpServerPath> named) {
        this.named = List.copyOf(Objects.requireNonNull(named, "named"));
    }

    @Override
    public List<SftpServerPath> candidates(RemoteTarget target) {
        List<SftpServerPath> places = new ArrayList<>(named);
        USUAL_PLACES.stream().filter(place -> !places.contains(place)).forEach(places::add);
        return List.copyOf(places);
    }
}
