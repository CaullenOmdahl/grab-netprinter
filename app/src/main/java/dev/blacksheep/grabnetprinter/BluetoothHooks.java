package dev.blacksheep.grabnetprinter;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

import java.io.IOException;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Installs every {@code android.bluetooth.*} hook the module needs.
 *
 * <p>All hooks work on string class names resolved through the app's class loader, so renames or
 * hidden members are handled by the reflective lookup rather than by compile-time references.
 * Every individual hook installation is guarded: a missing method (different Android version) or
 * a failing hook never prevents the remaining hooks from being installed.</p>
 *
 * <p>Object filtering: adapter hooks affect the whole process (we want Bluetooth to look enabled);
 * device hooks only act on our fake device (identity registry plus fake-MAC check); socket hooks
 * only act on fake sockets allocated by {@link FakeBluetooth}.</p>
 *
 * <p>Every hook body is wrapped in {@code try/catch (Throwable)} and only logs on failure - an
 * exception inside a hook must never propagate into GrabMerchant.</p>
 */
public final class BluetoothHooks {

    /** {@code BluetoothAdapter.STATE_ON} */
    private static final int STATE_ON = 12;
    /** {@code BluetoothDevice.BOND_BONDED} */
    private static final int BOND_BONDED = 12;
    /** {@code BluetoothDevice.DEVICE_TYPE_CLASSIC} */
    private static final int DEVICE_TYPE_CLASSIC = 1;
    /** {@code BluetoothProfile.STATE_CONNECTED} for the hidden socket connection-state getter. */
    private static final int PROFILE_STATE_CONNECTED = 2;
    /** Fake L2CAP/RFCOMM packet size reported to the app; conservative, real SPP printers use 990. */
    private static final int FAKE_PACKET_SIZE = 990;

    private static final String[] DEVICE_HOOKED_METHODS = new String[] {
            "getAddress",
            "getName",
            "getAlias",
            "getBondState",
            "getType",
            "getUuids",
            "getBluetoothClass",
            "fetchUuidsWithSdp",
            "createBond",
            "createRfcommSocketToServiceRecord",
            "createInsecureRfcommSocketToServiceRecord",
            "createRfcommSocket",
    };

    private static final String[] SOCKET_HOOKED_METHODS = new String[] {
            "connect",
            "close",
            "isConnected",
            "getOutputStream",
            "getInputStream",
            "getRemoteDevice",
            "getMaxReceivePacketSize",
            "getMaxTransmitPacketSize",
            "setSocketOpt",
            "getSocketOpt",
            "getConnectionState",
    };

    private static final AtomicBoolean INSTALLED = new AtomicBoolean(false);

    private BluetoothHooks() {
    }

    /**
     * Installs the hooks once per process. Safe to call multiple times.
     *
     * @param classLoader the target app's class loader ({@code lpparam.classLoader})
     * @param cfg         validated configuration
     */
    public static void install(ClassLoader classLoader, Config cfg) {
        if (classLoader == null) {
            Log.e("cannot install hooks: target class loader is null", null);
            return;
        }
        if (!INSTALLED.compareAndSet(false, true)) {
            Log.d("hooks already installed in this process; skipping");
            return;
        }
        try {
            installInternal(classLoader, cfg);
        } catch (Throwable t) {
            Log.e("hook installation failed (module may be partially active)", t);
        }
    }

    private static void installInternal(ClassLoader classLoader, Config cfg) {
        FakeBluetooth fakeBluetooth = FakeBluetooth.init(cfg);
        DiscoveryInjector discovery = DiscoveryInjector.init(cfg, fakeBluetooth);

        // Build the fake objects before the adapter hooks are live: creation then stays on the
        // plain public API path and cannot re-enter a hook.
        fakeBluetooth.warmup();

        // Capture ContextImpl receiver registrations before the app registers its BT receivers.
        discovery.installReceiverCapture(classLoader);

        Class<?> adapterClass = findClass("android.bluetooth.BluetoothAdapter", classLoader);
        if (adapterClass != null) {
            installAdapterHooks(adapterClass, cfg, fakeBluetooth, discovery);
        } else {
            Log.e("android.bluetooth.BluetoothAdapter not found; module cannot work", null);
        }

        Class<?> deviceClass = findClass("android.bluetooth.BluetoothDevice", classLoader);
        if (deviceClass != null) {
            installDeviceHooks(deviceClass, fakeBluetooth);
        } else {
            Log.e("android.bluetooth.BluetoothDevice not found", null);
        }

        Class<?> socketClass = findClass("android.bluetooth.BluetoothSocket", classLoader);
        if (socketClass != null) {
            installSocketHooks(socketClass, fakeBluetooth);
        } else {
            Log.e("android.bluetooth.BluetoothSocket not found", null);
        }

        Log.i("Bluetooth hooks installed: fake " + cfg.fakeMac() + " (\"" + cfg.fakeName()
                + "\") -> tcp " + cfg.printerHost() + ":" + cfg.printerPort()
                + " protocol=" + cfg.protocol()
                + " discovery=" + (cfg.discovery() ? 1 : 0));
    }

