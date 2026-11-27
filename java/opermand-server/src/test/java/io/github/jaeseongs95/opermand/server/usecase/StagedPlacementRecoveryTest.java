package io.github.jaeseongs95.opermand.server.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;

import io.github.jaeseongs95.opermand.app.artifact.ArtifactImporter;
import io.github.jaeseongs95.opermand.app.artifact.ArtifactPayloadStore;
import io.github.jaeseongs95.opermand.app.artifact.ArtifactPinning;
import io.github.jaeseongs95.opermand.app.artifact.ArtifactRecord;
import io.github.jaeseongs95.opermand.app.artifact.ArtifactRegistry;
import io.github.jaeseongs95.opermand.app.artifact.ArtifactRetention;
import io.github.jaeseongs95.opermand.app.artifact.ArtifactSubmission;
import io.github.jaeseongs95.opermand.app.artifact.StagedAction;
import io.github.jaeseongs95.opermand.app.artifact.StagedActionKind;
import io.github.jaeseongs95.opermand.app.artifact.StagedActionState;
import io.github.jaeseongs95.opermand.app.artifact.StagingTimes;
import io.github.jaeseongs95.opermand.app.file.OperationUploadAdmission;
import io.github.jaeseongs95.opermand.app.file.StagedActionSettlement;
import io.github.jaeseongs95.opermand.app.file.StagedBegin;
import io.github.jaeseongs95.opermand.app.file.StagedFileService;
import io.github.jaeseongs95.opermand.app.file.StagedPlacement;
import io.github.jaeseongs95.opermand.app.file.StagedPlacementRequest;
import io.github.jaeseongs95.opermand.app.file.UploadAdmission;
import io.github.jaeseongs95.opermand.app.file.UploadArtifactRelease;
import io.github.jaeseongs95.opermand.app.journal.OperationJournal;
import io.github.jaeseongs95.opermand.app.maintenance.MaintenanceStore;
import io.github.jaeseongs95.opermand.app.operation.OperationIntake;
import io.github.jaeseongs95.opermand.app.operation.OwnerReconciliation;
import io.github.jaeseongs95.opermand.app.operation.ReconcileOutcome;
import io.github.jaeseongs95.opermand.app.recovery.SettlementRecovery;
import io.github.jaeseongs95.opermand.domain.error.ArtifactErrorCode;
import io.github.jaeseongs95.opermand.domain.error.RequestRefusedException;
import io.github.jaeseongs95.opermand.domain.file.FileName;
import io.github.jaeseongs95.opermand.domain.id.ArtifactUuid;
import io.github.jaeseongs95.opermand.domain.id.ArtifactUuidGenerator;
import io.github.jaeseongs95.opermand.domain.id.Digester;
import io.github.jaeseongs95.opermand.domain.id.OperationId;
import io.github.jaeseongs95.opermand.domain.id.OperationIdGenerator;
import io.github.jaeseongs95.opermand.domain.id.OwnerId;
import io.github.jaeseongs95.opermand.domain.id.RequestKey;
import io.github.jaeseongs95.opermand.domain.operation.Elevation;
import io.github.jaeseongs95.opermand.domain.operation.NewOperation;
import io.github.jaeseongs95.opermand.domain.operation.Operation;
import io.github.jaeseongs95.opermand.domain.operation.OperationState;
import io.github.jaeseongs95.opermand.domain.operation.OverwriteMode;
import io.github.jaeseongs95.opermand.domain.operation.ReconciliationEvidence;
import io.github.jaeseongs95.opermand.domain.path.PathSyntax;

/**
 * What a copy or a move of a staged file leaves behind when it is cut off, and how the same key and the next start settle it (defects D-0155, D-0156 and D-0157; cases
 * RC-B-133 and RC-B-134 in the form this product has them: the journal is the queue). The real journal, artifact registry, payload stores, intake and dispatcher run; the SFTP
 * boundary is a plain test class. A cut-off is made by wrapping the admission (it admits and then fails, or fails before it admits) and by a journal that cannot be asked for
 * the key for a while, which is what a stop between two steps looks like to the use case.
 */
@UseCaseTest
class StagedPlacementRecoveryTest {

    private static final OwnerId OWNER = new OwnerId("recovery-owner");

