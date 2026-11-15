package io.github.jaeseongs95.portal.ssh.profile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import io.github.jaeseongs95.portal.app.remote.CredentialLookup;
import io.github.jaeseongs95.portal.app.remote.CredentialMiss;
import io.github.jaeseongs95.portal.app.remote.CredentialPart;
import io.github.jaeseongs95.portal.app.remote.SecretValue;
import io.github.jaeseongs95.portal.app.remote.SshCredential;
import io.github.jaeseongs95.portal.app.remote.SshCredentialSource;
import io.github.jaeseongs95.portal.domain.target.AuthMethod;
import io.github.jaeseongs95.portal.domain.target.ConnectionProfile;
import io.github.jaeseongs95.portal.domain.target.ProfileAlias;
import io.github.jaeseongs95.portal.domain.target.Setting;

/**
 * The {@link SshCredentialSource} that reads the {@code SSH_MCP_*} settings of 2.x from the Spring {@link Environment} (EV-01 to EV-13, decision D3-09). The names are the
 * names of 2.x, so a secret environment file of 2.x keeps working: for a profile with the prefix {@code SSH_MCP_<NAME>} (see {@link CredentialPrefix}) the variables
 * {@code <prefix>_PASSWORD}, {@code <prefix>_PASSPHRASE}, {@code <prefix>_KEY} and {@code <prefix>_CERT}; for every profile the global {@code SSH_MCP_PASSWORD},
 * {@code SSH_MCP_PASSPHRASE} and {@code SSH_MCP_KEY}; the {@code keyRef} of the profile; and {@code SSH_AUTH_SOCK} for an agent. A variable of the profile wins over the
 * global one, and an empty variable counts as no variable. The code never calls {@code System.getenv}: the environment is an argument, so a test and a secret file
 * (see {@link SecretEnvPropertySource}) put their values in it the same way.
 *
 * <p><b>What a profile gets follows its authentication method</b> (the sealed {@link SshCredential} has one variant per method): {@code password} gets the password;
 * {@code key} gets the key text of the file that {@code keyRef}, {@code <prefix>_KEY} or {@code SSH_MCP_KEY} names (in that order), with the passphrase and, when the
 * profile says {@code cert = true}, the certificate ({@code <prefix>_CERT} or {@code <key file without .pub>-cert.pub} next to the key); {@code keychain} is
 * {@link CredentialLookup.Unsupported}. {@code agent}, which is also the method of a profile that names none, collects its sources like 2.x did (EV-10): the key
 * (read as for {@code key}), else the password, else the agent when {@code SSH_AUTH_SOCK} is set, else a {@link CredentialLookup.Missing} that lists every place
 * searched. A profile whose method is {@code password}, {@code key} or {@code keychain} gets only what that method uses. A key file that is missing, empty or
 * unreadable is a {@link CredentialLookup.Missing} that names the place where the key was named ({@code keyRef of the profile}, {@code <prefix>_KEY} or
 * {@code SSH_MCP_KEY}) and never the value, which may be a key text (2.x skipped a missing file silently); the lookup of an {@code agent} profile does not go on to the
 * password then. An unreadable file is logged with the name of that place and the kind of the error. A leading {@code ~} name of a key path is the home folder ({@code HOME}, else
 * {@code user.home}); a name that starts with {@code ~} and a user name is not expanded. Nothing is made of {@code SSH_MCP_SUDO_PASSWORD}: 2.x never read it (EV-03).
 *
 * <p>The secrets are {@link SecretValue}s from the moment they are read, and nothing here logs, prints or puts them in a message: a {@link CredentialMiss} names the
 * variable or the setting that was searched, never the value of a setting.
 */
@Component
public final class EnvironmentCredentialSource implements SshCredentialSource {

    private static final Logger LOG = LoggerFactory.getLogger(EnvironmentCredentialSource.class);
    private static final String GLOBAL_PASSWORD = "SSH_MCP_PASSWORD";
    private static final String GLOBAL_PASSPHRASE = "SSH_MCP_PASSPHRASE";
    private static final String GLOBAL_KEY = "SSH_MCP_KEY";
    private static final String AGENT_SOCKET = "SSH_AUTH_SOCK";
    private static final String HOME_NAME = "~";
    private static final String KEY_REFERENCE_SOURCE = "keyRef of the profile";
    private static final String CERTIFICATE_SUFFIX = "-cert.pub";
    private static final String PUBLIC_KEY_SUFFIX = ".pub";

    /** A key setting: the text it holds (a path, or by mistake a key text) and the name of the place where it was set. Only the name goes into a message or a log. */
    private record NamedKey(String value, String source) {

        @Override
        public String toString() {
            return source;
        }
    }

