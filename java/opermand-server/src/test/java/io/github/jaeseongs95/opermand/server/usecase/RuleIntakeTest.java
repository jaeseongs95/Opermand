package io.github.jaeseongs95.opermand.server.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.jaeseongs95.opermand.app.audit.AuditAction;
import io.github.jaeseongs95.opermand.app.journal.OperationJournal;
import io.github.jaeseongs95.opermand.app.operation.ReadCommandRequest;
import io.github.jaeseongs95.opermand.app.operation.ReadCommandService;
import io.github.jaeseongs95.opermand.app.operation.OperationIntake;
import io.github.jaeseongs95.opermand.app.policy.CommandPolicyStore;
import io.github.jaeseongs95.opermand.domain.command.CommandNames;
import io.github.jaeseongs95.opermand.domain.command.CommandPolicyDocument;
import io.github.jaeseongs95.opermand.domain.command.LinuxPolicyName;
import io.github.jaeseongs95.opermand.domain.command.PolicyName;
import io.github.jaeseongs95.opermand.domain.command.SourceContract;
import io.github.jaeseongs95.opermand.domain.command.TargetRule;
import io.github.jaeseongs95.opermand.domain.command.WindowsPolicyName;
import io.github.jaeseongs95.opermand.domain.error.DispatchFailure;
import io.github.jaeseongs95.opermand.domain.error.DispatchReason;
import io.github.jaeseongs95.opermand.domain.error.DispatchRefusedException;
import io.github.jaeseongs95.opermand.domain.error.DispatchStage;
import io.github.jaeseongs95.opermand.domain.error.OperationErrorCode;
import io.github.jaeseongs95.opermand.domain.error.RequestErrorCode;
import io.github.jaeseongs95.opermand.domain.error.RequestRefusedException;
import io.github.jaeseongs95.opermand.domain.id.OwnerId;
import io.github.jaeseongs95.opermand.domain.id.RequestKey;
import io.github.jaeseongs95.opermand.domain.operation.ApprovalMode;
import io.github.jaeseongs95.opermand.domain.operation.Deadline;
import io.github.jaeseongs95.opermand.domain.operation.DecisionKind;
import io.github.jaeseongs95.opermand.domain.operation.DecisionReason;
import io.github.jaeseongs95.opermand.domain.operation.DecisionRecord;
import io.github.jaeseongs95.opermand.domain.operation.NewOperation;
import io.github.jaeseongs95.opermand.domain.operation.Operation;
import io.github.jaeseongs95.opermand.domain.operation.OperationEvent;
import io.github.jaeseongs95.opermand.domain.operation.OperationState;
import io.github.jaeseongs95.opermand.domain.operation.RecordedResult;
import io.github.jaeseongs95.opermand.domain.operation.Settlement;
import io.github.jaeseongs95.opermand.domain.target.TargetOs;
import io.github.jaeseongs95.opermand.domain.target.Toggle;
import io.github.jaeseongs95.opermand.domain.text.CommandText;
import io.github.jaeseongs95.opermand.domain.text.Utf8Text;

/**
 * The rule judgement at intake with the real journal (defect D-0119, cases S-D0119-01 to -03, -05, -10 to -12): the first stored state is the judgement, a read-only
 * command runs by rule on the read profile only (and the deny list stops it there), a full queue stores nothing, and the owner's button works on a pending operation only.
 */
@UseCaseTest
class RuleIntakeTest {

    private final OperationIntake intake;
    private final ReadCommandService reads;
    private final io.github.jaeseongs95.opermand.domain.schedule.ConcurrencyLimits limits;
    private final OperationJournal journal;
    private final CommandPolicyStore policies;
    private final ScriptedExecutor executor;
    private final RecordingAudit audit;
    private final FixedTargets targets;
    private final UseCaseKit kit;

    RuleIntakeTest(OperationIntake intake, io.github.jaeseongs95.opermand.domain.schedule.ConcurrencyLimits limits, ReadCommandService reads, OperationJournal journal, CommandPolicyStore policies, ScriptedExecutor executor, RecordingAudit audit, FixedTargets targets,
                   UseCaseKit kit) {
        this.intake = intake;
        this.reads = reads;
        this.limits = limits;
        this.journal = journal;
        this.policies = policies;
        this.executor = executor;
        this.audit = audit;
        this.targets = targets;
        this.kit = kit;
    }

