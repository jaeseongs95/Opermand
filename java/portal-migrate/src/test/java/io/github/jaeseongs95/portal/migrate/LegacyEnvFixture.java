package io.github.jaeseongs95.portal.migrate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.util.Set;

/**
 * A 2.x environment file made by the test, in the shape of the installer's file ({@code config/bridge.env.example}), and what the tests check of a settings file. Every value
 * here is invented by the test; none is a real token, key or address. The expected 3.0 text is written out by the test, never computed by the code under test.
 */
final class LegacyEnvFixture {

    static final String MCP_TOKEN = "mcp-token-0f3a9c51d7e84b26a1c0";
    static final String ADMIN_TOKEN = "admin-token-91b2c3d4e5f60718293a";
    static final String TICKET_KEY = "4f".repeat(32);
    static final String JEV_KEY = "jev-key-7c6b5a49382716f5e4d3";
    static final String PROFILE_PASSWORD = "profile-password-2b8e6d4c0a19";
    static final String ADMIN_ORIGIN = "https://admin.example.test";
    static final String OWNER_EMAIL = "owner@example.test";
    static final String TARGETS = "[{\"id\":\"alpha\",\"displayName\":\"Alpha\",\"readProfile\":\"alpha-read\",\"writeProfile\":\"alpha-write\",\"credentialRevision\":\"v1\"}]";

    /** Every value of the fixture that is a secret: none of them may appear in anything the command prints. */
    static final Set<String> SECRETS = Set.of(MCP_TOKEN, ADMIN_TOKEN, TICKET_KEY, JEV_KEY, PROFILE_PASSWORD);

    private LegacyEnvFixture() {
    }

    /** The lines of an installer file in token mode that also holds the settings 3.0 dropped, with a profile file that exists at {@code profileFile}. */
    static String[] installerLines(Path profileFile) {
        return new String[] {
            "# Real secrets are generated on the server.",
            "BRIDGE_MCP_TOKEN=" + MCP_TOKEN,
            "BRIDGE_ADMIN_TOKEN=" + ADMIN_TOKEN,
            "BRIDGE_UI_TICKET_KEY=" + TICKET_KEY,
            "BRIDGE_ADMIN_ORIGIN=" + ADMIN_ORIGIN,
            "BRIDGE_DISPLAY_NAME=\"Example Bridge\"",
            "",
            "BRIDGE_UI_ENABLED=false",
            "BRIDGE_TRANSFER_ROOT=/var/lib/example/transfers",
            "BRIDGE_AUDIT_PATH=/var/lib/example/audit.jsonl",
            "BRIDGE_CONFIG_PATH=" + profileFile.toString().replace('\\', '/'),
            "BRIDGE_TARGETS=" + TARGETS,
            "BRIDGE_ADMIN_AUTH_MODE=token",
            "BRIDGE_ACCESS_TEAM_DOMAIN=https://team.example.test",
            "BRIDGE_ACCESS_AUD=" + "a".repeat(64),
            "BRIDGE_ACCESS_OWNER_EMAILS=" + OWNER_EMAIL,
            "SSH_MCP_ALPHA_WRITE_PASSWORD=" + PROFILE_PASSWORD,
            "JEV_API_KEY=" + JEV_KEY,
        };
    }

    /** Writes the lines, each ended by LF, as the installer on Linux writes them. */
    static Path write(Path file, String... lines) {
        return writeText(file, String.join("\n", lines) + "\n");
    }

    /** Writes the lines, each ended by CRLF, as an editor on Windows saves them. */
    static Path writeCrLf(Path file, String... lines) {
        return writeText(file, String.join("\r\n", lines) + "\r\n");
    }

    static Path writeText(Path file, String text) {
        try {
            Files.createDirectories(file.getParent());
            Files.write(file, text.getBytes(StandardCharsets.UTF_8));
            return file;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** A profile file as 2.x wrote it ({@code config/config.example.toml}); the migration does not read it, it only looks whether it is there. */
    static Path profileFile(Path file) {
        return writeText(file, "[defaults]\ndefaultProfile = \"alpha-write\"\n\n[[profiles]]\nname = \"alpha-write\"\nhost = \"127.0.0.1\"\nport = 22\nuser = \"example\"\nauth = \"key\"\n");
    }

    /** The settings file of a real run reads back as the text the test expects. */
    static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Reads the rights of the file the way the file system keeps them (permission bits where there are bits, an access control list where there is a list) and requires that
     * nobody but the owner has any. This is the test's own reader, not the code under test.
     */
    static void assertOwnerOnly(Path file) {
        try {
            FileStore store = Files.getFileStore(file);
            if (store.supportsFileAttributeView("posix")) {
                assertThat(Files.getPosixFilePermissions(file)).as("permission bits of " + file.getFileName()).isEqualTo(PosixFilePermissions.fromString("rw-------"));
            } else if (store.supportsFileAttributeView("acl")) {
                AclFileAttributeView view = Files.getFileAttributeView(file, AclFileAttributeView.class);
                UserPrincipal owner = view.getOwner();
                assertThat(view.getAcl()).as("access control list of " + file.getFileName()).isNotEmpty().allSatisfy(entry -> {
                    assertThat(entry.type()).isEqualTo(AclEntryType.ALLOW);
                    assertThat(entry.principal()).isEqualTo(owner);
                });
                assertThat(view.getAcl().stream().map(AclEntry::principal).distinct()).containsExactly(owner);
            } else {
                fail("the file store " + store + " offers neither permission bits nor an access control list, so the test cannot see who may use the file");
            }
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
