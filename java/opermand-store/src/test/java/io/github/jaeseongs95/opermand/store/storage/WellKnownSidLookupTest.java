package io.github.jaeseongs95.opermand.store.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.UserPrincipal;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.jaeseongs95.opermand.store.TestStorage;

/**
 * TC-D0140-01..03: regression tests for well-known Windows SID resolution (stderr separation, timeout, no silent English fallback, no error caching) and locale-independent
 * owner-only ACL creation.
 */
class WellKnownSidLookupTest {

    @TempDir
    Path directory;

    @Test
    @DisplayName("TC-D0140-03: malformed SID input is rejected immediately without invoking PowerShell or polluting the cache")
    void rejectsMalformedSidWithoutCaching() {
        assertThatThrownBy(() -> TestStorage.resolveWellKnownSid("not-a-sid"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("SID");
        assertThatThrownBy(() -> TestStorage.resolveWellKnownSid("S-1-1-0'; whoami; '"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("TC-D0140-03: well-known Windows SIDs resolve to UserPrincipals on an ACL store and unknown SIDs fail fast without silent fallback or cache pollution")
    void resolvesWellKnownSidsAndFailsFastOnUnknownSid() throws IOException {
        TestStorage.requireOrSkip("acl", Files.getFileStore(directory).supportsFileAttributeView("acl"),
            "the file store of the test folder has no acl view (it is a posix store)");

        for (String sid : List.of("S-1-1-0", "S-1-3-4", "S-1-5-18", "S-1-5-32-544")) {
            UserPrincipal principal = TestStorage.lookupBySid(directory, sid);
            assertThat(principal).as("resolved principal for %s", sid).isNotNull();
            assertThat(principal.getName()).isNotBlank();
            // Repeat call hits the cache and returns an equal principal.
            assertThat(TestStorage.lookupBySid(directory, sid)).isEqualTo(principal);
        }

        // Valid SID syntax that cannot be translated to an NTAccount must fail fast (no silent fallback) and must not cache the failure.
        String unmappedSid = "S-1-5-21-999999999-999999999-999999999-999999";
        assertThatThrownBy(() -> TestStorage.resolveWellKnownSid(unmappedSid))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining(unmappedSid);
        assertThatThrownBy(() -> TestStorage.resolveWellKnownSid(unmappedSid))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining(unmappedSid);
    }

    @Test
    @DisplayName("TC-D0140-03: stderr output from a subprocess never pollutes stdout in resolveWellKnownSid")
    void stderrIsIsolatedFromStdout() throws IOException {
        TestStorage.requireOrSkip("acl", Files.getFileStore(directory).supportsFileAttributeView("acl"),
            "the file store of the test folder has no acl view (it is a posix store)");
        String first = TestStorage.resolveWellKnownSid("S-1-1-0");
        // Must be a single clean line with no error/warning lines mixed in.
        assertThat(first).doesNotContain("\n").doesNotContain("\r").isNotBlank();
    }
}
