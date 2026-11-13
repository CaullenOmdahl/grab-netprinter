package dev.blacksheep.grabnetprinter;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Immutable configuration of the module.
 *
 * <p>Configuration has two sources, merged in this order:</p>
 * <ol>
 *   <li>a plain {@code key=value} file at {@link #DEFAULT_PATH} (the optional KernelSU/Magisk
 *       module seeds it at boot; advanced users can edit it directly), and</li>
 *   <li>values from the GrabNetPrinter settings screen, read through
 *       {@link ConfigProviderClient}. Only keys the user actually saved there override the
 *       file, so a legacy file-only setup keeps working exactly as before.</li>
 * </ol>
 *
 * <p>The parser is deliberately forgiving because both sources are user editable: comments
 * ({@code #} and {@code //} full-line), blank lines, extra whitespace, unknown keys, missing
 * keys and missing/unreadable files are all tolerated. Invalid values fall back to compiled
 * defaults and are collected in {@link #warnings()} so the caller can log them once.</p>
 *
 * <p>Defaults:</p>
 * <pre>
 * enabled=1
 * targets=com.grab.merchant
 * printer_host=            (empty -&gt; module disables itself)
 * printer_port=9100
 * protocol=raw
 * fake_mac=02:00:00:00:00:01
 * fake_name=LAN Printer
 * discovery=1
 * discovery_broadcast=1    (optional fallback switch, defaults to 1)
 * debug=0
 * log=/data/local/tmp/grab-netprinter.log
 * </pre>
 *
 * <p>Thread safety: instances are immutable after construction except that the most
 * recently loaded instance is published through a volatile static field.</p>
 */
public final class Config {

    /** Optional legacy config file, seeded by the KernelSU/Magisk module. */
    public static final String DEFAULT_PATH = "/data/local/tmp/grab-netprinter.conf";

    /** Default target package. */
    public static final String DEFAULT_TARGET = "com.grab.merchant";

    /** Locally administered, unicast MAC address - safe to fabricate. */
    public static final String DEFAULT_FAKE_MAC = "02:00:00:00:00:01";

    /** Bluetooth name shown for the fake printer. */
    public static final String DEFAULT_FAKE_NAME = "LAN Printer";

    /** Where the module appends its own log lines (empty disables file logging). */
    public static final String DEFAULT_LOG_PATH = "/data/local/tmp/grab-netprinter.log";

    /** Raw ESC/POS over TCP (JetDirect style). */
    public static final int DEFAULT_PRINTER_PORT = 9100;

    public static final String PROTOCOL_RAW = "raw";
    public static final String PROTOCOL_LPD = "lpd";
    public static final String PROTOCOL_IPP = "ipp";

    /** Strict MAC check: six hex octets separated by colons. */
    private static final Pattern MAC_PATTERN =
            Pattern.compile("^(?:[0-9a-fA-F]{2}:){5}[0-9a-fA-F]{2}$");

    private static volatile Config current;

    private final boolean enabled;
    private final List<String> targets;
    private final String printerHost;
    private final int printerPort;
    private final String protocol;
    private final String fakeMac;
    private final String fakeName;
    private final boolean discovery;
    private final boolean discoveryBroadcast;
    private final boolean debug;
    private final String logPath;
    private final List<String> warnings;

    private Config(boolean enabled, List<String> targets, String printerHost, int printerPort,
                   String protocol, String fakeMac, String fakeName, boolean discovery,
                   boolean discoveryBroadcast, boolean debug, String logPath,
                   List<String> warnings) {
        this.enabled = enabled;
        this.targets = Collections.unmodifiableList(new ArrayList<String>(targets));
        this.printerHost = printerHost;
        this.printerPort = printerPort;
        this.protocol = protocol;
        this.fakeMac = fakeMac;
        this.fakeName = fakeName;
        this.discovery = discovery;
        this.discoveryBroadcast = discoveryBroadcast;
        this.debug = debug;
        this.logPath = logPath;
        this.warnings = Collections.unmodifiableList(new ArrayList<String>(warnings));
    }

    /** System property that overrides the config path (used by the offline JVM test harness). */
    public static final String PATH_PROPERTY = "gnp.config";

    /**
     * Reads the legacy config file and publishes the result as the process-wide config.
     * Never throws: unreadable files and bad values degrade to defaults.
     *
     * <p>The path is {@link #DEFAULT_PATH}, unless the {@value #PATH_PROPERTY} system property
     * names a different file. That override lets the parser be exercised in a plain JVM
     * (offline test harness) without touching the Android filesystem layout.</p>
     */
    public static Config load() {
        String path = DEFAULT_PATH;
        try {
            String override = System.getProperty(PATH_PROPERTY);
            if (override != null && !override.trim().isEmpty()) {
                path = override.trim();
            }
        } catch (Throwable ignored) {
            // SecurityException etc.: stick with the default path
        }
        return loadFrom(path);
    }

    /** Reads the config from an explicit path and publishes it as the process-wide config. */
    public static Config loadFrom(String path) {
        return loadFrom(path, null);
    }

    /**
     * Reads the config file, applies the given {@code overrides} (typically the in-app settings
     * exposed by {@link ConfigProviderClient}) and publishes the result process-wide.
     *
     * @param path      legacy config file path, or {@code null}/empty to skip it
     * @param overrides keys that take precedence over the file; {@code null} means none
     */
    public static Config loadFrom(String path, Map<String, String> overrides) {
        Config parsed = parse((path == null || path.isEmpty()) ? null : new File(path), overrides);
        current = parsed;
        return parsed;
    }

    /**
     * Returns the config loaded by {@link #load()}, loading defaults on first use.
     * Hook callbacks only reach this after {@code HookEntry} has loaded the config.
     */
    public static Config get() {
        Config cfg = current;
        if (cfg != null) {
            return cfg;
        }
        return load();
    }

    /** True when the package name matches one of the configured targets ("*" = all). */
    public boolean isTarget(String packageName) {
        if (packageName == null || packageName.isEmpty()) {
            return false;
        }
        for (String target : targets) {
            if ("*".equals(target) || target.equals(packageName)) {
                return true;
            }
        }
        return false;
    }

    /** The module is only useful when it is enabled and a printer host is configured. */
    public boolean isActive() {
        return enabled && !printerHost.isEmpty();
    }

    /** One-line summary suitable for the startup log (no secrets are ever present). */
    public String summary() {
        return "enabled=" + (enabled ? 1 : 0)
                + " targets=" + join(targets, ",")
                + " printer=" + printerHost + ":" + printerPort
                + " protocol=" + protocol
                + " fake_mac=" + fakeMac
                + " fake_name=\"" + fakeName + "\""
                + " discovery=" + (discovery ? 1 : 0)
                + " discovery_broadcast=" + (discoveryBroadcast ? 1 : 0)
                + " debug=" + (debug ? 1 : 0)
                + " log=" + logPath;
    }

    // ------------------------------------------------------------------ getters

    public boolean enabled() {
        return enabled;
    }

    public List<String> targets() {
        return targets;
    }

    public String printerHost() {
        return printerHost;
    }

    public int printerPort() {
        return printerPort;
    }

    public String protocol() {
        return protocol;
    }

    public String fakeMac() {
        return fakeMac;
    }

    public String fakeName() {
        return fakeName;
    }

    public boolean discovery() {
        return discovery;
    }

    public boolean discoveryBroadcast() {
        return discoveryBroadcast;
    }

    public boolean debug() {
        return debug;
    }

    public String logPath() {
        return logPath;
    }

    /** Non-fatal parse problems; {@code HookEntry} logs these once at startup. */
    public List<String> warnings() {
        return warnings;
    }

    // ------------------------------------------------------------ parsing

    /**
     * Reads a {@code key=value} file into a map (comments/blank lines/unknown keys tolerated).
     * Returns an empty map when the file is missing or unreadable. Never throws.
     */
    public static Map<String, String> readFile(File file) {
        Map<String, String> values = new HashMap<String, String>();
        if (file == null || !file.isFile() || !file.canRead()) {
            return values;
        }
        List<String> ignored = new ArrayList<String>();
        BufferedReader reader = null;
        try {
            reader = new BufferedReader(
                    new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8));
            String line;
            int lineNo = 0;
            while ((line = reader.readLine()) != null) {
                lineNo++;
                parseLine(line, lineNo, values, ignored);
            }
        } catch (Throwable readError) {
            // partial read is fine
        } finally {
            closeQuietly(reader);
        }
        return values;
    }

    /**
     * Builds a config from an already-merged key/value map (used by {@code ConfigReader} which
     * combines the target app's own config file, the settings provider and the legacy file).
     */
    public static Config fromKeyValues(Map<String, String> values, List<String> warnings) {
        return build(new HashMap<String, String>(values),
                warnings == null ? new ArrayList<String>() : warnings);
    }

    private static Config parse(File file, Map<String, String> overrides) {
        List<String> warnings = new ArrayList<String>();
        Map<String, String> values = new HashMap<String, String>();
        String pathLabel = file == null ? "(none)" : file.getAbsolutePath();
        boolean fileUsable = file != null && file.isFile() && file.canRead();

        if (!fileUsable) {
            if (overrides != null && !overrides.isEmpty()) {
                warnings.add("legacy config " + pathLabel
                        + " is absent; using in-app settings");
            } else {
                warnings.add("config " + pathLabel
                        + " is missing or unreadable; using compiled defaults");
            }
        } else {
            BufferedReader reader = null;
            try {
                reader = new BufferedReader(
                        new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8));
                String line;
                int lineNo = 0;
                while ((line = reader.readLine()) != null) {
                    lineNo++;
                    parseLine(line, lineNo, values, warnings);
                }
            } catch (Throwable t) {
                // Partial reads still leave usable values in the map.
                warnings.add("error reading " + pathLabel + ": " + t);
            } finally {
                closeQuietly(reader);
            }
        }

        if (overrides != null) {
            for (Map.Entry<String, String> entry : overrides.entrySet()) {
                if (entry.getKey() != null && entry.getValue() != null) {
                    values.put(entry.getKey().toLowerCase(Locale.US), entry.getValue());
                }
            }
        }

        return build(values, warnings);
    }

    private static void parseLine(String line, int lineNo, Map<String, String> values,
                                  List<String> warnings) {
        String trimmed = line.trim();
        if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("//")) {
            return; // blank line or full-line comment
        }
        int eq = trimmed.indexOf('=');
        if (eq < 0) {
            warnings.add("line " + lineNo + ": ignoring line without '='");
            return;
        }
        String key = trimmed.substring(0, eq).trim().toLowerCase(Locale.US);
        String value = trimmed.substring(eq + 1).trim();
        if (key.isEmpty()) {
            warnings.add("line " + lineNo + ": ignoring line with empty key");
            return;
        }
        // Last assignment wins; unknown keys are silently tolerated (forward compatibility).
        values.put(key, value);
    }

    private static Config build(Map<String, String> values, List<String> warnings) {
        boolean enabled = parseBoolean(values.get("enabled"), true, "enabled", warnings);

        List<String> targets = new ArrayList<String>();
        String rawTargets = values.get("targets");
        if (rawTargets != null) {
            for (String part : rawTargets.split("[,;\\s]+")) {
                String target = part.trim();
                if (!target.isEmpty()) {
                    targets.add(target);
                }
            }
        }
        if (targets.isEmpty()) {
            targets.add(DEFAULT_TARGET);
        }

        // Empty printer_host is meaningful: the module stays completely passive.
        String printerHost = values.containsKey("printer_host")
                ? values.get("printer_host").trim()
                : "";
        if (printerHost.isEmpty()) {
            warnings.add("printer_host is empty; module will not hook anything");
        }

        int printerPort = parseInt(values.get("printer_port"), DEFAULT_PRINTER_PORT, 1, 65535,
                "printer_port", warnings);

        String protocol = values.get("protocol");
        protocol = protocol == null ? PROTOCOL_RAW : protocol.trim().toLowerCase(Locale.US);
        if (protocol.isEmpty()) {
            protocol = PROTOCOL_RAW;
        }
        if (!PROTOCOL_RAW.equals(protocol) && !PROTOCOL_LPD.equals(protocol)
                && !PROTOCOL_IPP.equals(protocol)) {
            warnings.add("unknown protocol '" + protocol + "'; using " + PROTOCOL_RAW);
            protocol = PROTOCOL_RAW;
        }

        String fakeMac = values.get("fake_mac");
        fakeMac = fakeMac == null ? DEFAULT_FAKE_MAC : fakeMac.trim();
        if (!MAC_PATTERN.matcher(fakeMac).matches()) {
            warnings.add("invalid fake_mac '" + fakeMac + "'; using " + DEFAULT_FAKE_MAC);
            fakeMac = DEFAULT_FAKE_MAC;
        }
        fakeMac = fakeMac.toUpperCase(Locale.US);

        String fakeName = values.get("fake_name");
        if (fakeName != null) {
            fakeName = fakeName.trim();
        }
        if (fakeName == null || fakeName.isEmpty()) {
            if (fakeName != null) {
                warnings.add("empty fake_name; using \"" + DEFAULT_FAKE_NAME + "\"");
            }
            fakeName = DEFAULT_FAKE_NAME;
        }

        boolean discovery = parseBoolean(values.get("discovery"), true, "discovery", warnings);
        boolean discoveryBroadcast = parseBoolean(values.get("discovery_broadcast"), true,
                "discovery_broadcast", warnings);
        boolean debug = parseBoolean(values.get("debug"), false, "debug", warnings);

        // An explicitly empty log= disables file logging (XposedBridge log still works).
        String logPath;
        if (values.containsKey("log")) {
            logPath = values.get("log").trim();
        } else {
            logPath = DEFAULT_LOG_PATH;
        }

        return new Config(enabled, targets, printerHost, printerPort, protocol, fakeMac, fakeName,
                discovery, discoveryBroadcast, debug, logPath, warnings);
    }

    private static boolean parseBoolean(String raw, boolean defaultValue, String key,
                                        List<String> warnings) {
        if (raw == null) {
            return defaultValue;
        }
        String value = raw.trim().toLowerCase(Locale.US);
        if ("1".equals(value) || "true".equals(value) || "yes".equals(value) || "on".equals(value)) {
            return true;
        }
        if ("0".equals(value) || "false".equals(value) || "no".equals(value)
                || "off".equals(value)) {
            return false;
        }
        warnings.add("invalid boolean for " + key + ": '" + raw + "' (using " + defaultValue + ")");
        return defaultValue;
    }

    private static int parseInt(String raw, int defaultValue, int min, int max, String key,
                                List<String> warnings) {
        if (raw == null) {
            return defaultValue;
        }
        try {
            int parsed = Integer.parseInt(raw.trim());
            if (parsed < min || parsed > max) {
                warnings.add("invalid " + key + " '" + raw + "' (using " + defaultValue + ")");
                return defaultValue;
            }
            return parsed;
        } catch (Throwable t) {
            warnings.add("invalid " + key + " '" + raw + "' (using " + defaultValue + ")");
            return defaultValue;
        }
    }

    private static String join(List<String> values, String separator) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                sb.append(separator);
            }
            sb.append(values.get(i));
        }
        return sb.toString();
    }

    private static void closeQuietly(Closeable closeable) {
        if (closeable != null) {
            try {
                closeable.close();
            } catch (Throwable ignored) {
                // best effort only
            }
        }
    }
}
