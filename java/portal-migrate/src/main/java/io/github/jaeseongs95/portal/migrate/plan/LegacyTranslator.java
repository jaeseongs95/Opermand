package io.github.jaeseongs95.portal.migrate.plan;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import org.springframework.boot.json.JsonParser;

import io.github.jaeseongs95.portal.domain.error.DispatchFailure;
import io.github.jaeseongs95.portal.domain.error.RequestRefusedException;
import io.github.jaeseongs95.portal.domain.id.Digester;
import io.github.jaeseongs95.portal.domain.id.OperationId;
import io.github.jaeseongs95.portal.domain.id.OwnerId;
import io.github.jaeseongs95.portal.domain.id.RequestKey;
import io.github.jaeseongs95.portal.domain.id.TargetId;
import io.github.jaeseongs95.portal.domain.operation.Admission;
import io.github.jaeseongs95.portal.domain.operation.CommandInput;
import io.github.jaeseongs95.portal.domain.operation.ConflictKey;
import io.github.jaeseongs95.portal.domain.operation.Deadline;
import io.github.jaeseongs95.portal.domain.operation.DecisionReason;
import io.github.jaeseongs95.portal.domain.operation.ExecutionOutcome;
import io.github.jaeseongs95.portal.domain.operation.NewOperation;
import io.github.jaeseongs95.portal.domain.operation.Operation;
import io.github.jaeseongs95.portal.domain.operation.OperationKind;
import io.github.jaeseongs95.portal.domain.operation.OperationState;
import io.github.jaeseongs95.portal.domain.target.ProfileAlias;
import io.github.jaeseongs95.portal.domain.target.TargetRevision;
import io.github.jaeseongs95.portal.domain.text.CommandText;
import io.github.jaeseongs95.portal.domain.text.Utf8Text;
import io.github.jaeseongs95.portal.migrate.source.LegacyRow;

/**
 * Turns one 2.x row into what 3.0 stores, or says why it cannot. Nothing is written and nothing is changed to fit: the id, the request key, the command text and the two
 * context texts are the 2.x values exactly, and a row whose value the 3.0 model refuses (a request key that is not 16 to 128 characters of the 3.0 alphabet, a command with a
 * hidden character, an unknown enum value) is {@link Translation.Rejected} with the reason.
 *
 * <p>What 3.0 cannot represent is dropped on purpose and listed in the guide: the model evaluation details of the 2.x decision, the conflict key (an imported operation never
 * fences a resource; 2.x did the same, so that old unknowns cannot block new work through the fence), the target address of a 2.x job that has no administrator alias, and the
 * 2.x target revision, which 3.0 cannot reproduce (a 2.x revision that is 64 hex characters is kept as it is, anything else becomes 64 zeros). A job that was in flight when 2.x
 * stopped is imported the way 2.x itself recovered it: waiting for approval becomes expired, anything that may have run becomes unknown. An operation is never replayed.
 */
public final class LegacyTranslator {

    private static final String UNBOUND_TARGET = "legacy-unbound";
    private static final String UNBOUND_REVISION = "0".repeat(64);
    private static final String UNBOUND_PROFILE = "legacy";
    private static final String UNBOUND_OWNER = "legacy-owner";
    private static final long APPROVAL_VALIDITY_MS = 3_600_000L;
    private static final Set<String> LEGACY_STATES = Set.of("accepted", "pending", "evaluating", "queued", "starting", "running", "terminating", "completed", "failed",
        "cancelled", "expired", "denied", "unknown");

    private final JsonParser parser;
    private final Digester digester;
    private final DecisionReader decisions = new DecisionReader();
    private final OutcomeReader outcomes = new OutcomeReader();

    public LegacyTranslator(JsonParser parser, Digester digester) {
        this.parser = Objects.requireNonNull(parser, "parser");
        this.digester = Objects.requireNonNull(digester, "digester");
    }