    /**
     * The listeners of terminal operations that the product has and the slice leaves out: they give the copy of an upload back when its operation ended and settle the action of
     * a staged file. They make this class a context of its own, in which an operation that ends is told to them like in the product.
     */
    @TestConfiguration(proxyBeanMethods = false)
    @Import({UploadArtifactRelease.class, StagedActionSettlement.class, ArtifactPinning.class})
    static class WithSettlementListeners {
    }

    private final StagedFileService staged;
    private final OperationUploadAdmission realAdmission;
    private final OperationIntake intake;
    private final OperationJournal journal;
    private final ArtifactRegistry registry;
    private final ArtifactPayloadStore payloads;
    private final ArtifactImporter importer;
    private final MaintenanceStore maintenance;
    private final StagingTimes times;
    private final ArtifactUuidGenerator uuids;
    private final OperationIdGenerator operationIds;
    private final OwnerReconciliation reconciliation;
    private final SettlementRecovery settlement;
    private final ScriptedTransfer transfer;
    private final RecordingAudit audit;
    private final FixedTargets targets;
    private final UseCaseKit kit;
    private final Digester digester;
    private final Clock clock;

    StagedPlacementRecoveryTest(StagedFileService staged, OperationUploadAdmission realAdmission, OperationIntake intake, OperationJournal journal, ArtifactRegistry registry,
                                ArtifactPayloadStore payloads, ArtifactImporter importer, MaintenanceStore maintenance, StagingTimes times, ArtifactUuidGenerator uuids,
                                OperationIdGenerator operationIds, OwnerReconciliation reconciliation, SettlementRecovery settlement, ScriptedTransfer transfer,
                                RecordingAudit audit, FixedTargets targets, UseCaseKit kit, Digester digester, Clock clock) {
        this.staged = staged;
        this.realAdmission = realAdmission;
        this.intake = intake;
        this.journal = journal;
        this.registry = registry;
        this.payloads = payloads;
        this.importer = importer;
        this.maintenance = maintenance;
        this.times = times;
        this.uuids = uuids;
        this.operationIds = operationIds;
        this.reconciliation = reconciliation;
        this.settlement = settlement;
        this.transfer = transfer;
        this.audit = audit;
        this.targets = targets;
        this.kit = kit;
        this.digester = digester;
        this.clock = clock;
    }

    @BeforeEach
    void start() {
        transfer.reset();
        audit.failNothing();
        targets.reset();
    }

    // ---- RC-B-134: an action whose outcome is not known stops a move ----

    @Test
    @DisplayName("S-D0156-01 D-0156 RC-B-134: after a copy whose operation is UNKNOWN a move with another key is OPAQUE_ACTION_RECONCILIATION_REQUIRED, remove is busy, the same copy answers the same operation; once the owner reconciled, the move is accepted")
    void anUnknownCopyStopsANewMoveUntilTheOwnerReconciles() throws Exception {
        transfer.onUpload(transfer.losesTheOriginal());
        ArtifactUuid uuid = stage(bytes(300, 1));
        OperationId copy = placed(staged.place(request(uuid, StagedActionKind.COPY, "copy-doubt-key-0000001", "/srv/doubt.bin")));
        intake.approve(copy);
        assertThat(kit.awaitTerminal(copy).state()).isEqualTo(OperationState.UNKNOWN);
        int sent = transfer.uploadsSent();

        assertThatThrownBy(() -> staged.place(request(uuid, StagedActionKind.MOVE, "move-after-doubt-key-01", "/srv/doubt.bin"))).isInstanceOfSatisfying(
            RequestRefusedException.class, e -> assertThat(e.code()).isEqualTo(ArtifactErrorCode.OPAQUE_ACTION_RECONCILIATION_REQUIRED));
        assertThatThrownBy(() -> staged.remove(OWNER, uuid)).isInstanceOfSatisfying(RequestRefusedException.class,
            e -> assertThat(e.code()).isEqualTo(ArtifactErrorCode.ARTIFACT_BUSY));
        assertThat(placed(staged.place(request(uuid, StagedActionKind.COPY, "copy-doubt-key-0000001", "/srv/doubt.bin")))).as("the same copy is the same operation").isEqualTo(copy);
        assertThat(transfer.uploadsSent()).as("nothing was sent again").isEqualTo(sent);

        assertThat(reconciliation.reconcile(copy, new ReconciliationEvidence.NeverRan())).isInstanceOf(ReconcileOutcome.Reconciled.class);
        OperationId move = placed(staged.place(request(uuid, StagedActionKind.MOVE, "move-after-doubt-key-01", "/srv/doubt.bin")));

        assertThat(move).isNotEqualTo(copy);
        intake.deny(move);
    }

