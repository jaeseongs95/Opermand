package io.github.jaeseongs95.opermand.domain.command;

import io.github.jaeseongs95.opermand.domain.operation.DecisionReason;
import java.util.Objects;

/**
 * Judges a request by the owner's rule alone, at intake (defect D-0119, decision "rule auto-approval by default"). It holds no state and asks nobody: the rule, the
 * request and whether the command is read-only are values. The answer is the {@link DecisionReason}, which fixes what happens: {@link DecisionReason#kind()} is
 * {@code EXECUTE} (the operation is queued), {@code DENY} (it ends at once) or {@code REVIEW} (it waits for the owner's button). A request no rule covers is a review: nothing is
 * pre-approved by default, and nothing is refused except by the owner's deny list.
 *
 * <p>The order of a command line is the order of the lead's decision Q2, and an earlier check wins:
 * <ol>
 *   <li>The rule denies what the request would run, and the test server switch is off: refused ({@link DecisionReason#POLICY_DENIED}). The deny list applies to read-only
 *       commands too.</li>
 *   <li>The test server switch is on: runs ({@link DecisionReason#TEST_SERVER_BYPASS}).</li>
 *   <li>The command is read-only and runs on the read profile: runs under the read authority ({@link DecisionReason#READ_ONLY}). On the write profile, which may be a
 *       privileged account, a read-only command is judged like any other (it could print secrets, or run a program an automatic upload put in a repository).</li>
 *   <li>The kind's own screening of the rule (sudo switch, approved names, the source conditions): a command whose names are all approved runs
 *       ({@link DecisionReason#COMMAND_SET}); a screening that stops it names its reason and leaves it to the owner.</li>
 *   <li>Otherwise: the owner decides ({@link DecisionReason#NO_MATCHING_RULE}). Submitted source never runs by rule alone, since no model judges it any more.</li>
 * </ol>
 * An upload or a download is never refused by a rule; it runs only under its switch, otherwise the owner decides (2.x files.mjs:34, app.mjs:273-284).
 */
public final class RuleApprovalPolicy {

    /**
     * A command line or a submitted source.
     *
     * @param denied whether the owner's deny list forbids what the request runs, as the target's platform reads names (false without a rule)
     * @param readOnly whether the command is read-only and runs on the read profile, the only place a read runs without a rule (always false for submitted source)
     */
    public DecisionReason decideCommand(RuleLookup lookup, ApprovalSubject subject, boolean denied, boolean readOnly) {
        Objects.requireNonNull(lookup, "lookup");
        Objects.requireNonNull(subject, "subject");
        if (lookup instanceof RuleLookup.Rule found) {
            if (!found.rule().testServer().isOn() && denied) {
                return DecisionReason.POLICY_DENIED;
            }
            if (found.rule().testServer().isOn()) {
                return DecisionReason.TEST_SERVER_BYPASS;
            }
        }
        if (readOnly) {
            return DecisionReason.READ_ONLY;
        }
        if (subject.screen(lookup) instanceof ApprovalSubject.Screening.Decided screened) {
            return screened.reason();
        }
        return DecisionReason.NO_MATCHING_RULE;
    }

    /** An upload runs by rule only when the switch is on and it neither replaces a file nor uses elevated rights (2.x app.mjs:273-278). */
    public DecisionReason decideUpload(RuleLookup lookup, boolean elevated, boolean overwrites) {
        Objects.requireNonNull(lookup, "lookup");
        if (lookup instanceof RuleLookup.Rule found) {
            if (found.rule().testServer().isOn()) {
                return DecisionReason.TEST_SERVER_BYPASS;
            }
            if (found.rule().automaticUploads().isOn() && !elevated && !overwrites) {
                return DecisionReason.AUTOMATIC_UPLOAD;
            }
        }
        return DecisionReason.NO_MATCHING_RULE;
    }

    /** A download runs by rule when the switch is on; elevated rights also need the sudo switch (2.x app.mjs:279-284). */
    public DecisionReason decideDownload(RuleLookup lookup, boolean elevated) {
        Objects.requireNonNull(lookup, "lookup");
        if (lookup instanceof RuleLookup.Rule found) {
            if (found.rule().testServer().isOn()) {
                return DecisionReason.TEST_SERVER_BYPASS;
            }
            if (found.rule().automaticDownloads().isOn() && (!elevated || found.rule().automaticSudo().isOn())) {
                return DecisionReason.AUTOMATIC_DOWNLOAD;
            }
        }
        return DecisionReason.NO_MATCHING_RULE;
    }
}
