package io.github.jaeseongs95.opermand.ssh.session;

import io.github.jaeseongs95.opermand.app.remote.SecretValue;
import io.github.jaeseongs95.opermand.app.remote.SshCredential;
import io.github.jaeseongs95.opermand.domain.target.Setting;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.apache.sshd.client.auth.password.PasswordIdentityProvider;
import org.apache.sshd.common.NamedResource;
import org.apache.sshd.common.config.keys.FilePasswordProvider;
import org.apache.sshd.common.keyprovider.KeyIdentityProvider;
import org.apache.sshd.common.session.SessionContext;
import org.apache.sshd.common.util.security.SecurityUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The identities MINA offers to a server, chosen by the credential of the profile of that server ({@link HopRouting}): the one place a {@link SecretValue} is revealed,
 * and only to hand its characters to MINA. The SSH agent is not supported by this adapter: an {@code Agent} credential offers nothing, so the login fails.
 * The certificate of a key credential is not offered either.
 */
final class HopIdentities implements PasswordIdentityProvider, KeyIdentityProvider {

    private static final Logger LOG = LoggerFactory.getLogger(HopIdentities.class);

    private final HopRouting routing;
    private final ConnectAttempt attempt;

    HopIdentities(HopRouting routing, ConnectAttempt attempt) {
        this.routing = routing;
        this.attempt = attempt;
    }

    @Override
    public Iterable<String> loadPasswords(SessionContext session) {
        Optional<Hop> found = routing.find(session);
        if (found.isEmpty()) {
            LOG.warn("Withholding password credentials for an unidentified SSH session (fail-closed, SSH_PORTAL-D-0055)");
            return List.of();
        }
        Hop hop = found.orElseThrow();
        attempt.credentialAsked(hop);
        if (hop.credential() instanceof SshCredential.Password password) {
            char[] characters = password.password().reveal();
            String text = String.valueOf(characters);
            Arrays.fill(characters, '\0');
            return List.of(text);
        }
        return List.of();
    }

    @Override
    public Iterable<KeyPair> loadKeys(SessionContext session) throws IOException, GeneralSecurityException {
        Optional<Hop> found = routing.find(session);
        if (found.isEmpty()) {
            LOG.warn("Withholding key credentials for an unidentified SSH session (fail-closed, SSH_PORTAL-D-0055)");
            return List.of();
        }
        Hop hop = found.orElseThrow();
        attempt.credentialAsked(hop);
        if (!(hop.credential() instanceof SshCredential.PrivateKey key)) {
            return List.of();
        }
        char[] pem = key.pem().reveal();
        byte[] bytes = String.valueOf(pem).getBytes(StandardCharsets.UTF_8);
        Arrays.fill(pem, '\0');
        FilePasswordProvider passphrase = FilePasswordProvider.EMPTY;
        if (key.passphrase() instanceof Setting.Given<SecretValue> given) {
            char[] characters = given.value().reveal();
            passphrase = FilePasswordProvider.of(String.valueOf(characters));
            Arrays.fill(characters, '\0');
        }
        Iterable<KeyPair> keys = SecurityUtils.loadKeyPairIdentities(session, NamedResource.ofName(hop.alias().value()), new ByteArrayInputStream(bytes), passphrase);
        Arrays.fill(bytes, (byte) 0);
        return keys == null ? List.of() : keys;
    }
}
