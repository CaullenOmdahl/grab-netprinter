package dev.blacksheep.grabnetprinter;

import de.robv.android.xposed.XposedBridge;

import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Small logging facade used by every class of the module.
 *
 * <p>Each line is written to the Xposed/LSPosed log ({@link XposedBridge#log(String)}, which
 * is also mirrored to logcat by LSPosed) and, best effort, appended to the file configured
 * via the {@code log=} key. File IO is deliberately dumb: open/append/close on every write.
 * The file is small (a few lines per print job), the code runs in the app process and any
 * IO error must never affect the app, so this trade-off is intentional. The file is rotated
 * once it grows past {@link #MAX_LOG_BYTES}.</p>
 *
 * <p>No method in this class ever throws. The class name intentionally shadows
 * {@code android.util.Log}; nothing in this package imports the platform logger.</p>
 */
public final class Log {

    public static final String TAG = "GrabNetPrinter";

    /** Rotate the module log once it exceeds this size (bytes). */
    private static final long MAX_LOG_BYTES = 4L * 1024 * 1024L;

    /** 128 bytes per hex-dump line keeps messages comfortably below logcat's line limit. */
    private static final int HEX_BYTES_PER_LINE = 128;

    private static final Object FILE_LOCK = new Object();
    private static final Set<String> ONCE_KEYS = new HashSet<String>();
    private static final Map<String, Long> RATE_LIMITS = new HashMap<String, Long>();

    private static final ThreadLocal<SimpleDateFormat> TIMESTAMP =
            new ThreadLocal<SimpleDateFormat>() {
                @Override
                protected SimpleDateFormat initialValue() {
                    return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US);
                }
            };

    /** null or empty -&gt; no file logging. */
    private static volatile String filePath;
    private static volatile boolean debugEnabled;

    private Log() {
    }

    /**
     * Configures the sink. Called once by {@code HookEntry} after the config has been validated.
     *
     * @param path         log file path, or {@code null}/{@code ""} to disable file logging
     * @param debugOn      whether {@link #d(String)} emits anything
     */
    public static void init(String path, boolean debugOn) {
        filePath = (path == null || path.isEmpty()) ? null : path;
        debugEnabled = debugOn;
    }

    /** Whether debug-level logging is enabled. */
    public static boolean isDebugEnabled() {
        return debugEnabled;
    }

    /** Informational line: always emitted (startup, connect, fatal fallbacks). */
    public static void i(String message) {
        write("I", message, null);
    }

    /** Debug line: only emitted when {@code debug=1}. */
    public static void d(String message) {
        if (debugEnabled) {
            write("D", message, null);
        }
    }

    /** Warning line; {@code throwable} may be null. */
    public static void w(String message, Throwable throwable) {
        write("W", message, throwable);
    }

    /** Warning line without a stack trace. */
    public static void w(String message) {
        write("W", message, null);
    }

    /** Error line; {@code throwable} may be null. */
    public static void e(String message, Throwable throwable) {
        write("E", message, throwable);
    }

    /**
     * Emits an info line only the first time {@code key} is seen in this process.
     * Used by noisy paths that would otherwise repeat identical messages.
     */
    public static void once(String key, String message) {
        if (once(key)) {
            i(message);
        }
    }

    /** Debug variant of {@link #once(String, String)}. */
    public static void debugOnce(String key, String message) {
        if (debugEnabled && once(key)) {
            write("D", message, null);
        }
    }

    /**
     * Returns {@code true} exactly once per unique key and process.
     * Thread safe; never blocks on IO.
     */
    public static boolean once(String key) {
        if (key == null) {
            return true;
        }
        synchronized (ONCE_KEYS) {
            return ONCE_KEYS.add(key);
        }
    }

    /**
     * Rate-limits noisy messages. Returns {@code true} when the caller may emit the message
     * now (i.e. at least {@code minIntervalMs} elapsed since the last call with the same key).
     */
    public static boolean rateLimited(String key, long minIntervalMs) {
        if (key == null) {
            return true;
        }
        long now = System.currentTimeMillis();
        synchronized (RATE_LIMITS) {
            Long last = RATE_LIMITS.get(key);
            if (last != null && now - last.longValue() < minIntervalMs) {
                return false;
            }
            RATE_LIMITS.put(key, Long.valueOf(now));
            return true;
        }
    }

    /** Formats {@code length} bytes of {@code data} (starting at {@code offset}) as lowercase hex. */
    public static String hex(byte[] data, int offset, int length) {
        if (data == null || length <= 0) {
            return "";
        }
        int from = Math.max(0, offset);
        int to = Math.min(data.length, from + length);
        if (to <= from) {
            return "";
        }
        StringBuilder sb = new StringBuilder((to - from) * 3);
        for (int i = from; i < to; i++) {
            if (i > from) {
                sb.append(' ');
            }
            int value = data[i] & 0xFF;
            if (value < 0x10) {
                sb.append('0');
            }
            sb.append(Integer.toHexString(value));
        }
        return sb.toString();
    }

    /**
     * Emits a hex dump as several lines prefixed with {@code prefix}, e.g.
     * {@code TX 0000: 1b 40 1b 61 01 ...}. Debug-only and capped by the caller
     * (the socket bridge only passes the first 4 KiB of a connection).
     */
    public static void hexDump(String prefix, byte[] data, int offset, int length) {
        if (!debugEnabled || data == null || length <= 0) {
            return;
        }
        String safePrefix = prefix == null ? "" : prefix;
        int from = Math.max(0, offset);
        int remaining = Math.min(length, data.length - from);
        if (remaining <= 0) {
            return;
        }
        for (int pos = 0; pos < remaining; pos += HEX_BYTES_PER_LINE) {
            int chunk = Math.min(HEX_BYTES_PER_LINE, remaining - pos);
            String index = String.format(Locale.US, "%04x", Integer.valueOf(pos));
            write("D", safePrefix + " " + index + ": " + hex(data, from + pos, chunk), null);
        }
    }

    // -------------------------------------------------------------- internals

    private static void write(String level, String message, Throwable throwable) {
        try {
            String line = timestamp() + " [" + Thread.currentThread().getName() + "] "
                    + level + "/" + TAG + ": " + message;
            try {
                XposedBridge.log(line);
            } catch (Throwable ignored) {
                // Xposed bridge unavailable (e.g. unit test): nothing we can do.
            }
            if (throwable != null) {
                try {
                    XposedBridge.log(throwable);
                } catch (Throwable ignored) {
                    // ignore
                }
            }
            appendToFile(line, throwable);
        } catch (Throwable ignored) {
            // Logging must never break the host app.
        }
    }

    private static String timestamp() {
        try {
            SimpleDateFormat format = TIMESTAMP.get();
            if (format != null) {
                return format.format(new Date());
            }
        } catch (Throwable ignored) {
            // fall through
        }
        return Long.toString(System.currentTimeMillis());
    }

    private static void appendToFile(String line, Throwable throwable) {
        String path = filePath;
        if (path == null) {
            return;
        }
        StringBuilder payload = new StringBuilder(line.length() + 64);
        payload.append(line).append('\n');
        if (throwable != null) {
            try {
                StringWriter sw = new StringWriter();
                PrintWriter pw = new PrintWriter(sw);
                throwable.printStackTrace(pw);
                pw.flush();
                String stack = sw.toString();
                payload.append(stack);
                if (!stack.isEmpty() && stack.charAt(stack.length() - 1) != '\n') {
                    payload.append('\n');
                }
            } catch (Throwable ignored) {
                payload.append(throwable.getClass().getName()).append('\n');
            }
        }

        byte[] bytes;
        try {
            bytes = payload.toString().getBytes("UTF-8");
        } catch (Throwable t) {
            return;
        }

        synchronized (FILE_LOCK) {
            FileOutputStream out = null;
            try {
                File file = new File(path);
                rotateIfNeeded(file);
                out = new FileOutputStream(file, true);
                out.write(bytes);
                out.flush();
            } catch (Throwable ignored) {
                // Best effort: /data/local/tmp may be unavailable to the app sandbox.
            } finally {
                if (out != null) {
                    try {
                        out.close();
                    } catch (Throwable ignored) {
                        // ignore
                    }
                }
            }
        }
    }

    /** Keeps the log file bounded: one rotated generation ({@code .1}) is retained. */
    private static void rotateIfNeeded(File file) {
        try {
            if (file.isFile() && file.length() > MAX_LOG_BYTES) {
                File old = new File(file.getParentFile(), file.getName() + ".1");
                if (old.exists()) {
                    //noinspection ResultOfMethodCallIgnored
                    old.delete();
                }
                //noinspection ResultOfMethodCallIgnored
                file.renameTo(old);
            }
        } catch (Throwable ignored) {
            // rotation is a nicety, not a requirement
        }
    }
}
