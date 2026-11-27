package io.github.jaeseongs95.opermand.domain.target;

import java.util.Objects;
import java.util.regex.Pattern;

/** The absolute path of the Python executable on a Windows target: a valid Windows location that ends in {@code python.exe} or {@code python3.12.exe} and the like (2.x targets.mjs:33). */
public record WindowsPythonExecutable(String value) {

    private static final Pattern BASENAME = Pattern.compile("python(?:[0-9]++(?:\\.[0-9]++)*+)?\\.exe", Pattern.CASE_INSENSITIVE);

    public WindowsPythonExecutable {
        Objects.requireNonNull(value, "value");
        new WindowsDirectory(value);
        int separator = Math.max(value.lastIndexOf('\\'), value.lastIndexOf('/'));
        if (separator < 0 || !BASENAME.matcher(value.substring(separator + 1)).matches()) {
            throw new IllegalArgumentException("Use an absolute Windows Python executable");
        }
    }
}
