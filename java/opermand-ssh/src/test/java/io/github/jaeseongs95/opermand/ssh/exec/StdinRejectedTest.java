package io.github.jaeseongs95.opermand.ssh.exec;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jaeseongs95.opermand.app.remote.CommandKind;
import io.github.jaeseongs95.opermand.app.remote.ExecReply;
import io.github.jaeseongs95.opermand.app.remote.ExecutionLimits;
import io.github.jaeseongs95.opermand.app.remote.ExecutionReport;
import io.github.jaeseongs95.opermand.app.remote.PreparedCommand;
import io.github.jaeseongs95.opermand.app.remote.StdinBytes;
import io.github.jaeseongs95.opermand.domain.operation.ExecutionOutcome;
import io.github.jaeseongs95.opermand.domain.operation.ExecutionReason;
import io.github.jaeseongs95.opermand.domain.operation.RemoteState;
import io.github.jaeseongs95.opermand.ssh.testkit.AdapterFixture;
import io.github.jaeseongs95.opermand.ssh.testkit.CollectingSink;
import io.github.jaeseongs95.opermand.ssh.testkit.EmbeddedSshServer;
import io.github.jaeseongs95.opermand.ssh.testkit.ScriptedCommand;
import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.core.task.TaskExecutor;
import org.springframework.util.unit.DataSize;

/**
 * An executor that refuses to run the writer of the standard input (defect SSH_PORTAL-D-0052): the input is never written, so a command that waits for it waits for ever,
 * and the run used to go on as if the input had been delivered. The real {@link MinaRemoteExecutor} over the MINA test server; the task executor of the command executor
 * accepts the two readers of the output and refuses the third task, the writer of the input.
 */
@Timeout(120)
class StdinRejectedTest {

    private static final String USER = "tester";
    private static final String PASSWORD = "secret";
    private static final String ALIAS = "main";
    private static final Duration WAIT = Duration.ofSeconds(30);

    private EmbeddedSshServer server;
    private AdapterFixture adapter;

    @AfterEach
    void stop() throws IOException {
        if (adapter != null) {
            adapter.close();
        }
        if (server != null) {
            server.close();
        }
    }

    /** Accepts {@code accepted} tasks and refuses every later one, as an executor does that is shut down or full. */
    private static TaskExecutor refusingAfter(TaskExecutor real, int accepted) {
        AtomicInteger count = new AtomicInteger();
        return task -> {
            if (count.incrementAndGet() > accepted) {
                throw new RejectedExecutionException("injected: the executor is full");
            }
            real.execute(task);
        };
    }

    @Test
    @DisplayName("SSH_PORTAL-D-0052 an executor that refuses the writer of the standard input stops the run as INPUT_FAILED with the end of the command unknown; it is not a run whose input was delivered")
    void aRefusedInputWriterStopsTheRun() throws Exception {
        server = EmbeddedSshServer.builder().password(USER, PASSWORD).script("wait-for-input", ScriptedCommand.hangs()).start();
        adapter = AdapterFixture.start(AdapterFixture.defaultSettings(), real -> refusingAfter(real, 2));
        adapter.profiles().put(ALIAS, AdapterFixture.profileFor(server, USER));
        adapter.credentials().password(ALIAS, PASSWORD);
        PreparedCommand command = new PreparedCommand("wait-for-input", new StdinBytes("input".getBytes(java.nio.charset.StandardCharsets.UTF_8)), CommandKind.SOURCE);
        ExecutionLimits limits = new ExecutionLimits(Duration.ofSeconds(120), Duration.ofMillis(600), DataSize.ofMegabytes(1));

        CompletableFuture<ExecutionReport> running = CompletableFuture.supplyAsync(
            () -> adapter.executor().run(AdapterFixture.execution(ALIAS, command, limits, ExecReply.NOT_AWAITED), CollectingSink.accepting(), AdapterFixture.control()),
            adapter.async());
        ExecutionOutcome outcome = running.get(WAIT.toSeconds(), TimeUnit.SECONDS).outcome();

        assertThat(outcome.reason()).as("the run ended by the refused input, not by the deadline of 120 seconds").isEqualTo(ExecutionReason.INPUT_FAILED);
        assertThat(outcome.remoteState()).isEqualTo(RemoteState.UNKNOWN);
    }
}
