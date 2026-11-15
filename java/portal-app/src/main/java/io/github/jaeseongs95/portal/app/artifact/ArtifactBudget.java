package io.github.jaeseongs95.portal.app.artifact;

/**
 * The staging budget: what is allowed and what the rows charge now. A {@code CONSUMED} row and an empty file charge one byte each (2.x
 * {@code charge = state === 'consumed' ? 1 : Math.max(1, size)}, {@code artifacts.mjs:18}).
 *
 * <p>{@code retainedBytes} are the bytes in quarantine: payloads that have no row, that the start-up check moved aside and that nothing deletes (defect D-0012). They are a
 * figure of their own for the owner and are <em>not</em> part of {@code chargedBytes}, so they never decide whether a new transfer fits; the owner clears them by hand.
 *
 * @param budgetBytes   what the budget allows
 * @param chargedBytes  what the rows of the artifacts charge
 * @param retainedFiles how many files are in quarantine
 * @param retainedBytes what those files hold, without a row; not charged
 */
public record ArtifactBudget(long budgetBytes, long chargedBytes, long retainedFiles, long retainedBytes) {

    public ArtifactBudget {
        if (budgetBytes < 0 || chargedBytes < 0 || retainedFiles < 0 || retainedBytes < 0) {
            throw new IllegalArgumentException("The budget, the charge and the retained files and bytes must not be negative");
        }
    }

    /** What a new transfer may still take: the budget less the charge; the quarantine is not subtracted. */
    public long remainingBytes() {
        return Math.max(0, budgetBytes - chargedBytes);
    }
}
