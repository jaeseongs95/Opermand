package io.github.jaeseongs95.portal.store.journal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;

import io.github.jaeseongs95.portal.app.journal.ClaimResult;
import io.github.jaeseongs95.portal.app.journal.JournalInsert;
import io.github.jaeseongs95.portal.domain.error.ExecutionErrorCode;
import io.github.jaeseongs95.portal.domain.error.ExecutionFailure;
import io.github.jaeseongs95.portal.domain.error.ExecutionStage;
import io.github.jaeseongs95.portal.domain.error.OperationErrorCode;
import io.github.jaeseongs95.portal.domain.error.RemoteOutcome;
import io.github.jaeseongs95.portal.domain.error.RequestErrorCode;
import io.github.jaeseongs95.portal.domain.error.RequestRefusedException;
import io.github.jaeseongs95.portal.domain.file.FileBinding;
import io.github.jaeseongs95.portal.domain.file.FileName;
import io.github.jaeseongs95.portal.domain.file.FileTargetBinding;
import io.github.jaeseongs95.portal.domain.id.ArtifactId;
import io.github.jaeseongs95.portal.domain.id.Digest;
import io.github.jaeseongs95.portal.domain.id.Digester;
import io.github.jaeseongs95.portal.domain.id.OperationIdGenerator;
import io.github.jaeseongs95.portal.domain.id.OwnerId;
import io.github.jaeseongs95.portal.domain.id.TargetId;
import io.github.jaeseongs95.portal.domain.operation.AgentCliInput;
import io.github.jaeseongs95.portal.domain.operation.ConflictKey;
import io.github.jaeseongs95.portal.domain.operation.Deadline;
import io.github.jaeseongs95.portal.domain.operation.DownloadInput;
import io.github.jaeseongs95.portal.domain.operation.Elevation;
import io.github.jaeseongs95.portal.domain.operation.ExecutionOutcome;
import io.github.jaeseongs95.portal.domain.operation.NewOperation;
import io.github.jaeseongs95.portal.domain.operation.Operation;
import io.github.jaeseongs95.portal.domain.operation.OperationEvent;
import io.github.jaeseongs95.portal.domain.operation.OperationInput;
import io.github.jaeseongs95.portal.domain.operation.OperationState;
import io.github.jaeseongs95.portal.domain.operation.OutcomeCertainty;
import io.github.jaeseongs95.portal.domain.operation.OverwriteMode;
import io.github.jaeseongs95.portal.domain.operation.ReconciliationEvidence;
import io.github.jaeseongs95.portal.domain.operation.RecordedResult;
import io.github.jaeseongs95.portal.domain.operation.Settlement;
import io.github.jaeseongs95.portal.domain.operation.SourceInput;
import io.github.jaeseongs95.portal.domain.operation.StartAuthority;
import io.github.jaeseongs95.portal.domain.operation.UploadInput;
import io.github.jaeseongs95.portal.domain.operation.UploadProtocol;
import io.github.jaeseongs95.portal.domain.path.RemotePath;
import io.github.jaeseongs95.portal.domain.schedule.ConcurrencyLimits;
import io.github.jaeseongs95.portal.domain.target.ProfileAlias;
import io.github.jaeseongs95.portal.domain.target.TargetRevision;
import io.github.jaeseongs95.portal.domain.text.CommandText;
import io.github.jaeseongs95.portal.domain.text.Utf8Text;
import io.github.jaeseongs95.portal.store.TestSavepoints;

@JournalTest
class JdbcOperationJournalTest {

    private final JdbcOperationJournal journal;
    private final JdbcClaimStore claims;
    private final OperationRowRepository operations;
    private final OperationEventRowRepository events;
    private final OperationStorage storage;
    private final OperationIdGenerator ids;
    private final Clock clock;
    private final TestSavepoints savepoints;
    private final Digester digester;
    private final JdbcTemplate jdbc;
    private final JournalFixture fx = new JournalFixture();

