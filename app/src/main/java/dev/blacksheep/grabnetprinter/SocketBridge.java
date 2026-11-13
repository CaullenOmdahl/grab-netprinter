package dev.blacksheep.grabnetprinter;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Locale;

/**
 * One TCP connection from a fake {@code BluetoothSocket} to the real LAN printer.
 *
 * <p>Lifecycle: a {@code SocketBridge} is created together with every fake Bluetooth socket
 * ({@code FakeBluetooth#newFakeSocket()}). When the app calls {@code BluetoothSocket.connect()}
 * the bridge opens a fresh TCP connection to {@code printer_host:printer_port}; the app then
 * receives {@link #getOutputStream()}, a thin proxy that forwards all bytes to the printer and
 * flushes after every write (receipt apps also flush explicitly, so this is cheap). The app
 * never sees data come back: {@link #getInputStream()} is an end-of-stream-only stream because
 * raw ESC/POS printing is write-only on the path GrabMerchant uses.</p>
 *
 * <p>There is deliberately <b>no automatic reconnect</b>: the Grab plugin owns the lifecycle and
 * calls {@code connect()} before each print job / after a failure, and each such call gets a
 * fresh TCP connection. Failures are surfaced as {@link IOException} so the app's existing
 * fallback paths (reflective {@code createRfcommSocket(1)}) behave normally.</p>
 *
 * <p>All socket state is guarded by {@link #lock}; the instance is usable from any app thread.</p>
 */
public final class SocketBridge {

    /** TCP connect timeout for a print job (SPEC: 8s). */
    public static final int CONNECT_TIMEOUT_MS = 8000;

    /** Number of leading bytes captured for the debug hex dump (SPEC: first 4 KiB). */
    private static final int DEBUG_SNAPSHOT_BYTES = 4096;

    /** Shared, stateless end-of-stream used for every fake socket's input stream. */
    private static final EmptyInputStream EMPTY_INPUT = new EmptyInputStream();

    private final String host;
    private final int port;
    private final Protocol protocol;
    private final boolean debugEnabled;

    private final Object lock = new Object();
    private final ProxyOutputStream proxy = new ProxyOutputStream();

    private final byte[] debugSnapshot = new byte[DEBUG_SNAPSHOT_BYTES];
    private int debugLength;
    private boolean debugLogged;

    private Socket socket;
    private OutputStream socketOut;
    private boolean connected;
    private long bytesWritten;

    public SocketBridge(Config cfg) {
        this.host = cfg.printerHost();
        this.port = cfg.printerPort();
        this.protocol = protocolFor(cfg.protocol());
        this.debugEnabled = cfg.debug();
    }

    /**
     * Opens a fresh TCP connection to the printer, replacing any previous one.
     *
     * @throws IOException when the printer is unreachable or the socket cannot be created;
     *                     the message always contains host and port for easy diagnosis
     */
    public void connect() throws IOException {
        synchronized (lock) {
            closeLocked(false);

            Socket fresh = new Socket();
            try {
                fresh.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
                fresh.setTcpNoDelay(true);
                OutputStream rawOut = fresh.getOutputStream();
                OutputStream framedOut = protocol.wrapOutput(rawOut);

                socket = fresh;
                socketOut = framedOut;
                connected = true;
                bytesWritten = 0;
                debugLength = 0;
                debugLogged = false;

                Log.d("TCP connected to " + describe() + " (protocol=" + protocol.id()
                        + ", timeout=" + CONNECT_TIMEOUT_MS + "ms)");
            } catch (Throwable t) {
                closeQuietly(fresh);
                socket = null;
                socketOut = null;
                connected = false;

                String reason = "GrabNetPrinter: cannot open TCP connection to "
                        + host + ":" + port + " (" + t + ")";
                if (Log.rateLimited("tcp-connect-fail", 2000)) {
                    Log.w(reason, t);
                } else {
                    Log.d(reason);
                }
                IOException io = new IOException(reason, t);
                throw io;
            }
        }
    }

    /** Closes the TCP connection; idempotent and silent. */
    public void close() {
        synchronized (lock) {
            closeLocked(true);
        }
    }

    /** True while the TCP connection is open. */
    public boolean isConnected() {
        synchronized (lock) {
            Socket current = socket;
            return connected && current != null && current.isConnected() && !current.isClosed();
        }
    }

    /**
     * Output stream handed to the app via the hooked {@code BluetoothSocket.getOutputStream()}.
     * Writes are forwarded immediately and flushed; callers may call {@link OutputStream#close()}
     * which closes the TCP connection (matching BluetoothSocket semantics closely enough for the
     * ESC/POS clients).
     */
    public OutputStream getOutputStream() {
        return proxy;
    }

    /** End-of-stream-only input stream; see class javadoc. */
    public InputStream getInputStream() {
        return EMPTY_INPUT;
    }

