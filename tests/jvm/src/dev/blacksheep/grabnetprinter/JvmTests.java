package dev.blacksheep.grabnetprinter;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

/**
 * Offline (JVM) tests for the platform-independent parts of GrabNetPrinter:
 * config parsing and the TCP bridge. No Android classes are touched, so this runs on the Mac.
 *
 * <p>Config.loadFrom(String) is a small testability hook; invoked reflectively so the tests also
 * compile against a version of Config that only has load().</p>
 *
 * <p>Run: tests/jvm/run.sh (exit 0 = all pass)</p>
 */
public final class JvmTests {

    private static int passed;
    private static int failed;
    private static int skipped;

    public static void main(String[] args) throws Exception {
        testConfigParsing();
        testConfigDefaults();
        testConfigValidation();
        testConfigOverrides();
        testSocketBridgeRoundTrip();
        testSocketBridgeConnectFailure();
        testProtocolMapping();
        testLogHelpers();
        System.out.println();
        System.out.printf("RESULT: %d passed, %d failed, %d skipped%n", passed, failed, skipped);
        if (failed > 0) {
            System.exit(1);
        }
    }

    // ---------------------------------------------------------------- config

    private static void testConfigParsing() throws Exception {
        Method loadFrom = loadFromMethod();
        if (loadFrom == null) {
            skip("Config.loadFrom(String) not present yet");
            return;
        }
        String content = "# comment line\n"
                + "\n"
                + "  enabled = 1  \n"
                + "targets = com.grab.merchant, com.example.other\n"
                + "printer_host=192.168.1.50\n"
                + "printer_port=9100\n"
                + "protocol=raw\n"
                + "fake_mac=02:00:00:00:00:01\n"
                + "fake_name=LAN Printer\n"
                + "discovery=1\n"
                + "debug=0\n"
                + "unknown_key=whatever\n"
                + "// another comment style\n";
        File f = tempFile(content);
        Config c = (Config) loadFrom.invoke(null, f.getAbsolutePath());
        check("config enabled", c.enabled());
        check("config active", c.isActive());
        check("config host", "192.168.1.50".equals(c.printerHost()));
        check("config port", c.printerPort() == 9100);
        check("config protocol", "raw".equals(c.protocol()));
        check("config fake mac", "02:00:00:00:00:01".equals(c.fakeMac()));
        check("config fake name", "LAN Printer".equals(c.fakeName()));
        check("config discovery", c.discovery());
        check("config debug off", !c.debug());
        check("config target 1", c.isTarget("com.grab.merchant"));
        check("config target 2", c.isTarget("com.example.other"));
        check("config non-target", !c.isTarget("com.evil.app"));
        List<String> warnings = c.warnings();
        check("config warnings is a list", warnings != null);
        f.delete();
    }

    private static void testConfigDefaults() throws Exception {
        Method loadFrom = loadFromMethod();
        if (loadFrom == null) {
            skip("Config.loadFrom(String) not present yet");
            return;
        }
        Config c = (Config) loadFrom.invoke(new Object(), new Object[] {"/nonexistent/gnp.conf"});
        check("default enabled", c.enabled());
        check("default target", c.isTarget("com.grab.merchant"));
        check("default port", c.printerPort() == 9100);
        check("default fake mac", "02:00:00:00:00:01".equals(c.fakeMac()));
        check("default inactive without host", !c.isActive());
    }

    private static void testConfigValidation() throws Exception {
        Method loadFrom = loadFromMethod();
        if (loadFrom == null) {
            skip("Config.loadFrom(String) not present yet");
            return;
        }
        File f = tempFile("enabled=1\nprinter_host=192.168.1.50\nfake_mac=zz:00:00:00:00:01\n");
        Config c = (Config) loadFrom.invoke(null, f.getAbsolutePath());
        check("invalid mac falls back", "02:00:00:00:00:01".equals(c.fakeMac()));
        check("invalid mac recorded", c.warnings() != null && !c.warnings().isEmpty());
        f.delete();

        File off = tempFile("enabled=0\nprinter_host=192.168.1.50\n");
        Config c2 = (Config) loadFrom.invoke(null, off.getAbsolutePath());
        check("enabled=0 disables", !c2.isActive());
        off.delete();
    }

