package io.github.jaeseongs95.opermand.archchecks.fixtures.s3.app.remote;

/** An outcome record: not an interface, so A-REMOTE-RESULT lets it through (stands for opermand-app's ExecutionReport). */
public record ExecutionReport(String outcome) {
}
