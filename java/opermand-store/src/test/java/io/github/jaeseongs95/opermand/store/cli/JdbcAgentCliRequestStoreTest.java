package io.github.jaeseongs95.opermand.store.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import io.github.jaeseongs95.opermand.app.cli.AgentCliBinding;
import io.github.jaeseongs95.opermand.app.cli.AgentCliInstallRecord;
import io.github.jaeseongs95.opermand.app.cli.CliVersion;
import io.github.jaeseongs95.opermand.app.cli.RemoteState;
import io.github.jaeseongs95.opermand.app.cli.RemoteStatus;
import io.github.jaeseongs95.opermand.app.journal.HistoryCursor;
import io.github.jaeseongs95.opermand.app.journal.HistoryFilter;
import io.github.jaeseongs95.opermand.app.journal.HistoryPage;
import io.github.jaeseongs95.opermand.app.journal.HistoryQuery;
import io.github.jaeseongs95.opermand.domain.agentcli.AgentCliAction;
import io.github.jaeseongs95.opermand.domain.agentcli.AgentCliProviderId;
import io.github.jaeseongs95.opermand.domain.agentcli.AgentRelease;
import io.github.jaeseongs95.opermand.domain.error.HistoryErrorCode;
import io.github.jaeseongs95.opermand.domain.error.RequestErrorCode;
import io.github.jaeseongs95.opermand.domain.error.RequestRefusedException;
import io.github.jaeseongs95.opermand.domain.id.Digest;
import io.github.jaeseongs95.opermand.domain.id.OperationId;
import io.github.jaeseongs95.opermand.domain.id.TargetId;
import io.github.jaeseongs95.opermand.domain.operation.Operation;
import io.github.jaeseongs95.opermand.domain.target.ProfileAlias;
import io.github.jaeseongs95.opermand.domain.target.TargetRevision;
import io.github.jaeseongs95.opermand.store.StoreTest;
import io.github.jaeseongs95.opermand.store.TestSavepoints;
import io.github.jaeseongs95.opermand.store.sql.SqlBuilder;

/**
 * RC-A-027 .. RC-A-029 (2.x {@code test/agent-cli-history.test.mjs}) and the install request round trip. The history cases run on the keyset reader because the
 * {@code Operation} of an entry comes from the journal's {@code HistoryQuery} (not part of this slice); one case runs the store with a stub of that port.
 * The 2.x cases of a malformed cursor (a missing key, NaN, a string for a number, an extra key) cannot be written any more: the cursor is the typed record
 * {@code HistoryCursor.After}, so only an unknown row remains to be refused. The 2.x assertion that the serialized page has no command text belongs to the web layer.
 */
@StoreTest
@Import({JdbcAgentCliRequestStore.class, AgentCliMapper.class, AgentCliHistoryReader.class, SqlBuilder.class, JdbcAgentCliRequestStoreTest.StubHistory.class})
class JdbcAgentCliRequestStoreTest {

    private static final Instant T1000 = Instant.ofEpochMilli(1000);
    private static final Instant T2000 = Instant.ofEpochMilli(2000);
    private static final String REVISION_A = "a".repeat(64);
    private static final String REVISION_B = "b".repeat(64);

    private final JdbcAgentCliRequestStore store;
    private final AgentCliHistoryReader reader;
    private final JdbcTemplate jdbc;
    private final TestSavepoints savepoints;
    private final SqlBuilder sql;

    JdbcAgentCliRequestStoreTest(JdbcAgentCliRequestStore store, AgentCliHistoryReader reader, JdbcTemplate jdbc, TestSavepoints savepoints, SqlBuilder sql) {
        this.store = store;
        this.reader = reader;
        this.jdbc = jdbc;
        this.savepoints = savepoints;
        this.sql = sql;
    }

    /** A stand-in for the journal's read port: it knows no operation, which is all the wiring test needs. */
    @TestConfiguration(proxyBeanMethods = false)
    static class StubHistory {

        @Bean
        HistoryQuery historyQuery() {
            return new HistoryQuery() {
                @Override
                public HistoryPage page(HistoryFilter filter, HistoryCursor after, int pageSize) {
                    throw new UnsupportedOperationException("not needed by the CLI request store");
                }

                @Override
                public Optional<Operation> publicMetadata(OperationId id) {
                    return Optional.empty();
                }
            };
        }
    }

