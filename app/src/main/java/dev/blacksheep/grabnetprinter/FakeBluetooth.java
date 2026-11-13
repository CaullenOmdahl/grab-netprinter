package dev.blacksheep.grabnetprinter;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothClass;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.os.Parcel;
import android.os.ParcelUuid;

import de.robv.android.xposed.XposedHelpers;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Factory and registry for all fabricated Bluetooth objects.
 *
 * <ul>
 *   <li><b>BluetoothDevice</b>: a real {@code BluetoothDevice} for the fake MAC is obtained with
 *       the public {@code BluetoothAdapter.getRemoteDevice(mac)}, falling back to the hidden
 *       {@code BluetoothDevice(String)} constructor if there is no adapter. Every other device
 *       method is answered by hooks, so an ordinary (unbonded, classless) device object is
 *       enough.</li>
 *   <li><b>BluetoothClass</b>: built exactly the way SPEC describes - write {@code 0x0680} into a
 *       {@link Parcel} and run {@code BluetoothClass.CREATOR.createFromParcel}. No hidden API
 *       involved: the class hash code, major class {@code 0x0600} (IMAGING) and device class
 *       {@code 0x0680} (PRINTER) all fall out of that one integer, which is what the Grab app
 *       filters on.</li>
 *   <li><b>BluetoothSocket</b>: a zero-initialised instance allocated with
 *       {@code sun.misc.Unsafe.allocateInstance(BluetoothSocket.class)}. Its constructor would ask
 *       the Bluetooth service for a file descriptor, which is exactly what is unavailable on this
 *       device. LSPosed runs the module hidden-API exempt, so this works in practice; both the
 *       Unsafe and ReflectionFactory strategies are attempted and failure is logged loudly.</li>
 * </ul>
 *
 * <p>All fabricated objects are tracked in identity maps so hooks can tell "our fake" from a real
 * framework object. Lookups are synchronized; the registries live for the process lifetime (a
 * handful of entries, never cleared - GrabMerchant creates one socket per print job, so the map
 * grows slowly; entries are tiny references and the app itself may cache the same sockets).</p>
 */
public final class FakeBluetooth {

    /** Bluetooth SPP / Serial Port Profile service UUID. */
    public static final UUID SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");

    /** 0x0600 major class IMAGING | 0x0680 device class PRINTER. */
    public static final int PRINTER_CLASS = 0x0680;

    private static volatile FakeBluetooth instance;

    private final Config cfg;
    private final Object createLock = new Object();
    private final ParcelUuid[] uuids = new ParcelUuid[] {new ParcelUuid(SPP_UUID)};

    private final Map<Object, Boolean> fakeDevices =
            Collections.synchronizedMap(new IdentityHashMap<Object, Boolean>());
    private final Map<Object, Boolean> fakeSockets =
            Collections.synchronizedMap(new IdentityHashMap<Object, Boolean>());
    private final Map<Object, SocketBridge> bridges =
            Collections.synchronizedMap(new IdentityHashMap<Object, SocketBridge>());

    /** Re-entrancy guard: hooks may call back into this class while it is creating objects. */
    private final ThreadLocal<Boolean> creating = new ThreadLocal<Boolean>();

    private volatile BluetoothDevice fakeDevice;
    private volatile BluetoothClass fakeClass;
    private volatile boolean deviceFailed;
    private volatile boolean classFailed;
    private volatile boolean socketFailed;

    private volatile Object unsafe;
    private volatile Method unsafeAllocateInstance;

    private FakeBluetooth(Config cfg) {
        this.cfg = cfg;
    }

    /** Creates (or returns) the per-process singleton bound to {@code cfg}. */
    public static FakeBluetooth init(Config cfg) {
        synchronized (FakeBluetooth.class) {
            if (instance == null) {
                instance = new FakeBluetooth(cfg == null ? Config.get() : cfg);
            }
            return instance;
        }
    }