    private static void testConfigOverrides() throws Exception {
        File f = tempFile("enabled=1\n"
                + "targets=com.example.target\n"
                + "printer_host=192.168.1.10\n"
                + "printer_port=9100\n"
                + "fake_name=File Printer\n"
                + "debug=0\n");
        java.util.Map<String, String> overrides = new java.util.LinkedHashMap<String, String>();
        overrides.put("printer_host", "10.0.0.42");
        overrides.put("printer_port", "9200");
        overrides.put("fake_name", "UI Printer");
        overrides.put("debug", "1");
        Config c = Config.loadFrom(f.getAbsolutePath(), overrides);
        check("override host wins", "10.0.0.42".equals(c.printerHost()));
        check("override port wins", c.printerPort() == 9200);
        check("override name wins", "UI Printer".equals(c.fakeName()));
        check("override debug wins", c.debug());
        check("file-only value preserved", c.isTarget("com.example.target"));
        f.delete();

        File fileOnly = tempFile("enabled=1\nprinter_host=192.168.1.10\n");
        Config noOverrides = Config.loadFrom(fileOnly.getAbsolutePath(), null);
        check("null overrides keeps file value", "192.168.1.10".equals(noOverrides.printerHost()));
        fileOnly.delete();

        File blank = tempFile("enabled=1\nprinter_host=192.168.1.10\n");
        java.util.Map<String, String> blankHost = new java.util.LinkedHashMap<String, String>();
        blankHost.put("printer_host", "");
        Config disabledByOverride = Config.loadFrom(blank.getAbsolutePath(), blankHost);
        check("empty host override disables module", !disabledByOverride.isActive());
        check("empty host override keeps enabled flag", disabledByOverride.enabled());
        blank.delete();

        File macTest = tempFile("enabled=1\nprinter_host=192.168.1.10\nfake_mac=02:00:00:00:00:01\n");
        java.util.Map<String, String> badMac = new java.util.LinkedHashMap<String, String>();
        badMac.put("fake_mac", "not-a-mac");
        Config macFallback = Config.loadFrom(macTest.getAbsolutePath(), badMac);
        check("invalid override mac falls back", Config.DEFAULT_FAKE_MAC.equalsIgnoreCase(macFallback.fakeMac()));
        macTest.delete();
    }

    // ------------------------------------------------------------ tcp bridge

    private static void testSocketBridgeRoundTrip() throws Exception {
        ServerSocket server = new ServerSocket(0);
        int port = server.getLocalPort();
        final byte[][] received = new byte[1][];
        Thread acceptor = new Thread(new Runnable() {
            @Override
            public void run() {
                try (Socket s = server.accept()) {
                    InputStream in = s.getInputStream();
                    byte[] buf = new byte[8192];
                    int total = 0;
                    int n;
                    while (total < 3 + 2 + 5) {
                        n = in.read(buf, total, buf.length - total);
                        if (n < 0) {
                            break;
                        }
                        total += n;
                    }
                    received[0] = Arrays.copyOf(buf, total);
                    Thread.sleep(400);
                } catch (Exception ignored) {
                    // test fails below on timeout/content
                }
            }
        });
        acceptor.setDaemon(true);
        acceptor.start();

        Config cfg = reflectiveConfig("127.0.0.1", port, "raw");
        SocketBridge bridge = new SocketBridge(cfg);
        check("bridge initially disconnected", !bridge.isConnected());
        bridge.connect();
        check("bridge connected", bridge.isConnected());
        OutputStream out = bridge.getOutputStream();
        out.write(new byte[] {0x1b, 0x40, 0x41});
        out.write(new byte[] {0x0a, 0x0a});
        out.write("hello".getBytes(StandardCharsets.US_ASCII));
        out.flush();
        acceptor.join(4000);
        bridge.close();
        check("bridge disconnected after close", !bridge.isConnected());
        check("bridge byte count", bridge.getBytesWritten() >= 10);
        check("tcp payload round trip", received[0] != null
                && received[0].length == 10
                && received[0][0] == 0x1b && received[0][1] == 0x40
                && "hello".equals(new String(received[0], 5, 5, StandardCharsets.US_ASCII)));
        check("empty input stream is EOF", bridge.getInputStream().read() == -1);
        server.close();
    }