    /** What reading a file gave. */
    private sealed interface FileRead permits FileRead.Text, FileRead.Absent, FileRead.Unreadable {

        /** The text of the file. */
        record Text(String text) implements FileRead {
        }

        /** There is no such file (2.x skipped it silently, {@code ENOENT}). */
        record Absent() implements FileRead {
        }

        /** The file is there and cannot be read (2.x threw). */
        record Unreadable() implements FileRead {
        }
    }

    private final Environment environment;
    private final ProfileCatalog catalog;

    public EnvironmentCredentialSource(Environment environment, ProfileCatalog catalog) {
        this.environment = Objects.requireNonNull(environment, "environment");
        this.catalog = Objects.requireNonNull(catalog, "catalog");
    }

    @Override
    public CredentialLookup resolve(ProfileAlias alias, ConnectionProfile profile) {
        Objects.requireNonNull(alias, "alias");
        Objects.requireNonNull(profile, "profile");
        CredentialPrefix prefix = CredentialPrefix.of(alias.value());
        return switch (profile.auth()) {
            case AGENT -> agent(alias, prefix, profile);
            case PASSWORD -> password(prefix);
            case KEY -> privateKey(alias, prefix, profile);
            case KEYCHAIN -> new CredentialLookup.Unsupported(AuthMethod.KEYCHAIN);
        };
    }

    /** The agent method: a named key, else a password, else the agent socket. A key that is named but cannot be used is a miss; it does not fall through to the password. */
    private CredentialLookup agent(ProfileAlias alias, CredentialPrefix prefix, ConnectionProfile profile) {
        return namedKey(profile, prefix).<CredentialLookup>map(key -> privateKeyAt(key, alias, prefix))
            .or(() -> ownOrGlobalPassword(prefix).map(password -> new CredentialLookup.Resolved(new SshCredential.Password(password))))
            .or(() -> text(AGENT_SOCKET).map(socket -> new CredentialLookup.Resolved(new SshCredential.Agent())))
            .orElseGet(() -> {
                List<CredentialMiss> misses = new ArrayList<>(keyMisses(prefix));
                misses.addAll(passwordMisses(prefix));
                misses.add(new CredentialMiss(CredentialPart.PRIVATE_KEY, AGENT_SOCKET));
                return new CredentialLookup.Missing(misses);
            });
    }

    private CredentialLookup password(CredentialPrefix prefix) {
        return ownOrGlobalPassword(prefix).<CredentialLookup>map(password -> new CredentialLookup.Resolved(new SshCredential.Password(password)))
            .orElseGet(() -> new CredentialLookup.Missing(passwordMisses(prefix)));
    }

    private CredentialLookup privateKey(ProfileAlias alias, CredentialPrefix prefix, ConnectionProfile profile) {
        return namedKey(profile, prefix).map(key -> privateKeyAt(key, alias, prefix)).orElseGet(() -> new CredentialLookup.Missing(keyMisses(prefix)));
    }

    /** The key a profile names: its keyRef, else {@code <prefix>_KEY}, else {@code SSH_MCP_KEY}. */
    private Optional<NamedKey> namedKey(ConnectionProfile profile, CredentialPrefix prefix) {
        return keyReference(profile).map(value -> new NamedKey(value, KEY_REFERENCE_SOURCE)).or(() -> text(prefix.variable("KEY")).map(value -> new NamedKey(value, prefix.variable("KEY"))))
            .or(() -> text(GLOBAL_KEY).map(value -> new NamedKey(value, GLOBAL_KEY)));
    }

    private Optional<SecretValue> ownOrGlobalPassword(CredentialPrefix prefix) {
        return secret(prefix.variable("PASSWORD")).or(() -> secret(GLOBAL_PASSWORD));
    }

    private List<CredentialMiss> keyMisses(CredentialPrefix prefix) {
        return List.of(new CredentialMiss(CredentialPart.KEY_REFERENCE, KEY_REFERENCE_SOURCE), new CredentialMiss(CredentialPart.PRIVATE_KEY, prefix.variable("KEY")),
            new CredentialMiss(CredentialPart.PRIVATE_KEY, GLOBAL_KEY));
    }

    private List<CredentialMiss> passwordMisses(CredentialPrefix prefix) {
        return List.of(new CredentialMiss(CredentialPart.PASSWORD, prefix.variable("PASSWORD")), new CredentialMiss(CredentialPart.PASSWORD, GLOBAL_PASSWORD));
    }

