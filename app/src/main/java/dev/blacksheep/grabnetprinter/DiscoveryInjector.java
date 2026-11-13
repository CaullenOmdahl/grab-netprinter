package dev.blacksheep.grabnetprinter;

import android.bluetooth.BluetoothClass;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.Looper;
import android.os.Parcelable;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Fakes classic Bluetooth discovery for the Grab app without touching the (broken) real stack.
 *
 * <p>GrabMerchant has two discovery implementations:</p>
 * <ul>
 *   <li>the old Flutter plugin registers a {@link BroadcastReceiver} for
 *       {@code ACTION_FOUND}/{@code ACTION_UUID}/{@code ACTION_DISCOVERY_FINISHED} and calls
 *       {@code device.fetchUuidsWithSdp()} when a device is found;</li>
 *   <li>the newer {@code com.grab.iot.printer} scanner registers for
 *       {@code ACTION_FOUND}/{@code ACTION_BOND_STATE_CHANGED}/{@code ACTION_DISCOVERY_FINISHED}.</li>
 * </ul>
 *
 * <p>Both paths receive data through {@code Context.registerReceiver(...) -> ContextImpl}.
 * This class hooks the private {@code ContextImpl.registerReceiverInternal(...)} (and the public
 * overloads as a safety net) to remember which receivers are registered, then, when
 * {@code BluetoothAdapter.startDiscovery()} is hooked, posts fake broadcasts to those receivers
 * on the main looper after short delays: {@code ACTION_FOUND} (with our device and RSSI -50),
 * then {@code ACTION_UUID} (with the SPP UUID), then {@code ACTION_DISCOVERY_FINISHED}.</p>
 *
 * <p>If receiver capture fails (e.g. someone renames/removes the private method in a future
 * framework), the injector falls back to {@code Context.sendBroadcast(intent)} so the system's
 * normal dispatch still reaches the app's receiver. The fallback is configurable via
 * {@code discovery_broadcast=0}. Everything is disabled when {@code discovery=0}.</p>
 *
 * <p>Receivers are invoked directly (in-process) rather than through the system because the real
 * BluetoothAdapter is not performing discovery on this device; this is also why the injected
 * {@code ACTION_FOUND} reliably triggers the app's {@code fetchUuidsWithSdp()} path.</p>
 */
public final class DiscoveryInjector {

    /** Framework action/extras keys; spelled out as literals so no hidden constants are needed. */
    private static final String ACTION_FOUND = "android.bluetooth.device.action.FOUND";
    private static final String ACTION_UUID = "android.bluetooth.device.action.UUID";
    private static final String ACTION_DISCOVERY_FINISHED =
            "android.bluetooth.adapter.action.DISCOVERY_FINISHED";
    private static final String EXTRA_DEVICE = "android.bluetooth.device.extra.DEVICE";
    private static final String EXTRA_NAME = "android.bluetooth.device.extra.NAME";
    private static final String EXTRA_RSSI = "android.bluetooth.device.extra.RSSI";
    private static final String EXTRA_UUID = "android.bluetooth.device.extra.UUID";
    private static final String EXTRA_CLASS = "android.bluetooth.device.extra.CLASS";

    /** Delays (ms) from startDiscovery() to the fake broadcasts; mirrors real discovery timing. */
    private static final long DELAY_FOUND_MS = 150L;
    private static final long DELAY_UUID_MS = 250L;
    private static final long DELAY_FINISHED_MS = 400L;

    private static volatile DiscoveryInjector instance;

    private final Config cfg;
    private final FakeBluetooth fakeBluetooth;
    private final Handler mainHandler;
    private final Object lock = new Object();
    private final Map<BroadcastReceiver, Registration> receivers =
            new IdentityHashMap<BroadcastReceiver, Registration>();
    private final List<Runnable> pending = new ArrayList<Runnable>();

    private volatile boolean injecting;
    private volatile int activeGeneration;
    private volatile Context lastContext;

    private DiscoveryInjector(Config cfg, FakeBluetooth fakeBluetooth) {
        this.cfg = cfg;
        this.fakeBluetooth = fakeBluetooth;
        Handler handler = null;
        try {
            handler = new Handler(Looper.getMainLooper());
        } catch (Throwable t) {
            Log.e("cannot obtain main looper; discovery injection disabled", t);
        }
        this.mainHandler = handler;
    }