    /** Returns the singleton, creating it from the current config on first use. */
    public static FakeBluetooth get() {
        FakeBluetooth current = instance;
        if (current != null) {
            return current;
        }
        return init(Config.get());
    }

    /**
     * Creates the fake device and class ahead of hook installation. Doing this before the
     * {@code BluetoothAdapter} hooks are live keeps device creation on the plain public path
     * (no chance of re-entering a hooked method while the object is being built).
     */
    public void warmup() {
        try {
            getFakeDevice();
        } catch (Throwable t) {
            Log.w("fake device warmup failed", t);
        }
        try {
            getFakeClass();
        } catch (Throwable t) {
            Log.w("fake BluetoothClass warmup failed", t);
        }
    }

    // ---------------------------------------------------------------- device

    /** The single fake bonded printer device, or {@code null} if construction failed. */
    @SuppressWarnings("deprecation") // getDefaultAdapter() is the only API that works on all versions
    public BluetoothDevice getFakeDevice() {
        BluetoothDevice device = fakeDevice;
        if (device != null) {
            return device;
        }
        synchronized (createLock) {
            if (fakeDevice != null) {
                return fakeDevice;
            }
            if (deviceFailed) {
                return null;
            }
            boolean nested = Boolean.TRUE.equals(creating.get());
            creating.set(Boolean.TRUE);
            try {
                String mac = cfg.fakeMac();
                BluetoothDevice built = null;

                if (!nested) {
                    // Public API path: returns a normal BluetoothDevice for our locally
                    // administered MAC. This is preferred because the rest of the framework
                    // (and the app) treats it as a regular object.
                    try {
                        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
                        if (adapter != null) {
                            built = adapter.getRemoteDevice(mac);
                            if (built != null) {
                                Log.d("fake device created via adapter.getRemoteDevice(" + mac + ")");
                            }
                        } else {
                            Log.w("BluetoothAdapter.getDefaultAdapter() returned null", null);
                        }
                    } catch (Throwable t) {
                        Log.w("adapter.getRemoteDevice(" + mac + ") failed", t);
                    }
                }

                if (built == null) {
                    built = newDeviceViaConstructor(mac);
                }
                if (built == null) {
                    deviceFailed = true;
                    Log.e("FATAL: could not create fake BluetoothDevice " + mac
                            + "; paired-printer list will be empty", null);
                    return null;
                }

                registerDevice(built);
                fakeDevice = built;
                return built;
            } finally {
                creating.remove();
            }
        }
    }

    private BluetoothDevice newDeviceViaConstructor(String mac) {
        try {
            Class<?> cls = Class.forName("android.bluetooth.BluetoothDevice");
            Constructor<?> constructor = cls.getDeclaredConstructor(String.class);
            constructor.setAccessible(true);
            BluetoothDevice device = (BluetoothDevice) constructor.newInstance(mac);
            Log.d("fake device created via reflective BluetoothDevice(String)");
            return device;
        } catch (Throwable t) {
            Log.w("reflective BluetoothDevice(String) construction failed", t);
            return null;
        }
    }