    @BeforeEach
    void start() {
        executor.reset();
        audit.failNothing();
        targets.reset();
        policies.replace(CommandPolicyDocument.empty(), policies.current().revision());
    }

    private void rule(List<String> allowed, List<String> denied) {
        policies.replace(new CommandPolicyDocument(List.of(linuxTargetRule(allowed, denied))), policies.current().revision());
    }

    private TargetRule linuxTargetRule(List<String> allowed, List<String> denied) {
        return new TargetRule(FixedTargets.ID, targets.current().connection(), FixedTargets.WRITE_PROFILE, TargetOs.LINUX, names(allowed), names(denied),
            Toggle.OFF, Toggle.OFF, Toggle.OFF, Toggle.OFF, SourceContract.NONE);
    }

    private TargetRule windowsTargetRule(List<String> allowed) {
        List<PolicyName> windowsNames = new ArrayList<>();
        for (String text : allowed) {
            windowsNames.add(new WindowsPolicyName(text));
        }
        return new TargetRule(FixedTargets.WINDOWS_ID, targets.windows().connection(), FixedTargets.WINDOWS_WRITE_PROFILE, TargetOs.WINDOWS,
            new CommandNames(windowsNames), CommandNames.none(), Toggle.OFF, Toggle.OFF, Toggle.OFF, Toggle.OFF, SourceContract.NONE);
    }

    private static CommandNames names(List<String> texts) {
        List<PolicyName> names = new ArrayList<>();
        for (String text : texts) {
            names.add(new LinuxPolicyName(text));
        }
        return new CommandNames(names);
    }

