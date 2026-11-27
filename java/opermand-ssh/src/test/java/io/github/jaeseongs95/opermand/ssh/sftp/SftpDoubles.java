package io.github.jaeseongs95.opermand.ssh.sftp;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.channels.ClosedChannelException;
import java.nio.charset.StandardCharsets;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.UnaryOperator;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.sftp.client.SftpClient;
import org.apache.sshd.sftp.client.extensions.openssh.OpenSSHFsyncExtension;
import org.apache.sshd.sftp.common.SftpException;
import org.apache.sshd.sftp.common.extensions.openssh.FsyncExtensionParser;

/**
 * Faults the embedded server cannot make, put in front of a real MINA SFTP client with a plain JDK proxy (no mocking library): a server that changes a byte of the file
 * it was given, a flush ({@code fsync@openssh.com}) that is offered and refused, or not offered at all, a read stream whose {@code close()} fails while the channel is alive,
 * and a channel that ends right after the last byte (with or without a failing {@code close()}). Every other call goes to the real client unchanged.
 */
final class SftpDoubles {

    /** Counts the flushes that were tried. */
    static final class FlushCounter {
        private final AtomicInteger tried = new AtomicInteger();

        void flushTried() {
            tried.incrementAndGet();
        }

        int tried() {
            return tried.get();
        }
    }

    /** What a proxied call does, once the proxy has told it which method was called. */
    @FunctionalInterface
    private interface Interception {
        Object handle(Method method, Object[] arguments) throws Throwable;
    }

    /** A client that gives back the file as it is, except for its first byte (what a server with a bad disk would do), when the path ends with the suffix. */
    SftpClient tamperingWhenReading(SftpClient real, String pathSuffix) {
        return proxy(real, (method, arguments) -> {
            if ("read".equals(method.getName()) && arguments != null && arguments.length == 1 && arguments[0] instanceof String path && path.endsWith(pathSuffix)) {
                return new FlippedFirstByte(real.read(path));
            }
            if ("read".equals(method.getName()) && arguments != null && arguments.length == 5
                && arguments[0] instanceof SftpClient.Handle handle && handle.getPath().endsWith(pathSuffix)
                && arguments[1] instanceof Long fileOffset && arguments[2] instanceof byte[] dst && arguments[3] instanceof Integer dstOffset) {
                Object result = passThrough(real, method, arguments);
                if (result instanceof Integer count && count > 0 && fileOffset == 0L) {
                    dst[dstOffset] ^= 1;
                }
                return result;
            }
            return passThrough(real, method, arguments);
        });
    }

    /**
     * A client that hands out the file's bytes as they are, but whose read stream or handle throws an {@link IOException} from {@code close()} (the real resource is
     * closed first) while the channel stays alive: a close that fails by itself, after the last block. Only a read of a path that ends with the suffix is changed.
     */
    SftpClient failingToCloseWhenReading(SftpClient real, String pathSuffix) {
        return proxy(real, (method, arguments) -> {
            if ("read".equals(method.getName()) && arguments != null && arguments.length == 1 && arguments[0] instanceof String path && path.endsWith(pathSuffix)) {
                return new ClosedStream(real.read(path), true, new AtomicBoolean());
            }
            if ("open".equals(method.getName()) && arguments != null && arguments.length == 2 && arguments[0] instanceof String path && path.endsWith(pathSuffix)
                && arguments[1] instanceof java.util.Collection<?> modes && modes.equals(java.util.Set.of(SftpClient.OpenMode.Read))) {
                return new ClosingFails((SftpClient.CloseableHandle) passThrough(real, method, arguments), true, new AtomicBoolean());
            }
            return passThrough(real, method, arguments);
        });
    }