    private static void testSocketBridgeConnectFailure() throws Exception {
        ServerSocket probe = new ServerSocket(0);
        int deadPort = probe.getLocalPort();
        probe.close();
        Config cfg = reflectiveConfig("127.0.0.1", deadPort, "raw");
        SocketBridge bridge = new SocketBridge(cfg);
        boolean threw = false;
        String message = "";
        try {
            bridge.connect();
        } catch (java.io.IOException e) {
            threw = true;
            message = String.valueOf(e.getMessage());
        }
        check("connect to dead port throws IOException", threw);
        check("connect failure names endpoint", message.contains("127.0.0.1") && message.contains(String.valueOf(deadPort)));
        check("failed bridge disconnected", !bridge.isConnected());
    }

    // ----------------------------------------------------------------- misc

    private static void testProtocolMapping() throws Exception {
        check("raw protocol", SocketBridge.protocolFor("raw").id().equals("raw"));
        check("unknown protocol degrades to raw", SocketBridge.protocolFor("bogus").id().equals("raw"));
        check("lpd currently degrades to raw", SocketBridge.protocolFor("lpd").id().equals("raw"));
    }

    private static void testLogHelpers() {
        try {
            Log.init("", true);
            Log.i("jvm test info line");
            Log.d("jvm test debug line");
            Log.once("k1", "once line");
            Log.once("k1", "once line should be suppressed");
            boolean first = Log.rateLimited("rate", 5000);
            boolean second = Log.rateLimited("rate", 5000);
            check("rateLimited first true", first);
            check("rateLimited second false", !second);
            check("hex encodes bytes", "1b 40".equals(Log.hex(new byte[] {0x1b, 0x40}, 0, 2)));
            Log.hexDump("TX", new byte[] {1, 2, 3, 4, 5}, 0, 5);
            check("log helpers did not throw", true);
        } catch (Throwable t) {
            check("log helpers did not throw (" + t + ")", false);
        }
    }

    // -------------------------------------------------------------- helpers

    private static Method loadFromMethod() {
        try {
            return Config.class.getMethod("loadFrom", String.class);
        } catch (Throwable t) {
            return null;
        }
    }

    private static Config reflectiveConfig(String host, int port, String protocol) throws Exception {
        Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
        Field theUnsafe = unsafeClass.getDeclaredField("theUnsafe");
        theUnsafe.setAccessible(true);
        Object unsafe = theUnsafe.get(null);
        Method allocateInstance = unsafeClass.getMethod("allocateInstance", Class.class);
        Config cfg = (Config) allocateInstance.invoke(unsafe, Config.class);
        set(cfg, "printerHost", host);
        set(cfg, "printerPort", port);
        set(cfg, "protocol", protocol);
        set(cfg, "debug", true);
        set(cfg, "logPath", "");
        set(cfg, "fakeMac", Config.DEFAULT_FAKE_MAC);
        set(cfg, "fakeName", Config.DEFAULT_FAKE_NAME);
        set(cfg, "enabled", Boolean.TRUE);
        set(cfg, "discovery", Boolean.TRUE);
        set(cfg, "discoveryBroadcast", Boolean.TRUE);
        set(cfg, "targets", Arrays.asList(Config.DEFAULT_TARGET));
        return cfg;
    }

    private static void set(Object target, String field, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(field);
        f.setAccessible(true);
        f.set(target, value);
    }

    private static File tempFile(String content) throws Exception {
        File f = File.createTempFile("gnp-test", ".conf");
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(content.getBytes(StandardCharsets.UTF_8));
        }
        return f;
    }

    private static void check(String name, boolean condition) {
        if (condition) {
            passed++;
            System.out.println("PASS " + name);
        } else {
            failed++;
            System.out.println("FAIL " + name);
        }
    }

    private static void skip(String why) {
        skipped++;
        System.out.println("SKIP " + why);
    }

    private JvmTests() {
    }
}
