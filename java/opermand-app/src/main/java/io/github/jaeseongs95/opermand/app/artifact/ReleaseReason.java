package io.github.jaeseongs95.opermand.app.artifact;

/** Why the registry gives an artifact up; it is logged and audited, not stored. */
public enum ReleaseReason {
    EXPIRED, ABANDONED, REFUSED, CONSUMED, MAINTENANCE
}
