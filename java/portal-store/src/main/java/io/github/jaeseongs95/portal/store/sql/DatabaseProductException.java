package io.github.jaeseongs95.portal.store.sql;

import java.io.Serial;
import java.util.Objects;

/**
 * The business database cannot be used by {@link SqlBuilder}: its product has no SQL here, the product could not be read from the connection, or the configured JDBC
 * URL names another product than the connected one. These are start-up and configuration failures, not refusals of a request, so this is not a {@code PortalException}
 * and adds no code to the public contract (the same reasoning as {@code ArtifactStoreCorruptedException}). The message holds the {@link Reason} and the closed values of
 * the products (the names of {@link SqlBuilder.Database} or of Spring Boot's {@code DatabaseDriver}); the caught exception is the {@code cause} only.
 */
public final class DatabaseProductException extends IllegalStateException {

    /** Why the product cannot be used. Ported from the codes of AGS {@code StoreCode}. */
    public enum Reason {
        /** The product is not one of {@link SqlBuilder.Database}. */
        UNSUPPORTED_DATABASE_PRODUCT,
        /** The product name could not be read from the connection. */
        STORE_UNREACHABLE,
        /** The JDBC URL names another product than the connection reports. */
        DATABASE_PRODUCT_MISMATCH
    }

    @Serial
    private static final long serialVersionUID = 1L;

    private final Reason reason;

    DatabaseProductException(Reason reason, String message, Throwable cause) {
        super(Objects.requireNonNull(message, "message"), cause);
        this.reason = Objects.requireNonNull(reason, "reason");
    }

    DatabaseProductException(Reason reason, String message) {
        this(reason, message, null);
    }

    public Reason reason() {
        return reason;
    }
}
