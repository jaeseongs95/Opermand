package io.github.jaeseongs95.portal.migrate.cli;

import java.io.Serial;

/** The command line is wrong; the message says how. */
final class UsageException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    UsageException(String message) {
        super(message);
    }
}
