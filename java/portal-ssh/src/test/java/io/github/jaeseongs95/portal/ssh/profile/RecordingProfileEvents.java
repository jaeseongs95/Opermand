package io.github.jaeseongs95.portal.ssh.profile;

/** {@link ProfileEvents} for tests: it keeps what the loader reported. */
final class RecordingProfileEvents implements ProfileEvents {

    private int policyReports;

    @Override
    public synchronized void policyNotEnforced() {
        policyReports++;
    }

    /** How many times the loader said that a {@code [policy]} table is not enforced. */
    synchronized int policyReports() {
        return policyReports;
    }
}
