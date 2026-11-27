package io.github.jaeseongs95.opermand.app.remote;

/**
 * Where a download puts the bytes it reads, in order (the use case appends them to the artifact payload; the adapter only reads and forwards). A sink that fails
 * answers {@link SinkResult.Failed}; the adapter stops reading and answers {@code DownloadResult.Failed(LOCAL_IO_FAILED)}, and the use case, which owns the sink,
 * knows the cause.
 */
public interface PayloadSink {

    /**
     * The size the target announced for the file, told once, before the first {@link #append}: the adapter has looked at the file and will read exactly that many bytes
     * (defect D-0012: a download reserves the largest file a transfer carries until the real size is known, and gives the rest back at once). A sink that needs no size
     * keeps this default; one that cannot take the size answers {@link SinkResult.Failed}, and the adapter ends the read without a byte, like a failed {@link #append}.
     */
    default SinkResult expect(long totalBytes) {
        return new SinkResult.Accepted();
    }

    SinkResult append(byte[] data);
}
