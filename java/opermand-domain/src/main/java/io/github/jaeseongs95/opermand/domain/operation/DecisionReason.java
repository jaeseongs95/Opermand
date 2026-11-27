package io.github.jaeseongs95.opermand.domain.operation;

import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Locale;

/**
 * Why the rule-based approval decided as it did at intake (defect D-0119, D-0122). The reason fixes the {@link #kind()}:
 * only {@link #POLICY_DENIED} refuses, the reasons for which {@link #isAutomatic()} is true start the operation without the owner, every other reason leaves it to the owner.
 * The stored reason is also what the start authority is derived from ({@link StartAuthority.Policy#of}). The wire value is the lowercase name.
 */
public enum DecisionReason {
    POLICY_DENIED, TEST_SERVER_BYPASS, SOURCE_EXECUTION_NOT_ENABLED, SOURCE_PRIVILEGE_POLICY_REVIEW, SOURCE_COMMAND_POLICY_REVIEW, SUDO_NOT_ENABLED, COMMAND_SET,
    /** A command the target's platform classifies as read-only runs under the server's read authority. */
    READ_ONLY,
    /** An upload the rule allows to run without the owner. */
    AUTOMATIC_UPLOAD,
    /** A download the rule allows to run without the owner. */
    AUTOMATIC_DOWNLOAD,
    /** No rule of the owner covers the request: the owner decides. */
    NO_MATCHING_RULE,
    /** An agent CLI operation is never judged by a rule: the owner starts it (defect D-0119, decision Q10). */
    AGENT_CLI_REVIEW;

    /** True for the reasons under which the server starts an operation by itself. */
    public boolean isAutomatic() {
        return switch (this) {
            case COMMAND_SET, TEST_SERVER_BYPASS, READ_ONLY, AUTOMATIC_UPLOAD, AUTOMATIC_DOWNLOAD -> true;
            case POLICY_DENIED, SOURCE_EXECUTION_NOT_ENABLED, SOURCE_PRIVILEGE_POLICY_REVIEW, SOURCE_COMMAND_POLICY_REVIEW, SUDO_NOT_ENABLED,
                NO_MATCHING_RULE, AGENT_CLI_REVIEW -> false;
        };
    }

    /** What the reason decides: refuse, run, or leave it to the owner. */
    public DecisionKind kind() {
        if (this == POLICY_DENIED) {
            return DecisionKind.DENY;
        }
        return isAutomatic() ? DecisionKind.EXECUTE : DecisionKind.REVIEW;
    }

    @JsonValue
    public String wire() {
        return name().toLowerCase(Locale.ROOT);
    }
}