    @Test
    @DisplayName("S-D0156-02 D-0156 RC-B-134: a submitted copy that is not in the journal stops a move as well; a copy that is only pending does not (each action has a copy of its own)")
    void aMissingCopyStopsAMoveAndAPendingCopyDoesNot() {
        ArtifactUuid uuid = stage(bytes(200, 2));
        OperationId pending = placed(staged.place(request(uuid, StagedActionKind.COPY, "copy-pending-key-00001", "/srv/pending.bin")));

        OperationId independentMove = placed(staged.place(request(uuid, StagedActionKind.MOVE, "move-beside-copy-key-01", "/srv/other.bin")));
        assertThat(independentMove).as("the pending copy does not stop it").isNotEqualTo(pending);
        intake.deny(independentMove);
        intake.deny(pending);

        ArtifactUuid other = stage(bytes(200, 3));
        ArtifactRecord source = staged.stat(OWNER, other).artifact();
        registry.addAction(new StagedAction(source.id(), "copy-lost-key-0000001", new RequestKey("opaque:lost-copy-key-000001"), digester.digest(bytes(1, 1)), operationIds.next(),
            StagedActionKind.COPY, StagedActionState.SUBMITTED, targets.current().target().id(), targets.current().revision(), PathSyntax.POSIX.path("/srv/lost.bin"),
            OverwriteMode.REPLACE, Elevation.NONE));

        assertThatThrownBy(() -> staged.place(request(other, StagedActionKind.MOVE, "move-after-lost-key-001", "/srv/lost.bin"))).isInstanceOfSatisfying(
            RequestRefusedException.class, e -> assertThat(e.code()).isEqualTo(ArtifactErrorCode.OPAQUE_ACTION_RECONCILIATION_REQUIRED));
    }

    // ---- D-0155: the order, and a key that is sent again ----

    @Test
    @DisplayName("S-D0155-01 D-0155: a call cut off after the admission leaves the action reserved; the same key again is the same operation, the action is submitted and the copy is pinned by it, and no second operation exists")
    void theSameKeyAfterACutOffCallConvergesOnTheSameOperation() {
        ArtifactUuid uuid = stage(bytes(400, 4));
        AtomicBoolean blind = new AtomicBoolean(true);
        StagedFileService cutOff = serviceWith(admitsAndThenFails(), blindJournal(blind));
        StagedPlacementRequest request = request(uuid, StagedActionKind.COPY, "copy-cut-off-key-000001", "/srv/cut.bin");

        assertThatThrownBy(() -> cutOff.place(request)).isInstanceOf(IllegalStateException.class);
        StagedAction reserved = onlyAction(uuid);
        assertThat(reserved.state()).as("the call stopped between the admission and the submission").isEqualTo(StagedActionState.RESERVED);
        assertThat(journal.find(reserved.requestId())).as("but the operation reached the journal under the reserved id").isPresent();

        blind.set(false);
        StagedPlacement again = staged.place(request);

        assertThat(again).isEqualTo(new StagedPlacement.Placed(reserved.requestId(), StagedActionState.SUBMITTED));
        assertThat(onlyAction(uuid).state()).isEqualTo(StagedActionState.SUBMITTED);
        ArtifactRecord copy = copyOf(reserved.requestId());
        assertThat(copy.spec().retention()).as("pinned by the operation").isEqualTo(ArtifactRetention.PINNED);
        assertThat(copy.submission()).isEqualTo(new ArtifactSubmission.SubmittedAs(reserved.requestId()));
        assertThat(staged.place(request)).as("and a third call is the same answer").isEqualTo(again);
        intake.deny(reserved.requestId());
    }

