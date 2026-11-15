package io.github.jaeseongs95.portal.ssh.profile;

import java.io.Serial;
import java.util.Objects;

/**
 * The profile configuration cannot be used: it is not readable, it is not TOML, it breaks the schema, two profile names would share one credential prefix, the secret
 * environment file has an entry that does not fit, or the file of known hosts is not a regular file. Who may use a file or who owns it is not a reason (decision D-0117). It is raised while the configuration is
 * loaded, so the product does not start with a configuration it would misread (2.x {@code OperatorError}). The {@link #code()} names the category; the text names the file
 * and the keys, never a value of the file. The hierarchy is sealed: {@link ConfigNotFoundException} is the one kind that has a member of its own.
 */
public sealed class ProfileConfigException extends RuntimeException permits ConfigNotFoundException {

    /** The category of a configuration failure. */
    public enum Code {
        /** There is no file at the path. */
        NOT_FOUND,
        /** The file is there and cannot be read (a folder in place of the file, or an I/O failure). */
        UNREADABLE,
        /** The text is not TOML. */
        NOT_TOML,
        /** The file breaks the schema: an unknown key, a missing key, a wrong type or a value out of range. */
        INVALID_SCHEMA,
        /** Two profile names give one credential prefix. */
        CREDENTIAL_PREFIX_CLASH,
        /** A line of the secret environment file does not fit. */
        SECRET_FILE_ENTRY,
        /** The path of the file of known hosts is not a regular file (a folder is there). */
        KNOWN_HOSTS_UNSAFE
    }

    @Serial
    private static final long serialVersionUID = 1L;

    private final Code code;

    public ProfileConfigException(Code code, String message) {
        super(message);
        this.code = Objects.requireNonNull(code, "code");
    }

    public ProfileConfigException(Code code, String message, Throwable cause) {
        super(message, cause);
        this.code = Objects.requireNonNull(code, "code");
    }

    /** The category of the failure. */
    public Code code() {
        return code;
    }
}
