package dev.blacksheep.grabnetprinter;

import android.content.Context;

import java.util.concurrent.atomic.AtomicBoolean;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * LSPosed/Xposed entry point (declared in {@code assets/xposed_init}).
 *
 * <p>LSPosed calls {@link #handleLoadPackage} for every process of a scoped package before the
 * app's own code runs. Configuration lives in the module app's settings screen and is read
 * through {@link ConfigProviderClient} once a {@link Context} exists, with the optional legacy
 * {@code /data/local/tmp/grab-netprinter.conf} file as a fallback. Because the context is not
 * available this early, hook installation is deferred to {@code Application.onCreate()} (with an
 * immediate attempt when the application already exists for the process).</p>
 *
 * <p>Nothing is installed when the module is disabled, the process is not a configured target,
 * or no printer host is configured. Every step is guarded so a failure can never crash the
 * host app.</p>
 */
public final class HookEntry implements IXposedHookLoadPackage {

    /** Informational; keep in sync with the build. */
    public static final String VERSION = "1.1.0";

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            onLoadPackage(lpparam);
        } catch (Throwable t) {
            // Last line of defence: never let an exception escape into the hooked process.
            try {
                XposedBridge.log("GrabNetPrinter: unexpected error during handleLoadPackage: " + t);
            } catch (Throwable ignored) {
                // nothing else we can do
            }
        }
    }

    private void onLoadPackage(final XC_LoadPackage.LoadPackageParam lpparam) {
        if (lpparam == null || lpparam.packageName == null || lpparam.classLoader == null) {
            return;
        }

        // Logging to the file only becomes possible once the config is loaded; use Xposed's
        // log (visible in the LSPosed manager / bug reports) for the early messages.
        Log.init(null, false);

        Context application = currentApplication();
        if (application != null) {
            bootstrap(lpparam, application);
            return;
        }

        // Defer until the process has a Context: Application.onCreate runs before any of the
        // app's activities/services, so the Bluetooth hooks are in place long before the app
        // can open a printer screen.
        final XC_MethodHook onCreateHook = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                try {
                    if (param.thisObject instanceof Context) {
                        bootstrap(lpparam, (Context) param.thisObject);
                    }
                } catch (Throwable t) {
                    Log.e("deferred bootstrap failed", t);
                }
            }
        };
        try {
            XposedBridge.hookAllMethods(
                    XposedHelpers.findClass("android.app.Application", lpparam.classLoader),
                    "onCreate", onCreateHook);
        } catch (Throwable t) {
            Log.w("could not hook Application.onCreate", t);
        }

        // Second chance for frameworks where callApplicationOnCreate is the only entry point.
        try {
            XposedBridge.hookAllMethods(
                    XposedHelpers.findClass("android.app.Instrumentation", lpparam.classLoader),
                    "callApplicationOnCreate", new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                if (param.args != null && param.args.length > 0
                                        && param.args[0] instanceof Context) {
                                    bootstrap(lpparam, (Context) param.args[0]);
                                }
                            } catch (Throwable t) {
                                Log.e("deferred bootstrap failed (instrumentation)", t);
                            }
                        }
                    });
        } catch (Throwable t) {
            Log.d("could not hook Instrumentation.callApplicationOnCreate: " + t);
        }
    }

    /** Runs once per process; installs the Bluetooth hooks when the config asks for it. */
    private void bootstrap(XC_LoadPackage.LoadPackageParam lpparam, Context context) {
        if (!STARTED.compareAndSet(false, true)) {
            return;
        }
        try {
            ConfigReader.Result loaded = ConfigReader.load(context);
            Config cfg = loaded.config;

            if (!cfg.enabled()) {
                Log.i("GrabNetPrinter " + VERSION + ": disabled in settings, not hooking "
                        + lpparam.packageName);
                return;
            }
            if (!cfg.isTarget(lpparam.packageName)) {
                Log.d("GrabNetPrinter: " + lpparam.packageName
                        + " is not a configured target; not hooking");
                return;
            }
            if (cfg.printerHost().isEmpty()) {
                Log.i("GrabNetPrinter " + VERSION + ": no printer host configured; not hooking "
                        + lpparam.packageName);
                return;
            }

            Log.init(cfg.logPath(), cfg.debug());
            Log.i("GrabNetPrinter " + VERSION + " starting in " + lpparam.packageName
                    + " (process=" + lpparam.processName + ", firstApplication="
                    + lpparam.isFirstApplication + ")");
            Log.i("config sources: " + loaded.sources
                    + " provider=" + ConfigProviderClient.describe());
            for (String warning : cfg.warnings()) {
                Log.w("config: " + warning, null);
            }
            Log.i("GrabNetPrinter config: " + cfg.summary());

            BluetoothHooks.install(lpparam.classLoader, cfg);

            // Deploy/verification tooling greps for this exact phrase - keep it stable.
            Log.i("GNP: hooks installed targets=" + join(cfg.targets()) + " printer="
                    + cfg.printerHost() + ":" + cfg.printerPort() + " fake=" + cfg.fakeMac());
        } catch (Throwable t) {
            Log.e("GrabNetPrinter bootstrap failed", t);
        }
    }

    private static final AtomicBoolean STARTED = new AtomicBoolean(false);

    private static Context currentApplication() {
        try {
            Class<?> activityThread = Class.forName("android.app.ActivityThread");
            Object application = activityThread.getMethod("currentApplication").invoke(null);
            return application instanceof Context ? (Context) application : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String join(java.util.List<String> values) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(values.get(i));
        }
        return sb.toString();
    }
}
