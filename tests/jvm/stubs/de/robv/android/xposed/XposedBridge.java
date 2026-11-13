package de.robv.android.xposed;

/** JVM-only stub: the real class is provided by LSPosed at runtime. */
public final class XposedBridge {
    private XposedBridge() {}
    public static void log(String text) { System.out.println("[Xposed] " + text); }
    public static void log(Throwable t) { t.printStackTrace(System.out); }
}
