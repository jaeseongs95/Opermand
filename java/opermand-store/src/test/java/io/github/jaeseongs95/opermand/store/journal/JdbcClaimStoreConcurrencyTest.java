package io.github.jaeseongs95.opermand.store.journal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import io.github.jaeseongs95.opermand.app.journal.ClaimResult;
import io.github.jaeseongs95.opermand.domain.capacity.Capacity;
import io.github.jaeseongs95.opermand.domain.error.ExecutionAttempt;
import io.github.jaeseongs95.opermand.domain.error.OperationErrorCode;
import io.github.jaeseongs95.opermand.domain.error.RequestRefusedException;
import io.github.jaeseongs95.opermand.domain.operation.ConflictKey;
import io.github.jaeseongs95.opermand.domain.operation.Operation;
import io.github.jaeseongs95.opermand.domain.schedule.ConcurrencyLimits;
import io.github.jaeseongs95.opermand.domain.schedule.SaturationReason;

/**
 * S2-DB-06: claims from two threads. The slice does not wrap these tests in a transaction (each call runs inside a caller-owned transaction via {@link TransactionTemplate},
 * matching the service-layer boundary in Principles 4.4), so the rows are removed after each test.
 */
@JournalTest
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class JdbcClaimStoreConcurrencyTest {

    private static final ConcurrencyLimits ONE_AT_A_TIME = ConcurrencyLimits.defaults().withGlobalConcurrency(new Capacity(1)).withTargetConcurrency(new Capacity(5));
    private static final int ROUNDS = 8;

    private final JdbcOperationJournal journal;
    private final JdbcClaimStore claims;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final JournalFixture fx = new JournalFixture();

    JdbcClaimStoreConcurrencyTest(JdbcOperationJournal journal, JdbcClaimStore claims, JdbcTemplate jdbc, PlatformTransactionManager transactionManager) {
        this.journal = journal;
        this.claims = claims;
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(transactionManager);
    }

    @AfterEach
    void removeTheRows() {
        for (String table : List.of("operation_slot", "operation_fence", "operation_event", "operation_decision", "operation_command", "operation", "attachment")) {
            jdbc.update("DELETE FROM " + table);
        }
    }

    @Test
    @DisplayName("S2-DB-06: two threads claim two different operations at the same instant under a global limit of 1: exactly one is Claimed")
    void exactlyOneOfTwoDifferentOperationsIsClaimed() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            Operation a = fx.queued(journal, fx.command("a" + round, "alpha", "echo a", new ConflictKey.None()));
            Operation b = fx.queued(journal, fx.command("b" + round, "beta", "echo b", new ConflictKey.None()));

            List<ClaimResult> results = raceTwoClaims(a, b);

            assertThat(results.stream().filter(ClaimResult.Claimed.class::isInstance).count()).as("round " + round).isEqualTo(1);
            assertThat(results.stream().filter(result -> !(result instanceof ClaimResult.Claimed)).toList()).as("the loser of round " + round)
                .allSatisfy(result -> assertThat(result).isIn(new ClaimResult.Contended(), new ClaimResult.Saturated(SaturationReason.GLOBAL_CONCURRENCY)));
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM operation_slot WHERE scope = 'GLOBAL'", Long.class)).as("round " + round).isEqualTo(1);
            removeTheRows();
        }
    }

    @Test
    @DisplayName("S2-DB-06: two threads claim the same operation at the same instant: exactly one is Claimed, the other is told it is no longer queued or lost the race")
    void exactlyOneOfTwoClaimsOfTheSameOperationWins() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            Operation queued = fx.queued(journal, fx.command("same" + round, "alpha", "echo", new ConflictKey.None()));

            List<ClaimResult> results = raceTwoClaims(queued, queued);

            assertThat(results.stream().filter(ClaimResult.Claimed.class::isInstance).count()).as("round " + round).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM operation_slot WHERE scope = 'GLOBAL'", Long.class)).as("round " + round).isEqualTo(1);
            removeTheRows();
        }
    }

    @Test
    @DisplayName("S2-DB-06: two operations with the same conflict key claimed at the same instant: exactly one holds the fence")
    void exactlyOneOfTwoOperationsOfAConflictKeyIsClaimed() throws Exception {
        ConcurrencyLimits roomy = ConcurrencyLimits.defaults().withGlobalConcurrency(new Capacity(10)).withTargetConcurrency(new Capacity(10));
        for (int round = 0; round < ROUNDS; round++) {
            Operation a = fx.queued(journal, fx.command("fa" + round, "alpha", "echo a", new ConflictKey.Of("installer")));
            Operation b = fx.queued(journal, fx.command("fb" + round, "alpha", "echo b", new ConflictKey.Of("installer")));

            List<ClaimResult> results = raceTwoClaims(a, b, roomy);

            assertThat(results.stream().filter(ClaimResult.Claimed.class::isInstance).count()).as("round " + round).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM operation_fence", Long.class)).as("round " + round).isEqualTo(1);
            removeTheRows();
        }
    }

    private List<ClaimResult> raceTwoClaims(Operation first, Operation second) throws Exception {
        return raceTwoClaims(first, second, ONE_AT_A_TIME);
    }

    private List<ClaimResult> raceTwoClaims(Operation first, Operation second, ConcurrencyLimits limits) throws InterruptedException, ExecutionException, TimeoutException {
        // The pool is the test's own: the threads are what the dispatcher and a second instance would be.
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CyclicBarrier together = new CyclicBarrier(2);
            List<Future<ClaimResult>> futures = new ArrayList<>();
            for (Operation operation : List.of(first, second)) {
                futures.add(pool.submit(() -> {
                    together.await(10, TimeUnit.SECONDS);
                    try {
                        return tx.execute(status -> claims.claim(operation.id(), operation.version(), limits, JournalFixture.NOW.plusSeconds(10)));
                    } catch (DuplicateKeyException e) {
                        return new ClaimResult.Contended();
                    } catch (RequestRefusedException e) {
                        if (e.code() == OperationErrorCode.OPERATION_VERSION_CONFLICT) {
                            return new ClaimResult.NotQueued();
                        }
                        throw e;
                    }
                }));
            }
            List<ClaimResult> results = new ArrayList<>();
            for (Future<ClaimResult> future : futures) {
                results.add(future.get(30, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    private static final io.github.jaeseongs95.opermand.domain.operation.Admission QUEUED = io.github.jaeseongs95.opermand.domain.operation.Admission.of(
        io.github.jaeseongs95.opermand.domain.operation.DecisionReason.COMMAND_SET, io.github.jaeseongs95.opermand.domain.operation.OperationKind.COMMAND, 0L);

    private static io.github.jaeseongs95.opermand.domain.id.OperationId id(int number) {
        return new io.github.jaeseongs95.opermand.domain.id.OperationId(String.format("%048x", number));
    }

    private Object accept(String key, int number, Runnable beforeInsert) {
        io.github.jaeseongs95.opermand.domain.operation.NewOperation spec = fx.command(key, "alpha", "touch " + key, new io.github.jaeseongs95.opermand.domain.operation.ConflictKey.None());
        try {
            return tx.execute(status -> journal.accept(spec, QUEUED, id(number), JournalFixture.NOW, beforeInsert));
        } catch (DuplicateKeyException e) {
            return new RequestRefusedException(OperationErrorCode.OPERATION_VERSION_CONFLICT, true, ExecutionAttempt.NOT_ATTEMPTED, e);
        } catch (RequestRefusedException refused) {
            return refused;
        }
    }

    /** The answer of an insert or a write that lost its place: nothing was stored, nothing was attempted, and sending it again is safe (S-D0119-18, -19, -20). */
    private static void assertRetryableConflict(io.github.jaeseongs95.opermand.domain.error.RequestRefusedException refused) {
        assertThat(refused.code()).isEqualTo(io.github.jaeseongs95.opermand.domain.error.OperationErrorCode.OPERATION_VERSION_CONFLICT);
        assertThat(refused.retryable()).as("a client that does not resend what it cannot place would lose the request").isTrue();
        assertThat(refused.attempt()).isEqualTo(io.github.jaeseongs95.opermand.domain.error.ExecutionAttempt.NOT_ATTEMPTED);
    }

    private long queuedRows() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM operation WHERE state = 'QUEUED'", Long.class);
    }

    @Test
    @DisplayName("S-D0119-18 two queued inserts that took the same place meet at the unique ready position deterministically: one is stored, the other is told OPERATION_VERSION_CONFLICT, never OPERATION_STORE_FAILED")
    void twoQueuedInsertsForTheSamePlaceConflictDeterministically() throws Exception {
        // Both reach the check with the place they read, and neither inserts before both are there: the same place is taken twice, whatever the timing of the threads.
        CyclicBarrier met = new CyclicBarrier(2);
        Runnable meet = () -> {
            try {
                met.await(20, TimeUnit.SECONDS);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        };
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Object> first = pool.submit(() -> accept("same-place-0", 1, meet));
            Future<Object> second = pool.submit(() -> accept("same-place-1", 2, meet));
            List<Object> answers = List.of(first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS));

            assertThat(answers.stream().filter(answer -> answer instanceof io.github.jaeseongs95.opermand.app.journal.JournalInsert.Inserted)).hasSize(1);
            assertThat(answers.stream().filter(answer -> answer instanceof io.github.jaeseongs95.opermand.domain.error.RequestRefusedException)).hasSize(1)
                .allSatisfy(answer -> assertRetryableConflict((io.github.jaeseongs95.opermand.domain.error.RequestRefusedException) answer));
            assertThat(queuedRows()).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("S-D0119-19 an insert that commits between the count of the queue and the insert of another cannot take the queue over its room: the late one loses the place and is told OPERATION_VERSION_CONFLICT")
    void aCommitBetweenTheCountAndTheInsertCannotExceedTheRoom() throws Exception {
        // The room is one. B counts the queue (0) and waits inside the check; A counts (0), is stored and commits; B goes on. With the place read after the count B would take the
        // next place after A's and both would be queued (two in a queue of one); read before the count, B holds the place A took and loses it.
        Runnable roomOfOne = () -> {
            if (queuedRows() >= 1) {
                throw new io.github.jaeseongs95.opermand.domain.error.RequestRefusedException(io.github.jaeseongs95.opermand.domain.error.OperationErrorCode.OPERATION_QUEUE_FULL);
            }
        };
        java.util.concurrent.CountDownLatch counted = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch otherStored = new java.util.concurrent.CountDownLatch(1);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<Object> late = pool.submit(() -> accept("late-after-count", 2, () -> {
                roomOfOne.run();
                counted.countDown();
                try {
                    otherStored.await(20, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
            assertThat(counted.await(20, TimeUnit.SECONDS)).isTrue();

            Object early = accept("early-before-insert", 1, roomOfOne);
            otherStored.countDown();

            assertThat(early).isInstanceOf(io.github.jaeseongs95.opermand.app.journal.JournalInsert.Inserted.class);
            assertThat(late.get(30, TimeUnit.SECONDS)).isInstanceOfSatisfying(io.github.jaeseongs95.opermand.domain.error.RequestRefusedException.class,
                refused -> assertRetryableConflict(refused));
            assertThat(queuedRows()).as("the room of one was not exceeded").isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("S-D0119-20 an owner's approval reads its place before it counts the queue: an intake that commits in between cannot push the queue over its room, the approval is told a retryable conflict and the operation stays pending")
    void anApprovalCannotExceedTheRoomEither() throws Exception {
        io.github.jaeseongs95.opermand.domain.operation.Operation pending = fx.pending(journal, fx.command("waiting-for-the-owner", "alpha", "touch owner", new io.github.jaeseongs95.opermand.domain.operation.ConflictKey.None()));
        Runnable roomOfOne = () -> {
            if (queuedRows() >= 1) {
                throw new io.github.jaeseongs95.opermand.domain.error.RequestRefusedException(io.github.jaeseongs95.opermand.domain.error.OperationErrorCode.OPERATION_QUEUE_FULL);
            }
        };
        java.util.concurrent.CountDownLatch counted = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch intakeStored = new java.util.concurrent.CountDownLatch(1);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<Object> approval = pool.submit(() -> {
                try {
                    return tx.execute(status -> journal.save(pending.enqueue(new io.github.jaeseongs95.opermand.domain.operation.StartAuthority.Owner(), JournalFixture.NOW.plusSeconds(1)), pending.version(), () -> {
                        roomOfOne.run();
                        counted.countDown();
                        try {
                            intakeStored.await(20, TimeUnit.SECONDS);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    }));
                } catch (io.github.jaeseongs95.opermand.domain.error.RequestRefusedException refused) {
                    return refused;
                }
            });
            assertThat(counted.await(20, TimeUnit.SECONDS)).isTrue();

            Object intake = accept("intake-between", 1, roomOfOne);
            intakeStored.countDown();

            assertThat(intake).isInstanceOf(io.github.jaeseongs95.opermand.app.journal.JournalInsert.Inserted.class);
            assertThat(approval.get(30, TimeUnit.SECONDS)).isInstanceOfSatisfying(io.github.jaeseongs95.opermand.domain.error.RequestRefusedException.class,
                refused -> assertRetryableConflict(refused));
            assertThat(queuedRows()).as("the room of one was not exceeded").isEqualTo(1);
            assertThat(journal.find(pending.id()).orElseThrow().state()).as("the approval did not happen, so the owner may press the button again")
                .isEqualTo(io.github.jaeseongs95.opermand.domain.operation.OperationState.PENDING);
        } finally {
            pool.shutdownNow();
        }
    }
}
