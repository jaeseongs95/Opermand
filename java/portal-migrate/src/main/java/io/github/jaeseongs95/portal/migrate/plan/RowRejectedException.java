package io.github.jaeseongs95.portal.migrate.plan;

import java.io.Serial;

/** A 2.x row cannot be written to 3.0 exactly as it is (a value the 3.0 model refuses, or one this tool does not know); the row is reported and nothing is changed to fit. */
final class RowRejectedException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    RowRejectedException(String message) {
        super(message);
    }
}