    /**
     * A client whose channel ends after the read: once the read stream or handle of a path with the suffix is closed, every {@code lstat} (the look at the file after the
     * read) runs {@code atTheLook} and then fails with a {@link ClosedChannelException}, what a client that was closed from outside answers. The bytes of the read are the
     * real ones; with {@code closeFails} the {@code close()} fails as well (the real resource is closed first), which is the close failure and the channel end together.
     */
    SftpClient channelEndingAfterTheRead(SftpClient real, String pathSuffix, boolean closeFails, Runnable atTheLook) {
        AtomicBoolean readClosed = new AtomicBoolean();
        return proxy(real, (method, arguments) -> {
            if ("read".equals(method.getName()) && arguments != null && arguments.length == 1 && arguments[0] instanceof String path && path.endsWith(pathSuffix)) {
                return new ClosedStream(real.read(path), closeFails, readClosed);
            }
            if ("open".equals(method.getName()) && arguments != null && arguments.length == 2 && arguments[0] instanceof String path && path.endsWith(pathSuffix)
                && arguments[1] instanceof java.util.Collection<?> modes && modes.equals(java.util.Set.of(SftpClient.OpenMode.Read))) {
                return new ClosingFails((SftpClient.CloseableHandle) passThrough(real, method, arguments), closeFails, readClosed);
            }
            if ("lstat".equals(method.getName()) && readClosed.get()) {
                atTheLook.run();
                throw new ClosedChannelException();
            }
            return passThrough(real, method, arguments);
        });
    }

    /**
     * A client whose channel ends at the first look at a path: the first {@code lstat} runs {@code atTheLook} and then fails with a {@link ClosedChannelException}, what a
     * client that was closed from outside answers (a stop that fires during the look closes the channel the same way). With a no-op {@code atTheLook} the loss has no
     * cause but the connection. Every later call goes to the real client.
     */
    SftpClient channelEndingAtTheFirstLook(SftpClient real, Runnable atTheLook) {
        AtomicBoolean ended = new AtomicBoolean();
        return proxy(real, (method, arguments) -> {
            if ("lstat".equals(method.getName()) && ended.compareAndSet(false, true)) {
                atTheLook.run();
                throw new ClosedChannelException();
            }
            return passThrough(real, method, arguments);
        });
    }

    /**
     * A client whose {@code close()} fails with an {@link IOException} after the real client was closed: a close that fails by itself while the work with the client is
     * done (the channel that only looked for the server program of an elevated transfer). Every other call goes to the real client.
     */
    SftpClient failingToClose(SftpClient real) {
        return proxy(real, (method, arguments) -> {
            if ("close".equals(method.getName()) && (arguments == null || arguments.length == 0)) {
                real.close();
                throw new IOException("injected: closing the channel failed");
            }
            return passThrough(real, method, arguments);
        });
    }

    /**
     * A client whose {@code stat} of a path that ends with the suffix is answered with the SFTP status: a server that refuses to say whether a file is there. Every other
     * call goes to the real client.
     */
    SftpClient refusingToStat(SftpClient real, String pathSuffix, int status) {
        return proxy(real, (method, arguments) -> {
            if ("stat".equals(method.getName()) && arguments != null && arguments.length == 1 && arguments[0] instanceof String path && path.endsWith(pathSuffix)) {
                throw new SftpException(status, "injected: the status of " + path + " is refused");
            }
            return passThrough(real, method, arguments);
        });
    }

    /**
     * A client whose request to set the attributes of an open file (a handle: the mode of a file just made) is answered with the SFTP status, as a server does that cannot
     * give the new file its mode. Every other call goes to the real client.
     */
    SftpClient refusingToSetTheModeOfAHandle(SftpClient real, int status) {
        return proxy(real, (method, arguments) -> {
            if ("setStat".equals(method.getName()) && arguments != null && arguments.length == 2 && arguments[0] instanceof SftpClient.Handle) {
                throw new SftpException(status, "injected: the mode of the file is refused");
            }
            return passThrough(real, method, arguments);
        });
    }

    /**
     * A client that runs {@code atTheChange} when the attributes of a path are set by name (the change of a mode: a cancel or a time limit that comes in the middle of it)
     * and then does the real call. Every other call goes to the real client.
     */
    SftpClient runningWhenTheModeIsChanged(SftpClient real, Runnable atTheChange) {
        return proxy(real, (method, arguments) -> {
            if ("setStat".equals(method.getName()) && arguments != null && arguments.length == 2 && arguments[0] instanceof String) {
                atTheChange.run();
            }
            return passThrough(real, method, arguments);
        });
    }

    /**
     * A client whose {@code lstat} is answered with the SFTP status once the attributes of a path were set by name: a server that took the change of the mode and then
     * refuses to say what the file is now (the look that reads the mode back). Every other call goes to the real client.
     */
    SftpClient refusingToLookAfterTheModeIsSet(SftpClient real, int status) {
        AtomicBoolean set = new AtomicBoolean();
        return proxy(real, (method, arguments) -> {
            if ("setStat".equals(method.getName()) && arguments != null && arguments.length == 2 && arguments[0] instanceof String) {
                Object result = passThrough(real, method, arguments);
                set.set(true);
                return result;
            }
            if ("lstat".equals(method.getName()) && set.get()) {
                throw new SftpException(status, "injected: the status of the file is refused after its mode was set");
            }
            return passThrough(real, method, arguments);
        });
    }

