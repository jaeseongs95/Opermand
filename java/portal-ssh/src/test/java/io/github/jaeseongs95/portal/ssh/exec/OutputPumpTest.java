package io.github.jaeseongs95.portal.ssh.exec;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jaeseongs95.portal.app.output.StreamKind;
import io.github.jaeseongs95.portal.app.remote.SinkResult;
import io.github.jaeseongs95.portal.domain.operation.CaptureState;
import io.github.jaeseongs95.portal.domain.operation.CleanupState;
import io.github.jaeseongs95.portal.domain.operation.ExecutionOutcome;
import io.github.jaeseongs95.portal.domain.operation.ExitStatus;
import io.github.jaeseongs95.portal.domain.operation.OutputState;
import io.github.jaeseongs95.portal.domain.operation.SignalDelivery;
import io.github.jaeseongs95.portal.domain.operation.StreamCapture;
import io.github.jaeseongs95.portal.domain.operation.Truncation;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * The pump of one output stream and what the outcome says of a stream that was not read to its end (defect SSH_PORTAL-D-0053): an {@code IOException} of the read used to
 * be swallowed and the capture and the outcome were the same as for a stream that ended normally.
 */
@Timeout(60)
class OutputPumpTest {

    /** A stream that gives {@code first} bytes and then fails with an I/O error, as a channel does that ends abnormally. */
    private static final class FailingStream extends InputStream {
        private final byte[] first;
        private int position;

        FailingStream(byte[] first) {
            this.first = first;
        }

        @Override
        public int read() throws IOException {
            throw new UnsupportedOperationException("the pump reads blocks");
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            if (position >= first.length) {
                throw new IOException("injected: the channel ended abnormally");
            }
            int count = Math.min(length, first.length - position);
            System.arraycopy(first, position, buffer, offset, count);
            position += count;
            return count;
        }
    }

    private static OutputPump pumpOf(InputStream stream, AtomicLong accepted) {
        OutputPump pump = new OutputPump(StreamKind.STDOUT, stream, frame -> {
            accepted.addAndGet(frame.data().length);
            return new SinkResult.Accepted();
        }, 1_000_000);
        pump.start(Runnable::run);
        return pump;
    }

    @Test
    @DisplayName("SSH_PORTAL-D-0053 a stream that fails while it is read is done, says that it was not read to its end, and keeps what arrived before the failure")
    void aFailingReadIsKnown() {
        AtomicLong accepted = new AtomicLong();

        OutputPump pump = pumpOf(new FailingStream("partial".getBytes(java.nio.charset.StandardCharsets.UTF_8)), accepted);

        assertThat(pump.done()).isCompleted();
        assertThat(pump.readFailed()).isTrue();
        assertThat(pump.sinkFailed()).as("the sink did not fail: the read did").isFalse();
        assertThat(accepted.get()).isEqualTo("partial".length());
        assertThat(pump.capture()).isEqualTo(new StreamCapture.Captured(7, 7, 7, Truncation.COMPLETE));
    }

    @Test
    @DisplayName("SSH_PORTAL-D-0053 a stream that ends at its end is not a failed read: the capture of a normal end is unchanged")
    void aNormalEndIsNotAFailure() {
        AtomicLong accepted = new AtomicLong();

        OutputPump pump = pumpOf(new ByteArrayInputStream(new byte[100]), accepted);

        assertThat(pump.done()).isCompleted();
        assertThat(pump.readFailed()).isFalse();
        assertThat(accepted.get()).isEqualTo(100);
    }

    @Test
    @DisplayName("SSH_PORTAL-D-0053 an executor that refuses to read a stream leaves a pump that is done and says that the stream was not read, not one that saw an empty stream")
    void aRefusedReaderIsNotAnEmptyStream() {
        OutputPump pump = new OutputPump(StreamKind.STDERR, new ByteArrayInputStream(new byte[10]), frame -> new SinkResult.Accepted(), 1000);

        pump.start(task -> {
            throw new java.util.concurrent.RejectedExecutionException("injected: the executor is full");
        });

        assertThat(pump.done()).isCompleted();
        assertThat(pump.readFailed()).isTrue();
    }

    @Test
    @DisplayName("SSH_PORTAL-D-0053 an execution whose command exited and whose output was not read to its end is PARTIAL with a text that says so, never COMPLETE")
    void anOutcomeWithAnUnreadEndIsPartial() {
        StreamCapture captured = new StreamCapture.Captured(7, 7, 7, Truncation.COMPLETE);
        ExecOutcomes outcomes = new ExecOutcomes();
        ExecOutcomes.Facts normal = new ExecOutcomes.Facts(new ExitStatus.Exited(0), StopReason.NONE, true, true, false, SignalDelivery.NOT_SENT, captured, captured, false,
            Duration.ofSeconds(30), false);
        ExecOutcomes.Facts unread = new ExecOutcomes.Facts(new ExitStatus.Exited(0), StopReason.NONE, true, false, true, SignalDelivery.NOT_SENT, captured, captured, false,
            Duration.ofSeconds(30), false);

        ExecutionOutcome complete = outcomes.afterDispatch(normal);
        ExecutionOutcome partial = outcomes.afterDispatch(unread);

        assertThat(complete.outputState()).isEqualTo(OutputState.COMPLETE);
        assertThat(complete.texts()).doesNotContain("The output was not read to its end.");
        assertThat(partial.outputState()).isEqualTo(OutputState.PARTIAL);
        assertThat(partial.texts()).contains("The output was not read to its end.");
        assertThat(partial.capture()).isEqualTo(CaptureState.OK);
        assertThat(partial.cleanup()).isEqualTo(CleanupState.COMPLETE);
    }
}