    /**
     * The fake BluetoothClass (major IMAGING 0x0600 / device PRINTER 0x0680), or {@code null}
     * if the Parcelable trick failed - in that case the app filters our device out and the
     * failure is reported in the log.
     */
    public BluetoothClass getFakeClass() {
        BluetoothClass current = fakeClass;
        if (current != null) {
            return current;
        }
        synchronized (createLock) {
            if (fakeClass != null) {
                return fakeClass;
            }
            if (classFailed) {
                return null;
            }
            Parcel parcel = null;
            try {
                parcel = Parcel.obtain();
                parcel.writeInt(PRINTER_CLASS);
                parcel.setDataPosition(0);
                BluetoothClass created = BluetoothClass.CREATOR.createFromParcel(parcel);
                if (created == null) {
                    throw new IllegalStateException("BluetoothClass.CREATOR returned null");
                }
                fakeClass = created;
                Log.d("fake BluetoothClass 0x" + Integer.toHexString(PRINTER_CLASS)
                        + " (major=0x" + Integer.toHexString(created.getMajorDeviceClass())
                        + ", device=0x" + Integer.toHexString(created.getDeviceClass())
                        + ", hash=0x" + Integer.toHexString(created.hashCode()) + ")");
                return created;
            } catch (Throwable t) {
                classFailed = true;
                fakeClass = null;
                Log.e("FATAL: could not build fake BluetoothClass via Parcel/BluetoothClass.CREATOR;"
                        + " GrabMerchant will not list the printer", t);
                return null;
            } finally {
                if (parcel != null) {
                    try {
                        parcel.recycle();
                    } catch (Throwable ignored) {
                        // ignore
                    }
                }
            }
        }
    }

    /** ParcelUuid array exposed through the hooked {@code BluetoothDevice.getUuids()}. */
    public ParcelUuid[] getUuids() {
        return uuids.clone();
    }

    // ---------------------------------------------------------------- socket

    /**
     * Allocates a fresh fake {@code BluetoothSocket} plus its {@link SocketBridge}.
     * A fresh pair is created on every {@code createRfcommSocket*()} call, which matches what
     * the app expects and makes the reflective fallback path retry on a new TCP connection.
     *
     * @return the fake socket, or {@code null} if allocation failed (logged)
     */
    public BluetoothSocket newFakeSocket() {
        BluetoothSocket socket = allocateSocketWithUnsafe();
        if (socket == null) {
            socket = allocateSocketWithReflectionFactory();
        }
        if (socket == null) {
            if (!socketFailed) {
                socketFailed = true;
                Log.e("FATAL: could not allocate a fake BluetoothSocket"
                        + " (Unsafe and ReflectionFactory both failed)", null);
            }
            return null;
        }
        SocketBridge bridge = new SocketBridge(cfg);
        synchronized (bridges) {
            bridges.put(socket, bridge);
        }
        fakeSockets.put(socket, Boolean.TRUE);
        Log.debugOnce("fake-socket-created", "fake BluetoothSocket allocated ("
                + (socket.getClass().getName()) + "), bridged to " + bridge.describe());
        return socket;
    }

    private BluetoothSocket allocateSocketWithUnsafe() {
        try {
            Object unsafeObject = unsafeInstance();
            Method allocate = unsafeAllocateInstance;
            if (unsafeObject == null || allocate == null) {
                return null;
            }
            Object allocated = allocate.invoke(unsafeObject, BluetoothSocket.class);
            if (allocated instanceof BluetoothSocket) {
                return (BluetoothSocket) allocated;
            }
            Log.w("Unsafe.allocateInstance returned unexpected type for BluetoothSocket", null);
        } catch (Throwable t) {
            Log.w("Unsafe.allocateInstance(BluetoothSocket) failed", t);
        }
        return null;
    }

    private Object unsafeInstance() {
        Object current = unsafe;
        if (current != null) {
            return current;
        }
        synchronized (createLock) {
            if (unsafe != null) {
                return unsafe;
            }
            try {
                Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
                Field theUnsafe = unsafeClass.getDeclaredField("theUnsafe");
                theUnsafe.setAccessible(true);
                Object instanceObject = theUnsafe.get(null);
                Method allocate = unsafeClass.getMethod("allocateInstance", Class.class);
                unsafe = instanceObject;
                unsafeAllocateInstance = allocate;
                Log.d("sun.misc.Unsafe obtained (theUnsafe)");
                return instanceObject;
            } catch (Throwable t) {
                Log.w("sun.misc.Unsafe unavailable; will try ReflectionFactory", t);
                unsafe = null;
                return null;
            }
        }
    }

