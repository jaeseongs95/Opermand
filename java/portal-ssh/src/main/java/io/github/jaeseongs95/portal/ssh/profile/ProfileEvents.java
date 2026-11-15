package io.github.jaeseongs95.portal.ssh.profile;

/**
 * What loading the profile file reports to whoever runs the product, besides the profiles: things that do not stop the load and are not to pass unsaid. The loader decides
 * and reports; it does not log. {@link LoggingProfileEvents} is the implementation of the product (one log line per event); a test passes one that records the events.
 */
public interface ProfileEvents {

    /** The file has a {@code [policy]} table: it is accepted so that a file written for 2.x still loads, and nothing enforces it. */
    void policyNotEnforced();
}
