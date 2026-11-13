package dev.blacksheep.grabnetprinter;

import java.io.IOException;
import java.io.OutputStream;

/**
 * Transport framing used on top of the raw TCP connection to the LAN printer.
 *
 * <p>Only the {@code raw} protocol is implemented today: ESC/POS bytes are forwarded verbatim
 * (the target printer at {@code 192.168.1.50:9100} speaks raw ESC/POS). The abstraction exists so
 * future protocols can be added without touching the Bluetooth hooks:</p>
 *
 * <ul>
 *   <li>{@code raw} - plain TCP byte stream (JetDirect port 9100). Implemented as
 *       {@link Raw}.</li>
 *   <li>{@code lpd} - RFC 1179: a control file must be sent before the data file, usually to
 *       port 515. A future implementation would wrap the output stream and issue the
 *       control-file handshake on connect or lazily on first write.</li>
 *   <li>{@code ipp} - IPP is request/response and needs HTTP framing plus a printer URI; a future
 *       implementation would have to translate ESC/POS bytes into IPP document jobs, which is
 *       out of scope for this module.</li>
 * </ul>
 *
 * <p>For both future protocols the {@code printer_port} config key selects the actual TCP port,
 * so no API change is required here beyond adding a new {@link Protocol} implementation and a
 * branch in {@code SocketBridge.protocolFor(String)}.</p>
 */
public interface Protocol {

    /** Lowercase identifier matching the {@code protocol=} config value. */
    String id();

    /**
     * Wraps the connected socket's output stream. Called once per TCP connection, on the thread
     * that opened the connection. The returned stream must tolerate being flushed after every
     * write (the bridge flushes each write anyway).
     */
    OutputStream wrapOutput(OutputStream raw) throws IOException;

    /** Shared instance of the only implemented protocol (raw TCP passthrough). */
    Protocol RAW = new Raw();

    /**
     * {@code protocol=raw}: plain TCP byte stream, ESC/POS bytes pass through untouched.
     *
     * <p>Nested in the interface (rather than a separate top-level file) so the small build and
     * test source lists only ever need {@code Protocol.java}.</p>
     */
    final class Raw implements Protocol {

        Raw() {
            // only Protocol.RAW is used; the constructor stays package-private
        }

        @Override
        public String id() {
            return Config.PROTOCOL_RAW;
        }

        @Override
        public OutputStream wrapOutput(OutputStream raw) {
            return raw;
        }
    }
}
