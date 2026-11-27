package io.github.jaeseongs95.opermand.ssh.exec;

import io.github.jaeseongs95.opermand.app.output.StreamFrame;
import io.github.jaeseongs95.opermand.app.output.StreamKind;
import io.github.jaeseongs95.opermand.app.remote.OutputSink;
import io.github.jaeseongs95.opermand.app.remote.SinkResult;
import io.github.jaeseongs95.opermand.domain.operation.StreamCapture;
import io.github.jaeseongs95.opermand.domain.operation.Truncation;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.task.TaskExecutor;

/**
 * Empties one output stream of a channel into the {@link OutputSink} (stage 3 design 3.4, defects D-0040 and D-0012's output cap). It reads the <em>inverted</em> stream of
 * the channel ({@code getInvertedOut}, {@code getInvertedErr}) on a thread of the injected executor, so a slow sink slows only this pump and never MINA's I/O thread; MINA
 * gives the window back as the bytes are read.
 *
 * <ul>
 * <li>The cap is per stream: the first {@code cap} bytes go to the sink, the rest are counted and dropped, and the capture says it was truncated.</li>
 * <li>A sink that answers {@link SinkResult.Failed} (or throws) loses its frame and every later one, but the pump keeps reading until the stream ends, so the channel is
 * emptied, released, and the next command on the session is not blocked. The capture is then reported as failed.</li>
 * <li>The bytes are not decoded: a multibyte character cut by a frame boundary reaches the sink as the bytes it was (the sink and the renderer decode, case S3-EX-13).</li>
 * </ul>
 */
final class OutputPump {

    private static final Logger LOG = LoggerFactory.getLogger(OutputPump.class);
    private static final int FRAME_BYTES = 8192;

    private final StreamKind kind;
    private final InputStream stream;
    private final OutputSink sink;
    private final long cap;
    private final CompletableFuture<Void> done = new CompletableFuture<>();
    private final AtomicLong received = new AtomicLong();
    private final AtomicLong retained = new AtomicLong();
    private final AtomicBoolean sinkFailed = new AtomicBoolean();
    private final AtomicBoolean readFailed = new AtomicBoolean();

    OutputPump(StreamKind kind, InputStream stream, OutputSink sink, long cap) {
        this.kind = kind;
        this.stream = stream;
        this.sink = sink;
        this.cap = cap;
    }

    /** Starts reading on the executor; the returned future completes when the stream has ended (or failed), whatever the sink did. */
    CompletableFuture<Void> start(TaskExecutor executor) {
        try {
            executor.execute(this::pump);
        } catch (RuntimeException e) {
            // The stream is never read: the output is not there, and the capture says so instead of looking like a stream that was empty (defect SSH_PORTAL-D-0053).
            readFailed.set(true);
            LOG.warn("The executor refused to read the {} stream of a channel; its output is lost", kind, e);
            done.complete(null);
        }
        return done;
    }

    /** Completes when the stream has ended. */
    CompletableFuture<Void> done() {
        return done;
    }

    /** True when the sink lost a frame. */
    boolean sinkFailed() {
        return sinkFailed.get();
    }

    /** True when reading the stream failed before its end: the bytes that arrived are all there is, and the output is not complete (a normal end is not a failure). */
    boolean readFailed() {
        return readFailed.get();
    }

    /** What was captured so far: the bytes received, the bytes the sink took, and whether the cap cut the stream. */
    StreamCapture capture() {
        long total = received.get();
        long kept = retained.get();
        Truncation truncation = !sinkFailed.get() && total > kept ? Truncation.TRUNCATED : Truncation.COMPLETE;
        return new StreamCapture.Captured(total, kept, kept, truncation);
    }

    private void pump() {
        byte[] buffer = new byte[FRAME_BYTES];
        try {
            while (true) {
                int count = stream.read(buffer);
                if (count < 0) {
                    break;
                }
                if (count > 0) {
                    take(buffer, count);
                }
            }
        } catch (IOException e) {
            // The channel ended abnormally: what was read is what there is, and the capture says that the stream was not read to its end (defect SSH_PORTAL-D-0053); the
            // supervisor decides what the end of the execution means.
            readFailed.set(true);
            LOG.debug("The {} stream of a channel ended with an I/O failure after {} bytes", kind, received.get(), e);
        } finally {
            done.complete(null);
        }
    }

    private void take(byte[] buffer, int count) {
        received.addAndGet(count);
        if (sinkFailed.get()) {
            return;
        }
        long room = cap - retained.get();
        int size = (int) Math.min(count, Math.max(room, 0));
        if (size <= 0) {
            return;
        }
        SinkResult result;
        try {
            result = sink.accept(new StreamFrame(kind, Arrays.copyOf(buffer, size)));
        } catch (RuntimeException e) {
            result = new SinkResult.Failed(e);
        }
        if (result instanceof SinkResult.Failed) {
            sinkFailed.set(true);
        } else {
            retained.addAndGet(size);
        }
    }
}