    public static DiscoveryInjector init(Config cfg, FakeBluetooth fakeBluetooth) {
        synchronized (DiscoveryInjector.class) {
            if (instance == null) {
                instance = new DiscoveryInjector(cfg, fakeBluetooth);
            }
            return instance;
        }
    }

    public static DiscoveryInjector get() {
        DiscoveryInjector current = instance;
        if (current != null) {
            return current;
        }
        FakeBluetooth fake = FakeBluetooth.get();
        return init(Config.get(), fake);
    }

    // -------------------------------------------------------- receiver capture

    /**
     * Installs the {@code ContextImpl} hooks that record registered receivers. Must be called
     * before any of the app's receivers are registered (i.e. when the app process starts), which
     * is guaranteed because {@code HookEntry} runs before the application code.
     */
    public void installReceiverCapture(ClassLoader classLoader) {
        try {
            Class<?> contextImpl = XposedHelpers.findClass("android.app.ContextImpl", classLoader);

            XC_MethodHook registerHook = new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        if (param.getThrowable() != null) {
                            return; // registration failed: do not invent a receiver
                        }
                        recordRegistration(param);
                    } catch (Throwable t) {
                        Log.w("receiver capture hook failed", t);
                    }
                }
            };
            // registerReceiverInternal is private and used by every ContextImpl overload; hooking
            // the public overloads as well covers framework versions where the private method is
            // inlined at the call site (then the callee hook would never fire).
            hookAll(contextImpl, "registerReceiverInternal", registerHook);
            hookAll(contextImpl, "registerReceiver", registerHook);

            XC_MethodHook unregisterHook = new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        Object arg = (param.args != null && param.args.length > 0)
                                ? param.args[0] : null;
                        if (arg instanceof BroadcastReceiver) {
                            removeReceiver((BroadcastReceiver) arg);
                        }
                    } catch (Throwable t) {
                        Log.w("unregister capture hook failed", t);
                    }
                }
            };
            hookAll(contextImpl, "unregisterReceiver", unregisterHook);
            Log.d("ContextImpl receiver capture installed");
        } catch (Throwable t) {
            Log.w("could not install receiver capture; discovery falls back to sendBroadcast", t);
        }
    }

    private void recordRegistration(XC_MethodHook.MethodHookParam param) {
        Member member = param.method;
        if (!(member instanceof Method) || param.args == null) {
            return;
        }
        Class<?>[] parameterTypes = ((Method) member).getParameterTypes();
        int receiverIndex = -1;
        int filterIndex = -1;
        for (int i = 0; i < parameterTypes.length; i++) {
            if (receiverIndex < 0 && BroadcastReceiver.class.isAssignableFrom(parameterTypes[i])) {
                receiverIndex = i;
            } else if (filterIndex < 0 && IntentFilter.class.equals(parameterTypes[i])) {
                filterIndex = i;
            }
        }
        if (receiverIndex < 0 || receiverIndex >= param.args.length) {
            return;
        }
        Object receiverArg = param.args[receiverIndex];
        if (!(receiverArg instanceof BroadcastReceiver)) {
            return; // e.g. registerReceiver(null, filter) used to query sticky broadcasts
        }
        IntentFilter filter = null;
        if (filterIndex >= 0 && filterIndex < param.args.length
                && param.args[filterIndex] instanceof IntentFilter) {
            filter = (IntentFilter) param.args[filterIndex];
        }
        Context context = (param.thisObject instanceof Context) ? (Context) param.thisObject : null;

        Set<String> actions = null;
        if (filter != null) {
            actions = new HashSet<String>();
            int actionCount = filter.countActions();
            for (int i = 0; i < actionCount; i++) {
                String action = filter.getAction(i);
                if (action != null) {
                    actions.add(action);
                }
            }
        }

        BroadcastReceiver receiver = (BroadcastReceiver) receiverArg;
        synchronized (lock) {
            Registration existing = receivers.get(receiver);
            Set<String> merged = mergeActions(existing == null ? null : existing.actions,
                    actions, existing != null);
            receivers.put(receiver, new Registration(receiver, merged, context));
            if (context != null) {
                lastContext = context;
            }
        }
        Log.debugOnce("receiver-captured-" + System.identityHashCode(receiver),
                "captured BroadcastReceiver for Bluetooth discovery");
    }

    /**
     * Merges the action set of a repeated registration of the same receiver instance (a receiver
     * may legitimately be registered more than once; we must not lose any action).
     * {@code null} means "no filter known - match everything".
     */
    private static Set<String> mergeActions(Set<String> previous, Set<String> current,
                                            boolean hasPrevious) {
        if (!hasPrevious) {
            return current;
        }
        if (previous == null || current == null) {
            return null; // one of the registrations cannot be filtered: match everything
        }
        Set<String> merged = new HashSet<String>(previous);
        merged.addAll(current);
        return merged;
    }

    private void removeReceiver(BroadcastReceiver receiver) {
        synchronized (lock) {
            receivers.remove(receiver);
        }
    }

    // ------------------------------------------------------------- injection

    /**
     * Called from the {@code BluetoothAdapter.startDiscovery()} hook. Returns {@code false} when
     * injection is disabled (config) or impossible (no main looper), in which case the hook still
     * reports success to the app.
     */
    public boolean startInjection() {
        if (!cfg.discovery()) {
            Log.once("discovery-disabled",
                    "discovery=0: startDiscovery() reports success but no devices are injected");
            return false;
        }
        if (mainHandler == null) {
            Log.once("discovery-no-handler", "discovery=1 but main looper unavailable; not injecting");
            return false;
        }
        final int generation;
        synchronized (lock) {
            cancelLocked();
            generation = ++activeGeneration;
            injecting = true;
        }
        Log.d("discovery injection started (generation " + generation + ")");
        postStage(generation, 0, DELAY_FOUND_MS);
        postStage(generation, 1, DELAY_UUID_MS);
        postStage(generation, 2, DELAY_FINISHED_MS);
        return true;
    }

    /** Called from the {@code BluetoothAdapter.cancelDiscovery()} hook. */
    public void cancelInjection() {
        synchronized (lock) {
            cancelLocked();
            activeGeneration++;
            injecting = false;
        }
        Log.d("discovery injection cancelled by app");
    }

    /** True between startDiscovery() and the injected ACTION_DISCOVERY_FINISHED. */
    public boolean isInjecting() {
        return injecting;
    }

    private void cancelLocked() {
        for (Runnable runnable : pending) {
            try {
                mainHandler.removeCallbacks(runnable);
            } catch (Throwable ignored) {
                // ignore
            }
        }
        pending.clear();
    }

    private void postStage(final int generation, final int stage, long delayMs) {
        Handler handler = mainHandler;
        if (handler == null) {
            return;
        }
        Runnable runnable = new Runnable() {
            @Override
            public void run() {
                synchronized (lock) {
                    pending.remove(this);
                }
                try {
                    if (generation != activeGeneration) {
                        return; // superseded by a newer startDiscovery/cancelDiscovery
                    }
                    switch (stage) {
                        case 0:
                            dispatchFound();
                            break;
                        case 1:
                            dispatchUuid();
                            break;
                        default:
                            dispatchDiscoveryFinished();
                            break;
                    }
                } catch (Throwable t) {
                    Log.w("discovery stage " + stage + " failed", t);
                    if (stage >= 2) {
                        injecting = false;
                    }
                }
            }
        };
        synchronized (lock) {
            pending.add(runnable);
        }
        handler.postDelayed(runnable, delayMs);
    }

    private void dispatchFound() {
        BluetoothDevice device = fakeBluetooth.getFakeDevice();
        Intent intent = new Intent(ACTION_FOUND);
        if (device != null) {
            intent.putExtra(EXTRA_DEVICE, (Parcelable) device);
            intent.putExtra(EXTRA_NAME, fakeBluetooth.fakeName());
        }
        intent.putExtra(EXTRA_RSSI, (short) -50);
        BluetoothClass bluetoothClass = fakeBluetooth.getFakeClass();
        if (bluetoothClass != null) {
            intent.putExtra(EXTRA_CLASS, (Parcelable) bluetoothClass);
        }
        dispatch(intent, "ACTION_FOUND");
    }

    private void dispatchUuid() {
        BluetoothDevice device = fakeBluetooth.getFakeDevice();
        Intent intent = new Intent(ACTION_UUID);
        if (device != null) {
            intent.putExtra(EXTRA_DEVICE, (Parcelable) device);
        }
        intent.putExtra(EXTRA_UUID, fakeBluetooth.getUuids());
        dispatch(intent, "ACTION_UUID");
    }

    private void dispatchDiscoveryFinished() {
        Intent intent = new Intent(ACTION_DISCOVERY_FINISHED);
        dispatch(intent, "ACTION_DISCOVERY_FINISHED");
        injecting = false;
    }

    /**
     * Delivers {@code intent} to every captured receiver whose filter matches the action, on the
     * main thread. Falls back to {@code sendBroadcast} when nothing was captured.
     */
    private void dispatch(Intent intent, String label) {
        List<Registration> snapshot;
        synchronized (lock) {
            snapshot = new ArrayList<Registration>(receivers.values());
        }
        Context fallbackContext = lastContext;
        int matched = 0;
        for (Registration registration : snapshot) {
            try {
                if (registration.actions != null
                        && !registration.actions.contains(intent.getAction())) {
                    continue;
                }
                Context context = registration.context != null
                        ? registration.context : fallbackContext;
                if (context == null) {
                    context = applicationContext();
                }
                if (context == null) {
                    continue;
                }
                registration.receiver.onReceive(context, intent);
                matched++;
            } catch (Throwable t) {
                Log.w("receiver threw while handling injected " + label, t);
            }
        }

        if (matched > 0) {
            Log.d("delivered " + label + " to " + matched + " receiver(s)");
            return;
        }
        if (!snapshot.isEmpty()) {
            // Receivers were captured, none of them listens to this action: nothing to do.
            Log.debugOnce("discovery-no-matching-receiver-" + label,
                    "no captured receiver registered for " + label);
            return;
        }

        if (!cfg.discoveryBroadcast()) {
            Log.debugOnce("discovery-no-receivers-" + label,
                    "no captured receivers for " + label + " and discovery_broadcast=0");
            return;
        }
        Context context = fallbackContext != null ? fallbackContext : applicationContext();
        if (context == null) {
            Log.once("discovery-no-context", "no captured receivers and no Context available; "
                    + "cannot deliver " + label);
            return;
        }
        try {
            // Constrain the implicit broadcast to this app. Note that the framework will parcel
            // the Intent, so the receiving app sees a *copy* of the fake device - that is why the
            // device hooks also recognise our fake device by MAC, not only by identity.
            Intent broadcast = new Intent(intent);
            broadcast.setPackage(context.getPackageName());
            context.sendBroadcast(broadcast);
            Log.once("discovery-broadcast-fallback",
                    "no captured receivers; delivered " + label + " via sendBroadcast fallback");
        } catch (Throwable t) {
            Log.w("sendBroadcast fallback failed for " + label, t);
        }
    }

    /**
     * Last-resort application context, fetched through {@code ActivityThread.currentApplication()}
     * via reflection (ActivityThread is not part of the public SDK).
     */
    private static Context applicationContext() {
        try {
            Class<?> activityThread = Class.forName("android.app.ActivityThread");
            Method currentApplication = activityThread.getMethod("currentApplication");
            Object application = currentApplication.invoke(null);
            if (application instanceof Context) {
                return (Context) application;
            }
        } catch (Throwable ignored) {
            // not available in this process
        }
        return null;
    }

    private static void hookAll(Class<?> targetClass, String methodName, XC_MethodHook hook) {
        try {
            java.util.Set<XC_MethodHook.Unhook> unhooks =
                    XposedBridge.hookAllMethods(targetClass, methodName, hook);
            if (unhooks == null || unhooks.isEmpty()) {
                Log.w("receiver capture: no method " + targetClass.getName() + "." + methodName
                        + " (not hooked)");
            } else {
                Log.d("receiver capture: hooked " + targetClass.getSimpleName() + "."
                        + methodName + " x" + unhooks.size());
            }
        } catch (Throwable t) {
            Log.w("receiver capture: failed to hook " + targetClass.getName() + "." + methodName, t);
        }
    }

    /** One captured receiver registration. */
    private static final class Registration {
        final BroadcastReceiver receiver;
        /** Actions the receiver listens to, or {@code null} when no filter is known. */
        final Set<String> actions;
        final Context context;

        Registration(BroadcastReceiver receiver, Set<String> actions, Context context) {
            this.receiver = receiver;
            this.actions = actions;
            this.context = context;
        }
    }
}
