package io.github.jaeseongs95.portal.domain.agentcli;

import java.net.URI;
import java.util.Base64;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.github.jaeseongs95.portal.domain.id.CanonicalForm;
import io.github.jaeseongs95.portal.domain.id.Digest;
import io.github.jaeseongs95.portal.domain.id.Digester;

/**
 * One release of an agent tool as its distribution source announced it, kept to the five public distribution fields and the digest of them.
 * Metadata is untrusted: only a stable version, an HTTPS distribution URL matching the provider's {@link AgentCliProvider#distributionUrl(String)} and a canonical
 * sha512 checksum are accepted, so a release plan can never point to an untrusted host or a floating tag.
 *
 * <p>The record checks the shape of its parts. That {@code releaseDigest} really is the digest of the other fields is checked with a {@link Digester}
 * ({@link #digestMatches}), because a value does not hold a hash function.
 */
public record AgentRelease(AgentCliProviderId provider, String packageName, String version, String tarball, String integrity, Digest releaseDigest) {

    /** Three numbers of up to six digits without leading zeros: no tag, range, pre-release or build part. */
    private static final Pattern VERSION = Pattern.compile("(0|[1-9][0-9]{0,5})(\\.(0|[1-9][0-9]{0,5})){2}");
    private static final Pattern PACKAGE = Pattern.compile("(@[a-z0-9][a-z0-9._-]*/)?[a-z0-9][a-z0-9._-]*");
    /** {@code sha512-} and 86 base64 characters and {@code ==}: the base64 of exactly 64 bytes. */
    private static final Pattern INTEGRITY = Pattern.compile("sha512-([A-Za-z0-9+/]{86})==");
    private static final int SHA512_BYTES = 64;
    private static final String DIGEST_LABEL = "sha256:";

    public AgentRelease {
        Objects.requireNonNull(provider, "provider");
        Objects.requireNonNull(packageName, "packageName");
        Objects.requireNonNull(version, "version");
        Objects.requireNonNull(tarball, "tarball");
        Objects.requireNonNull(integrity, "integrity");
        Objects.requireNonNull(releaseDigest, "releaseDigest");
        if (!PACKAGE.matcher(packageName).matches()) {
            throw new IllegalArgumentException("Invalid CLI package name");
        }
        if (!VERSION.matcher(version).matches()) {
            throw new IllegalArgumentException("Invalid CLI release version");
        }
        requireHttpsDistributionUrl(tarball);
        requireCanonicalSha512(integrity);
    }

    /**
     * The release of the public metadata a registry returned for the provider. {@code name} must be the provider's package and {@code tarball} must equal
     * {@link AgentCliProvider#distributionUrl(String)}; the digest is computed here via {@link CanonicalForm} from the five fields, so a caller cannot supply one.
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
        if (!VERSION.matcher(version).matches()) {
            throw new IllegalArgumentException("Invalid CLI release version");
        }
        if (!tarball.equals(provider.distributionUrl(version))) {
            throw new IllegalArgumentException("Untrusted CLI distribution URL");
        }
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

    /** The checksum must be base64 of 64 bytes in the canonical form: decoding and encoding again gives the same text. */
    private static void requireCanonicalSha512(String integrity) {
        Matcher matcher = INTEGRITY.matcher(integrity);
        if (!matcher.matches()) {
            throw new IllegalArgumentException("Invalid CLI distribution integrity");
        }
        String base64 = matcher.group(1) + "==";
        byte[] bytes = Base64.getDecoder().decode(base64);
        if (bytes.length != SHA512_BYTES || !Base64.getEncoder().encodeToString(bytes).equals(base64)) {
            throw new IllegalArgumentException("Invalid CLI distribution integrity");
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
