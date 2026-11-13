package dev.blacksheep.grabnetprinter;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Persistence and validation for the in-app settings.
 *
 * <p>Saved values live in the module app's private SharedPreferences. The hooked app reads them
 * through {@link ConfigProvider}; only keys that were explicitly saved are exposed, so an existing
 * file-based configuration is overridden on a per-key basis.</p>
 */
final class SettingsStore {

    private SettingsStore() {
    }

    static final String PREFS_NAME = "gnp_settings";

    static final String K_ENABLED = "enabled";
    static final String K_TARGETS = "targets";
    static final String K_HOST = "printer_host";
    static final String K_PORT = "printer_port";
    static final String K_PROTOCOL = "protocol";
    static final String K_FAKE_MAC = "fake_mac";
    static final String K_FAKE_NAME = "fake_name";
    static final String K_DISCOVERY = "discovery";
    static final String K_DISCOVERY_BROADCAST = "discovery_broadcast";
    static final String K_DEBUG = "debug";
    static final String K_LOG = "log";

    /** Every key the provider may expose. Order is used for the generated file/config text. */
    static final String[] KEYS = new String[] {
            K_ENABLED, K_TARGETS, K_HOST, K_PORT, K_PROTOCOL, K_FAKE_MAC, K_FAKE_NAME,
            K_DISCOVERY, K_DISCOVERY_BROADCAST, K_DEBUG, K_LOG
    };

    private static final Pattern MAC_PATTERN =
            Pattern.compile("^(?:[0-9a-fA-F]{2}:){5}[0-9a-fA-F]{2}$");

    static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    /** True when the user has saved at least one setting. */
    static boolean hasSaved(Context context) {
        try {
            return !prefs(context).getAll().isEmpty();
        } catch (Throwable t) {
            return false;
        }
    }

    /** Only the keys the user saved; used as config overrides by the hooked app. */
    static Map<String, String> savedOverrides(Context context) {
        Map<String, String> out = new LinkedHashMap<String, String>();
        try {
            SharedPreferences p = prefs(context);
            for (String key : KEYS) {
                if (p.contains(key)) {
                    String value = p.getString(key, "");
                    if (value != null) {
                        out.put(key, value);
                    }
                }
            }
        } catch (Throwable ignored) {
            // unreadable prefs => empty overrides => legacy file/defaults
        }
        return out;
    }

    /** Full settings for the UI, defaults applied. */
    static Settings load(Context context) {
        Settings s = Settings.defaults();
        try {
            SharedPreferences p = prefs(context);
            s.enabled = getBoolean(p, K_ENABLED, s.enabled);
            s.targets = p.getString(K_TARGETS, s.targets);
            s.printerHost = p.getString(K_HOST, s.printerHost);
            s.printerPort = p.getString(K_PORT, s.printerPort);
            s.protocol = p.getString(K_PROTOCOL, s.protocol);
            s.fakeMac = p.getString(K_FAKE_MAC, s.fakeMac);
            s.fakeName = p.getString(K_FAKE_NAME, s.fakeName);
            s.discovery = getBoolean(p, K_DISCOVERY, s.discovery);
            s.discoveryBroadcast = getBoolean(p, K_DISCOVERY_BROADCAST, s.discoveryBroadcast);
            s.debug = getBoolean(p, K_DEBUG, s.debug);
            s.logPath = p.getString(K_LOG, s.logPath);
        } catch (Throwable ignored) {
            // defaults are fine
        }
        return s;
    }

    static void save(Context context, Settings s) {
        SharedPreferences.Editor e = prefs(context).edit();
        e.putString(K_ENABLED, s.enabled ? "1" : "0");
        e.putString(K_TARGETS, s.targets);
        e.putString(K_HOST, s.printerHost);
        e.putString(K_PORT, s.printerPort);
        e.putString(K_PROTOCOL, s.protocol);
        e.putString(K_FAKE_MAC, s.fakeMac);
        e.putString(K_FAKE_NAME, s.fakeName);
        e.putString(K_DISCOVERY, s.discovery ? "1" : "0");
        e.putString(K_DISCOVERY_BROADCAST, s.discoveryBroadcast ? "1" : "0");
        e.putString(K_DEBUG, s.debug ? "1" : "0");
        e.putString(K_LOG, s.logPath);
        e.apply();
    }

