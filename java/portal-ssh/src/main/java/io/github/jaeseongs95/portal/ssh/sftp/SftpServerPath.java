package io.github.jaeseongs95.portal.ssh.sftp;

import java.util.Objects;

/**
 * Where the SFTP server program of a POSIX target is: an absolute path of the target, as the administrator configured it or as a distribution's package puts it. The path
 * becomes a word of the command {@code sudo -n <path>} (always written through {@link ShellWord}), so the type refuses what is no path of a POSIX target: a path that is
 * not absolute, one with a control character (a command line has no line break or NUL), and one longer than 512 characters.
 *
 * @param value the path
 */
public record SftpServerPath(String value) {

    private static final int MAX_LENGTH = 512;

    public SftpServerPath {
        Objects.requireNonNull(value, "value");
        if (!value.startsWith("/") || value.length() > MAX_LENGTH || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("The SFTP server program of a target is an absolute path of at most " + MAX_LENGTH + " characters without a control character");
        }
    }
}