    /**
     * Last-resort allocation using serialization-style constructor construction. This exists
     * only because Unsafe could disappear in a future libcore; it is expected to fail on most
     * Android builds and is reported at debug level.
     */
    private BluetoothSocket allocateSocketWithReflectionFactory() {
        try {
            Class<?> factoryClass = Class.forName("sun.reflect.ReflectionFactory");
            Method getFactory = factoryClass.getMethod("getReflectionFactory");
            Object factory = getFactory.invoke(null);
            Method newConstructor = factoryClass.getMethod("newConstructorForSerialization",
                    Class.class, Constructor.class);
            Constructor<?> objectConstructor = Object.class.getDeclaredConstructor();
            Constructor<?> constructor = (Constructor<?>) newConstructor.invoke(factory,
                    BluetoothSocket.class, objectConstructor);
            constructor.setAccessible(true);
            Object allocated = constructor.newInstance();
            if (allocated instanceof BluetoothSocket) {
                Log.d("fake BluetoothSocket allocated via ReflectionFactory");
                return (BluetoothSocket) allocated;
            }
        } catch (Throwable t) {
            Log.d("ReflectionFactory allocation path unavailable: " + t);
        }
        return null;
    }

    /** The bridge for a fake socket, or {@code null} when the object is not ours. */
    public SocketBridge getBridge(Object socket) {
        if (socket == null) {
            return null;
        }
        return bridges.get(socket);
    }

    // -------------------------------------------------------------- registry

    public boolean isFakeDevice(Object candidate) {
        return candidate != null && fakeDevices.containsKey(candidate);
    }

    public boolean isFakeSocket(Object candidate) {
        return candidate != null && fakeSockets.containsKey(candidate);
    }

    /**
     * True when {@code candidate} is the registered fake device object <b>or</b> any
     * {@code BluetoothDevice} that carries the configured fake MAC.
     *
     * <p>The MAC fallback matters for objects the framework re-creates from a {@link Parcel}
     * (a broadcast Intent's {@code EXTRA_DEVICE} arrives as a new instance) and for code paths
     * that build a device themselves instead of calling our hooked
     * {@code adapter.getRemoteDevice()}. Without it those instances would be treated as real,
     * unbonded devices and the app would filter the printer out.</p>
     */
    public boolean isFakeDeviceLike(Object candidate) {
        if (candidate == null) {
            return false;
        }
        if (fakeDevices.containsKey(candidate)) {
            return true;
        }
        if (!(candidate instanceof BluetoothDevice)) {
            return false;
        }
        try {
            Object address = XposedHelpers.getObjectField(candidate, "mAddress");
            return address instanceof String && matchesFakeMac((String) address);
        } catch (Throwable ignored) {
            // Field renamed/removed: identity registry is the only signal then.
            return false;
        }
    }

    private void registerDevice(BluetoothDevice device) {
        if (device != null) {
            fakeDevices.put(device, Boolean.TRUE);
        }
    }

    /** Case-insensitive comparison against the configured fake MAC (trimmed). */
    public boolean matchesFakeMac(String address) {
        if (address == null) {
            return false;
        }
        return cfg.fakeMac().equalsIgnoreCase(address.trim());
    }

    /** Converts a six-byte address (as passed to {@code getRemoteDevice(byte[])}) to "AA:BB:...". */
    public static String macFromBytes(byte[] address) {
        if (address == null || address.length != 6) {
            return null;
        }
        StringBuilder sb = new StringBuilder(17);
        for (int i = 0; i < address.length; i++) {
            if (i > 0) {
                sb.append(':');
            }
            int value = address[i] & 0xFF;
            if (value < 0x10) {
                sb.append('0');
            }
            sb.append(Integer.toHexString(value).toUpperCase(Locale.US));
        }
        return sb.toString();
    }

    public String fakeMac() {
        return cfg.fakeMac();
    }

    public String fakeName() {
        return cfg.fakeName();
    }
}
