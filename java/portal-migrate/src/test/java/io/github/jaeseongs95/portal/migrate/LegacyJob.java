package io.github.jaeseongs95.portal.migrate;

import java.util.ArrayList;
import java.util.List;

/**
 * One 2.x job as the test wants it to be, written the way 2.4.2 wrote it: as the record of the formal journal ({@code operations.sqlite}, which holds the public job under
 * {@code metadata.approval}) or as the public job of a request journal. Every value is chosen by the test; the builder only knows the JSON shape of 2.x.
 */
final class LegacyJob {

    static final long T0 = 1_792_000_000_000L;
    static final String REVISION = "a".repeat(64);

    private final String id;
    private String requestKey;
    private String explicitKey;
    private String kind = "command";
    private String state = "completed";
    private String command = "echo hello";
    private String context = "";
    private String displayContext = "";
    private String owner = "mcp";
    private String targetId = "prod";
    private String targetRevision = REVISION;
    private long createdAt = T0;
    private Long startedAt;
    private Long finishedAt;
    private Long expiresAt;
    private String decision;
    private String approvalMode;
    private String result;
    private boolean readOnly;

    private LegacyJob(String id) {
        this.id = id;
        this.requestKey = "key-" + id;
        this.expiresAt = T0 + 3_600_000L;
    }

    /** A job with an id of 48 hex characters made from the number and the key {@code key-<id>}. */
    static LegacyJob job(int number) {
        return new LegacyJob(String.format("%048x", number));
    }

    String id() {
        return id;
    }

    String requestKey() {
        return requestKey;
    }

    String explicitKey() {
        return explicitKey;
    }

    String state() {
        return state;
    }

    String targetId() {
        return targetId;
    }

    String targetRevision() {
        return targetRevision;
    }

    LegacyJob key(String value) {
        this.requestKey = value;
        this.explicitKey = value;
        return this;
    }

    /** The request journal has no key for this row (the older shape of the command journal). */
    LegacyJob withoutKey() {
        this.explicitKey = null;
        return this;
    }

    LegacyJob kind(String value) {
        this.kind = value;
        return this;
    }

    LegacyJob state(String value) {
        this.state = value;
        return this;
    }

    LegacyJob command(String value) {
        this.command = value;
        return this;
    }

    LegacyJob context(String value) {
        this.context = value;
        return this;
    }

    LegacyJob displayContext(String value) {
        this.displayContext = value;
        return this;
    }

    LegacyJob target(String value) {
        this.targetId = value;
        return this;
    }

    LegacyJob times(long created, Long started, Long finished) {
        this.createdAt = created;
        this.startedAt = started;
        this.finishedAt = finished;
        this.expiresAt = created + 3_600_000L;
        return this;
    }

    /** The automatic approval's decision, as the raw JSON object of 2.x. */
    LegacyJob decision(String json) {
        this.decision = json;
        return this;
    }

    LegacyJob approvalMode(String value) {
        this.approvalMode = value;
        return this;
    }

    LegacyJob result(String json) {
        this.result = json;
        return this;
    }

    LegacyJob readOnly() {
        this.readOnly = true;
        return this;
    }

    /** A job that ran and exited with the code, in the result shape 2.4.2 stored. */
    LegacyJob exited(int exitCode) {
        this.result = "{\"failed\":" + (exitCode != 0) + ",\"texts\":[\"finished\"],\"execution\":{\"phase\":\"settled\",\"reason\":\"exit\",\"remoteState\":\"exited\","
            + "\"exitCode\":" + exitCode + ",\"signal\":null,\"signalDelivery\":null,\"automaticReplay\":false,\"cleanupComplete\":true,\"requiresReconciliation\":false,"
            + "\"outputState\":\"complete\",\"captureFailed\":false,"
            + "\"stdout\":{\"receivedBytes\":12,\"retainedBytes\":12,\"cursor\":12,\"truncated\":false},\"stderr\":{\"receivedBytes\":0,\"retainedBytes\":0,\"cursor\":0,\"truncated\":false}}}";
        return this;
    }