    @Test
    @DisplayName("RC-A-027: the history of a binding holds only the requests of that provider, target, revision and profile, and reading changes no row")
    void onlyTheCurrentBindingIsListed() {
        OperationId own = operationId(1);
        insertOperation(own, T1000, "alpha", REVISION_A, "alpha-write");
        store.insert(install(own, AgentCliProviderId.parse("codex"), new RemoteStatus.NotReported()));
        OperationId otherProvider = operationId(2);
        insertOperation(otherProvider, T1000, "alpha", REVISION_A, "alpha-write");
        store.insert(install(otherProvider, AgentCliProviderId.parse("claude"), new RemoteStatus.NotReported()));
        OperationId otherTarget = operationId(3);
        insertOperation(otherTarget, T1000, "beta", REVISION_A, "alpha-write");
        store.insert(install(otherTarget, AgentCliProviderId.parse("codex"), new RemoteStatus.NotReported()));
        OperationId otherRevision = operationId(4);
        insertOperation(otherRevision, T1000, "alpha", REVISION_B, "alpha-write");
        store.insert(install(otherRevision, AgentCliProviderId.parse("codex"), new RemoteStatus.NotReported()));
        OperationId otherProfile = operationId(5);
        insertOperation(otherProfile, T1000, "alpha", REVISION_A, "beta-write");
        store.insert(install(otherProfile, AgentCliProviderId.parse("codex"), new RemoteStatus.NotReported()));
        List<Map<String, Object>> requestsBefore = jdbc.queryForList("SELECT * FROM agent_cli_request ORDER BY operation_id");
        List<Map<String, Object>> operationsBefore = jdbc.queryForList("SELECT * FROM operation ORDER BY id");

        AgentCliHistoryReader.Slice slice = reader.slice(binding(AgentCliProviderId.parse("codex")), new HistoryCursor.FirstPage());

        assertThat(slice.hits()).extracting(hit -> hit.install().operationId()).containsExactly(own);
        assertThat(slice.hits().get(0).createdAt()).isEqualTo(T1000);
        assertThat(slice.next()).isEqualTo(new HistoryCursor.End());
        assertThat(jdbc.queryForList("SELECT * FROM agent_cli_request ORDER BY operation_id")).isEqualTo(requestsBefore);
        assertThat(jdbc.queryForList("SELECT * FROM operation ORDER BY id")).isEqualTo(operationsBefore);
    }

    @Test
    @Tag("zone")
    @DisplayName("S-D0161-04: requests created in the hour that daylight saving time repeats are listed newest first with their own moments, and a cursor at the newer one finds the older")
    void historyKeepsTheMomentsOfTheRepeatedHourApart() {
        assertThat(ZoneId.systemDefault()).as("the zoneTest task starts this JVM in a zone with daylight saving time").isEqualTo(ZoneId.of("America/Los_Angeles"));
        Instant first = Instant.parse("2027-11-07T08:30:00Z");
        Instant second = Instant.parse("2027-11-07T09:30:00Z");
        OperationId early = operationId(1);
        insertOperation(early, first, "alpha", REVISION_A, "alpha-write");
        store.insert(install(early, AgentCliProviderId.parse("codex"), new RemoteStatus.NotReported()));
        OperationId late = operationId(2);
        insertOperation(late, second, "alpha", REVISION_A, "alpha-write");
        store.insert(install(late, AgentCliProviderId.parse("codex"), new RemoteStatus.NotReported()));

        AgentCliHistoryReader.Slice page = reader.slice(binding(AgentCliProviderId.parse("codex")), new HistoryCursor.FirstPage());

        assertThat(ids(page)).containsExactly(late, early);
        assertThat(page.hits()).extracting(AgentCliHistoryReader.Hit::createdAt).containsExactly(second, first);
        assertThat(ids(reader.slice(binding(AgentCliProviderId.parse("codex")), new HistoryCursor.After(second, late)))).containsExactly(early);
    }