    @Test
    @DisplayName("S-D0156-03 D-0156 RC-B-133: the start settles a reserved action by the journal: the one whose operation is there is submitted and pins its copy, the one whose operation never reached the journal is refused and nothing is sent")
    void theStartSettlesReservedActionsByTheJournal() {
        AtomicBoolean blind = new AtomicBoolean(true);
        ArtifactUuid accepted = stage(bytes(150, 5));
        ArtifactUuid neverAdmitted = stage(bytes(150, 6));
        assertThatThrownBy(() -> serviceWith(admitsAndThenFails(), blindJournal(blind)).place(request(accepted, StagedActionKind.COPY, "copy-accepted-key-0001", "/srv/a.bin")))
            .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> serviceWith(failsBeforeAdmitting(), blindJournal(blind)).place(request(neverAdmitted, StagedActionKind.COPY, "copy-never-key-0000001", "/srv/n.bin")))
            .isInstanceOf(IllegalStateException.class);
        StagedAction acceptedAction = onlyAction(accepted);
        StagedAction neverAction = onlyAction(neverAdmitted);
        assertThat(acceptedAction.state()).isEqualTo(StagedActionState.RESERVED);
        assertThat(neverAction.state()).isEqualTo(StagedActionState.RESERVED);
        blind.set(false);
        int sent = transfer.uploadsSent();

        assertThat(staged.recoverReservations()).isGreaterThanOrEqualTo(2);