    /** A job whose transport was lost after the command may have started: the outcome 2.4.2 stored as unknown. */
    LegacyJob transportLost() {
        this.result = "{\"failed\":true,\"texts\":[\"The connection was lost.\"],\"failure\":{\"stage\":\"ssh_execution\",\"code\":\"SSH_EXECUTION_FAILED\",\"remoteOutcome\":\"unknown\","
            + "\"automaticReplay\":false},\"execution\":{\"phase\":\"settled\",\"reason\":\"transport_lost\",\"remoteState\":\"unknown\",\"cleanupComplete\":false,"
            + "\"requiresReconciliation\":true}}";
        return this;
    }

    /** The row of the formal journal: the record with the public job under {@code metadata.approval}. */
    String operationBody() {
        StringBuilder text = new StringBuilder("{");
        text.append("\"id\":").append(quote(id)).append(",\"requestKey\":").append(quote(requestKey)).append(",\"owner\":").append(quote(owner));
        text.append(",\"targetId\":").append(quote(targetId)).append(",\"targetRevision\":").append(quote(targetRevision)).append(",\"kind\":").append(quote(kind));
        text.append(",\"input\":{\"requestId\":").append(quote(id)).append(",\"operation\":").append(operation()).append(",\"profile\":\"aws-local\",\"commandClass\":\"privileged\"}");
        text.append(",\"createdAt\":").append(createdAt).append(",\"acceptedAt\":").append(createdAt).append(",\"updatedAt\":").append(finishedAt == null ? createdAt : finishedAt);
        if (expiresAt != null) {
            text.append(",\"expiresAt\":").append(expiresAt);
        }
        if (startedAt != null) {
            text.append(",\"startedAt\":").append(startedAt);
        }
        if (finishedAt != null) {
            text.append(",\"finishedAt\":").append(finishedAt);
        }
        text.append(",\"state\":").append(quote(state)).append(",\"version\":2");
        if (result != null) {
            text.append(",\"result\":").append(result);
        }
        text.append(",\"metadata\":{\"approval\":").append(journalBody()).append("}}");
        return text.toString();
    }

    /** The public job of a request journal. */
    String journalBody() {
        StringBuilder text = new StringBuilder("{");
        text.append("\"id\":").append(quote(id)).append(",\"kind\":").append(quote(kind)).append(",\"operation\":").append(operation());
        text.append(",\"command\":").append(quote(command)).append(",\"targetId\":").append(quote(targetId)).append(",\"targetRevision\":").append(quote(targetRevision));
        if (decision != null) {
            text.append(",\"decision\":").append(decision);
        }
        text.append(",\"profile\":\"aws-local\",\"commandClass\":\"privileged\",\"target\":\"ubuntu@203.0.113.10:22\",\"state\":").append(quote(state));
        if (approvalMode != null) {
            text.append(",\"approvalMode\":").append(quote(approvalMode));
        }
        text.append(",\"createdAt\":").append(createdAt);
        if (expiresAt != null) {
            text.append(",\"expiresAt\":").append(expiresAt);
        }
        if (finishedAt != null) {
            text.append(",\"finishedAt\":").append(finishedAt);
        }
        if (result != null) {
            text.append(",\"result\":").append(result);
        }
        return text.append("}").toString();
    }

    private String operation() {
        List<String> fields = new ArrayList<>();
        fields.add("\"kind\":" + quote(kind));
        fields.add("\"command\":" + quote(command));
        if (!context.isEmpty()) {
            fields.add("\"context\":" + quote(context));
        }
        if (!displayContext.isEmpty()) {
            fields.add("\"displayContext\":" + quote(displayContext));
        }
        if (readOnly) {
            fields.add("\"readOnly\":true");
        }
        return "{" + String.join(",", fields) + "}";
    }

    /** JSON text of the string: quotes, backslashes and control characters escaped, every other character (multibyte ones included) as it is. */
    static String quote(String value) {
        StringBuilder text = new StringBuilder("\"");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> text.append("\\\"");
                case '\\' -> text.append("\\\\");
                case '\n' -> text.append("\\n");
                case '\r' -> text.append("\\r");
                case '\t' -> text.append("\\t");
                default -> {
                    if (c < 0x20) {
                        text.append(String.format("\\u%04x", (int) c));
                    } else {
                        text.append(c);
                    }
                }
            }
        }
        return text.append('"').toString();
    }
}
