package dev.blacksheep.grabnetprinter;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Configuration screen for the GrabNetPrinter LSPosed module.
 *
 * <p>No root is required to change settings: they are stored in this app's private
 * SharedPreferences and exposed to the hooked target app through {@link ConfigProvider}.
 * Root is only used for the optional convenience actions (restart the target app, write the
 * legacy config file, open LSPosed) and every one of them fails gracefully without it.</p>
 */
public final class SettingsActivity extends Activity {

    private static final int PAD_DP = 20;

    private EditText hostField;
    private EditText portField;
    private EditText nameField;
    private EditText macField;
    private EditText targetsField;
    private EditText protocolField;
    private EditText logField;
    private Switch enabledSwitch;
    private Switch discoverySwitch;
    private Switch debugSwitch;
    private TextView statusView;
    private TextView rootView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle("GrabNetPrinter settings");

        SettingsStore.Settings settings = SettingsStore.load(this);
        if (!SettingsStore.hasSaved(this)) {
            applyLegacyFileIfPresent(settings);
        }

        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(PAD_DP);
        root.setPadding(pad, pad, pad, pad);
        scroll.addView(root, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(scroll);

        addHeader(root, "GrabNetPrinter");
        addText(root, "Makes a LAN ESC/POS receipt printer appear as a bonded Bluetooth SPP printer "
                + "inside the target app, and forwards its print bytes to the printer over TCP.\n\n"
                + "Enable the module and its scope for the target app in LSPosed. Saving writes the "
                + "configuration into the target app (via root) and restarts it.");

        addSection(root, "Printer");
        hostField = addField(root, "Printer host (IP or hostname)", settings.printerHost,
                InputType.TYPE_CLASS_TEXT, "e.g. 192.168.1.50");
        portField = addField(root, "Printer port", settings.printerPort,
                InputType.TYPE_CLASS_NUMBER, "usually 9100 (raw ESC/POS)");
        addText(root, "Most receipt printers accept raw ESC/POS on TCP port 9100. "
                + "Use the printer's IP address, not its name, if mDNS is not available.");

        addSection(root, "Fake Bluetooth printer");
        nameField = addField(root, "Bluetooth name", settings.fakeName,
                InputType.TYPE_CLASS_TEXT, "name shown in the target app");
        macField = addField(root, "Bluetooth address", settings.fakeMac,
                InputType.TYPE_CLASS_TEXT, "02:00:00:00:00:01");
        addText(root, "A locally administered unicast MAC (02:...) is recommended. It only has to "
                + "look like a valid printer address; nothing is sent over real Bluetooth.");

        addSection(root, "Behaviour");
        enabledSwitch = addSwitch(root, "Enabled", settings.enabled);
        discoverySwitch = addSwitch(root, "Answer Bluetooth discovery scans", settings.discovery);
        debugSwitch = addSwitch(root, "Verbose logging", settings.debug);
        addText(root, "Discovery injection makes the fake printer appear in the app's "
                + "\"search for Bluetooth devices\" screen. The paired-printer list works even "
                + "with discovery off.");

        addSection(root, "Advanced");
        targetsField = addField(root, "Target packages (comma separated)", settings.targets,
                InputType.TYPE_CLASS_TEXT, "com.grab.merchant");
        protocolField = addField(root, "Protocol", settings.protocol,
                InputType.TYPE_CLASS_TEXT, "raw");
        logField = addField(root, "Log file path (optional)", settings.logPath,
                InputType.TYPE_CLASS_TEXT, "/data/local/tmp/grab-netprinter.log");
        addText(root, "Target packages must also be ticked in the LSPosed scope. Protocol is "
                + "currently always raw TCP; lpd/ipp are reserved for future transports.");

        addSection(root, "Apply");
        statusView = addStatus(root);
        rootView = addStatus(root);

        buttonRow(root,
                "Save", new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        saveSettings();
                    }
                },
                "Test connection", new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        testConnection();
                    }
                });

        buttonRow(root,
                "Restart target app", new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        restartTarget();
                    }
                },
                "Open LSPosed", new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        openLsposed();
                    }
                });

        Button writeLegacy = new Button(this);
        writeLegacy.setText("Write legacy config file (root, optional)");
        writeLegacy.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                writeLegacyFile();
            }
        });
        addGap(root, 8);
        root.addView(writeLegacy, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        status("Settings loaded. Enable the module + scope in LSPosed, then restart the target app.");
        checkRootAsync();
    }

    // ------------------------------------------------------------------ actions

    private void saveSettings() {
        final SettingsStore.Settings s = collect();
        String problem = SettingsStore.validate(s);
        if (problem != null) {
            status("Not saved: " + problem);
            toast(problem);
            return;
        }
        SettingsStore.save(this, s);
        writePendingConfig(s);
        status("Saved. Applying to target app(s) ...");
        applyToTargets(s);
    }

    /**
     * Writes the configuration to a pending file in this app's private storage. If root is not
     * granted, the optional KernelSU/Magisk support module imports this file at the next boot and
     * pushes it to the target app(s) — so changing settings never strictly requires root.
     */
    private void writePendingConfig(SettingsStore.Settings s) {
        try {
            File pending = new File(getFilesDir(), "pending-gnp.conf");
            OutputStream out = new FileOutputStream(pending);
            try {
                out.write(SettingsStore.toConfigText(s).getBytes(StandardCharsets.UTF_8));
                out.flush();
            } finally {
                out.close();
            }
        } catch (Throwable ignored) {
            // root apply still works; pending file is only a fallback
        }
    }

    /**
     * Pushes the configuration into every target app's private files dir and writes the optional
     * legacy file, in a single root shell invocation (one KernelSU/Magisk prompt).
     *
     * <p>Reading a file from the hooked app's own files directory is the only cross-app config
     * channel that needs neither package-visibility exemptions nor SELinux permissive mode.</p>
     */
    private void applyToTargets(final SettingsStore.Settings s) {
        final List<String> packages = splitPackages(s.targets);
        new Thread(new Runnable() {
            @Override
            public void run() {
                String text = SettingsStore.toConfigText(s);
                File tmp = null;
                String output = null;
                boolean ok = false;
                try {
                    tmp = new File(getCacheDir(), "gnp-apply.conf");
                    OutputStream out = new FileOutputStream(tmp);
                    try {
                        out.write(text.getBytes(StandardCharsets.UTF_8));
                        out.flush();
                    } finally {
                        out.close();
                    }
                    String quotedTmp = shellQuote(tmp.getAbsolutePath());
                    StringBuilder script = new StringBuilder();
                    for (String pkg : packages) {
                        if ("*".equals(pkg)) {
                            continue;
                        }
                        String quotedPkg = shellQuote(pkg);
                        script.append("if [ -d /data/data/").append(pkg).append(" ]; then ")
                                .append("mkdir -p /data/data/").append(pkg).append("/files; ")
                                .append("cp ").append(quotedTmp)
                                .append(" /data/data/").append(pkg).append("/files/")
                                .append(ConfigReader.TARGET_FILE).append("; ")
                                .append("chown \"$(stat -c %u:%g /data/data/").append(pkg)
                                .append(")\" /data/data/").append(pkg).append("/files/")
                                .append(ConfigReader.TARGET_FILE).append(" 2>/dev/null; ")
                                .append("chmod 0600 /data/data/").append(pkg).append("/files/")
                                .append(ConfigReader.TARGET_FILE).append("; ")
                                .append("echo APPLIED:").append(quotedPkg).append("; ")
                                .append("fi; ");
                    }
                    script.append("cp ").append(quotedTmp).append(' ').append(Config.DEFAULT_PATH)
                            .append("; chmod 0644 ").append(Config.DEFAULT_PATH)
                            .append("; touch /data/local/tmp/grab-netprinter.log")
                            .append("; chmod 0666 /data/local/tmp/grab-netprinter.log");
                    SuResult result = runSuCapture(script.toString());
                    ok = result.ok;
                    output = result.output;
                } catch (Throwable t) {
                    output = String.valueOf(t);
                } finally {
                    if (tmp != null) {
                        //noinspection ResultOfMethodCallIgnored
                        tmp.delete();
                    }
                }

                int applied = 0;
                if (output != null) {
                    for (String line : output.split("\n")) {
                        if (line.trim().startsWith("APPLIED:")) {
                            applied++;
                        }
                    }
                }
                final String message;
                if (ok && applied > 0) {
                    message = "Applied to " + applied + " target app(s) and the legacy file. "
                            + "Restart the target app (button above) to activate.";
                } else if (ok) {
                    message = "Saved, but no installed target app matched "
                            + s.targets.trim() + ". Check the package name and LSPosed scope.";
                } else {
                    message = "Saved. Root was not available, so the configuration is pending: "
                            + "install/keep the optional support module and reboot to apply it "
                            + "(or grant root to GrabNetPrinter and press Save again).";
                }
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        status(message);
                        toast(message);
                    }
                });
            }
        }, "gnp-apply").start();
    }

    private void testConnection() {
        SettingsStore.Settings s = collect();
        String problem = SettingsStore.validate(s);
        if (problem != null) {
            status("Cannot test: " + problem);
            return;
        }
        final String host = s.printerHost.trim();
        final int port;
        try {
            port = Integer.parseInt(s.printerPort.trim());
        } catch (Throwable t) {
            status("Cannot test: bad port");
            return;
        }
        status("Testing TCP connection to " + host + ":" + port + " ...");
        new Thread(new Runnable() {
            @Override
            public void run() {
                Socket socket = new Socket();
                String result;
                try {
                    socket.connect(new InetSocketAddress(host, port), 4000);
                    result = "OK: printer is reachable at " + host + ":" + port;
                } catch (Throwable t) {
                    result = "FAILED: could not reach " + host + ":" + port + " (" + t + ")";
                } finally {
                    try {
                        socket.close();
                    } catch (Throwable ignored) {
                        // ignore
                    }
                }
                final String text = result;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        status(text);
                    }
                });
            }
        }, "gnp-test-connection").start();
    }

    private void restartTarget() {
        SettingsStore.Settings s = collect();
        List<String> packages = splitPackages(s.targets);
        if (packages.isEmpty()) {
            status("No target package configured.");
            return;
        }
        StringBuilder cmd = new StringBuilder();
        for (String pkg : packages) {
            if ("*".equals(pkg)) {
                continue;
            }
            cmd.append("am force-stop ").append(shellQuote(pkg)).append("; ");
        }
        if (TextUtilsEmpty(cmd.toString())) {
            status("Cannot restart: target list only contains '*'.");
            return;
        }
        status("Restarting target app(s) ...");
        final String restartCmd = cmd.toString();
        new Thread(new Runnable() {
            @Override
            public void run() {
                boolean ok = runSu(restartCmd);
                // Best effort: reopen GrabMerchant when it is the target.
                if (ok && restartCmd.contains("com.grab.merchant")) {
                    runSu("am start -n com.grab.merchant/com.grab.mex.activity.MainActivity");
                }
                final String text = ok
                        ? "Target app restarted (root). Open its printer settings to verify."
                        : "Could not restart via root. Force-stop the target app manually.";
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        status(text);
                    }
                });
            }
        }, "gnp-restart").start();
    }

    private void openLsposed() {
        status("Opening LSPosed manager (root) ...");
        new Thread(new Runnable() {
            @Override
            public void run() {
                boolean ok = runSu("am start -c org.lsposed.manager.LAUNCH_MANAGER "
                        + "com.android.shell/.BugreportWarningActivity");
                final String text = ok ? "LSPosed manager opened."
                        : "Could not open LSPosed manager via root. Use the LSPosed notification.";
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        status(text);
                    }
                });
            }
        }, "gnp-lsposed").start();
    }

    private void writeLegacyFile() {
        SettingsStore.Settings s = collect();
        String problem = SettingsStore.validate(s);
        if (problem != null) {
            status("Not written: " + problem);
            return;
        }
        final String text = SettingsStore.toConfigText(s);
        status("Writing legacy config file ...");
        new Thread(new Runnable() {
            @Override
            public void run() {
                boolean ok = false;
                File tmp = null;
                try {
                    tmp = new File(getCacheDir(), "gnp-legacy.conf");
                    OutputStream out = new FileOutputStream(tmp);
                    try {
                        out.write(text.getBytes(StandardCharsets.UTF_8));
                        out.flush();
                    } finally {
                        out.close();
                    }
                    ok = runSu("cp " + shellQuote(tmp.getAbsolutePath())
                            + " " + Config.DEFAULT_PATH
                            + " && chmod 0644 " + Config.DEFAULT_PATH
                            + " && touch /data/local/tmp/grab-netprinter.log"
                            + " && chmod 0666 /data/local/tmp/grab-netprinter.log");
                } catch (Throwable ignored) {
                    ok = false;
                } finally {
                    if (tmp != null) {
                        //noinspection ResultOfMethodCallIgnored
                        tmp.delete();
                    }
                }
                final String result = ok
                        ? "Legacy file written to " + Config.DEFAULT_PATH + " (root)."
                        : "Could not write " + Config.DEFAULT_PATH
                        + " (no root?). The in-app settings still apply without it.";
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        status(result);
                    }
                });
            }
        }, "gnp-write-file").start();
    }

    // ------------------------------------------------------------------- root

    private void checkRootAsync() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                final boolean root = runSu("true");
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (rootView != null) {
                            rootView.setText(root
                                    ? "Root: available - settings can be applied to target apps"
                                    : "Root: NOT available - settings are saved but cannot be "
                                            + "pushed to the target app. Grant root to "
                                            + "GrabNetPrinter in KernelSU/Magisk.");
                            rootView.setTextColor(root ? 0xFF2E7D32 : 0xFFB26A00);
                        }
                    }
                });
            }
        }, "gnp-root-check").start();
    }

    /** Result of a root command: exit status plus captured stdout. */
    private static final class SuResult {
        final boolean ok;
        final String output;

        SuResult(boolean ok, String output) {
            this.ok = ok;
            this.output = output == null ? "" : output;
        }
    }

    /** Runs {@code su -c <command>} and returns true on exit code 0. Never throws. */
    private static boolean runSu(String command) {
        return runSuCapture(command).ok;
    }

    /** Runs {@code su -c <command>}, capturing stdout/stderr for status reporting. */
    private static SuResult runSuCapture(String command) {
        Process process = null;
        try {
            process = Runtime.getRuntime().exec(new String[] {"su", "-c", command});
            process.getOutputStream().close();
            StringBuilder output = new StringBuilder();
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
            try {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append('\n');
                }
            } finally {
                reader.close();
            }
            int code = process.waitFor();
            return new SuResult(code == 0, output.toString());
        } catch (Throwable t) {
            return new SuResult(false, String.valueOf(t));
        } finally {
            if (process != null) {
                try {
                    process.destroy();
                } catch (Throwable ignored) {
                    // ignore
                }
            }
        }
    }

    // -------------------------------------------------------------- ui helpers

    private SettingsStore.Settings collect() {
        SettingsStore.Settings s = new SettingsStore.Settings();
        s.enabled = enabledSwitch != null && enabledSwitch.isChecked();
        s.discovery = discoverySwitch != null && discoverySwitch.isChecked();
        s.debug = debugSwitch != null && debugSwitch.isChecked();
        s.printerHost = value(hostField);
        s.printerPort = value(portField);
        s.fakeName = value(nameField);
        s.fakeMac = value(macField);
        s.targets = value(targetsField);
        s.protocol = value(protocolField).toLowerCase(java.util.Locale.US);
        s.logPath = value(logField);
        return s;
    }

    private static String value(EditText field) {
        return field == null || field.getText() == null ? "" : field.getText().toString().trim();
    }

    private static List<String> splitPackages(String raw) {
        List<String> out = new ArrayList<String>();
        if (raw == null) {
            return out;
        }
        for (String part : raw.split("[,;\\s]+")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                out.add(trimmed);
            }
        }
        return out;
    }

    private static boolean TextUtilsEmpty(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static String shellQuote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }

    private void addHeader(LinearLayout parent, String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(22);
        view.setPadding(0, 0, 0, dp(4));
        parent.addView(view);
    }

    private void addSection(LinearLayout parent, String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(15);
        view.setPadding(0, dp(18), 0, dp(6));
        parent.addView(view);
    }

    private void addText(LinearLayout parent, String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(12);
        view.setTextColor(Color.GRAY);
        view.setPadding(0, dp(2), 0, dp(6));
        parent.addView(view);
    }

    private TextView addStatus(LinearLayout parent) {
        TextView view = new TextView(this);
        view.setTextSize(13);
        view.setPadding(0, dp(6), 0, dp(2));
        parent.addView(view);
        return view;
    }

    private EditText addField(LinearLayout parent, String label, String value, int inputType,
                              String hint) {
        addText(parent, label);
        EditText field = new EditText(this);
        field.setText(value);
        field.setHint(hint);
        field.setInputType(inputType);
        field.setSingleLine(true);
        parent.addView(field, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return field;
    }

    private Switch addSwitch(LinearLayout parent, String label, boolean checked) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(8), 0, dp(8));
        TextView text = new TextView(this);
        text.setText(label);
        text.setTextSize(14);
        row.addView(text, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Switch toggle = new Switch(this);
        toggle.setChecked(checked);
        row.addView(toggle);
        parent.addView(row, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return toggle;
    }

    private void buttonRow(LinearLayout parent, String leftText,
                          View.OnClickListener leftListener, String rightText,
                          View.OnClickListener rightListener) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        Button left = new Button(this);
        left.setText(leftText);
        left.setOnClickListener(leftListener);
        row.addView(left, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Button right = new Button(this);
        right.setText(rightText);
        right.setOnClickListener(rightListener);
        row.addView(right, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        addGap(parent, 8);
        parent.addView(row, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private void addGap(LinearLayout parent, int heightDp) {
        View gap = new View(this);
        parent.addView(gap, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(heightDp)));
    }

    private void status(String text) {
        if (statusView != null) {
            statusView.setText(text);
        }
    }

    private void toast(String text) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show();
    }

    private int dp(int value) {
        return Math.round(getResources().getDisplayMetrics().density * value);
    }

    /**
     * On first run, pre-fill the form from the optional legacy file (if this app can read it).
     * This makes migration from a file-only setup painless.
     */
    private void applyLegacyFileIfPresent(SettingsStore.Settings settings) {
        BufferedReader reader = null;
        try {
            File file = new File(Config.DEFAULT_PATH);
            if (!file.isFile()) {
                return;
            }
            reader = new BufferedReader(
                    new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("//")) {
                    continue;
                }
                int eq = trimmed.indexOf('=');
                if (eq < 0) {
                    continue;
                }
                String key = trimmed.substring(0, eq).trim();
                String value = trimmed.substring(eq + 1).trim();
                if (key.equals(SettingsStore.K_HOST)) {
                    settings.printerHost = value;
                } else if (key.equals(SettingsStore.K_PORT)) {
                    settings.printerPort = value;
                } else if (key.equals(SettingsStore.K_FAKE_NAME)) {
                    settings.fakeName = value;
                } else if (key.equals(SettingsStore.K_FAKE_MAC)) {
                    settings.fakeMac = value;
                } else if (key.equals(SettingsStore.K_TARGETS)) {
                    settings.targets = value;
                } else if (key.equals(SettingsStore.K_DEBUG)) {
                    settings.debug = "1".equals(value) || "true".equalsIgnoreCase(value);
                }
            }
        } catch (Throwable ignored) {
            // File not readable from here (enforcing SELinux): keep defaults.
        } finally {
            if (reader != null) {
                try {
                    reader.close();
                } catch (Throwable ignored) {
                    // ignore
                }
            }
        }
    }
}
