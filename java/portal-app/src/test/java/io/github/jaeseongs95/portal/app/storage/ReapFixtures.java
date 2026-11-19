package io.github.jaeseongs95.portal.app.storage;

import java.time.Instant;

import io.github.jaeseongs95.portal.app.maintenance.MaintenanceStore;

/** The two guards a reaper asks, as plain test classes: whether a maintenance reservation holds the installation, and what the protection of the file stores answers. */
public final class ReapFixtures {

    private ReapFixtures() {
    }

    /** A maintenance store that is only the answer to "is it occupied". */
    public static final class Maintenance implements MaintenanceStore {

        private boolean occupied;

        public void occupy(boolean value) {
            this.occupied = value;
        }

        @Override
        public boolean occupied() {
            return occupied;
        }

        @Override
        public void reserve(Instant reservedAt) {
            occupied = true;
        }

        @Override
        public void release() {
            occupied = false;
        }
    }

    /** A protection whose answer the test sets; it counts the checks, so a test can show that maintenance is asked first and the files after. */
    public static final class Protection implements StorageProtection {

        private ProtectionCheck answer = new ProtectionCheck.Protected();
        private int checks;

        public void answer(ProtectionCheck value) {
            this.answer = value;
        }

        public int checks() {
            return checks;
        }

        @Override
        public ProtectionCheck check() {
            checks++;
            return answer;
        }
    }
}