    @Test
    @DisplayName("RC-A-028: the keyset pages of 20 hold every request once, newest first, also with equal timestamps and a request that arrives between two pages")
    void keysetPagesLoseAndRepeatNothing() {
        for (int number = 1; number <= 41; number++) {
            OperationId id = operationId(number);
            insertOperation(id, T1000, "alpha", REVISION_A, "alpha-write");
            store.insert(install(id, AgentCliProviderId.parse("codex"), new RemoteStatus.NotReported()));
        }

        AgentCliHistoryReader.Slice first = reader.slice(binding(AgentCliProviderId.parse("codex")), new HistoryCursor.FirstPage());

        assertThat(ids(first)).isEqualTo(numbers(41, 22));
        assertThat(first.next()).isEqualTo(new HistoryCursor.After(T1000, operationId(22)));

        OperationId arrival = operationId(42);
        insertOperation(arrival, T2000, "alpha", REVISION_A, "alpha-write");
        store.insert(install(arrival, AgentCliProviderId.parse("codex"), new RemoteStatus.NotReported()));
        AgentCliHistoryReader.Slice second = reader.slice(binding(AgentCliProviderId.parse("codex")), first.next());

        assertThat(ids(second)).isEqualTo(numbers(21, 2));
        assertThat(second.next()).isEqualTo(new HistoryCursor.After(T1000, operationId(2)));
        AgentCliHistoryReader.Slice third = reader.slice(binding(AgentCliProviderId.parse("codex")), second.next());

        assertThat(ids(third)).isEqualTo(numbers(1, 1));
        assertThat(third.next()).isEqualTo(new HistoryCursor.End());
        Set<OperationId> union = new HashSet<>();
        union.addAll(ids(first));
        union.addAll(ids(second));
        union.addAll(ids(third));
        assertThat(union).hasSize(41);
        assertThat(ids(reader.slice(binding(AgentCliProviderId.parse("codex")), new HistoryCursor.FirstPage())).get(0)).isEqualTo(arrival);
    }

    @Test
    @DisplayName("S-SQLB-11: the statement is written by the SqlBuilder; the row limit and the keyset condition are in the SQL and every value is a placeholder")
    void theStatementCarriesTheKeysetConditionAndTheRowLimit() {
        String select = """
            SELECT o.id AS operation_id, o.created_at AS created_at
            FROM agent_cli_request r JOIN operation o ON o.id = r.operation_id
            WHERE r.provider = :provider AND o.target_id = :targetId AND o.target_revision = :targetRevision AND o.profile = :profile""";
        String newestFirst = " ORDER BY o.created_at DESC, o.id DESC FETCH FIRST :limit ROWS ONLY";

        assertThat(reader.statement(false)).isEqualTo(select + newestFirst);
        assertThat(reader.statement(true)).isEqualTo(select + " AND (o.created_at < :createdAt OR (o.created_at = :createdAt AND o.id < :id))" + newestFirst);
    }

    @Test
    @DisplayName("S-SQLB-12: exactly one page of requests is one page of 20 and the End cursor")
    void exactlyOnePageHasNoNextPage() {
        for (int number = 1; number <= AgentCliHistoryReader.PAGE_SIZE; number++) {
            insertRequest(number, T1000);
        }

        AgentCliHistoryReader.Slice page = reader.slice(binding(AgentCliProviderId.parse("codex")), new HistoryCursor.FirstPage());

        assertThat(ids(page)).isEqualTo(numbers(20, 1));
        assertThat(page.next()).isEqualTo(new HistoryCursor.End());
    }

    @Test
    @DisplayName("S-SQLB-13: one request more than a page gives a full page with the keyset cursor of its last entry, then a page of one and the End cursor")
    void oneRequestMoreThanAPageHasANextPage() {
        for (int number = 1; number <= AgentCliHistoryReader.PAGE_SIZE + 1; number++) {
            insertRequest(number, T1000);
        }

        AgentCliHistoryReader.Slice first = reader.slice(binding(AgentCliProviderId.parse("codex")), new HistoryCursor.FirstPage());
        assertThat(ids(first)).isEqualTo(numbers(21, 2));
        assertThat(first.next()).isEqualTo(new HistoryCursor.After(T1000, operationId(2)));

        AgentCliHistoryReader.Slice second = reader.slice(binding(AgentCliProviderId.parse("codex")), first.next());
        assertThat(ids(second)).isEqualTo(numbers(1, 1));
        assertThat(second.next()).isEqualTo(new HistoryCursor.End());
    }