    @Test
    @DisplayName("S-D0119-01 S-D0119-10 a command on the allow list is stored once as queued, automatic, with its decision and one event, audited submitted then queued, and it runs without the owner")
    void anAllowedCommandIsQueuedAtIntake() throws Exception {
        rule(List.of("touch"), List.of());
        long revision = policies.current().revision();

        Operation submitted = intake.submit(kit.command("touch allowed"));

        assertThat(submitted.approvalMode()).isEqualTo(ApprovalMode.AUTOMATIC);
        assertThat(submitted.decision()).isEqualTo(new DecisionRecord.Decided(DecisionKind.EXECUTE, DecisionReason.COMMAND_SET, io.github.jaeseongs95.opermand.domain.operation.ReviewReason.NONE, revision));
        assertThat(journal.events(submitted.id()).get(0)).as("the first event is the judgement, version 1").extracting(OperationEvent::version, OperationEvent::state)
            .containsExactly(1L, OperationState.QUEUED);
        assertThat(submitted.state()).isIn(OperationState.QUEUED, OperationState.STARTING, OperationState.RUNNING, OperationState.COMPLETED);
        assertThat(journal.events(submitted.id()).stream().map(OperationEvent::state)).as("neither accepted nor pending came first").startsWith(OperationState.QUEUED);
        assertThat(kit.awaitTerminal(submitted.id()).state()).isEqualTo(OperationState.COMPLETED);
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(audit.actionsOf(submitted.id()))
            .containsExactly(AuditAction.SUBMITTED, AuditAction.EXECUTION_QUEUED, AuditAction.POLICY_EXECUTION_STARTED, AuditAction.COMPLETED));
        assertThat(executor.sentLines()).containsExactly("touch allowed");
    }

    @Test
    @DisplayName("S-D0119-02 a denied command is stored as denied, never runs, is audited submitted then denied, and the same key gives the same denied operation")
    void aDeniedCommandIsRefusedAtIntake() {
        rule(List.of(), List.of("rm"));
        NewOperation spec = kit.command("sudo /bin/rm /tmp/x");

        Operation denied = intake.submit(spec);

        assertThat(denied.state()).isEqualTo(OperationState.DENIED);
        assertThat(denied.decision()).isInstanceOfSatisfying(DecisionRecord.Decided.class, decided -> {
            assertThat(decided.kind()).isEqualTo(DecisionKind.DENY);
            assertThat(decided.reason()).isEqualTo(DecisionReason.POLICY_DENIED);
        });
        assertThat(journal.events(denied.id()).stream().map(OperationEvent::state)).containsExactly(OperationState.DENIED);
        assertThat(intake.submit(spec).id()).isEqualTo(denied.id());
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(audit.actionsOf(denied.id())).containsExactly(AuditAction.SUBMITTED, AuditAction.DENIED));
        assertThat(executor.requestsSent()).isZero();
    }

    @Test
    @DisplayName("S-D0119-03 a command no rule covers, and a target without a rule, is stored as pending for the owner and audited submitted only")
    void aCommandNoRuleCoversWaitsForTheOwner() {
        Operation withoutRule = intake.submit(kit.command("touch no-rule"));
        rule(List.of("ls"), List.of());
        Operation unlisted = intake.submit(kit.command("touch not-listed"));

        for (Operation pending : List.of(withoutRule, unlisted)) {
            assertThat(pending.state()).isEqualTo(OperationState.PENDING);
            assertThat(pending.approvalMode()).isEqualTo(ApprovalMode.NOT_DECIDED);
            assertThat(pending.decision()).isInstanceOfSatisfying(DecisionRecord.Decided.class, decided -> assertThat(decided.reason()).isEqualTo(DecisionReason.NO_MATCHING_RULE));
            assertThat(audit.actionsOf(pending.id())).containsExactly(AuditAction.SUBMITTED);
            intake.deny(pending.id());
        }
        assertThat(executor.requestsSent()).isZero();
    }

    @Test
    @DisplayName("S-D0119-12 the owner's button works on a pending operation only: a queued operation is refused at REQUEST_STATE for approve and for deny, and stays as it is")
    void theButtonIsForPendingOperationsOnly() throws Exception {
        rule(List.of("touch"), List.of());
        Operation queued = intake.submit(kit.command("touch automatic"));
        assertThatThrownBy(() -> intake.approve(queued.id())).isInstanceOfSatisfying(DispatchRefusedException.class,
            e -> assertThat(e.failure()).isEqualTo(DispatchFailure.refused(DispatchReason.REQUEST_STATE, DispatchStage.REQUEST_STATE)));
        assertThatThrownBy(() -> intake.deny(queued.id())).isInstanceOfSatisfying(DispatchRefusedException.class,
            e -> assertThat(e.failure()).isEqualTo(DispatchFailure.refused(DispatchReason.REQUEST_STATE, DispatchStage.REQUEST_STATE)));
        assertThat(kit.awaitTerminal(queued.id()).state()).isEqualTo(OperationState.COMPLETED);
        assertThat(executor.requestsSent()).isEqualTo(1);

        rule(List.of("ls"), List.of());
        Operation pending = intake.submit(kit.command("touch owner"));
        Operation approved = intake.approve(pending.id());
        assertThat(approved.approvalMode()).isEqualTo(ApprovalMode.OWNER);
        assertThat(kit.awaitTerminal(pending.id()).state()).isEqualTo(OperationState.COMPLETED);
    }

    @Test
    @DisplayName("S-D0119-11 with the queue full an execute judgement stores nothing and fails with OPERATION_QUEUE_FULL; the same key is judged again once there is room")
    void aFullQueueStoresNothing() throws Exception {
        rule(List.of("touch"), List.of());
        CountDownLatch release = new CountDownLatch(1);
        executor.when("hold", executor.blocksUntil(release));
        List<Operation> held = new ArrayList<>();
        try {
            for (int number = 0; number < 2; number++) {
                held.add(intake.submit(kit.command("touch hold running " + number)));
            }
            for (Operation running : held) {
                kit.await(running.id(), operation -> operation.state() == OperationState.RUNNING);
            }
            NewOperation firstQueued = kit.command("touch hold queued 0");
            held.add(intake.submit(firstQueued));
            for (int number = 1; number < 32; number++) {
                held.add(intake.submit(kit.command("touch hold queued " + number)));
            }
            NewOperation overflow = kit.command("touch hold overflow");

            assertThatThrownBy(() -> intake.submit(overflow)).isInstanceOfSatisfying(RequestRefusedException.class,
                e -> assertThat(e.code()).isEqualTo(OperationErrorCode.OPERATION_QUEUE_FULL));
            assertThat(journal.findByRequestKey(overflow.requestKey())).as("nothing was stored, not even a failed row").isEmpty();
            assertThat(intake.submit(firstQueued).id()).as("S-D0119-11 D4 the same key and contract is answered before the room in the queue is looked at").isEqualTo(held.get(2).id());

            release.countDown();
            for (Operation operation : held) {
                kit.awaitTerminal(operation.id());
            }
            Operation again = intake.submit(overflow);
            assertThat(again.state()).isIn(OperationState.QUEUED, OperationState.STARTING, OperationState.RUNNING, OperationState.COMPLETED);
            assertThat(again.approvalMode()).isEqualTo(ApprovalMode.AUTOMATIC);
            kit.awaitTerminal(again.id());
        } finally {
            release.countDown();
        }
    }

    @Test
    @DisplayName("S-D0119-05 a read-only command runs by rule on the read profile only; on the write profile it waits for the owner like any other command")
    void aReadOnlyCommandRunsByRuleOnTheReadProfileOnly() throws Exception {
        Operation read = intake.submit(kit.command(kit.newKey(), "cat /tmp/a", FixedTargets.READ_PROFILE, new Deadline.None()));
        assertThat(read.approvalMode()).isEqualTo(ApprovalMode.AUTOMATIC);
        assertThat(read.decision()).isInstanceOfSatisfying(DecisionRecord.Decided.class, decided -> assertThat(decided.reason()).isEqualTo(DecisionReason.READ_ONLY));
        assertThat(kit.awaitTerminal(read.id()).state()).isEqualTo(OperationState.COMPLETED);

        Operation write = intake.submit(kit.command("cat /tmp/b"));
        assertThat(write.state()).isEqualTo(OperationState.PENDING);
        assertThat(write.decision()).isInstanceOfSatisfying(DecisionRecord.Decided.class, decided -> assertThat(decided.reason()).isEqualTo(DecisionReason.NO_MATCHING_RULE));
        intake.deny(write.id());
        assertThat(executor.sentLines()).containsExactly("cat /tmp/a");
    }

    @Test
    @DisplayName("S-D0119-05 the deny list stops a read-only command on the read tool: READ_COMMAND_NOT_STARTED, the operation is stored as denied and nothing runs (the rule is found by the write profile)")
    void theDenyListStopsTheReadTool() {
        rule(List.of(), List.of("cat"));
        RequestKey key = kit.newKey();
        ReadCommandRequest request = new ReadCommandRequest(key, new OwnerId("owner"), FixedTargets.ID, new CommandText("cat /tmp/a"), new Utf8Text(""), new Utf8Text(""),
            Duration.ofSeconds(5));

        assertThatThrownBy(() -> reads.run(request)).isInstanceOfSatisfying(RequestRefusedException.class,
            e -> assertThat(e.code()).isEqualTo(RequestErrorCode.READ_COMMAND_NOT_STARTED));
        assertThat(journal.findByRequestKey(key).orElseThrow().state()).isEqualTo(OperationState.DENIED);
        assertThat(executor.requestsSent()).isZero();
    }

    @Test
    @DisplayName("S-D0119-16 the audit of a new operation is durable before its row exists: submitted and then execution-queued or denied are written while the journal does not yet hold it")
    void theAuditComesBeforeTheRow() throws Exception {
        rule(List.of("touch"), List.of("rm"));
        java.util.Map<AuditAction, Boolean> rowThere = new java.util.concurrent.ConcurrentHashMap<>();
        audit.whenWritten(event -> {
            if (event.action() == AuditAction.SUBMITTED || event.action() == AuditAction.EXECUTION_QUEUED || event.action() == AuditAction.DENIED) {
                rowThere.put(event.action(), journal.find(event.operationId()).isPresent());
            }
        });

        Operation queued = intake.submit(kit.command("touch audited-first"));
        assertThat(rowThere).containsEntry(AuditAction.SUBMITTED, false).containsEntry(AuditAction.EXECUTION_QUEUED, false);
        kit.awaitTerminal(queued.id());

        rowThere.clear();
        Operation denied = intake.submit(kit.command("rm audited-first"));
        assertThat(denied.state()).isEqualTo(OperationState.DENIED);
        assertThat(rowThere).containsEntry(AuditAction.SUBMITTED, false).containsEntry(AuditAction.DENIED, false);

        rowThere.clear();
        Operation pending = intake.submit(kit.command("uname audited-first"));
        assertThat(pending.state()).isEqualTo(OperationState.PENDING);
        assertThat(rowThere).containsEntry(AuditAction.SUBMITTED, false);
        intake.deny(pending.id());
    }

    @Test
    @DisplayName("S-D0119-16 an audit that does not become durable stores nothing and refuses at DECISION_AUDIT, on every path: queued, denied and pending")
    void anAuditThatFailsStoresNothing() {
        rule(List.of("touch"), List.of("rm"));
        record Case(AuditAction failing, String command) {
        }
        for (Case attempt : List.of(new Case(AuditAction.SUBMITTED, "touch no-audit"), new Case(AuditAction.EXECUTION_QUEUED, "touch no-queued-audit"),
            new Case(AuditAction.DENIED, "rm no-denied-audit"), new Case(AuditAction.SUBMITTED, "uname no-review-audit"))) {
            audit.failNothing();
            audit.failOn(attempt.failing());
            NewOperation spec = kit.command(attempt.command());

            assertThatThrownBy(() -> intake.submit(spec)).as(attempt.command()).isInstanceOfSatisfying(DispatchRefusedException.class, e -> {
                assertThat(e.failure()).isEqualTo(DispatchFailure.stepFailed(DispatchStage.DECISION_AUDIT, DispatchReason.AUDIT_FAILED));
                assertThat(e.getMessage()).doesNotContain("SECRET-AUDIT-DETAIL");
            });
            assertThat(journal.findByRequestKey(spec.requestKey())).as(attempt.command()).isEmpty();
        }
        assertThat(executor.requestsSent()).isZero();
    }

    @Test
    @DisplayName("S-D0119-16 an audit that was written for a row that could not be stored gets one more line that ends it: the room in the queue was gone at the moment of the insert")
    void aRowThatIsNotStoredAfterItsAuditEndsItsLines() throws Exception {
        rule(List.of("touch"), List.of());
        CountDownLatch release = new CountDownLatch(1);
        executor.when("hold", executor.blocksUntil(release));
        List<Operation> held = new ArrayList<>();
        try {
            for (int number = 0; number < 2; number++) {
                held.add(intake.submit(kit.command("touch hold running " + number)));
            }
            for (Operation running : held) {
                kit.await(running.id(), operation -> operation.state() == OperationState.RUNNING);
            }
            for (int number = 0; number < 30; number++) {
                held.add(intake.submit(kit.command("touch hold queued " + number)));
            }
            NewOperation late = kit.command("touch hold late");
            java.util.concurrent.atomic.AtomicReference<io.github.jaeseongs95.opermand.domain.id.OperationId> orphan = new java.util.concurrent.atomic.AtomicReference<>();
            java.util.concurrent.atomic.AtomicBoolean filled = new java.util.concurrent.atomic.AtomicBoolean();
            // The room was there when the intake looked (30 of 32); while its audit is written, two other operations take it.
            audit.whenWritten(event -> {
                if (event.action() == AuditAction.EXECUTION_QUEUED && filled.compareAndSet(false, true)) {
                    orphan.set(event.operationId());
                    for (int number = 0; number < 2; number++) {
                        held.add(intake.submit(kit.command("touch hold filler " + number)));
                    }
                }
            });

            assertThatThrownBy(() -> intake.submit(late)).isInstanceOfSatisfying(RequestRefusedException.class,
                e -> assertThat(e.code()).isEqualTo(OperationErrorCode.OPERATION_QUEUE_FULL));

            assertThat(journal.findByRequestKey(late.requestKey())).isEmpty();
            assertThat(audit.actionsOf(orphan.get())).as("the lines of the operation that was never stored have an end")
                .containsExactly(AuditAction.SUBMITTED, AuditAction.EXECUTION_QUEUED, AuditAction.NOT_STORED);
        } finally {
            release.countDown();
            audit.failNothing();
        }
        for (Operation operation : held) {
            kit.awaitTerminal(operation.id());
        }
    }

    @Test
    @DisplayName("S-D0119-17 a second audit line that fails is ended by a not-stored line and nothing is stored; when that line fails too, its failure is added to the refusal")
    void aFailedSecondLineIsEndedByANotStoredLine() {
        rule(List.of("touch"), List.of("rm"));
        assertThat(AuditAction.NOT_STORED.wire()).isEqualTo("not-stored");
        java.util.concurrent.atomic.AtomicReference<io.github.jaeseongs95.opermand.domain.id.OperationId> id = new java.util.concurrent.atomic.AtomicReference<>();

        for (String command : List.of("touch ends-queued", "rm ends-denied")) {
            audit.failNothing();
            audit.failOn(AuditAction.EXECUTION_QUEUED);
            audit.failOn(AuditAction.DENIED);
            audit.whenWritten(event -> {
                if (event.action() == AuditAction.SUBMITTED) {
                    id.set(event.operationId());
                }
            });
            NewOperation spec = kit.command(command);

            assertThatThrownBy(() -> intake.submit(spec)).as(command).isInstanceOfSatisfying(DispatchRefusedException.class, e -> {
                assertThat(e.failure()).isEqualTo(DispatchFailure.stepFailed(DispatchStage.DECISION_AUDIT, DispatchReason.AUDIT_FAILED));
                assertThat(e.getSuppressed()).as("the ending line became durable, so nothing is added").isEmpty();
            });
            assertThat(journal.findByRequestKey(spec.requestKey())).as(command).isEmpty();
            assertThat(audit.actionsOf(id.get())).as(command).containsExactly(AuditAction.SUBMITTED, AuditAction.NOT_STORED);
        }

        audit.failNothing();
        audit.failOn(AuditAction.EXECUTION_QUEUED);
        audit.failOn(AuditAction.NOT_STORED);
        audit.whenWritten(event -> {
            if (event.action() == AuditAction.SUBMITTED) {
                id.set(event.operationId());
            }
        });
        NewOperation spec = kit.command("touch ending-fails-too");

        assertThatThrownBy(() -> intake.submit(spec)).isInstanceOfSatisfying(DispatchRefusedException.class, e -> {
            assertThat(e.failure()).isEqualTo(DispatchFailure.stepFailed(DispatchStage.DECISION_AUDIT, DispatchReason.AUDIT_FAILED));
            assertThat(e.getSuppressed()).hasSize(1).allSatisfy(suppressed -> assertThat(suppressed).isInstanceOf(DispatchRefusedException.class));
        });
        assertThat(journal.findByRequestKey(spec.requestKey())).isEmpty();
        assertThat(audit.actionsOf(id.get())).containsExactly(AuditAction.SUBMITTED);
        assertThat(executor.requestsSent()).isZero();
    }

    @Test
    @DisplayName("S-D0119-22 an approval whose queue room is gone at the write is refused with OPERATION_QUEUE_FULL, the operation stays pending and the lines end with not-stored: the check of the write is really connected")
    void anApprovalThatLosesTheRoomAtTheWriteStaysPending() throws Exception {
        rule(List.of("touch"), List.of());
        Operation waiting = intake.submit(kit.command("uname waits-for-the-owner"));
        assertThat(waiting.state()).isEqualTo(OperationState.PENDING);
        CountDownLatch release = new CountDownLatch(1);
        executor.when("hold", executor.blocksUntil(release));
        List<Operation> held = new ArrayList<>();
        try {
            // The limits are read from the configuration of the test, not assumed: the running places are taken, the queue is left one place short.
            for (int number = 0; number < limits.globalConcurrency().value(); number++) {
                held.add(intake.submit(kit.command("touch hold running " + number)));
            }
            for (Operation running : held) {
                kit.await(running.id(), operation -> operation.state() == OperationState.RUNNING);
            }
            for (int number = 0; number < limits.queueCapacity().value() - 1; number++) {
                held.add(intake.submit(kit.command("touch hold queued " + number)));
            }
            java.util.concurrent.atomic.AtomicBoolean filled = new java.util.concurrent.atomic.AtomicBoolean();
            // The first check of the approval finds room (one place left). While its audit line is written, another intake takes the last place: only the check inside the write sees it.
            audit.whenWritten(event -> {
                if (event.action() == AuditAction.EXECUTION_QUEUED && event.operationId().equals(waiting.id()) && filled.compareAndSet(false, true)) {
                    held.add(intake.submit(kit.command("touch hold filler")));
                }
            });

            assertThatThrownBy(() -> intake.approve(waiting.id())).isInstanceOfSatisfying(RequestRefusedException.class,
                e -> assertThat(e.code()).isEqualTo(OperationErrorCode.OPERATION_QUEUE_FULL));

            assertThat(kit.find(waiting.id()).state()).isEqualTo(OperationState.PENDING);
            assertThat(audit.actionsOf(waiting.id())).containsExactly(AuditAction.SUBMITTED, AuditAction.EXECUTION_QUEUED, AuditAction.NOT_STORED);
        } finally {
            release.countDown();
            audit.failNothing();
        }
        for (Operation operation : held) {
            kit.awaitTerminal(operation.id());
        }
        intake.deny(waiting.id());
    }

    @Test
    @DisplayName("S-D0121-01 S-D0121-02 D-0121 editing another target's policy or adding another allowed command keeps queued automatic operations running, while revoking the command refuses with POLICY_CHANGED")
    void unrelatedPolicyEditsKeepQueuedAutomaticOperationsRunningWhileRevocationRefuses() throws Exception {
        rule(List.of("touch"), List.of());
        CountDownLatch firstHold = new CountDownLatch(1);
        executor.when("hold-one", executor.blocksUntil(firstHold));
        List<Operation> blockers = new ArrayList<>();
        try {
            for (int number = 0; number < limits.globalConcurrency().value(); number++) {
                blockers.add(intake.submit(kit.command("touch hold-one " + number)));
            }
            for (Operation running : blockers) {
                kit.await(running.id(), op -> op.state() == OperationState.RUNNING);
            }
            Operation survivesOtherTarget = intake.submit(kit.command("touch survives-other-target"));
            assertThat(survivesOtherTarget.state()).isEqualTo(OperationState.QUEUED);

            // Editing win-1's rule and adding an allowed name on web-1 both advance the global policy revision without revoking 'touch'.
            policies.replace(new CommandPolicyDocument(List.of(linuxTargetRule(List.of("touch"), List.of()), windowsTargetRule(List.of("powershell.exe")))),
                policies.current().revision());
            policies.replace(new CommandPolicyDocument(List.of(linuxTargetRule(List.of("touch", "ls"), List.of()), windowsTargetRule(List.of("powershell.exe")))),
                policies.current().revision());

            firstHold.countDown();
            for (Operation running : blockers) {
                kit.awaitTerminal(running.id());
            }
            Operation completed = kit.awaitTerminal(survivesOtherTarget.id());
            assertThat(completed.state()).isEqualTo(OperationState.COMPLETED);
            assertThat(executor.sentLines()).contains("touch survives-other-target");
        } finally {
            firstHold.countDown();
        }

        blockers.clear();
        CountDownLatch secondHold = new CountDownLatch(1);
        executor.when("hold-two", executor.blocksUntil(secondHold));
        try {
            for (int number = 0; number < limits.globalConcurrency().value(); number++) {
                blockers.add(intake.submit(kit.command("touch hold-two " + number)));
            }
            for (Operation running : blockers) {
                kit.await(running.id(), op -> op.state() == OperationState.RUNNING);
            }
            Operation revoked = intake.submit(kit.command("touch revoked-while-queued"));
            assertThat(revoked.state()).isEqualTo(OperationState.QUEUED);
            rule(List.of("ls"), List.of());

            secondHold.countDown();
            for (Operation running : blockers) {
                kit.awaitTerminal(running.id());
            }
            Operation refused = kit.awaitTerminal(revoked.id());
            assertThat(refused.state()).isEqualTo(OperationState.FAILED);
            assertThat(refused.settlement()).isInstanceOfSatisfying(Settlement.Settled.class, settled -> assertThat(settled.result())
                .isEqualTo(new RecordedResult.Refused(DispatchFailure.refused(DispatchReason.POLICY_CHANGED, DispatchStage.POLICY_GUARD))));
            assertThat(executor.sentLines()).doesNotContain("touch revoked-while-queued");
        } finally {
            secondHold.countDown();
        }
    }

    @Test
    @DisplayName("S-D0121-03 RC-A-433 D-0121 a newly denied command is refused with POLICY_DENIED on owner approve and before dispatch across owner and read-only paths")
    void newlyDeniedCommandsAreRefusedOnApproveAndBeforeDispatch() throws Exception {
        Operation pendingOwner = intake.submit(kit.command("sudo id"));
        assertThat(pendingOwner.state()).isEqualTo(OperationState.PENDING);
        rule(List.of("touch"), List.of("sudo"));

        assertThatThrownBy(() -> intake.approve(pendingOwner.id())).isInstanceOfSatisfying(DispatchRefusedException.class,
            e -> assertThat(e.failure()).isEqualTo(DispatchFailure.refused(DispatchReason.POLICY_DENIED, DispatchStage.POLICY_GUARD)));
        assertThat(kit.find(pendingOwner.id()).state()).isEqualTo(OperationState.PENDING);
        intake.deny(pendingOwner.id());

        rule(List.of("touch"), List.of());
        CountDownLatch hold = new CountDownLatch(1);
        executor.when("hold-deny", executor.blocksUntil(hold));
        List<Operation> blockers = new ArrayList<>();
        try {
            for (int number = 0; number < limits.globalConcurrency().value(); number++) {
                blockers.add(intake.submit(kit.command("touch hold-deny " + number)));
            }
            for (Operation running : blockers) {
                kit.await(running.id(), op -> op.state() == OperationState.RUNNING);
            }
            Operation queuedOwner = intake.submit(kit.command("uname -a"));
            intake.approve(queuedOwner.id());
            Operation queuedReadOnly = intake.submit(kit.command(kit.newKey(), "cat /tmp/secret", FixedTargets.READ_PROFILE, new Deadline.None()));
            assertThat(queuedReadOnly.state()).isEqualTo(OperationState.QUEUED);

            rule(List.of("touch"), List.of("uname", "cat"));
            hold.countDown();
            for (Operation running : blockers) {
                kit.awaitTerminal(running.id());
            }
            for (Operation refused : List.of(kit.awaitTerminal(queuedOwner.id()), kit.awaitTerminal(queuedReadOnly.id()))) {
                assertThat(refused.state()).isEqualTo(OperationState.FAILED);
                assertThat(refused.settlement()).isInstanceOfSatisfying(Settlement.Settled.class, settled -> assertThat(settled.result())
                    .isEqualTo(new RecordedResult.Refused(DispatchFailure.refused(DispatchReason.POLICY_DENIED, DispatchStage.POLICY_GUARD))));
            }
            assertThat(executor.sentLines()).doesNotContain("uname -a", "cat /tmp/secret");
        } finally {
            hold.countDown();
        }
    }

    @Test
    @DisplayName("S-D0121-04 S-D0121-05 D-0121 renaming a target keeps its allow and deny lists active, while changing the SSH host invalidates the old rule")
    void nonConnectionTargetChangePreservesRuleWhileHostChangeInvalidatesRule() throws Exception {
        rule(List.of("touch"), List.of("rm"));
        var originalRevision = targets.current().revision();

        targets.change("Web one renamed");
        assertThat(targets.current().revision()).isNotEqualTo(originalRevision);

        Operation allowedAfterRename = intake.submit(kit.command("touch after-rename"));
        assertThat(allowedAfterRename.approvalMode()).isEqualTo(ApprovalMode.AUTOMATIC);
        assertThat(kit.awaitTerminal(allowedAfterRename.id()).state()).isEqualTo(OperationState.COMPLETED);

        Operation deniedAfterRename = intake.submit(kit.command("rm /tmp/after-rename"));
        assertThat(deniedAfterRename.state()).isEqualTo(OperationState.DENIED);

        targets.changeHost("other-host.example");
        Operation afterHostChange = intake.submit(kit.command("touch after-host-change"));
        assertThat(afterHostChange.state()).isEqualTo(OperationState.PENDING);
        assertThat(afterHostChange.decision()).isInstanceOfSatisfying(DecisionRecord.Decided.class,
            decided -> assertThat(decided.reason()).isEqualTo(DecisionReason.NO_MATCHING_RULE));
        intake.deny(afterHostChange.id());
        assertThat(executor.sentLines()).containsExactly("touch after-rename");
    }
}