    JdbcOperationJournalTest(JdbcOperationJournal journal, JdbcClaimStore claims, OperationRowRepository operations, OperationEventRowRepository events,
                             OperationStorage storage, OperationIdGenerator ids, Clock clock, TestSavepoints savepoints,
                             Digester digester, JdbcTemplate jdbc) {
        this.journal = journal;
        this.claims = claims;
        this.operations = operations;
        this.events = events;
        this.storage = storage;
        this.ids = ids;
        this.clock = clock;
        this.savepoints = savepoints;
        this.digester = digester;
        this.jdbc = jdbc;
    }

    private JournalInsert accept(NewOperation spec) {
        return journal.accept(spec, JournalFixture.review(spec));
    }

    private void assertConflict(NewOperation changed) {
        assertThatThrownBy(() -> accept(changed)).isInstanceOfSatisfying(RequestRefusedException.class,
            e -> assertThat(e.code()).isEqualTo(RequestErrorCode.OPERATION_KEY_CONFLICT));
    }

    @Test
    @DisplayName("RC-B-201 and S2-DB-04: the same contract is the same operation, a changed contract is OPERATION_KEY_CONFLICT, and the stored copy is the operation of the first intake")
    void theSameContractIsTheSameOperation() {
        NewOperation spec = fx.command("same", "alpha", "printf ok", new ConflictKey.None());
        JournalInsert first = accept(spec);
        JournalInsert second = accept(spec);

        assertThat(first).isInstanceOf(JournalInsert.Inserted.class);
        assertThat(second).isInstanceOf(JournalInsert.Existing.class);
        assertThat(second.operation().id()).isEqualTo(first.operation().id());
        assertThat(journal.find(first.operation().id()).orElseThrow().snapshot()).isEqualTo(first.operation().snapshot());

        assertConflict(fx.command("same", "alpha", "printf ok ", new ConflictKey.None()));
        assertConflict(fx.command("same", "alpha", "printf ok", "other", JournalFixture.REVISION, new ConflictKey.None(), new Deadline.None()));
        assertConflict(fx.command("same", "alpha", "printf ok", "owner", "1".repeat(64), new ConflictKey.None(), new Deadline.None()));
        assertConflict(fx.command("same", "alpha", "printf ok", "owner", JournalFixture.REVISION, new ConflictKey.None(),
            new Deadline.At(JournalFixture.NOW.plus(Duration.ofMinutes(30)))));

        assertThat(journal.findByRequestKey(fx.requestKey("same")).orElseThrow().id()).isEqualTo(first.operation().id());
        assertThat(journal.events(first.operation().id()).stream().map(OperationEvent::state)).containsExactly(OperationState.PENDING);
    }

    @Test
    @DisplayName("RC-A-426: the request key is kept: submitting it again gives the same operation and the changed arguments are refused")
    void theRequestKeyIsKept() {
        Operation first = accept(fx.command("fixture-request-0001", "alpha", "sudo id", new ConflictKey.None())).operation();
        assertThat(accept(fx.command("fixture-request-0001", "alpha", "sudo id", new ConflictKey.None())).operation().id()).isEqualTo(first.id());
        assertConflict(fx.command("fixture-request-0001", "alpha", "sudo pwd", new ConflictKey.None()));
    }

    @Test
    @DisplayName("S2-DB-03: two writers that read the same version: the first transition is stored, the second is OPERATION_VERSION_CONFLICT and changes nothing")
    void theSecondWriterOfAVersionLoses() {
        Operation accepted = accept(fx.command("race", "alpha", "echo", new ConflictKey.None())).operation();
        Operation staleCopy = journal.find(accepted.id()).orElseThrow();

        Operation queued = journal.save(accepted.enqueue(new StartAuthority.Owner(), JournalFixture.NOW), accepted.version());

        assertThatThrownBy(() -> journal.save(staleCopy.requestCancel(JournalFixture.NOW), staleCopy.version())).isInstanceOfSatisfying(RequestRefusedException.class,
            e -> assertThat(e.code()).isEqualTo(OperationErrorCode.OPERATION_VERSION_CONFLICT));
        assertThat(journal.find(accepted.id()).orElseThrow().snapshot()).isEqualTo(queued.snapshot());
        assertThat(journal.events(accepted.id()).stream().map(OperationEvent::version)).containsExactly(1L, 2L);
    }

