package io.github.jaeseongs95.opermand.domain.agentcli;

import java.net.URI;
import java.util.Objects;
import java.util.regex.Pattern;

import io.github.jaeseongs95.opermand.domain.id.CanonicalForm;
import io.github.jaeseongs95.opermand.domain.id.Digest;
import io.github.jaeseongs95.opermand.domain.id.Digester;

/**
 * One release of an agent tool as its distribution source announced it, kept to the five public distribution fields and the digest of them.
 * Metadata is untrusted: only a stable version, an HTTPS distribution URL matching the provider's {@link AgentCliProvider#distributionUrl(String)}, and a
 * well-formed integrity token verified by {@link AgentCliProvider#verifyIntegrity(String)} are accepted, so a release plan can never point to an untrusted host
 * or a floating tag while keeping the domain independent of any specific package registry.
 *
 * <p>The record checks the shape of its parts. That {@code releaseDigest} really is the digest of the other fields is checked with a {@link Digester}
 * ({@link #digestMatches}), because a value does not hold a hash function.
 */
public record AgentRelease(AgentCliProviderId provider, String packageName, String version, String tarball, String integrity, Digest releaseDigest) {

    /** Three numbers of up to six digits without leading zeros: no tag, range, pre-release or build part. */
    private static final Pattern VERSION_PATTERN = Pattern.compile("(0|[1-9][0-9]{0,5})(\\.(0|[1-9][0-9]{0,5})){2}");
    /** Generic printable package identifier without whitespace, control characters, or path traversal. */
    private static final Pattern PACKAGE_PATTERN = Pattern.compile("[A-Za-z0-9@._:/-]{1,214}");
    /** Generic checksum token ({@code <algorithm>-<encoded>} or {@code <algorithm>:<encoded>}) without whitespace or control characters. */
    private static final Pattern INTEGRITY_PATTERN = Pattern.compile("[a-z0-9]{3,16}[-:][A-Za-z0-9+/=_-]{16,256}");
    private static final String DIGEST_LABEL = "sha256:";

    public AgentRelease {
        Objects.requireNonNull(provider, "provider");
        Objects.requireNonNull(packageName, "packageName");
        Objects.requireNonNull(version, "version");
        Objects.requireNonNull(tarball, "tarball");
        Objects.requireNonNull(integrity, "integrity");
        Objects.requireNonNull(releaseDigest, "releaseDigest");
        if (!PACKAGE_PATTERN.matcher(packageName).matches() || packageName.contains("..")) {
            throw new IllegalArgumentException("Invalid CLI package name");
        }
        if (!VERSION_PATTERN.matcher(version).matches()) {
            throw new IllegalArgumentException("Invalid CLI release version");
        }
        requireHttpsDistributionUrl(tarball);
        if (!INTEGRITY_PATTERN.matcher(integrity).matches()) {
            throw new IllegalArgumentException("Invalid CLI distribution integrity");
        }
    }

    /**
     * The release of the public metadata a distribution source returned for the provider. {@code name} must be the provider's package, {@code tarball} must
     * equal {@link AgentCliProvider#distributionUrl(String)}, and {@code integrity} must satisfy {@link AgentCliProvider#verifyIntegrity(String)}; the digest is
     * computed here via {@link CanonicalForm} from the five fields, so a caller cannot supply one.
     */
    public static AgentRelease verify(AgentCliProvider provider, String name, String version, String tarball, String integrity, Digester digester) {
        Objects.requireNonNull(provider, "provider");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(version, "version");
        Objects.requireNonNull(tarball, "tarball");
        Objects.requireNonNull(integrity, "integrity");
        Objects.requireNonNull(digester, "digester");
        if (!name.equals(provider.packageName())) {
            throw new IllegalArgumentException("Invalid CLI release");
        }
        if (!VERSION_PATTERN.matcher(version).matches()) {
            throw new IllegalArgumentException("Invalid CLI release version");
        }
        if (!tarball.equals(provider.distributionUrl(version))) {
            throw new IllegalArgumentException("Untrusted CLI distribution URL");
        }
        provider.verifyIntegrity(integrity);
        Digest digest = canonicalForm(provider.id(), name, version, tarball, integrity).digest(digester);
        return new AgentRelease(provider.id(), name, version, tarball, integrity, digest);
    }

    /** Whether {@code releaseDigest} is the digest of the other five fields, that is, whether the release was kept as announced. */
    public boolean digestMatches(Digester digester) {
        Objects.requireNonNull(digester, "digester");
        return releaseDigest.equals(canonicalForm(provider, packageName, version, tarball, integrity).digest(digester));
    }

    /** The digest as the administrator interface carries it: {@code sha256:} and 64 hex characters. */
    public String releaseDigestLabel() {
        return DIGEST_LABEL + releaseDigest.hex();
    }

    private static void requireHttpsDistributionUrl(String tarball) {
        URI uri;
        try {
            uri = URI.create(tarball);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Untrusted CLI distribution URL", e);
        }
        if (!"https".equals(uri.getScheme()) || uri.getHost() == null || uri.getHost().isEmpty() || uri.getRawUserInfo() != null
                || uri.getPort() != -1 || uri.getRawQuery() != null || uri.getRawFragment() != null
                || uri.getRawPath() == null || uri.getRawPath().isEmpty() || uri.getRawPath().contains("..")) {
            throw new IllegalArgumentException("Untrusted CLI distribution URL");
        }
    }

    private static CanonicalForm canonicalForm(AgentCliProviderId provider, String packageName, String version, String tarball, String integrity) {
        return new CanonicalForm()
                .field("provider", provider.wire())
                .field("packageName", packageName)
                .field("version", version)
                .field("tarball", tarball)
                .field("integrity", integrity);
    }
}