    @Test
    @DisplayName("S-SQLB-14: the page boundary falls inside a group of equal creation times and the next page neither skips nor repeats a request")
    void theKeysetBoundaryInsideAGroupOfEqualTimes() {
        for (int number = 1; number <= 11; number++) {
            insertRequest(number, T1000);
        }
        for (int number = 12; number <= 22; number++) {
            insertRequest(number, T2000);
        }

        AgentCliHistoryReader.Slice first = reader.slice(binding(AgentCliProviderId.parse("codex")), new HistoryCursor.FirstPage());
        assertThat(ids(first)).isEqualTo(numbers(22, 3));
        assertThat(first.next()).isEqualTo(new HistoryCursor.After(T1000, operationId(3)));

        AgentCliHistoryReader.Slice second = reader.slice(binding(AgentCliProviderId.parse("codex")), first.next());
        assertThat(ids(second)).isEqualTo(numbers(2, 1));
        assertThat(second.next()).isEqualTo(new HistoryCursor.End());
    }

    @Test
    @DisplayName("RC-A-029: a cursor that names no request of that creation time is refused, another scope is empty, and the End cursor gives an empty End page")
    void unknownCursorsAreRefused() {
        OperationId own = operationId(1);
        insertOperation(own, T1000, "alpha", REVISION_A, "alpha-write");
        store.insert(install(own, AgentCliProviderId.parse("codex"), new RemoteStatus.NotReported()));
        OperationId noRequest = operationId(2);
        insertOperation(noRequest, T1000, "alpha", REVISION_A, "alpha-write");
        AgentCliBinding codex = binding(AgentCliProviderId.parse("codex"));

        assertThatThrownBy(() -> reader.slice(codex, new HistoryCursor.After(T1000, operationId(99)))).isInstanceOfSatisfying(RequestRefusedException.class,
            e -> assertThat(e.code()).isEqualTo(HistoryErrorCode.HISTORY_INVALID_CURSOR));
        assertThatThrownBy(() -> reader.slice(codex, new HistoryCursor.After(T2000, own))).isInstanceOfSatisfying(RequestRefusedException.class,
            e -> assertThat(e.code()).isEqualTo(HistoryErrorCode.HISTORY_INVALID_CURSOR));
        assertThatThrownBy(() -> reader.slice(codex, new HistoryCursor.After(T1000, noRequest))).isInstanceOfSatisfying(RequestRefusedException.class,
            e -> assertThat(e.code()).isEqualTo(HistoryErrorCode.HISTORY_INVALID_CURSOR));

        AgentCliHistoryReader.Slice otherScope = reader.slice(binding(AgentCliProviderId.parse("claude")), new HistoryCursor.FirstPage());
        assertThat(otherScope.hits()).isEmpty();
        assertThat(otherScope.next()).isEqualTo(new HistoryCursor.End());
        AgentCliHistoryReader.Slice ended = reader.slice(codex, new HistoryCursor.End());
        assertThat(ended.hits()).isEmpty();
        assertThat(ended.next()).isEqualTo(new HistoryCursor.End());
    }

    @Test
    @DisplayName("RC-A-027: the store takes the Operation of each entry from the journal port and refuses the page when that port knows none; another scope is an empty End page")
    void theStoreAsksTheJournalForTheOperation() {
        OperationId own = operationId(1);
        insertOperation(own, T1000, "alpha", REVISION_A, "alpha-write");
        store.insert(install(own, AgentCliProviderId.parse("codex"), new RemoteStatus.NotReported()));

        assertThatThrownBy(() -> store.history(binding(AgentCliProviderId.parse("codex")), new HistoryCursor.FirstPage())).isInstanceOfSatisfying(
            RequestRefusedException.class, e -> assertThat(e.code()).isEqualTo(HistoryErrorCode.HISTORY_METADATA_UNAVAILABLE));
        assertThat(store.history(binding(AgentCliProviderId.parse("claude")), new HistoryCursor.FirstPage()).entries()).isEmpty();
    }

    @Test
    @DisplayName("RC-A-027: an install request is stored and read back equal for every remote status variant")
    void everyRemoteStatusRoundTrips() {
        List<RemoteStatus> variants = List.of(new RemoteStatus.NotReported(), new RemoteStatus.NotFound(),
            new RemoteStatus.Known(RemoteState.COMPLETED, new CliVersion("1.2.3"), 0), new RemoteStatus.Known(RemoteState.INSTALLING, new CliVersion("1.2.3"), 2),
            new RemoteStatus.Failed(new CliVersion("1.2.4"), 0), new RemoteStatus.Failed(new CliVersion("1.2.4"), 3));
        for (int number = 0; number < variants.size(); number++) {
            OperationId id = operationId(number + 1);
            insertOperation(id, T1000, "alpha", REVISION_A, "alpha-write");
            AgentCliInstallRecord record = install(id, AgentCliProviderId.parse("claude"), variants.get(number));

            store.insert(record);

            assertThat(store.findByOperation(id)).contains(record);
        }
        assertThat(store.findByOperation(operationId(99))).isEmpty();
    }