    @Test
    @DisplayName("RC-B-202: a transition that did not pass the claim cannot move an operation into STARTING (it would take a slot nobody counted)")
    void saveRefusesAClaimThatSkippedTheLimits() {
        Operation queued = fx.queued(journal, fx.command("skip", "alpha", "echo", new ConflictKey.None()));
        assertThatThrownBy(() -> journal.save(queued.claim(JournalFixture.NOW), queued.version())).isInstanceOfSatisfying(RequestRefusedException.class,
            e -> assertThat(e.code()).isEqualTo(OperationErrorCode.INVALID_OPERATION_TRANSITION));
    }

    @Test
    @DisplayName("S-D0050-01: the journal has no row limit: with 10,000 non-legacy rows (the 2.x limit) a new intake is Inserted, one row is added and none is deleted")
    void theJournalHasNoRowLimit() {
        long before = operations.count();
        int filler = 10_000;
        // The column holds the UTC date and time of the moment (D-0161), whatever zone the JVM of the run is in.
        LocalDateTime at = LocalDateTime.ofInstant(JournalFixture.NOW, ZoneOffset.UTC);
        // The rows are written straight into the table (a bulk batch, ANSI SQL): 10,000 intakes through accept would only repeat the same insert. Every NOT NULL column is set.
        jdbc.batchUpdate("INSERT INTO operation (id, request_key, owner_id, target_id, target_revision, kind, state, version, approval_mode, profile, contract_digest, "
            + "created_at, updated_at, recovery_reason, audit_failure) VALUES (?, ?, 'owner', 'alpha', ?, 'COMMAND', 'PENDING', 1, 'NOT_DECIDED', 'default', ?, ?, ?, 'NONE', 'NONE')",
            new BatchPreparedStatementSetter() {
                @Override
                public void setValues(PreparedStatement ps, int i) throws SQLException {
                    ps.setString(1, String.format("%048x", i));
                    ps.setString(2, "filler-" + i);
                    ps.setString(3, JournalFixture.REVISION);
                    ps.setString(4, JournalFixture.REVISION);
                    ps.setObject(5, at);
                    ps.setObject(6, at);
                }

                @Override
                public int getBatchSize() {
                    return filler;
                }
            });
        assertThat(operations.count()).isGreaterThanOrEqualTo(10_000);
        long full = operations.count();
        assertThat(full).isEqualTo(before + filler);

        NewOperation next = fx.command("beyond", "alpha", "echo beyond", new ConflictKey.None());
        JournalInsert inserted = accept(next);

        assertThat(inserted).isInstanceOf(JournalInsert.Inserted.class);
        assertThat(journal.findByRequestKey(next.requestKey())).isPresent();
        assertThat(operations.count()).isEqualTo(full + 1);
        // No row was deleted to make room: every filler row is still there.
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM operation WHERE request_key LIKE 'filler-%'", Long.class)).isEqualTo(filler);
    }

    @Test
    @DisplayName("RC-B-204: an executed outcome is stored with the state its verdict gives, and the stored operation is the same value when it is read again")
    void theOutcomeDecidesTheStoredState() {
        Operation running = running("done");
        ExecutionOutcome exited = ExecutionOutcome.exited(0, List.of("ok"));
        Operation completed = journal.save(running.settle(exited, JournalFixture.NOW.plusSeconds(5)), running.version());

        assertThat(completed.state()).isEqualTo(OperationState.COMPLETED);
        Operation read = journal.find(completed.id()).orElseThrow();
        assertThat(read.snapshot()).isEqualTo(completed.snapshot());
        assertThat(read.settlement()).isInstanceOfSatisfying(Settlement.Settled.class, settled -> {
            assertThat(settled.certainty()).isEqualTo(OutcomeCertainty.EXITED);
            assertThat(settled.result()).isEqualTo(new RecordedResult.Executed(exited));
        });
    }