    /** Number of bytes forwarded to the printer on the current/last connection. */
    public long getBytesWritten() {
        synchronized (lock) {
            return bytesWritten;
        }
    }

    public String describe() {
        return host + ":" + port;
    }

    /** Shared empty stream for the (rare) case where no bridge is registered for a socket. */
    public static InputStream emptyInputStream() {
        return EMPTY_INPUT;
    }

    // ------------------------------------------------------------- internals

    private void closeLocked(boolean logIt) {
        Socket currentSocket = socket;
        OutputStream currentOut = socketOut;
        socket = null;
        socketOut = null;
        connected = false;

        if (debugEnabled && !debugLogged && debugLength > 0) {
            debugLogged = true;
            Log.hexDump("TX", debugSnapshot, 0, debugLength);
        }
        closeQuietly(currentOut);
        closeQuietly(currentSocket);
        if (logIt) {
            Log.d("TCP closed " + describe() + " after " + bytesWritten + " bytes");
        }
    }

    private void ensureOpen() throws IOException {
        if (!connected || socketOut == null) {
            throw new IOException("GrabNetPrinter: Bluetooth socket is not connected ("
                    + describe() + ")");
        }
    }

    private void captureForDebug(byte[] data, int offset, int length) {
        if (!debugEnabled || debugLogged) {
            return;
        }
        int room = DEBUG_SNAPSHOT_BYTES - debugLength;
        int copy = Math.min(length, room);
        if (copy > 0) {
            System.arraycopy(data, offset, debugSnapshot, debugLength, copy);
            debugLength += copy;
        }
        if (debugLength >= DEBUG_SNAPSHOT_BYTES) {
            debugLogged = true;
            Log.hexDump("TX", debugSnapshot, 0, debugLength);
        }
    }

    /**
     * Maps {@code protocol=} to an implementation. Unknown/future values degrade to raw with a
     * one-time log line so a config typo can never silence printing.
     */
    static Protocol protocolFor(String rawProtocol) {
        String id = rawProtocol == null
                ? Config.PROTOCOL_RAW
                : rawProtocol.trim().toLowerCase(Locale.US);
        if (id.isEmpty() || Config.PROTOCOL_RAW.equals(id)) {
            return Protocol.RAW;
        }
        if (Config.PROTOCOL_LPD.equals(id) || Config.PROTOCOL_IPP.equals(id)) {
            Log.once("protocol-" + id, "protocol=" + id + " not implemented yet; "
                    + "using raw TCP passthrough");
            return Protocol.RAW;
        }
        Log.once("protocol-unknown", "unknown protocol '" + id + "'; using raw TCP passthrough");
        return Protocol.RAW;
    }

    private static void closeQuietly(Object closeable) {
        if (!(closeable instanceof java.io.Closeable)) {
            return;
        }
        try {
            ((java.io.Closeable) closeable).close();
        } catch (Throwable ignored) {
            // best effort only
        }
    }

    /**
     * Forwards every write straight to the TCP stream and flushes immediately, then records the
     * leading bytes for the optional debug hex dump. Also guards against writes after close.
     */
    private final class ProxyOutputStream extends OutputStream {

        @Override
        public void write(int b) throws IOException {
            byte[] single = new byte[] {(byte) b};
            write(single, 0, 1);
        }

        @Override
        public void write(byte[] b) throws IOException {
            if (b == null) {
                throw new NullPointerException("buffer == null");
            }
            write(b, 0, b.length);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            if (b == null) {
                throw new NullPointerException("buffer == null");
            }
            if (off < 0 || len < 0 || off > b.length - len) {
                throw new IndexOutOfBoundsException("offset=" + off + " length=" + len
                        + " buffer=" + b.length);
            }
            if (len == 0) {
                return;
            }
            synchronized (lock) {
                ensureOpen();
                socketOut.write(b, off, len);
                socketOut.flush();
                bytesWritten += len;
                captureForDebug(b, off, len);
            }
        }

        @Override
        public void flush() throws IOException {
            synchronized (lock) {
                ensureOpen();
                socketOut.flush();
            }
        }

        @Override
        public void close() {
            SocketBridge.this.close();
        }
    }

    /**
     * Stream that always signals end-of-stream. GrabMerchant only writes to the socket; if some
     * SDK does read, returning -1 is the correct "no more data" answer and avoids blocking.
     */
    private static final class EmptyInputStream extends InputStream {

        @Override
        public int read() {
            return -1;
        }

        @Override
        public int read(byte[] b, int off, int len) {
            if (b == null) {
                throw new NullPointerException("buffer == null");
            }
            if (off < 0 || len < 0 || off > b.length - len) {
                throw new IndexOutOfBoundsException("offset=" + off + " length=" + len
                        + " buffer=" + b.length);
            }
            return len == 0 ? 0 : -1;
        }

        @Override
        public long skip(long n) {
            return 0;
        }

        @Override
        public int available() {
            return 0;
        }

        @Override
        public void close() {
            // nothing to release
        }
    }
}
