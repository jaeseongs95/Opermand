package io.github.jaeseongs95.portal.app.storage;

import java.util.Objects;

/** The answer of {@link StorageProtection#check}: the folders can be used, or the first folder that cannot (it is not a folder, or it could not be created private to its owner) and why. */
public sealed interface ProtectionCheck permits ProtectionCheck.Protected, ProtectionCheck.Unprotected {

    /** Every folder is a folder the stores can use (created private to its owner when it was missing). */
    record Protected() implements ProtectionCheck {
    }

    /**
     * A folder cannot be used (it is not a folder, or a missing folder could not be created private to its owner), or the check itself failed. The portal does not read or write bytes there, and it deletes none (case RC-B-004: a guard that fails keeps the
     * expired bytes).
     *
     * @param area the folder
     * @param cause what the check threw
     */
    record Unprotected(StorageArea area, Throwable cause) implements ProtectionCheck {

        public Unprotected {
            Objects.requireNonNull(area, "area");
            Objects.requireNonNull(cause, "cause");
        }
    }
}