    private CredentialLookup privateKeyAt(NamedKey named, ProfileAlias alias, CredentialPrefix prefix) {
        String keyText = named.value();
        String place = "the key file named by " + named.source();
        Path keyFile;
        try {
            keyFile = locate(keyText);
        } catch (InvalidPathException e) {
            // The reason only: the message of the exception holds the setting, which may be a key text.
            LOG.warn("The key path named by {} for the profile {} is not a valid path ({})", named.source(), alias.value(), e.getReason());
            return missing(CredentialPart.PRIVATE_KEY, place + " cannot be read");
        }
        return switch (read(keyFile, place)) {
            case FileRead.Text key when key.text().isEmpty() -> missing(CredentialPart.PRIVATE_KEY, place + " is empty");
            case FileRead.Text key -> withPassphraseAndCertificate(SecretValue.of(key.text()), alias, prefix, keyFile, named.source());
            case FileRead.Absent absent -> missing(CredentialPart.PRIVATE_KEY, place + " does not exist");
            case FileRead.Unreadable unreadable -> missing(CredentialPart.PRIVATE_KEY, place + " cannot be read");
        };
    }

    private CredentialLookup withPassphraseAndCertificate(SecretValue pem, ProfileAlias alias, CredentialPrefix prefix, Path keyFile, String keySource) {
        Setting<SecretValue> passphrase = secret(prefix.variable("PASSPHRASE")).or(() -> secret(GLOBAL_PASSPHRASE)).<Setting<SecretValue>>map(Setting::given)
            .orElseGet(Setting::unset);
        if (!catalog.wantsCertificate(alias)) {
            return new CredentialLookup.Resolved(new SshCredential.PrivateKey(pem, passphrase, Setting.unset()));
        }
        String certSource = text(prefix.variable("CERT")).isPresent() ? "the certificate file named by " + prefix.variable("CERT")
            : "the certificate next to the key named by " + keySource;
        Path certFile;
        try {
            certFile = text(prefix.variable("CERT")).map(this::locate).orElseGet(() -> certificateNextTo(keyFile));
        } catch (InvalidPathException e) {
            LOG.warn("{} is not a valid path ({})", certSource, e.getReason());
            return missing(CredentialPart.CERTIFICATE, certSource + " cannot be read");
        }
        return switch (read(certFile, certSource)) {
            case FileRead.Text certificate -> new CredentialLookup.Resolved(new SshCredential.PrivateKey(pem, passphrase, Setting.given(certificate.text())));
            case FileRead.Absent absent -> new CredentialLookup.Resolved(new SshCredential.PrivateKey(pem, passphrase, Setting.unset()));
            case FileRead.Unreadable unreadable -> missing(CredentialPart.CERTIFICATE, certSource + " cannot be read");
        };
    }

    private CredentialLookup missing(CredentialPart part, String place) {
        return new CredentialLookup.Missing(List.of(new CredentialMiss(part, place)));
    }

    private Optional<String> keyReference(ConnectionProfile profile) {
        return profile.keyRef() instanceof Setting.Given<String> given && !given.value().isEmpty() ? Optional.of(given.value()) : Optional.empty();
    }

    /** The certificate that goes with a key file: {@code id-cert.pub} next to {@code id} or {@code id.pub}. */
    private Path certificateNextTo(Path keyFile) {
        String name = keyFile.getFileName().toString();
        String stem = name.endsWith(PUBLIC_KEY_SUFFIX) ? name.substring(0, name.length() - PUBLIC_KEY_SUFFIX.length()) : name;
        return keyFile.resolveSibling(stem + CERTIFICATE_SUFFIX);
    }

    /** The value of a setting; an empty value is no value (2.x tested the variable for truth). */
    private Optional<String> text(String name) {
        String value = environment.getProperty(name);
        return value == null || value.isEmpty() ? Optional.empty() : Optional.of(value);
    }

    private Optional<SecretValue> secret(String name) {
        return text(name).map(SecretValue::of);
    }

    /** The path of a file as the configuration spells it; a first name {@code ~} is the home folder when one is known. */
    private Path locate(String spelling) {
        Path path = Path.of(spelling);
        if (path.getRoot() != null || path.getNameCount() == 0 || !HOME_NAME.equals(path.getName(0).toString())) {
            return path;
        }
        return text("HOME").or(() -> text("user.home")).map(Path::of)
            .map(home -> path.getNameCount() == 1 ? home : home.resolve(path.subpath(1, path.getNameCount())))
            .orElse(path);
    }

    /** Reads a file; {@code source} is a phrase that says where its path was set (it opens the log line), because the path itself may be a secret text and is never logged. */
    private FileRead read(Path file, String source) {
        if (!Files.exists(file)) {
            return new FileRead.Absent();
        }
        try {
            return new FileRead.Text(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException e) {
            LOG.warn("{} cannot be read ({})", source,e.getClass().getSimpleName());
            return new FileRead.Unreadable();
        }
    }
}
