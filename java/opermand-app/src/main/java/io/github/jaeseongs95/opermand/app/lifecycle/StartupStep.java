package io.github.jaeseongs95.opermand.app.lifecycle;

/**
 * One thing the application does once at its start, after every bean exists and before it serves a request: check the protection of the file stores, reconcile the file stores
 * with their rows, recover the journal and open the dispatcher. The steps depend on one another (the dispatcher must not open before the stores are checked, and a pending
 * output must be given up before a new command reserves one), and that order was a matter of the order in which the beans were registered. A step now says its place with
 * {@link org.springframework.core.annotation.Order} and the constants below, and {@link StartupSequence} runs the steps in that order (defect D-0159).
 *
 * <p>A step that fails stops the start; the steps after it do not run.
 */
public interface StartupStep {

    /** The guard of the file stores: a folder that cannot be protected stops the start before a file is read. */
    int PROTECTION = 100;
    /** The file stores against their rows: quarantine, unacknowledged tails, finished deletions, pending outputs. */
    int STORAGE = 200;
    /** The journal after a restart, the settlement of what the last run left half done, and the opening of the dispatcher. It is the last: nothing is dispatched before it. */
    int JOURNAL = 300;

    /** Does the step. */
    void run();
}