    @Test
    @DisplayName("RC-B-204: a lost transport is stored as UNKNOWN with the reconciliation required; reconciling it is stored as a final state and the result is kept")
    void anUnprovenOutcomeIsUnknownUntilItIsReconciled() {
        Operation running = running("lost");
        ExecutionFailure failure = new ExecutionFailure.Classified(ExecutionStage.SSH_EXECUTION, ExecutionErrorCode.SSH_EXECUTION_FAILED, RemoteOutcome.UNKNOWN);
        ExecutionOutcome lost = ExecutionOutcome.transportLost(failure, List.of("connection lost"));
        Operation unknown = journal.save(running.settle(lost, JournalFixture.NOW.plusSeconds(5)), running.version());

        assertThat(unknown.state()).isEqualTo(OperationState.UNKNOWN);
        assertThat(journal.find(unknown.id()).orElseThrow().snapshot()).isEqualTo(unknown.snapshot());

        Operation reconciled = journal.save(unknown.reconcile(new ReconciliationEvidence.Failed(), JournalFixture.NOW.plusSeconds(60)), unknown.version());
        assertThat(reconciled.state()).isEqualTo(OperationState.FAILED);
        assertThat(journal.find(unknown.id()).orElseThrow().snapshot()).isEqualTo(reconciled.snapshot());
        assertThat(journal.events(unknown.id()).stream().map(OperationEvent::state)).endsWith(OperationState.RUNNING, OperationState.UNKNOWN, OperationState.FAILED);
    }

    @Test
    @DisplayName("S2-ST-05: every kind of input is stored and read back as the same value (source, upload with and without binding, download, agent CLI)")
    void everyInputKindRoundTrips() {
        Digest hex = new Digest("1".repeat(64));
        List<OperationInput> inputs = List.of(
            new SourceInput(new CommandText("python3 -"), hex, hex, 5, new Utf8Text("[\"a\"]"), new Utf8Text("/tmp"), Elevation.SUDO, hex, new Utf8Text("c"), new Utf8Text("d")),
            new UploadInput(new ArtifactId("2".repeat(48)), RemotePath.parse("/tmp/a.txt"), new FileName("a.txt"), 10, hex, OverwriteMode.FAIL_IF_EXISTS, Elevation.NONE,
                new FileBinding.Bound(new FileTargetBinding(new TargetId("alpha"), TargetRevision.of("0".repeat(64)))), UploadProtocol.MCP_JSON_V1),
            new UploadInput(new ArtifactId("3".repeat(48)), RemotePath.parse("C:\\data\\a.txt"), new FileName("a.txt"), 0, hex, OverwriteMode.REPLACE, Elevation.NONE,
                new FileBinding.Unbound(), UploadProtocol.UNSPECIFIED),
            new DownloadInput(RemotePath.parse("/var/log/x"), Elevation.SUDO, new FileBinding.Unbound(), new Utf8Text("c"), new Utf8Text("d")),
            new AgentCliInput(hex));
        int number = 0;
        for (OperationInput input : inputs) {
            NewOperation spec = NewOperation.of(digester, fx.requestKey("kind" + number++), new OwnerId("owner"), new TargetId("alpha"), TargetRevision.of(JournalFixture.REVISION),
                new ProfileAlias("default"), input, new ConflictKey.Of("one"), new Deadline.None());
            Operation accepted = accept(spec).operation();
            assertThat(journal.find(accepted.id()).orElseThrow().snapshot()).as(input.getClass().getSimpleName()).isEqualTo(accepted.snapshot());
        }
    }

