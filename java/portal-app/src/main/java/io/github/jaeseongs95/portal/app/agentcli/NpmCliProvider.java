package io.github.jaeseongs95.portal.app.agentcli;

import java.util.Base64;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.github.jaeseongs95.portal.domain.agentcli.AgentCliProvider;

/**
 * Shared npm registry distribution contract for npm-packaged agent CLIs. Keeps npm registry URLs and canonical SRI {@code sha512} rules in the application
 * adapter layer rather than in {@code portal-domain}.
 */
abstract class NpmCliProvider implements AgentCliProvider {

    /** {@code sha512-} and 86 base64 characters and {@code ==}: the canonical base64 of exactly 64 bytes. */
    private static final Pattern SRI_SHA512 = Pattern.compile("sha512-([A-Za-z0-9+/]{86})==");
    private static final int SHA512_BYTES = 64;

    protected abstract String tarballBaseName();

    @Override
    public final String distributionUrl(String version) {
        return "https://registry.npmjs.org/" + packageName() + "/-/" + tarballBaseName() + "-" + version + ".tgz";
    }

    @Override
    public final void verifyIntegrity(String integrity) {
        Matcher matcher = SRI_SHA512.matcher(integrity);
        if (!matcher.matches()) {
            throw new IllegalArgumentException("Invalid npm CLI distribution integrity");
        }
        String base64 = matcher.group(1) + "==";
        byte[] bytes = Base64.getDecoder().decode(base64);
        if (bytes.length != SHA512_BYTES || !Base64.getEncoder().encodeToString(bytes).equals(base64)) {
            throw new IllegalArgumentException("Invalid npm CLI distribution integrity");
        }
    }
}
