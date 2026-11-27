package io.github.jaeseongs95.opermand.domain.target;

import io.github.jaeseongs95.opermand.domain.text.Utf8Text;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * The absolute path of the Python interpreter on a POSIX target (defects SSH_PORTAL-D-0170 and SSH_PORTAL-D-0172): the counterpart of {@link WindowsPythonExecutable},
 * so that a Linux target whose Python is not {@code /usr/bin/python3} (a distribution that keeps it in {@code /usr/local/bin}, or one that has only {@code python3.12})
 * can run a source as a Windows target can. The path is one quoted word of the command line, so it is refused when it is not an absolute POSIX path naming a Python
 * binary ({@code python}, {@code python3}, {@code python3.12}), has an ISO control character or NUL, or exceeds {@value #MAX_BYTES} UTF-8 bytes.
 *
 * @param value the absolute POSIX path of the Python executable
 */
public record PosixPythonExecutable(String value) {

    /** Maximum length in UTF-8 bytes (aligned with POSIX receiver command-line limits and stage 4 B-3). */
    public static final int MAX_BYTES = 512;

    private static final Pattern BASENAME = Pattern.compile("python(?:[0-9]++(?:\\.[0-9]++)*+)?");

    /** The interpreter 2.x ran on every Linux target when no override is configured. */
    public static final PosixPythonExecutable DEFAULT = new PosixPythonExecutable("/usr/bin/python3");

    public PosixPythonExecutable {
        Objects.requireNonNull(value, "value");
        if (!value.startsWith("/") || !Utf8Text.isWellFormed(value) || new Utf8Text(value).byteLength() > MAX_BYTES
            || value.chars().anyMatch(Character::isISOControl) || !hasValidSegmentsAndName(value)) {
            throw new IllegalArgumentException("Use an absolute POSIX path of a Python interpreter (python, python3, python3.12) of at most " + MAX_BYTES
                + " UTF-8 bytes without a control character");
        }
    }

    private static boolean hasValidSegmentsAndName(String path) {
        if (path.endsWith("/")) {
            return false;
        }
        int lastSlash = path.lastIndexOf('/');
        if (lastSlash < 0 || path.contains("//")) {
            return false;
        }
        String[] segments = path.substring(1).split("/");
        for (String segment : segments) {
            if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment)) {
                return false;
            }
        }
        String basename = path.substring(lastSlash + 1);
        return BASENAME.matcher(basename).matches();
    }
}
