package io.github.jaeseongs95.opermand.app.storage;

import java.time.Instant;
import java.util.Optional;

import io.github.jaeseongs95.opermand.app.maintenance.MaintenanceStore;

/** The two guards a reaper asks, as plain test classes: whether a maintenance reservation holds the installation, and what the protection of the file stores answers. */
public final class ReapFixtures {

    private ReapFixtures() {
    }

    /** A maintenance store that is only the answer to "is it occupied". */
    public static final class Maintenance implements MaintenanceStore {

        private Instant reservedAt;

        public void occupy(boolean value) {
            this.reservedAt = value ? Instant.EPOCH : null;
        }

        @Override
        public boolean occupied() {
            return reservedAt != null;
        }

        @Override
        public Optional<Instant> reservedAt() {
            return Optional.ofNullable(reservedAt);
        }

        @Override
        public void reserve(Instant reservedAt) {
            this.reservedAt = reservedAt;
        }

        @Override
        public void release() {
            this.reservedAt = null;
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