    /**
     * A client whose handle for reading a path that ends with the suffix (the look that reads a replaced file back) fails in {@code close()} after the real handle was
     * closed: a close that fails by itself after the file was read and confirmed. Every other call goes to the real client.
     */
    SftpClient failingToCloseTheReadHandle(SftpClient real, String pathSuffix) {
        return proxy(real, (method, arguments) -> {
            if ("open".equals(method.getName()) && arguments != null && arguments.length == 2 && arguments[0] instanceof String path && path.endsWith(pathSuffix)
                && arguments[1] instanceof java.util.Collection<?> modes && modes.equals(java.util.Set.of(SftpClient.OpenMode.Read))) {
                return new ClosingFails((SftpClient.CloseableHandle) passThrough(real, method, arguments));
            }
            return passThrough(real, method, arguments);
        });
    }

    /**
     * A client that runs {@code beforeOpen} immediately before opening a path ending with {@code pathSuffix} for reading (simulating a TOCTOU file or symlink swap
     * between {@code lstat} and {@code open}, defect SSH_PORTAL-D-0082). Every other call goes to the real client.
     */
    SftpClient runningBeforeReadOpen(SftpClient real, String pathSuffix, Runnable beforeOpen) {
        AtomicBoolean ran = new AtomicBoolean();
        return proxy(real, (method, arguments) -> {
            if ("open".equals(method.getName()) && arguments != null && arguments.length == 2 && arguments[0] instanceof String path && path.endsWith(pathSuffix)
                && arguments[1] instanceof java.util.Collection<?> modes && modes.equals(java.util.Set.of(SftpClient.OpenMode.Read))
                && ran.compareAndSet(false, true)) {
                beforeOpen.run();
            }
            return passThrough(real, method, arguments);
        });
    }

    /**
     * A client that throws {@link AssertionError} if any path-based {@code setStat(String, ...)}, {@code write(String, ...)}, or {@code read(String)} is invoked on a
     * path ending with {@code pathSuffix}, verifying that all temporary-file staging runs exclusively over the pinned handle (defect SSH_PORTAL-D-0082).
     */
    SftpClient forbiddingPathOperationsOn(SftpClient real, String pathSuffix) {
        return proxy(real, (method, arguments) -> {
            if (arguments != null && arguments.length >= 1 && arguments[0] instanceof String path && path.endsWith(pathSuffix)) {
                String name = method.getName();
                if ("setStat".equals(name) || "write".equals(name) || "read".equals(name)) {
                    throw new AssertionError("Unexpected path-based SFTP " + name + " on " + path + " (SSH_PORTAL-D-0082)");
                }
            }
            return passThrough(real, method, arguments);
        });
    }

    /**
     * A client whose channel ends when the temporary file of an upload is created: the open of a path of the portal's temporary files with the exclusive-create mode runs
     * {@code atTheOpen} and then fails with a {@link ClosedChannelException}, what a client that was closed from outside (by a cancel or the time limit) answers. Every other
     * call goes to the real client.
     */
    SftpClient channelEndingAtTheTemporaryFile(SftpClient real, Runnable atTheOpen) {
        return proxy(real, (method, arguments) -> {
            if ("open".equals(method.getName()) && arguments != null && arguments.length == 2 && arguments[0] instanceof String path && path.contains(".opermand-")
                && arguments[1] instanceof java.util.Collection<?> modes && modes.contains(SftpClient.OpenMode.Exclusive)) {
                atTheOpen.run();
                throw new ClosedChannelException();
            }
            return passThrough(real, method, arguments);
        });
    }

    /** A handle of the real client whose {@code close()} closes the real handle first, marks {@code closed}, and optionally throws. */
    private static final class ClosingFails extends SftpClient.CloseableHandle {
        private final SftpClient.CloseableHandle real;
        private final boolean failOnClose;
        private final AtomicBoolean closed;

        ClosingFails(SftpClient.CloseableHandle real) {
            this(real, true, new AtomicBoolean());
        }

