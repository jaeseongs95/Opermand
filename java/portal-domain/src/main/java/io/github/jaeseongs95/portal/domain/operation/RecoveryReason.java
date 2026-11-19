package io.github.jaeseongs95.portal.domain.operation;

/** Why a recovery changed the operation, if one did: the bridge restarted while it was in flight. */
public enum RecoveryReason {
    NONE, BRIDGE_RESTART
}