    public Translation translate(LegacyRow row) {
        if (!row.readable()) {
            return new Translation.Rejected(row.problem());
        }
        try {
            return build(row);
        } catch (RowRejectedException | IllegalArgumentException e) {
            return new Translation.Rejected(e.getMessage());
        } catch (RequestRefusedException e) {
            return new Translation.Rejected("the 3.0 domain refuses it: " + e.code());
        }
    }

    private Translation build(LegacyRow row) {
        JsonObject body = JsonObject.parse(parser, row.body());
        if (!row.id().equals(body.string("id"))) {
            throw new RowRejectedException("the id of the body differs from the id column");
        }
        JsonObject approval = JsonObject.firstOf(nested(body, "metadata", "approval"), nested(body, "legacy", "original"), body);
        JsonObject operation = JsonObject.firstOf(approval.object("operation"), nested(body, "input", "operation"));
        String kind = kindOf(body, approval, operation);
        Translation notMoved = notMoved(kind);
        if (notMoved != null) {
            return notMoved;
        }
        String state = stateOf(body, approval);
        long createdMs = firstLong(body.integer("createdAt"), approval.integer("createdAt"), "createdAt");
        Instant createdAt = Instant.ofEpochMilli(createdMs);
        Long expiresMs = firstOrNull(body.integer("expiresAt"), approval.integer("expiresAt"));
        Long startedMs = body.integer("startedAt");
        Long finishedMs = firstOrNull(body.integer("finishedAt"), approval.integer("finishedAt"));

        String command = operation == null ? null : operation.string("command");
        if (command == null) {
            command = approval.string("command");
        }
        if (command == null) {
            throw new RowRejectedException("the command text is missing");
        }
        String context = operation == null ? null : operation.string("context");
        String displayContext = operation == null ? null : operation.string("displayContext");
        boolean readOnly = operation != null && Boolean.TRUE.equals(operation.bool("readOnly"));
        CommandInput input = new CommandInput(new CommandText(command), new Utf8Text(context == null ? "" : context), new Utf8Text(displayContext == null ? "" : displayContext));

        String requestKey = row.requestKey() != null ? row.requestKey() : "legacy:" + row.source() + ":" + row.id();
        Deadline requested = requestedDeadline(createdMs, expiresMs);
        NewOperation spec = NewOperation.of(digester, requestKeyOf(requestKey), ownerOf(firstString(body.string("owner"), approval.string("requestOwner"))),
            targetOf(firstString(body.string("targetId"), approval.string("targetId"))), revisionOf(firstString(body.string("targetRevision"), approval.string("targetRevision"))),
            profileOf(firstString(approval.string("profile"), nestedString(body, "input", "profile"))), input, new ConflictKey.None(), requested);

        DecisionReader.Choice choice = decisions.read(approval.object("decision"), firstString(approval.string("approvalMode"), body.string("approvalMode")), readOnly);
        boolean refusedByPolicy = choice.reason() == DecisionReason.POLICY_DENIED;
        Admission admission = refusedByPolicy && !"denied".equals(state) ? Admission.of(DecisionReason.NO_MATCHING_RULE, OperationKind.COMMAND, choice.policyRevision())
            : Admission.of(choice.reason(), OperationKind.COMMAND, choice.policyRevision());

        Instant expiry = requested instanceof Deadline.At at ? at.instant() : createdAt.plusMillis(APPROVAL_VALIDITY_MS);
        Instant started = latest(createdAt, startedMs);
        Instant finished = latest(started, finishedMs);
        JsonObject result = firstJson(body.object("result"), approval.object("result"));
        List<Step> steps = new ArrayList<>();
        OperationState expected = stepsFor(state, admission, result, approval.object("decision"), createdAt, started, finished, expiry, steps);

        OperationId id = new OperationId(row.id());
        Operation current = Operation.admit(spec, id, createdAt, admission);
        for (Step step : steps) {
            current = step.advance(current);
        }
        String reinterpretation = current.state() == expected ? null : "2.x state \"" + state + "\" is stored as " + current.state() + " (3.0 expected " + expected
            + "; the recorded outcome does not confirm it)";
        return new Translation.Import(new ImportPlan(spec, admission, id, createdAt, steps, current.state(), reinterpretation));
    }

