package io.github.jaeseongs95.portal.app.artifact;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import io.github.jaeseongs95.portal.domain.error.RequestRefusedException;

/**
 * Tells the owner what is in quarantine: the payloads without a row that the start-up check moved aside and that nothing deletes (defect D-0012). The start-up check and
 * every reap run end with it, so the figure is in the log without the owner asking for it. It is the one place of the line, which reads {@link ArtifactRegistry#budget()}.
 *
 * <p>Stage 3 shows the figures in the log only. The administrator view and the owner's command that clears the quarantine belong to the administrator API of stage 4; nothing
 * is deleted automatically. The read of the folder is file I/O and the call is made outside a transaction (Principles 4.4). A failed read of the quarantine folder, which
 * {@code budget()} reports as a {@link RequestRefusedException}, does not undo the work that called this: it is logged with its cause and the caller goes on. The other read
 * of {@code budget()}, the sum of the charged bytes of the rows, is not caught: a {@code DataAccessException} from it is thrown to the caller.
 */
@Component
public class QuarantineNotice {

    private static final Logger LOG = LoggerFactory.getLogger(QuarantineNotice.class);

    private final ArtifactRegistry registry;

    public QuarantineNotice(ArtifactRegistry registry) {
        this.registry = registry;
    }

    public void log() {
        try {
            ArtifactBudget budget = registry.budget();
            LOG.info("Artifact quarantine: {} file(s), {} retained byte(s) (not charged to the staging budget, only the owner clears them)", budget.retainedFiles(),
                budget.retainedBytes());
        } catch (RequestRefusedException e) {
            LOG.warn("The artifact quarantine could not be read", e);
        }
    }
}
