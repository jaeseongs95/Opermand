package io.github.jaeseongs95.opermand.domain.target;

import io.github.jaeseongs95.opermand.domain.id.CanonicalForm;
import java.util.List;
import java.util.Objects;

/**
 * The jump hosts that a connection goes through, nearest to the target first, as the profiles they are looked up as. A profile only names its jump host ({@code via}); the host,
 * the user, the host key and the authentication of that jump host are in another profile, and they decide which machines the connection passes. They are part of what a
 * revision and a connection identity cover, so that an approval given for one route is not carried over to another route behind the same alias (defect D-0083).
 *
 * <p>The chain must be as long as the names say: a profile that names a jump host needs that jump host's profile, the last jump host names none, and a profile that names none has
 * an empty chain. The names of the jump profiles are not kept here (a profile does not know its own alias), so the chain cannot tell that the right profile stands for a name;
 * the one who resolves the aliases hands the profiles over in the order of the {@code via} links.
 */
final class JumpChain {

    private static final JumpChain NONE = new JumpChain(List.of());

    private final List<ConnectionProfile> hops;

    private JumpChain(List<ConnectionProfile> hops) {
        this.hops = hops;
    }

    /** No jump host. */
    static JumpChain none() {
        return NONE;
    }

    /**
     * The chain of the connection that {@code first} makes, with the jump profiles in the order of the {@code via} links.
     *
     * @throws IllegalArgumentException when the number of the profiles does not fit the {@code via} links
     */
    static JumpChain of(ConnectionProfile first, List<ConnectionProfile> hops) {
        Objects.requireNonNull(first, "first");
        List<ConnectionProfile> copy = List.copyOf(hops);
        ConnectionProfile previous = first;
        for (ConnectionProfile hop : copy) {
            if (!previous.via().isGiven()) {
                throw new IllegalArgumentException("A profile that names no jump host cannot be followed by a jump profile");
            }
            previous = hop;
        }
        if (previous.via().isGiven()) {
            throw new IllegalArgumentException("A profile that names a jump host needs the profile of that jump host");
        }
        return copy.isEmpty() ? NONE : new JumpChain(copy);
    }

    /** Writes the whole of every jump profile, in order, for the revision of a target. */
    CanonicalForm describeTo(CanonicalForm form) {
        for (int index = 0; index < hops.size(); index++) {
            hops.get(index).describeTo(form, "jump." + index);
        }
        return form;
    }

    /** Writes the connection core fields of every jump profile, in order, for the connection identity of a target. */
    CanonicalForm describeConnectionTo(CanonicalForm form) {
        for (int index = 0; index < hops.size(); index++) {
            hops.get(index).describeConnectionTo(form, "connection.jump." + index);
        }
        return form;
    }
}
