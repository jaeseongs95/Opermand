package io.github.jaeseongs95.portal.ssh.platform;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * The absolute path of the Python interpreter on a POSIX target (defect SSH_PORTAL-D-0172): the counterpart of the Windows target's {@code WindowsPythonExecutable}, so that
 * a Linux target whose Python is not {@code /usr/bin/python3} (a distribution that keeps it in {@code /usr/local/bin}, one that has only {@code python3.12}) can run a
 * source as a Windows target can. The path is one quoted word of the command line, so it is refused when it is not an absolute path of a POSIX target that names a Python
 * ({@code python}, {@code python3}, {@code python3.12}), has a control character, or is longer than 512 characters.
 *
 * @param value the path
 */
public record PosixPythonExecutable(String value) {

    private static final int MAX_LENGTH = 512;
    private static final Pattern NAME = Pattern.compile("/(?:[^/]+/)*python(?:[0-9]+(?:\\.[0-9]+)*)?");

    /** The interpreter 2.x ran on every Linux target. */
    public static final PosixPythonExecutable DEFAULT = new PosixPythonExecutable("/usr/bin/python3");

    public PosixPythonExecutable {
        Objects.requireNonNull(value, "value");
        if (value.length() > MAX_LENGTH || value.chars().anyMatch(Character::isISOControl) || !NAME.matcher(value).matches()) {
            throw new IllegalArgumentException("Use an absolute POSIX path of a Python interpreter (python, python3, python3.12) of at most " + MAX_LENGTH
                + " characters without a control character");
        }
    }
}
