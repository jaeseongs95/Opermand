package io.github.jaeseongs95.opermand.app.policy;

import io.github.jaeseongs95.opermand.domain.command.CommandPolicyDocument;
import io.github.jaeseongs95.opermand.domain.command.PolicySnapshot;
import io.github.jaeseongs95.opermand.domain.command.TargetPolicy;
import io.github.jaeseongs95.opermand.domain.id.TargetId;

/** The one command policy of the installation with its revision (2.x {@code command-state.mjs}: {@code policy(id=1, revision, body)}). */
public interface CommandPolicyStore {

    /** The stored policy; a fresh installation has the empty policy at revision 0. */
    PolicySnapshot current();

    /**
     * Reads only the rule for {@code targetId} together with its monotonic {@code ruleRevision} in one {@code REPEATABLE_READ} snapshot (defect D-0121). Returns
     * {@link TargetPolicy#none()} when no rule exists for {@code targetId}.
     */
    TargetPolicy targetPolicy(TargetId targetId);

    /**
     * Replaces the policy when the stored revision is {@code expectedRevision}, in one transaction with the rule rows; the global revision rises by one, and each
     * target's {@code ruleRevision} is preserved when its rule is unchanged or advances to the new global revision when added or modified. Another revision throws
     * {@code RequestRefusedException(POLICY_REVISION_CONFLICT)} and changes nothing.
     */
    PolicySnapshot replace(CommandPolicyDocument next, long expectedRevision);
}
