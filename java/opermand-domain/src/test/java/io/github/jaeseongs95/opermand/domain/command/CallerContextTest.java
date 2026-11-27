package io.github.jaeseongs95.opermand.domain.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.jaeseongs95.opermand.domain.error.ErrorCode;
import io.github.jaeseongs95.opermand.domain.error.RequestErrorCode;
import io.github.jaeseongs95.opermand.domain.error.RequestRefusedException;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** RC-A-439, RC-A-752, RC-A-753 and RC-A-758: caller context limits and secret detection (SSH_PORTAL-D-0122). */
class CallerContextTest {

    @Test
    @DisplayName("RC-A-752 a sensitive path may be mentioned in a description; the command that reads it is still sensitive")
    void sensitivePathsAreContextButCommandsReadingThemAreSensitive() {
        for (String path : List.of(".env", "/etc/shadow", "/home/operator/.ssh/id_ed25519", "/secrets/service")) {
            String description = "The service reads " + path + " at start and the check is read only.";
            assertThat(new CallerContext(description).value()).isEqualTo(description);
            assertThat(new SensitiveText("cat " + path).isSensitive()).as(path).isTrue();
            assertThat(new SensitiveText(description).containsCredential()).as(path).isFalse();
        }
        assertThat(new SensitiveText("ls /tmp").isSensitive()).isFalse();
        assertThat(new SensitiveText("cat /usr/share/environment").isSensitive()).isFalse();
    }

    @Test
    @DisplayName("S-D0169-01 a sensitive place written with the separators of a Windows path is sensitive as well (the text of a command is not tied to the server's operating system)")
    void sensitivePlacesWithWindowsSeparators() {
        for (String path : List.of("C:\\Users\\operator\\.ssh\\id_ed25519", "C:\\ProgramData\\service\\secrets\\db", "\\secrets", "D:\\work\\.env", "D:\\work\\.env\\local")) {
            assertThat(new SensitiveText("type " + path).isSensitive()).as(path).isTrue();
        }
        assertThat(new SensitiveText("dir C:\\tools\\secretsanta\\run").isSensitive()).isFalse();
        assertThat(new SensitiveText("dir C:\\Users\\operator\\.ssh\\config").isSensitive()).isFalse();
    }

    @Test
    @DisplayName("RC-A-753 / RC-A-439 credential material in a description is refused with COMMAND_CONTEXT_CONTAINS_SECRET")
    void credentialsAreRefused() {
        for (String text : List.of("-----BEGIN PRIVATE KEY-----", "send Bearer synthetic-credential", "api_key=synthetic", "api-key: x", "apikey = x", "password: x", "secret=x", "token=x",
            "TOKEN = x", "password=synthetic-only")) {
            assertThat(codeOf(text)).as(text).isEqualTo(RequestErrorCode.COMMAND_CONTEXT_CONTAINS_SECRET);
        }
    }

    @Test
    @DisplayName("RC-A-753 text with a NUL, or null, is INVALID_COMMAND_CONTEXT; above 8192 characters is COMMAND_CONTEXT_TOO_LARGE; 8192 Hangul syllables pass")
    void invalidAndOversizedDescriptions() {
        assertThat(codeOf("a\0b")).isEqualTo(RequestErrorCode.INVALID_COMMAND_CONTEXT);
        assertThat(codeOf(null)).isEqualTo(RequestErrorCode.INVALID_COMMAND_CONTEXT);
        assertThat(codeOf("lone surrogate \ud800")).isEqualTo(RequestErrorCode.INVALID_COMMAND_CONTEXT);
        assertThat(codeOf("a".repeat(8193))).isEqualTo(RequestErrorCode.COMMAND_CONTEXT_TOO_LARGE);
        assertThat(new CallerContext("a".repeat(8192)).value()).hasSize(8192);
        assertThat(new CallerContext("한".repeat(8192)).value()).hasSize(8192);
        assertThat(CallerContext.MAX_CHARS).isEqualTo(8192);
        assertThat(CallerContext.none().isBlank()).isTrue();
        assertThat(new CallerContext(" \t\n ").isBlank()).isTrue();
        assertThat(new CallerContext(" x ").isBlank()).isFalse();
    }

    @Test
    @DisplayName("RC-A-753 the refusal is a contract answer: it is not retryable and nothing was attempted")
    void theRefusalIsAContractAnswer() {
        assertThatThrownBy(() -> new CallerContext("password=x")).isInstanceOfSatisfying(RequestRefusedException.class, refusal -> {
            assertThat(refusal.retryable()).isFalse();
            assertThat(refusal.attempt().wire()).isEqualTo(Boolean.FALSE);
            assertThat(refusal.getMessage()).doesNotContain("password=x");
        });
    }

    @Test
    @DisplayName("RC-A-758 a normalised secret reference is a public identifier, not credential material; a literal next to it still is")
    void secretReferences() {
        for (String reference : List.of("secret://local-file/windows-gui-agent", "secret://a/b", "secret://" + "h".repeat(32) + "/n", "secret://h/" + "n".repeat(64))) {
            assertThat(new SensitiveText(reference).containsCredential()).as(reference).isFalse();
            assertThat(new CallerContext("use " + reference + " for the login").value()).contains(reference);
            assertThat(new SensitiveText("open " + reference + ", then run").isSensitive()).as(reference).isFalse();
            assertThat(new SensitiveText(reference + " password=x").containsCredential()).as(reference).isTrue();
            assertThat(new SensitiveText("token=x " + reference).containsCredential()).as(reference).isTrue();
            assertThat(codeOf("Bearer x " + reference)).isEqualTo(RequestErrorCode.COMMAND_CONTEXT_CONTAINS_SECRET);
        }
    }

    @Test
    @DisplayName("RC-A-758 a reference that is not in the normal form is credential material: upper case, spaces, empty name, extra path, query, escapes, over-long parts")
    void malformedSecretReferences() {
        for (String bad : List.of("SECRET://local-file/name", "secret://Local/name", "secret://local file/name", "secret://local/", "secret://local/name/extra", "secret://local/name?x=1",
            "secret://local/name%20x", "secret://local\\name", "xsecret://local/name", "secret://" + "h".repeat(33) + "/n", "secret://h/" + "n".repeat(65), "secret://local/name#frag")) {
            assertThat(new SensitiveText(bad).containsCredential()).as(bad).isTrue();
        }
    }

    private ErrorCode codeOf(String text) {
        try {
            new CallerContext(text);
        } catch (RequestRefusedException refusal) {
            return refusal.code();
        }
        throw new AssertionError("The description was accepted: " + text);
    }
}
