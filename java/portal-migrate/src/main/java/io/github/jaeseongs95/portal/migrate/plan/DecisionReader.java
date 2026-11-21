package io.github.jaeseongs95.portal.migrate.plan;

import io.github.jaeseongs95.portal.domain.operation.DecisionReason;

/**
 * Reads how the 2.x approval judged a command: the {@code decision} object of the job ({@code decision}, {@code reasonCode}, {@code policyRevision}), the approval mode and the
 * read-only flag. The 3.0 reasons are fewer than the 2.x reason codes (the model evaluation is gone), so the evaluation details are not kept: a decision to execute becomes the
 * reason that tells which authority started it, a refusal by the policy stays a refusal, and everything that left the decision to the owner becomes "no matching rule".
 */
final class DecisionReader {

    /** The reason, and the policy revision it was decided under (0 when 2.x recorded none). */
    record Choice(DecisionReason reason, long policyRevision) {
    }

    Choice read(JsonObject decision, String approvalMode, boolean readOnly) {
        String kind = decision == null ? null : decision.string("decision");
        String code = decision == null ? null : decision.string("reasonCode");
        Long revision = decision == null ? null : decision.integer("policyRevision");
        long policyRevision = revision == null || revision < 0 ? 0L : revision;
        if ("deny".equals(kind) || "policy_denied".equals(code)) {
            return new Choice(DecisionReason.POLICY_DENIED, policyRevision);
        }
        if ("owner".equals(approvalMode)) {
            return new Choice(DecisionReason.NO_MATCHING_RULE, policyRevision);
        }
        if ("execute".equals(kind)) {
            if (readOnly) {
                return new Choice(DecisionReason.READ_ONLY, policyRevision);
            }
            return new Choice("test_server_bypass".equals(code) ? DecisionReason.TEST_SERVER_BYPASS : DecisionReason.COMMAND_SET, policyRevision);
        }
        if (kind == null && readOnly) {
            return new Choice(DecisionReason.READ_ONLY, policyRevision);
        }
        return new Choice(DecisionReason.NO_MATCHING_RULE, policyRevision);
    }
}