        assertThat(onlyAction(accepted).state()).isEqualTo(StagedActionState.SUBMITTED);
        assertThat(copyOf(acceptedAction.requestId()).spec().retention()).isEqualTo(ArtifactRetention.PINNED);
        assertThat(onlyAction(neverAdmitted).state()).isEqualTo(StagedActionState.REFUSED);
        assertThat(journal.findByRequestKey(neverAction.requestKey())).as("the refused action has no operation, and none is made").isEmpty();
        assertThat(transfer.uploadsSent()).as("nothing was sent").isEqualTo(sent);
        assertThat(staged.place(request(neverAdmitted, StagedActionKind.COPY, "copy-never-key-0000001", "/srv/n.bin"))).as("the same key answers the refusal")
            .isEqualTo(new StagedPlacement.Placed(neverAction.requestId(), StagedActionState.REFUSED));
        intake.deny(acceptedAction.requestId());
    }

    // ---- D-0157: the settlement does not depend on the call in memory ----

    @Test
    @DisplayName("S-D0157-01 D-0157: an operation that ended while nobody told the listeners leaves its copy pinned and its action submitted; the settlement finds both in the stored state and gives the copy back and settles the action")
    void anEndedOperationWhoseListenersWereNotToldIsSettledFromTheStoredState() {
        ArtifactUuid uuid = stage(bytes(250, 7));
        OperationId id = placed(staged.place(request(uuid, StagedActionKind.COPY, "copy-lost-call-key-001", "/srv/lost-call.bin")));
        Operation pending = journal.find(id).orElseThrow();
        journal.save(pending.deny(clock.instant()), pending.version());
        assertThat(copyOf(id).spec().retention()).as("the listener was not called").isEqualTo(ArtifactRetention.PINNED);
        assertThat(onlyAction(uuid).state()).isEqualTo(StagedActionState.SUBMITTED);

        int told = settlement.sweep(clock.instant());

        assertThat(told).isGreaterThanOrEqualTo(1);
        assertThat(copyOf(id).spec().retention()).as("given back to the expiry calculation").isEqualTo(ArtifactRetention.RELEASABLE);
        assertThat(onlyAction(uuid).state()).isEqualTo(StagedActionState.SETTLED);
        assertThat(copyOf(id).spec().retention()).as("and it stays given back after another sweep").isEqualTo(ArtifactRetention.RELEASABLE);
        settlement.sweep(clock.instant());
        assertThat(onlyAction(uuid).state()).isEqualTo(StagedActionState.SETTLED);
    }

    @Test
    @DisplayName("S-D0157-02 D-0157: the sweep of every minute leaves an operation that ended a moment ago to the call that ended it; the settlement of the start does not wait")
    void aYoungEndIsLeftToTheCallThatMadeIt() {
        ArtifactUuid uuid = stage(bytes(120, 8));
        OperationId id = placed(staged.place(request(uuid, StagedActionKind.COPY, "copy-young-end-key-001", "/srv/young.bin")));
        Operation pending = journal.find(id).orElseThrow();
        journal.save(pending.deny(clock.instant()), pending.version());

        assertThat(settlement.sweep(clock.instant().minus(Duration.ofHours(1)))).isZero();
        assertThat(copyOf(id).spec().retention()).isEqualTo(ArtifactRetention.PINNED);

        assertThat(settlement.settleAfterRestart()).isGreaterThanOrEqualTo(1);
        assertThat(copyOf(id).spec().retention()).isEqualTo(ArtifactRetention.RELEASABLE);
    }

    @Test
    @DisplayName("S-D0157-03 D-0157: a copy whose operation is still pending stays pinned and its action submitted whatever the settlement does")
    void aPendingOperationKeepsItsCopyPinned() {
        ArtifactUuid uuid = stage(bytes(120, 9));
        OperationId id = placed(staged.place(request(uuid, StagedActionKind.COPY, "copy-still-pending-key1", "/srv/still.bin")));

        settlement.settleAfterRestart();

        assertThat(copyOf(id).spec().retention()).isEqualTo(ArtifactRetention.PINNED);
        assertThat(onlyAction(uuid).state()).isEqualTo(StagedActionState.SUBMITTED);
        intake.deny(id);
    }

    // ---- helpers ----

    private byte[] bytes(int length, int fill) {
        byte[] bytes = new byte[length];
        java.util.Arrays.fill(bytes, (byte) fill);
        return bytes;
    }

    private ArtifactUuid stage(byte[] content) {
        StagedBegin begun = staged.begin(OWNER, kit.newKey().value(), new FileName("staged.bin"), content.length);
        ArtifactUuid uuid = ((StagedBegin.Begun) begun).uuid();
        staged.append(OWNER, uuid, 0, content, digester.digest(content));
        staged.complete(OWNER, uuid);
        return uuid;
    }

    private StagedPlacementRequest request(ArtifactUuid uuid, StagedActionKind kind, String operationKey, String path) {
        return new StagedPlacementRequest(OWNER, uuid, kind, operationKey, targets.current(), targets.current().revision(), PathSyntax.POSIX.path(path), OverwriteMode.REPLACE,
            Elevation.NONE);
    }

    private static OperationId placed(StagedPlacement placement) {
        return ((StagedPlacement.Placed) placement).operation();
    }

    private StagedAction onlyAction(ArtifactUuid uuid) {
        List<StagedAction> actions = staged.stat(OWNER, uuid).actions();
        assertThat(actions).hasSize(1);
        return actions.get(0);
    }

    /** The copy that the upload of the operation was made for. */
    private ArtifactRecord copyOf(OperationId operation) {
        Operation stored = journal.find(operation).orElseThrow();
        return registry.find(((io.github.jaeseongs95.opermand.domain.operation.UploadInput) stored.input()).artifactId()).orElseThrow();
    }

    /** The service of the test, with an admission and a journal of the test's own; every other part is the real bean. */
    private StagedFileService serviceWith(UploadAdmission admission, OperationJournal asked) {
        return new StagedFileService(registry, payloads, importer, maintenance, admission, asked, times, uuids, operationIds, digester, clock);
    }

    /** An admission that accepts the operation for real and then fails, like a call that is cut off right after the journal wrote the row. */
    private UploadAdmission admitsAndThenFails() {
        return (NewOperation operation, OperationId id) -> {
            realAdmission.admit(operation, id);
            throw new IllegalStateException("cut off after the admission");
        };
    }

    /** An admission that fails before anything is written, like a call that is cut off before the journal got the row. */
    private UploadAdmission failsBeforeAdmitting() {
        AtomicInteger calls = new AtomicInteger();
        return (NewOperation operation, OperationId id) -> {
            calls.incrementAndGet();
            throw new IllegalStateException("cut off before the admission");
        };
    }

    /** The real journal, except that it cannot say whether a key was accepted while {@code blind} is set: the use case then cannot tell what its cut-off call did. */
    private OperationJournal blindJournal(AtomicBoolean blind) {
        return (OperationJournal) Proxy.newProxyInstance(OperationJournal.class.getClassLoader(), new Class<?>[] {OperationJournal.class}, (proxy, method, args) -> {
            if (blind.get() && method.getName().equals("findByRequestKey")) {
                throw new IllegalStateException("the journal cannot be read now");
            }
            try {
                return method.invoke(journal, args);
            } catch (InvocationTargetException e) {
                throw e.getCause();
            }
        });
    }
}
