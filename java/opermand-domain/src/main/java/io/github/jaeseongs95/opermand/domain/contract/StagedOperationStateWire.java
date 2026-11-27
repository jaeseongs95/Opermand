package io.github.jaeseongs95.opermand.domain.contract;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * {@code stagedFile.operations[].state} on the wire (public-contract.mjs:71): the operation states plus the two a placement can have before an operation exists.
 * It is all 11 operation states ({@code accepted} and {@code evaluating} do not exist, defect D-0119).
 */
public enum StagedOperationStateWire {
    PENDING("pending"), QUEUED("queued"), STARTING("starting"), RUNNING("running"), TERMINATING("terminating"), COMPLETED("completed"), FAILED("failed"),
    CANCELLED("cancelled"), EXPIRED("expired"), DENIED("denied"), UNKNOWN("unknown"), ADMISSION_UNCONFIRMED("admission-unconfirmed"), REFUSED("refused");

    private final String wire;

    StagedOperationStateWire(String wire) {
        this.wire = wire;
    }

    @JsonValue
    public String wire() {
        return wire;
    }
}