    @Test
    @DisplayName("S2-ST-05: the contract of a stored operation is the same after every transition")
    void theContractDoesNotChange() {
        NewOperation spec = fx.command("contract", "alpha", "echo", new ConflictKey.Of("k"));
        Operation queued = fx.queued(journal, spec);

        assertThat(queued.state()).isEqualTo(OperationState.QUEUED);
        assertThat(journal.find(queued.id()).orElseThrow().contractDigest()).isEqualTo(spec.contractDigest());
        assertThat(journal.find(queued.id()).orElseThrow().requestKey()).isEqualTo(spec.requestKey());
        assertThat(journal.find(queued.id()).orElseThrow().input()).isEqualTo(spec.input());
    }

    /**
     * The zone of the JVM of the {@code zoneTest} task (build file of this module): a zone that repeats an hour when daylight saving time ends. Its default zone is read when the
     * JVM starts (H2 keeps what it read), so a test cannot change it for itself; the task starts a JVM in it.
     */
    private static final ZoneId DAYLIGHT_SAVING_ZONE = ZoneId.of("America/Los_Angeles");

    @Test
    @Tag("zone")
    @DisplayName("S2-DB-07 (D-0161): two moments that are the same local time of the JVM's zone (the hour that daylight saving time repeats) are stored as two different UTC values, "
        + "read back as themselves and kept in order")
    void momentsThatShareALocalTimeStayApart() {
        assertThat(ZoneId.systemDefault()).as("the zoneTest task starts this JVM in a zone with daylight saving time").isEqualTo(DAYLIGHT_SAVING_ZONE);
        Instant first = Instant.parse("2027-11-07T08:30:00Z");
        Instant second = Instant.parse("2027-11-07T09:30:00Z");
        assertThat(second.atZone(DAYLIGHT_SAVING_ZONE).toLocalDateTime()).as("the premise: 01:30 before and after the change of the clock").isEqualTo(first.atZone(DAYLIGHT_SAVING_ZONE).toLocalDateTime());
        NewOperation earlySpec = fx.command("zone-early", "alpha", "echo early", new ConflictKey.None());
        NewOperation lateSpec = fx.command("zone-late", "alpha", "echo late", new ConflictKey.None());

        Operation early = journal.accept(earlySpec, JournalFixture.review(earlySpec), ids.next(), first, () -> { }).operation();
        Operation late = journal.accept(lateSpec, JournalFixture.review(lateSpec), ids.next(), second, () -> { }).operation();

        assertThat(journal.find(early.id()).orElseThrow().createdAt()).isEqualTo(first);
        assertThat(journal.find(late.id()).orElseThrow().createdAt()).isEqualTo(second);
        assertThat(jdbc.queryForObject("SELECT CAST(created_at AS VARCHAR(40)) FROM operation WHERE id = ?", String.class, early.id().value())).startsWith("2027-11-07 08:30:00");
        assertThat(jdbc.queryForObject("SELECT CAST(created_at AS VARCHAR(40)) FROM operation WHERE id = ?", String.class, late.id().value())).startsWith("2027-11-07 09:30:00");
        Set<OperationState> pending = Set.of(OperationState.PENDING, OperationState.QUEUED);
        assertThat(operations.findByStateInOrderByCreatedAtDescIdDesc(pending, PageRequest.ofSize(10)).stream().map(OperationRow::id)
            .filter(Set.of(early.id().value(), late.id().value())::contains)).containsExactly(late.id().value(), early.id().value());
        assertThat(operations.findByStateInAndCreatedAtBeforeOrderByCreatedAtDescIdDesc(pending, Instant.parse("2027-11-07T09:00:00Z"), PageRequest.ofSize(10)).stream()
            .map(OperationRow::id)).containsExactly(early.id().value());
    }