    // ---------------------------------------------------------------- adapter

    private static void installAdapterHooks(Class<?> adapterClass, final Config cfg,
                                            final FakeBluetooth fakeBluetooth,
                                            final DiscoveryInjector discovery) {
        // isEnabled() -> true
        hookAllSafe(adapterClass, "isEnabled", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                try {
                    param.setResult(Boolean.TRUE);
                } catch (Throwable t) {
                    Log.w("adapter.isEnabled hook failed", t);
                }
            }
        });

        // getState() -> STATE_ON
        hookAllSafe(adapterClass, "getState", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                try {
                    param.setResult(Integer.valueOf(STATE_ON));
                } catch (Throwable t) {
                    Log.w("adapter.getState hook failed", t);
                }
            }
        });

        // getBondedDevices() -> original set + our fake printer.
        // The original is invoked explicitly (and guarded) because on this device the Bluetooth
        // service is dead and the real call can throw; in that case we return the fake only.
        // A before hook is used so the hook works regardless of how after-hook-on-throw is
        // implemented by the runtime.
        hookAllSafe(adapterClass, "getBondedDevices", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                try {
                    Set<BluetoothDevice> devices = new HashSet<BluetoothDevice>();
                    BluetoothDevice fake = fakeBluetooth.getFakeDevice();
                    if (fake != null) {
                        devices.add(fake);
                    }

                    Object original = null;
                    Throwable originalError = null;
                    try {
                        original = XposedBridge.invokeOriginalMethod(param.method, param.thisObject,
                                param.args);
                    } catch (Throwable t) {
                        originalError = unwrap(t);
                    }

                    if (original instanceof Set) {
                        for (Object item : (Set<?>) original) {
                            if (item instanceof BluetoothDevice) {
                                devices.add((BluetoothDevice) item);
                            }
                        }
                    } else if (originalError != null) {
                        // The real stack usually throws here on this device; that is fine.
                        Log.once("bonded-devices-threw", "adapter.getBondedDevices() threw ("
                                + originalError + "); listing the fake printer anyway");
                    }
                    param.setResult(devices);
                } catch (Throwable t) {
                    Log.w("adapter.getBondedDevices hook failed", t);
                }
            }
        });

        // getRemoteDevice(String|byte[]) -> fake device for the fake MAC
        hookAllSafe(adapterClass, "getRemoteDevice", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                try {
                    String address = extractAddress(param.args);
                    if (address == null || !fakeBluetooth.matchesFakeMac(address)) {
                        return;
                    }
                    BluetoothDevice fake = fakeBluetooth.getFakeDevice();
                    if (fake != null) {
                        param.setResult(fake);
                    } else {
                        Log.once("remote-device-unavailable",
                                "getRemoteDevice(" + address + "): fake device unavailable");
                    }
                } catch (Throwable t) {
                    Log.w("adapter.getRemoteDevice hook failed", t);
                }
            }
        });

        // startDiscovery() -> true and inject fake ACTION_FOUND/UUID/FINISHED broadcasts
        hookAllSafe(adapterClass, "startDiscovery", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                try {
                    param.setResult(Boolean.TRUE);
                    discovery.startInjection();
                } catch (Throwable t) {
                    Log.w("adapter.startDiscovery hook failed", t);
                }
            }
        });

        // cancelDiscovery() -> true, abort pending injections
        hookAllSafe(adapterClass, "cancelDiscovery", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                try {
                    param.setResult(Boolean.TRUE);
                    discovery.cancelInjection();
                } catch (Throwable t) {
                    Log.w("adapter.cancelDiscovery hook failed", t);
                }
            }
        });

        // isDiscovering() -> true only while our injection is running
        hookAllSafe(adapterClass, "isDiscovering", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                try {
                    param.setResult(Boolean.valueOf(discovery.isInjecting()));
                } catch (Throwable t) {
                    Log.w("adapter.isDiscovering hook failed", t);
                }
            }
        });

        // getAddress() -> fake MAC
        hookAllSafe(adapterClass, "getAddress", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                try {
                    param.setResult(fakeBluetooth.fakeMac());
                } catch (Throwable t) {
                    Log.w("adapter.getAddress hook failed", t);
                }
            }
        });

        // getName() -> fake printer name (some SDKs display the adapter name)
        hookAllSafe(adapterClass, "getName", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                try {
                    param.setResult(fakeBluetooth.fakeName());
                } catch (Throwable t) {
                    Log.w("adapter.getName hook failed", t);
                }
            }
        });
    }

    private static String extractAddress(Object[] args) {
        if (args == null || args.length == 0 || args[0] == null) {
            return null;
        }
        Object arg = args[0];
        if (arg instanceof String) {
            return (String) arg;
        }
        if (arg instanceof byte[]) {
            return FakeBluetooth.macFromBytes((byte[]) arg);
        }
        return null;
    }

    // ----------------------------------------------------------------- device

    private static void installDeviceHooks(Class<?> deviceClass, final FakeBluetooth fakeBluetooth) {
        XC_MethodHook deviceHook = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                try {
                    handleDeviceMethod(param, fakeBluetooth);
                } catch (Throwable t) {
                    Log.w("BluetoothDevice hook failed (" + methodName(param) + ")", t);
                }
            }
        };
        for (String method : DEVICE_HOOKED_METHODS) {
            hookAllSafe(deviceClass, method, deviceHook);
        }
    }

    private static void handleDeviceMethod(XC_MethodHook.MethodHookParam param,
                                           FakeBluetooth fakeBluetooth) {
        if (!fakeBluetooth.isFakeDeviceLike(param.thisObject)) {
            return; // a real device: keep the framework behaviour
        }
        String name = methodName(param);
        if ("getAddress".equals(name)) {
            param.setResult(fakeBluetooth.fakeMac());
        } else if ("getName".equals(name) || "getAlias".equals(name)) {
            param.setResult(fakeBluetooth.fakeName());
        } else if ("getBondState".equals(name)) {
            param.setResult(Integer.valueOf(BOND_BONDED));
        } else if ("getType".equals(name)) {
            param.setResult(Integer.valueOf(DEVICE_TYPE_CLASSIC));
        } else if ("getUuids".equals(name)) {
            param.setResult(fakeBluetooth.getUuids());
        } else if ("getBluetoothClass".equals(name)) {
            // May be null if the Parcel trick failed; the app filters the device out then.
            param.setResult(fakeBluetooth.getFakeClass());
        } else if ("fetchUuidsWithSdp".equals(name)) {
            // No-op: the real SDP query would hit the broken stack. DiscoveryInjector delivers
            // the ACTION_UUID broadcast that the app's receiver expects instead.
            param.setResult(Boolean.TRUE);
        } else if ("createBond".equals(name)) {
            param.setResult(Boolean.TRUE);
        } else if ("createRfcommSocketToServiceRecord".equals(name)
                || "createInsecureRfcommSocketToServiceRecord".equals(name)
                || "createRfcommSocket".equals(name)) {
            BluetoothSocket socket = fakeBluetooth.newFakeSocket();
            if (socket != null) {
                param.setResult(socket);
            } else {
                Log.once("fake-socket-unavailable",
                        "createRfcommSocket*(): fake socket allocation failed; falling back to the"
                                + " real (broken) stack for this call");
            }
        }
    }

    // ----------------------------------------------------------------- socket

    private static void installSocketHooks(Class<?> socketClass, final FakeBluetooth fakeBluetooth) {
        XC_MethodHook socketHook = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                try {
                    handleSocketMethod(param, fakeBluetooth);
                } catch (Throwable t) {
                    Log.w("BluetoothSocket hook failed (" + methodName(param) + ")", t);
                }
            }
        };
        for (String method : SOCKET_HOOKED_METHODS) {
            hookAllSafe(socketClass, method, socketHook);
        }
    }

    private static void handleSocketMethod(XC_MethodHook.MethodHookParam param,
                                           FakeBluetooth fakeBluetooth) {
        if (!fakeBluetooth.isFakeSocket(param.thisObject)) {
            return; // real socket: keep framework behaviour
        }
        SocketBridge bridge = fakeBluetooth.getBridge(param.thisObject);
        String name = methodName(param);

        if ("connect".equals(name)) {
            if (bridge == null) {
                param.setThrowable(new IOException(
                        "GrabNetPrinter: fake socket has no TCP bridge"));
                return;
            }
            try {
                bridge.connect();
                param.setResult(null);
            } catch (IOException ioException) {
                // Propagate as IOException: the Grab plugin catches it and tries its reflective
                // createRfcommSocket(1) fallback, which simply opens a fresh TCP connection.
                param.setThrowable(ioException);
            } catch (Throwable t) {
                param.setThrowable(new IOException(
                        "GrabNetPrinter: fake SPP connect failed: " + t, t));
            }
            return;
        }

        if ("close".equals(name)) {
            if (bridge != null) {
                bridge.close();
            }
            param.setResult(null);
            return;
        }

        if ("isConnected".equals(name)) {
            param.setResult(Boolean.valueOf(bridge != null && bridge.isConnected()));
            return;
        }

        if ("getOutputStream".equals(name)) {
            if (bridge == null) {
                param.setThrowable(new IOException(
                        "GrabNetPrinter: fake socket has no TCP bridge"));
                return;
            }
            param.setResult(bridge.getOutputStream());
            return;
        }

        if ("getInputStream".equals(name)) {
            // Raw ESC/POS is write-only here; return an always-EOF stream instead of blocking on
            // the real (non-existent) RFCOMM channel.
            param.setResult(SocketBridge.emptyInputStream());
            return;
        }

        if ("getRemoteDevice".equals(name)) {
            param.setResult(fakeBluetooth.getFakeDevice());
            return;
        }

        if ("getMaxReceivePacketSize".equals(name) || "getMaxTransmitPacketSize".equals(name)) {
            param.setResult(Integer.valueOf(FAKE_PACKET_SIZE));
            return;
        }

        if ("setSocketOpt".equals(name) || "getSocketOpt".equals(name)) {
            param.setResult(Integer.valueOf(0));
            return;
        }

        if ("getConnectionState".equals(name)) {
            param.setResult(Integer.valueOf(bridge != null && bridge.isConnected()
                    ? PROFILE_STATE_CONNECTED : 0));
            return;
        }
    }

    // ---------------------------------------------------------------- helpers

    private static Class<?> findClass(String name, ClassLoader classLoader) {
        try {
            return XposedHelpers.findClass(name, classLoader);
        } catch (Throwable t) {
            Log.w("could not find class " + name, t);
            return null;
        }
    }

    /**
     * Hooks every declared method with the given name (any signature) and guards the whole
     * operation. A method that does not exist on this Android version is only a debug line.
     */
    private static void hookAllSafe(Class<?> targetClass, String methodName,
                                    XC_MethodHook callback) {
        try {
            Set<XC_MethodHook.Unhook> unhooks =
                    XposedBridge.hookAllMethods(targetClass, methodName, callback);
            if (unhooks == null || unhooks.isEmpty()) {
                Log.d("no method " + targetClass.getSimpleName() + "." + methodName
                        + " on this Android version (skipped)");
            } else {
                Log.d("hooked " + targetClass.getSimpleName() + "." + methodName
                        + " (" + unhooks.size() + ")");
            }
        } catch (Throwable t) {
            // A single broken hook must not abort the remaining installations.
            Log.w("failed to hook " + targetClass.getName() + "." + methodName + " (continuing)", t);
        }
    }

    private static String methodName(XC_MethodHook.MethodHookParam param) {
        try {
            return param.method == null ? "?" : param.method.getName();
        } catch (Throwable t) {
            return "?";
        }
    }

    /** Unwraps the reflection wrapper used by {@code XposedBridge.invokeOriginalMethod}. */
    private static Throwable unwrap(Throwable throwable) {
        if (throwable instanceof java.lang.reflect.InvocationTargetException
                && throwable.getCause() != null) {
            return throwable.getCause();
        }
        return throwable;
    }
}