    @Test
    @DisplayName("RC-A-027, RC-A-047: the remote status is replaced as a whole; an unknown operation is refused and a second insert for the same operation is refused")
    void theRemoteStatusIsRecorded() {
        OperationId id = operationId(1);
        insertOperation(id, T1000, "alpha", REVISION_A, "alpha-write");
        AgentCliInstallRecord record = install(id, AgentCliProviderId.parse("codex"), new RemoteStatus.NotReported());
        store.insert(record);

        RemoteStatus failed = new RemoteStatus.Failed(new CliVersion("2.0.0"), 1);
        assertThat(store.recordRemote(id, failed)).isEqualTo(failed);
        assertThat(store.findByOperation(id)).contains(install(id, AgentCliProviderId.parse("codex"), failed));
        RemoteStatus completed = new RemoteStatus.Known(RemoteState.COMPLETED, new CliVersion("2.0.0"), 0);
        assertThat(store.recordRemote(id, completed)).isEqualTo(completed);
        assertThat(jdbc.queryForObject("SELECT remote_error FROM agent_cli_request WHERE operation_id = ?", String.class, id.value())).isNull();
        assertThat(jdbc.queryForObject("SELECT remote_cleanup_failures FROM agent_cli_request WHERE operation_id = ?", Integer.class, id.value())).isNull();

        assertThatThrownBy(() -> store.recordRemote(operationId(98), completed)).isInstanceOfSatisfying(RequestRefusedException.class,
            e -> assertThat(e.code()).isEqualTo(RequestErrorCode.OPERATION_NOT_FOUND));
        assertThatThrownBy(() -> savepoints.run(() -> store.insert(record))).isInstanceOf(RequestRefusedException.class);
    }

    /**
     * RC-A-047 (the remote status half) in 3.0: 2.x refused a cleanup count of 0, -1 and the string '1' passed to {@code recordRemote}, and kept private fields of the
     * reported object out of the stored row. In 3.0 the count is an {@code int} of a typed record, so the string cannot be passed and a negative number is refused when
     * the record is built; a count of 0 is the absence of a failure (the record says so) and is stored as null, because the table accepts only a positive count or
     * null. A private field cannot be passed either: {@link RemoteStatus} has no place for one, and the table has a column for nothing but the install request. The
     * pending and running normalisation of the 2.x journal is the one recovery of the operation journal ({@code JdbcRestartRecoveryTest}).
     */
    @Test
    @DisplayName("RC-A-047: a failed install is stored as the error INSTALL_FAILED with its cleanup failures; a count of 0 is stored as none and a count below 1 is refused by the type and by the table; the row has no column for anything else")
    void aFailedRemoteStatusIsStoredWithItsErrorAndCount() {
        OperationId id = operationId(1);
        insertOperation(id, T1000, "alpha", REVISION_A, "alpha-write");
        store.insert(install(id, AgentCliProviderId.parse("codex"), new RemoteStatus.NotReported()));
        RemoteStatus failed = new RemoteStatus.Failed(new CliVersion("2.0.0"), 3);

        assertThat(store.recordRemote(id, failed)).isEqualTo(failed);

        assertThat(jdbc.queryForObject("SELECT remote_state FROM agent_cli_request WHERE operation_id = ?", String.class, id.value())).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("SELECT remote_error FROM agent_cli_request WHERE operation_id = ?", String.class, id.value())).isEqualTo(RemoteError.INSTALL_FAILED.name());
        assertThat(jdbc.queryForObject("SELECT remote_cleanup_failures FROM agent_cli_request WHERE operation_id = ?", Integer.class, id.value())).isEqualTo(3);
        assertThat(store.findByOperation(id)).contains(install(id, AgentCliProviderId.parse("codex"), failed));
        Map<String, Object> row = jdbc.queryForList("SELECT * FROM agent_cli_request WHERE operation_id = ?", id.value()).get(0);
        assertThat(row.keySet().stream().map(name -> name.toLowerCase(Locale.ROOT)).toList()).containsExactlyInAnyOrder("operation_id", "provider", "action", "package_name",
            "release_version", "release_tarball", "release_integrity", "release_digest", "expected_installation_revision", "intent_digest", "plan_expires_at", "remote_state",
            "remote_version", "remote_error", "remote_cleanup_failures");

