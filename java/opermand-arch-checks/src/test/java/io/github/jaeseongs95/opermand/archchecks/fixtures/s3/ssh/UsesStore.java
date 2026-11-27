package io.github.jaeseongs95.opermand.archchecks.fixtures.s3.ssh;

import io.github.jaeseongs95.opermand.archchecks.fixtures.s3.store.StoreAdapter;

/** Breaks A-DIR on purpose: opermand-ssh uses opermand-store (RuleFixturesTest.a_dir_sshDoesNotUseStore). */
final class UsesStore {
    Object store() {
        return new StoreAdapter();
    }
}
