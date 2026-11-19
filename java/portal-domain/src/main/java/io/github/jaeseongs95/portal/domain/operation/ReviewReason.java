package io.github.jaeseongs95.portal.domain.operation;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Secondary review classification recorded on an operation decision. Rule-based approval (3.0.0, defect D-0122) records {@link #NONE}.
 */
public enum ReviewReason {
    NONE("none");

    private final String wire;

    ReviewReason(String wire) {
        this.wire = wire;
    }

    @JsonValue
    public String wire() {
        return wire;
    }
}