    @Test
    @Tag("zone")
    @DisplayName("S2-DB-07 (D-0161): what was stored under another zone of the host is read as the same moment: the column holds the UTC date and time, the zone of the JVM is not part of it")
    void theColumnDoesNotDependOnTheZoneOfTheJvm() {
        assertThat(ZoneId.systemDefault()).isEqualTo(DAYLIGHT_SAVING_ZONE);
        NewOperation spec = fx.command("zone-moved", "alpha", "echo moved", new ConflictKey.None());
        Instant moment = Instant.parse("2027-07-01T12:34:56.123456Z");
        Operation accepted = journal.accept(spec, JournalFixture.review(spec), ids.next(), moment, () -> { }).operation();
        Operation moved = journal.save(accepted.requestCancel(moment.plusSeconds(1)), accepted.version());

        assertThat(jdbc.queryForObject("SELECT CAST(created_at AS VARCHAR(40)) FROM operation WHERE id = ?", String.class, accepted.id().value())).isEqualTo("2027-07-01 12:34:56.123456");
        assertThat(jdbc.queryForObject("SELECT CAST(updated_at AS VARCHAR(40)) FROM operation WHERE id = ?", String.class, accepted.id().value())).isEqualTo("2027-07-01 12:34:57.123456");
        assertThat(journal.find(moved.id()).orElseThrow().updatedAt()).isEqualTo(moment.plusSeconds(1));
        assertThat(journal.find(moved.id()).orElseThrow().createdAt()).isEqualTo(moment);
    }

    @Test
    @DisplayName("S-D0119-11 D3 the check of the insert runs before the row and what it throws stops the insert; a known key is answered without running it (D4)")
    void theCheckOfTheInsertStopsTheInsert() {
        NewOperation spec = fx.command("check-first", "alpha", "touch a", new ConflictKey.None());
        io.github.jaeseongs95.portal.domain.operation.Admission queued = io.github.jaeseongs95.portal.domain.operation.Admission.of(
            io.github.jaeseongs95.portal.domain.operation.DecisionReason.COMMAND_SET, io.github.jaeseongs95.portal.domain.operation.OperationKind.COMMAND, 0L);
        long before = operations.count();

        // The refusal leaves the transaction of the test, which goes on: the call that is refused runs in a savepoint (Savepoints), as every caller that goes on does.
        assertThatThrownBy(() -> savepoints.run(() -> journal.accept(spec, queued, ids.next(), clock.instant(), () -> {
            assertThat(operations.count()).as("the check runs before the row: nothing is stored yet").isEqualTo(before);
            assertThat(journal.findByRequestKey(spec.requestKey())).isEmpty();
            throw new RequestRefusedException(OperationErrorCode.OPERATION_QUEUE_FULL);
        }))).isInstanceOfSatisfying(RequestRefusedException.class, e -> assertThat(e.code()).isEqualTo(OperationErrorCode.OPERATION_QUEUE_FULL));
        assertThat(operations.count()).as("nothing was stored").isEqualTo(before);
        assertThat(journal.findByRequestKey(spec.requestKey())).isEmpty();

        JournalInsert inserted = journal.accept(spec, queued, ids.next(), clock.instant(), () -> { });
        assertThat(inserted).isInstanceOf(JournalInsert.Inserted.class);
        assertThat(inserted.operation().state()).isEqualTo(OperationState.QUEUED);
        assertThat(jdbc.queryForObject("SELECT ready_sequence FROM operation WHERE request_key = ?", Long.class, spec.requestKey().value())).as("a queued insert takes its place").isNotNull();

        JournalInsert again = journal.accept(spec, queued, ids.next(), clock.instant(), () -> {
            throw new AssertionError("a known key does not run the check");
        });
        assertThat(again).isInstanceOf(JournalInsert.Existing.class);
        assertThat(again.operation().id()).isEqualTo(inserted.operation().id());
    }

    private Operation running(String key) {
        Operation queued = fx.queued(journal, fx.command(key, "alpha", "echo " + key, new ConflictKey.None()));
        ClaimResult claimed = claims.claim(queued.id(), queued.version(), ConcurrencyLimits.defaults(), JournalFixture.NOW.plusSeconds(2));
        assertThat(claimed).isInstanceOf(ClaimResult.Claimed.class);
        Operation starting = ((ClaimResult.Claimed) claimed).operation();
        return journal.save(starting.markRunning(JournalFixture.NOW.plusSeconds(3)), starting.version());
    }
}
