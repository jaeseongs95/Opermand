package io.github.jaeseongs95.portal.server.usecase;

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

import io.github.jaeseongs95.portal.app.audit.AuditAction;
import io.github.jaeseongs95.portal.app.journal.OperationJournal;
import io.github.jaeseongs95.portal.app.operation.OperationIntake;
import io.github.jaeseongs95.portal.app.policy.CommandPolicyStore;
import io.github.jaeseongs95.portal.domain.command.CommandNames;
import io.github.jaeseongs95.portal.domain.command.CommandPolicyDocument;
import io.github.jaeseongs95.portal.domain.command.Fallback;
import io.github.jaeseongs95.portal.domain.command.LinuxPolicyName;
import io.github.jaeseongs95.portal.domain.command.PolicyName;
import io.github.jaeseongs95.portal.domain.command.SourceContract;
import io.github.jaeseongs95.portal.domain.command.TargetRule;
import io.github.jaeseongs95.portal.domain.command.Thresholds;
import io.github.jaeseongs95.portal.domain.error.DispatchFailure;
import io.github.jaeseongs95.portal.domain.error.DispatchReason;
import io.github.jaeseongs95.portal.domain.error.DispatchRefusedException;
import io.github.jaeseongs95.portal.domain.error.DispatchStage;
import io.github.jaeseongs95.portal.domain.error.OperationErrorCode;
import io.github.jaeseongs95.portal.domain.error.RequestRefusedException;
import io.github.jaeseongs95.portal.domain.operation.ApprovalMode;
import io.github.jaeseongs95.portal.domain.operation.DecisionKind;
import io.github.jaeseongs95.portal.domain.operation.DecisionReason;
import io.github.jaeseongs95.portal.domain.operation.DecisionRecord;
import io.github.jaeseongs95.portal.domain.operation.NewOperation;
import io.github.jaeseongs95.portal.domain.operation.Operation;
import io.github.jaeseongs95.portal.domain.operation.OperationEvent;
import io.github.jaeseongs95.portal.domain.operation.OperationState;
import io.github.jaeseongs95.portal.domain.target.TargetOs;
import io.github.jaeseongs95.portal.domain.target.Toggle;

/**
 * The rule judgement at intake with the real journal (defect D-0119, cases S-D0119-01 to -03, -05, -10 to -12): the first stored state is the judgement, a full queue
 * stores nothing, and the owner's button works on a pending operation only.
 */
@UseCaseTest
class RuleIntakeTest {

    private final OperationIntake intake;
    private final OperationJournal journal;
    private final CommandPolicyStore policies;
    private final ScriptedExecutor executor;
    private final RecordingAudit audit;
    private final FixedTargets targets;
    private final UseCaseKit kit;

    RuleIntakeTest(OperationIntake intake, OperationJournal journal, CommandPolicyStore policies, ScriptedExecutor executor, RecordingAudit audit, FixedTargets targets,
                   UseCaseKit kit) {
        this.intake = intake;
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
        TargetRule rule = new TargetRule(FixedTargets.ID, targets.current().revision(), FixedTargets.WRITE_PROFILE, TargetOs.LINUX, names(allowed), names(denied), Toggle.OFF,
            Toggle.OFF, Toggle.OFF, Toggle.OFF, Toggle.OFF, Toggle.OFF, SourceContract.NONE, Fallback.NONE, Thresholds.none());
        policies.replace(new CommandPolicyDocument(List.of(rule)), policies.current().revision());
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
        assertThat(submitted.decision()).isEqualTo(new DecisionRecord.Decided(DecisionKind.EXECUTE, DecisionReason.COMMAND_SET, io.github.jaeseongs95.portal.domain.operation.ReviewReason.NONE, revision));
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
            for (int number = 0; number < 32; number++) {
                held.add(intake.submit(kit.command("touch hold queued " + number)));
            }
            NewOperation overflow = kit.command("touch hold overflow");

            assertThatThrownBy(() -> intake.submit(overflow)).isInstanceOfSatisfying(RequestRefusedException.class,
                e -> assertThat(e.code()).isEqualTo(OperationErrorCode.OPERATION_QUEUE_FULL));
            assertThat(journal.findByRequestKey(overflow.requestKey())).as("nothing was stored, not even a failed row").isEmpty();

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
}
