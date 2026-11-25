package io.github.jaeseongs95.portal.domain.target;

import io.github.jaeseongs95.portal.domain.id.CanonicalForm;
import io.github.jaeseongs95.portal.domain.id.Digest;
import io.github.jaeseongs95.portal.domain.id.Digester;
import java.util.List;
import java.util.Objects;

/**
 * The revision of a target: the digest of everything that decides where and how a request reaches it, the administrator's entry and the two connection
 * profiles. An operation is bound to the revision it was approved for, so a changed target needs a new approval. Credentials are not part of it, only the names they
 * are looked up by and the administrator's credential revision, so rotating a credential means a new revision on purpose.
 *
 * <p>The 3.0 revision is not the 2.x value: the 2.x digest was taken over a JavaScript object of the vendor registry's format, which Java cannot reproduce. An
 * agent that holds a 2.x revision gets a revision conflict and asks for the targets again (stage 2 design, decision D-09).
 */
public record TargetRevision(Digest value) {

    public TargetRevision {
        Objects.requireNonNull(value, "value");
    }

    /** The revision for the 64 hex characters a caller sends. */
    public static TargetRevision of(String hex) {
        return new TargetRevision(new Digest(hex));
    }

    /**
     * The revision of a target with its two profiles and no jump host: the digest of the fixed-order canonical form of the entry, the read profile and the write profile. The same
     * input always gives the same revision, and any changed field gives another.
     *
     * @throws IllegalArgumentException when a profile names a jump host, whose profile this form does not have (use {@link #of(Target, ConnectionProfile, ConnectionProfile, List, Digester)})
     */
    public static TargetRevision of(Target target, ConnectionProfile reader, ConnectionProfile writer, Digester digester) {
        return of(target, reader, writer, List.of(), digester);
    }

    /**
     * The revision of a target with its two profiles and the profiles of the jump hosts the connection goes through, nearest first (defect D-0083): a changed jump host, behind
     * the same alias, gives another revision, so an approval is not carried over to another route.
     *
     * @throws IllegalArgumentException when the jump profiles do not fit the {@code via} links of the profiles
     */
    public static TargetRevision of(Target target, ConnectionProfile reader, ConnectionProfile writer, List<ConnectionProfile> jumps, Digester digester) {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(reader, "reader");
        Objects.requireNonNull(writer, "writer");
        Objects.requireNonNull(jumps, "jumps");
        Objects.requireNonNull(digester, "digester");
        JumpChain chain = JumpChain.of(writer, jumps);
        CanonicalForm form = new CanonicalForm();
        target.describeTo(form);
        reader.describeTo(form, "reader");
        writer.describeTo(form, "writer");
        chain.describeTo(form);
        return new TargetRevision(form.digest(digester));
    }

    /** The 64 hex characters. */
    public String hex() {
        return value.hex();
    }
}
