package io.github.jaeseongs95.opermand.migrate.source;

import java.io.Serial;

/** The 2.x data cannot be read safely at all (as opposed to one row that cannot be translated): the migration stops with an error and writes nothing. */
public final class SourceUnusableException extends RuntimeException {

    /** Why the source cannot be used. */
    public enum Problem {
        NOT_A_DIRECTORY, UNSAFE_FILE, NOT_QUIESCENT, UNSUPPORTED_SCHEMA, UNREADABLE
    }

    @Serial
    private static final long serialVersionUID = 1L;

    private final Problem problem;

    public SourceUnusableException(Problem problem, String message) {
        super(message);
        this.problem = problem;
    }

    public SourceUnusableException(Problem problem, String message, Throwable cause) {
        super(message, cause);
        this.problem = problem;
    }

    public Problem problem() {
        return problem;
    }
}
