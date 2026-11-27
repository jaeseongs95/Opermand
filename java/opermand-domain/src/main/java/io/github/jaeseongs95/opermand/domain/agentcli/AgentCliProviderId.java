package io.github.jaeseongs95.opermand.domain.agentcli;

import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Open, vendor-independent identifier for an agent command-line tool provider (SSH_PORTAL-D-0061).
 * The catalog determines which providers are active; this value object enforces only safe lowercase identifier syntax so new providers require no domain change.
 */
public record AgentCliProviderId(@JsonValue String wire) {

    /** Lowercase ASCII identifier (1..32 chars): starts with a letter, followed by lowercase letters, digits, hyphens, or underscores. */
    private static final Pattern VALID = Pattern.compile("[a-z][a-z0-9_-]{0,31}");
    private static final Set<String> RESERVED = Set.of("constructor", "prototype");

    public AgentCliProviderId {
        if (wire == null || !VALID.matcher(wire).matches() || RESERVED.contains(wire)) {
            throw new IllegalArgumentException("Unsupported CLI provider");
        }
    }

    /** Parses a wire provider identifier, refusing null, blank, uppercase, whitespace, or shell metacharacters. */
    public static AgentCliProviderId parse(String text) {
        return new AgentCliProviderId(text);
    }

    @Override
    public String toString() {
        return wire;
    }
}