    /** Fills {@code steps} with the transitions after intake for the 2.x state and returns the state 2.x's own rules say the operation ends in. */
    private OperationState stepsFor(String state, Admission admission, JsonObject result, JsonObject decision, Instant created, Instant started, Instant finished,
                                    Instant expiry, List<Step> steps) {
        boolean needsOwner = admission instanceof Admission.Review;
        switch (state) {
            case "denied" -> {
                if (!(admission instanceof Admission.Deny)) {
                    steps.add(new Step.Deny(finished));
                }
                return OperationState.DENIED;
            }
            case "expired", "pending", "accepted" -> {
                steps.add(new Step.Expire(latest(expiry, finished)));
                return OperationState.EXPIRED;
            }
            case "cancelled" -> {
                if (outcomes.hasExecution(result)) {
                    started(steps, needsOwner, created, started, new Step.Settle(outcomes.read(result, state), finished));
                } else {
                    steps.add(new Step.Cancel(finished));
                }
                return OperationState.CANCELLED;
            }
            case "failed" -> {
                DispatchFailure refusal = outcomes.dispatchFailure(decision);
                if (refusal != null) {
                    steps.add(new Step.Refuse(refusal, finished));
                } else if (outcomes.hasExecution(result)) {
                    started(steps, needsOwner, created, started, new Step.Settle(outcomes.read(result, state), finished));
                } else {
                    steps.add(new Step.Refuse(new DispatchFailure.Unclassified(), finished));
                }
                return OperationState.FAILED;
            }
            case "completed" -> {
                started(steps, needsOwner, created, started, new Step.Settle(outcomeOf(result, state), finished));
                return OperationState.COMPLETED;
            }
            case "unknown" -> {
                if (outcomes.hasExecution(result)) {
                    started(steps, needsOwner, created, started, new Step.Settle(outcomes.read(result, state), finished));
                } else {
                    started(steps, needsOwner, created, started, new Step.RecoverAfterRestart(finished));
                }
                return OperationState.UNKNOWN;
            }
            default -> {
                // evaluating, queued, starting, running, terminating: in flight when 2.x stopped, so what happened is unknown (2.x recovered it the same way).
                started(steps, needsOwner, created, started, new Step.RecoverAfterRestart(finished));
                return OperationState.UNKNOWN;
            }
        }
    }

    private void started(List<Step> steps, boolean needsOwner, Instant created, Instant started, Step last) {
        if (needsOwner) {
            steps.add(new Step.OwnerApproval(created));
        }
        steps.add(new Step.Claim(started));
        steps.add(new Step.MarkRunning(started));
        steps.add(last);
    }

    private ExecutionOutcome outcomeOf(JsonObject result, String state) {
        return outcomes.hasExecution(result) ? outcomes.read(result, state) : outcomes.withoutRecord(result);
    }

    private String kindOf(JsonObject body, JsonObject approval, JsonObject operation) {
        if (operation != null && operation.has("sourceContract")) {
            return "source";
        }
        String kind = firstString(body.string("kind"), approval.string("kind"), operation == null ? null : operation.string("kind"));
        if (kind == null) {
            throw new RowRejectedException("the kind of the operation is missing");
        }
        return kind;
    }