        ClosingFails(SftpClient.CloseableHandle real, boolean failOnClose, AtomicBoolean closed) {
            super(real.getPath(), real.getIdentifier());
            this.real = real;
            this.failOnClose = failOnClose;
            this.closed = closed;
        }

        @Override
        public boolean isOpen() {
            return real.isOpen();
        }

        @Override
        public void close() throws IOException {
            real.close();
            closed.set(true);
            if (failOnClose) {
                throw new IOException("injected: closing the read handle failed");
            }
        }
    }

    /** Opens the real client of a session and puts a fault in front of it. */
    static final class FaultyChannels extends SftpChannels {
        private final UnaryOperator<SftpClient> fault;

        FaultyChannels(UnaryOperator<SftpClient> fault) {
            this.fault = fault;
        }

        @Override
        public SftpClient open(ClientSession session) throws IOException {
            return fault.apply(super.open(session));
        }
    }

    /** A client whose server offers {@code fsync@openssh.com} (or does not, as chosen) and whose flush always fails; the counter says how often it was tried. */
    SftpClient refusingToFlush(SftpClient real, boolean offered, FlushCounter counter) {
        return proxy(real, (method, arguments) -> {
            if ("getServerExtensions".equals(method.getName())) {
                NavigableMap<String, byte[]> extensions = new TreeMap<>(real.getServerExtensions());
                if (offered) {
                    extensions.put(FsyncExtensionParser.NAME, "1".getBytes(StandardCharsets.US_ASCII));
                } else {
                    extensions.remove(FsyncExtensionParser.NAME);
                }
                return extensions;
            }
            if ("getExtension".equals(method.getName()) && arguments != null && arguments.length == 1 && OpenSSHFsyncExtension.class.equals(arguments[0])) {
                return failingFlush(counter);
            }
            return passThrough(real, method, arguments);
        });
    }

    private OpenSSHFsyncExtension failingFlush(FlushCounter counter) {
        return (OpenSSHFsyncExtension) Proxy.newProxyInstance(OpenSSHFsyncExtension.class.getClassLoader(), new Class<?>[] {OpenSSHFsyncExtension.class},
            (proxy, method, arguments) -> switch (method.getName()) {
                case "fsync" -> {
                    counter.flushTried();
                    throw new IOException("injected: the flush is refused");
                }
                case "isSupported" -> Boolean.TRUE;
                case "getName" -> FsyncExtensionParser.NAME;
                case "equals" -> proxy == arguments[0];
                case "hashCode" -> System.identityHashCode(proxy);
                default -> "failing flush";
            });
    }

    private SftpClient proxy(SftpClient real, Interception interception) {
        return (SftpClient) Proxy.newProxyInstance(SftpClient.class.getClassLoader(), new Class<?>[] {SftpClient.class},
            (proxy, method, arguments) -> interception.handle(method, arguments));
    }

    private Object passThrough(SftpClient real, Method method, Object[] arguments) throws Throwable {
        try {
            return method.invoke(real, arguments);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    /**
     * Every byte is given as it is. Closing the stream closes the real one, tells the flag that it was closed, and then fails when it is asked to. The failure is the
     * stream's own: it says nothing about the channel, which stays alive unless another part of the double ends it.
     */
    private static final class ClosedStream extends FilterInputStream {
        private final boolean failOnClose;
        private final AtomicBoolean closed;

        ClosedStream(InputStream in, boolean failOnClose, AtomicBoolean closed) {
            super(in);
            this.failOnClose = failOnClose;
            this.closed = closed;
        }

        @Override
        public void close() throws IOException {
            super.close();
            closed.set(true);
            if (failOnClose) {
                throw new IOException("injected: closing the read stream failed");
            }
        }
    }

    /** The first byte that is read is changed; the rest is as it was. */
    private static final class FlippedFirstByte extends FilterInputStream {
        private boolean flipped;

        FlippedFirstByte(InputStream in) {
            super(in);
        }

        @Override
        public int read() throws IOException {
            int value = super.read();
            if (value >= 0 && !flipped) {
                flipped = true;
                return value ^ 1;
            }
            return value;
        }

        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException {
            int count = super.read(bytes, offset, length);
            if (count > 0 && !flipped) {
                flipped = true;
                bytes[offset] ^= 1;
            }
            return count;
        }
    }
}