        RemoteStatus noneFailedToClean = new RemoteStatus.Failed(new CliVersion("2.0.0"), 0);
        assertThat(store.recordRemote(id, noneFailedToClean)).isEqualTo(noneFailedToClean);
        assertThat(jdbc.queryForObject("SELECT remote_cleanup_failures FROM agent_cli_request WHERE operation_id = ?", Integer.class, id.value())).isNull();
        assertThatThrownBy(() -> new RemoteStatus.Failed(new CliVersion("2.0.0"), -1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RemoteStatus.Known(RemoteState.COMPLETED, new CliVersion("2.0.0"), -1)).isInstanceOf(IllegalArgumentException.class);
        for (int count : new int[] {0, -1}) {
            assertThatThrownBy(() -> savepoints.run(() -> jdbc.update("UPDATE agent_cli_request SET remote_cleanup_failures = ? WHERE operation_id = ?", count, id.value())))
                .as("the table refuses a stored count of %d", count).isInstanceOf(DataIntegrityViolationException.class);
        }
    }

    /** One install request of the CODEX binding of {@link #binding} whose operation was created at {@code createdAt}. */
    private void insertRequest(int number, Instant createdAt) {
        OperationId id = operationId(number);
        insertOperation(id, createdAt, "alpha", REVISION_A, "alpha-write");
        store.insert(install(id, AgentCliProviderId.parse("codex"), new RemoteStatus.NotReported()));
    }

    private List<OperationId> ids(AgentCliHistoryReader.Slice slice) {
        List<OperationId> ids = new ArrayList<>();
        for (AgentCliHistoryReader.Hit hit : slice.hits()) {
            ids.add(hit.install().operationId());
        }
        return ids;
    }

    private List<OperationId> numbers(int from, int to) {
        List<OperationId> ids = new ArrayList<>();
        for (int number = from; number >= to; number--) {
            ids.add(operationId(number));
        }
        return ids;
    }

    private OperationId operationId(int number) {
        return new OperationId(String.format("%048x", number));
    }

    private AgentCliBinding binding(AgentCliProviderId provider) {
        return new AgentCliBinding(provider, new TargetId("alpha"), TargetRevision.of(REVISION_A), new ProfileAlias("alpha-write"));
    }

    private AgentCliInstallRecord install(OperationId id, AgentCliProviderId provider, RemoteStatus remote) {
        String packageName = "codex".equals(provider.wire()) ? "@openai/codex" : "@anthropic-ai/claude-code";
        String shortName = "codex".equals(provider.wire()) ? "codex" : "claude-code";
        AgentRelease release = new AgentRelease(provider, packageName, "1.2.3", "https://registry.npmjs.org/" + packageName + "/-/" + shortName + "-1.2.3.tgz",
            "sha512-" + "A".repeat(86) + "==", new Digest("d".repeat(64)));
        return new AgentCliInstallRecord(id, provider, AgentCliAction.INSTALL, release, new Digest("e".repeat(64)), "sha256:" + "f".repeat(64), T2000, remote);
    }

    /** The operation row the install request references (the journal owns this table; the test inserts the NOT NULL columns with plain SQL). */
    private void insertOperation(OperationId id, Instant createdAt, String targetId, String targetRevision, String profile) {
        // The column holds the UTC date and time of the moment (D-0161), whatever zone the JVM of the run is in.
        LocalDateTime at = LocalDateTime.ofInstant(createdAt, ZoneOffset.UTC);
        jdbc.update("""
            INSERT INTO operation (id, request_key, owner_id, target_id, target_revision, kind, state, version, approval_mode, profile,
                input_digest, contract_digest, created_at, updated_at, recovery_reason, audit_failure)
            VALUES (?, ?, 'owner', ?, ?, 'AGENT_CLI', 'COMPLETED', 1, 'OWNER', ?, ?, ?, ?, ?, 'NONE', 'NONE')""",
            id.value(), "key-" + id.value(), targetId, targetRevision, profile, "9".repeat(64), "c".repeat(64), at, at);
    }
}