    /** Renders the settings as a legacy {@code key=value} file (for the file-config workflow). */
    static String toConfigText(Settings s) {
        StringBuilder sb = new StringBuilder();
        sb.append("# GrabNetPrinter configuration (written by the settings screen)\n");
        sb.append("# Source of truth for the hooked app is the in-app settings provider.\n");
        sb.append("# This file is optional and only used as a fallback / by the KernelSU module.\n\n");
        sb.append(K_ENABLED).append('=').append(s.enabled ? "1" : "0").append('\n');
        sb.append(K_TARGETS).append('=').append(s.targets).append('\n');
        sb.append(K_HOST).append('=').append(s.printerHost).append('\n');
        sb.append(K_PORT).append('=').append(s.printerPort).append('\n');
        sb.append(K_PROTOCOL).append('=').append(s.protocol).append('\n');
        sb.append(K_FAKE_MAC).append('=').append(s.fakeMac).append('\n');
        sb.append(K_FAKE_NAME).append('=').append(s.fakeName).append('\n');
        sb.append(K_DISCOVERY).append('=').append(s.discovery ? "1" : "0").append('\n');
        sb.append(K_DISCOVERY_BROADCAST).append('=').append(s.discoveryBroadcast ? "1" : "0")
                .append('\n');
        sb.append(K_DEBUG).append('=').append(s.debug ? "1" : "0").append('\n');
        sb.append(K_LOG).append('=').append(s.logPath).append('\n');
        return sb.toString();
    }

    /**
     * Validates user input. Returns {@code null} when valid, otherwise a human readable problem.
     */
    static String validate(Settings s) {
        if (s.enabled && (s.printerHost == null || s.printerHost.trim().isEmpty())) {
            return "Printer host is required when the module is enabled.";
        }
        String host = s.printerHost == null ? "" : s.printerHost.trim();
        if (host.contains(" ") || host.contains("/") || host.contains(":")) {
            return "Printer host must be a hostname or IP address, without a port (set the port separately).";
        }
        int port;
        try {
            port = Integer.parseInt(s.printerPort.trim());
        } catch (Throwable t) {
            return "Printer port must be a number (usually 9100).";
        }
        if (port < 1 || port > 65535) {
            return "Printer port must be between 1 and 65535.";
        }
        if (s.fakeMac == null || !MAC_PATTERN.matcher(s.fakeMac.trim()).matches()) {
            return "Bluetooth MAC must look like 02:00:00:00:00:01.";
        }
        if (s.fakeName == null || s.fakeName.trim().isEmpty()) {
            return "Bluetooth printer name must not be empty.";
        }
        if (s.targets == null || s.targets.trim().isEmpty()) {
            return "At least one target package is required (default: com.grab.merchant).";
        }
        if (!"raw".equals(s.protocol) && !"lpd".equals(s.protocol) && !"ipp".equals(s.protocol)) {
            return "Protocol must be raw, lpd or ipp.";
        }
        return null;
    }

    private static boolean getBoolean(SharedPreferences p, String key, boolean fallback) {
        try {
            String v = p.getString(key, fallback ? "1" : "0");
            if (v == null) {
                return fallback;
            }
            v = v.trim().toLowerCase(Locale.US);
            return "1".equals(v) || "true".equals(v) || "yes".equals(v) || "on".equals(v);
        } catch (Throwable t) {
            return fallback;
        }
    }

    /** Editable settings values (all strings so they map 1:1 to the config format). */
    static final class Settings {
        boolean enabled = true;
        String targets = Config.DEFAULT_TARGET;
        String printerHost = "";
        String printerPort = String.valueOf(Config.DEFAULT_PRINTER_PORT);
        String protocol = Config.PROTOCOL_RAW;
        String fakeMac = Config.DEFAULT_FAKE_MAC;
        String fakeName = Config.DEFAULT_FAKE_NAME;
        boolean discovery = true;
        boolean discoveryBroadcast = true;
        boolean debug = false;
        String logPath = Config.DEFAULT_LOG_PATH;

        static Settings defaults() {
            return new Settings();
        }

        Settings copy() {
            Settings s = new Settings();
            s.enabled = enabled;
            s.targets = targets;
            s.printerHost = printerHost;
            s.printerPort = printerPort;
            s.protocol = protocol;
            s.fakeMac = fakeMac;
            s.fakeName = fakeName;
            s.discovery = discovery;
            s.discoveryBroadcast = discoveryBroadcast;
            s.debug = debug;
            s.logPath = logPath;
            return s;
        }
    }
}
