package io.github.jaeseongs95.portal.domain.contract;

import com.fasterxml.jackson.annotation.JsonValue;

/** The version of the public contract. The envelope always carries the constant {@code "3.0"} (2.4.2 carried "2.0"; the change is design B-1.1 of stage4-design.md). */
public enum ContractVersion {
    V3_0("3.0");

    private final String wire;

    ContractVersion(String wire) {
        this.wire = wire;
    }

    @JsonValue
    public String wire() {
        return wire;
    }
}