    private Translation notMoved(String kind) {
        return switch (kind) {
            case "command" -> null;
            case "source" -> new Translation.NotMigrated(kind, "a source operation kept a private plan that 3.0 never reconstructs or replays");
            case "upload" -> new Translation.NotMigrated(kind, "an upload refers to a staged file that expired long ago; the upload history is not moved");
            case "download" -> new Translation.NotMigrated(kind, "a download refers to a staged result that expired long ago; the download history is not moved");
            case "cli-probe" -> new Translation.NotMigrated(kind, "an internal probe of 2.x with no meaning in 3.0");
            default -> throw new RowRejectedException("the kind \"" + kind + "\" is not a 2.x operation kind this tool knows");
        };
    }

    private String stateOf(JsonObject body, JsonObject approval) {
        String state = body.string("state");
        if ("accepted".equals(state)) {
            String approvalState = approval.string("state");
            state = approvalState == null ? "pending" : approvalState;
        }
        if (state == null || !LEGACY_STATES.contains(state)) {
            throw new RowRejectedException("the state \"" + state + "\" is not a 2.x operation state");
        }
        return state;
    }

    private Deadline requestedDeadline(long createdMs, Long expiresMs) {
        if (expiresMs != null && expiresMs > createdMs && expiresMs <= createdMs + APPROVAL_VALIDITY_MS) {
            return new Deadline.At(Instant.ofEpochMilli(expiresMs));
        }
        return new Deadline.None();
    }

    private RequestKey requestKeyOf(String key) {
        try {
            return new RequestKey(key);
        } catch (IllegalArgumentException e) {
            throw new RowRejectedException("the request key \"" + key + "\" is not a 3.0 request key (16 to 128 characters of A-Z a-z 0-9 _ . : -); keys are never rewritten");
        }
    }

    private OwnerId ownerOf(String owner) {
        return new OwnerId(owner == null ? UNBOUND_OWNER : owner);
    }

    private TargetId targetOf(String candidate) {
        if (candidate != null) {
            try {
                return new TargetId(candidate);
            } catch (IllegalArgumentException e) {
                return new TargetId(UNBOUND_TARGET);
            }
        }
        return new TargetId(UNBOUND_TARGET);
    }

    private TargetRevision revisionOf(String candidate) {
        if (candidate != null) {
            try {
                return TargetRevision.of(candidate);
            } catch (IllegalArgumentException e) {
                return TargetRevision.of(UNBOUND_REVISION);
            }
        }
        return TargetRevision.of(UNBOUND_REVISION);
    }

    private ProfileAlias profileOf(String candidate) {
        if (candidate != null) {
            try {
                return new ProfileAlias(candidate);
            } catch (IllegalArgumentException e) {
                return new ProfileAlias(UNBOUND_PROFILE);
            }
        }
        return new ProfileAlias(UNBOUND_PROFILE);
    }

    private JsonObject nested(JsonObject root, String first, String second) {
        JsonObject level = root.object(first);
        return level == null ? null : level.object(second);
    }

    private String nestedString(JsonObject root, String first, String key) {
        JsonObject level = root.object(first);
        return level == null ? null : level.string(key);
    }

    private JsonObject firstJson(JsonObject first, JsonObject second) {
        return first != null ? first : second;
    }

    private String firstString(String... candidates) {
        for (String candidate : candidates) {
            if (candidate != null) {
                return candidate;
            }
        }
        return null;
    }

    private Long firstOrNull(Long first, Long second) {
        return first != null ? first : second;
    }

    private long firstLong(Long first, Long second, String name) {
        Long value = firstOrNull(first, second);
        if (value == null || value <= 0) {
            throw new RowRejectedException(name + " is missing or not a positive time");
        }
        return value;
    }

    /** The later of the instant and the 2.x time (when there is one): the transitions of an operation never go back in time. */
    private Instant latest(Instant floor, Long millis) {
        if (millis == null) {
            return floor;
        }
        Instant candidate = Instant.ofEpochMilli(millis);
        return candidate.isAfter(floor) ? candidate : floor;
    }

    private Instant latest(Instant first, Instant second) {
        return second.isAfter(first) ? second : first;
    }
}
