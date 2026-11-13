package dev.blacksheep.grabnetprinter.diag;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothClass;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.Log;

import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * GNP-DIAG — validates the on-device assumptions behind GrabNetPrinter.
 *
 * One launcher activity, no UI, no resources. On start it requests the
 * Android 12+ Bluetooth runtime permissions (best effort), then runs checks
 * B1..B9 on a background thread. Every check logs exactly one line:
 *
 *     STEP=B1 OK <detail>
 *     STEP=B1 ERR <exceptionClass>: <message>
 *
 * and the run ends with STEP=END done, then the activity finishes. Grep with:
 *
 *     adb logcat -s GNP-DIAG
 *
 * On a rooted POS you can pre-grant the permissions so the checks are
 * meaningful without touching the UI:
 *
 *     adb shell pm grant dev.blacksheep.grabnetprinter.diag android.permission.BLUETOOTH_CONNECT
 *     adb shell pm grant dev.blacksheep.grabnetprinter.diag android.permission.BLUETOOTH_SCAN
 *
 * Steps:
 *   B1  BluetoothAdapter.getDefaultAdapter() null?
 *   B2  adapter.getRemoteDevice(fake MAC): address/name/getBondState/getUuids
 *   B3  BluetoothClass.CREATOR.createFromParcel(Parcel.writeInt(0x0680))
 *   B4  sun.misc.Unsafe.theUnsafe + allocateInstance(BluetoothSocket.class)
 *   B5  BluetoothSocket.isConnected() on the allocated (uninitialised) socket
 *   B6  TCP connect to the printer passed with --es printer_host [--es printer_port],
 *       write DLE EOT 1 and read the status reply (skipped when not supplied)
 *   B7  sendBroadcast(ACTION_FOUND + EXTRA_DEVICE) — SecurityException?
 *   B8  adapter.getBondedDevices() size / exception
 *   B9  adapter.isEnabled() / adapter.getState()
 */
public final class DiagActivity extends Activity {

    private static final String TAG = "GNP-DIAG";

    /** Locally-administered unicast address used as the fake printer MAC. */
    private static final String FAKE_MAC = "02:00:00:00:00:01";

    /** Printer endpoint for the optional B6 TCP check, from the launch intent. */
    private String printerHost = "";
    private int printerPort = 9100;

    private static final int REQ_BT_PERMS = 1001;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final AtomicBoolean started = new AtomicBoolean(false);

    // Cross-step state (background thread only).
    private BluetoothAdapter adapter;
    private BluetoothDevice fakeDevice;
    private Object allocatedSocket;

    // ------------------------------------------------------------------ life

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Log.i(TAG, "=== GNP-DIAG start device=" + Build.MODEL + " sdk=" + Build.VERSION.SDK_INT
                + " build=" + Build.DISPLAY + " ===");
        try {
            String host = getIntent().getStringExtra("printer_host");
            if (host != null) {
                printerHost = host.trim();
            }
            String port = getIntent().getStringExtra("printer_port");
            if (port != null && !port.trim().isEmpty()) {
                printerPort = Integer.parseInt(port.trim());
            }
        } catch (Throwable ignored) {
            // keep defaults; B6 is skipped unless a host is supplied
        }
        Log.i(TAG, "STEP=B0 printer_endpoint=" + (printerHost.isEmpty()
                ? "<not supplied>" : printerHost + ":" + printerPort));

        boolean needPerms = Build.VERSION.SDK_INT >= 31
                && (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                        != PackageManager.PERMISSION_GRANTED
                    || checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN)
                        != PackageManager.PERMISSION_GRANTED);
        if (needPerms) {
            Log.i(TAG, "STEP=B0 requesting BLUETOOTH_CONNECT/BLUETOOTH_SCAN");
            requestPermissions(new String[] {
                    Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.BLUETOOTH_SCAN
            }, REQ_BT_PERMS);
            // Give the (possibly auto-granted) dialog a moment, then run anyway:
            // every step is individually guarded, so un-granted permissions just
            // show up as ERR lines instead of aborting the run.
            handler.postDelayed(runAll, 2500);
        } else {
            handler.postDelayed(runAll, 200);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQ_BT_PERMS) return;
        for (int i = 0; i < permissions.length; i++) {
            boolean granted = i < grantResults.length
                    && grantResults[i] == PackageManager.PERMISSION_GRANTED;
            Log.i(TAG, "STEP=B0 permission " + permissions[i] + "=" + granted);
        }
        handler.postDelayed(runAll, 500);
    }

    // ------------------------------------------------------------ run thread

    private final Runnable runAll = new Runnable() {
        @Override
        public void run() {
            if (!started.compareAndSet(false, true)) return; // guard double-schedule
            Thread t = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        runSteps();
                    } finally {
                        Log.i(TAG, "STEP=END done");
                        handler.post(new Runnable() {
                            @Override
                            public void run() {
                                finish();
                            }
                        });
                    }
                }
            }, "gnp-diag");
            t.start();
        }
    };

    /** One guarded diagnostic step. */
    private interface Body {
        void run() throws Throwable;
    }

    private void step(String id, Body body) {
        try {
            body.run();
        } catch (Throwable t) {
            Log.i(TAG, "STEP=" + id + " ERR " + t.getClass().getName() + ": " + t.getMessage());
        }
    }

    private void runSteps() {
        // -- B1: is there any BluetoothAdapter at all? ------------------------
        step("B1", new Body() {
            @Override public void run() {
                adapter = BluetoothAdapter.getDefaultAdapter();
                if (adapter == null) {
                    throw new IllegalStateException("getDefaultAdapter() returned null");
                }
                Log.i(TAG, "STEP=B1 OK adapter=" + adapter.getClass().getName()
                        + " address=" + adapter.getAddress());
            }
        });

        // -- B2: public-API fake device --------------------------------------
        step("B2", new Body() {
            @Override public void run() {
                if (adapter == null) throw new IllegalStateException("no adapter (B1 failed)");
                BluetoothDevice d = adapter.getRemoteDevice(FAKE_MAC);
                fakeDevice = d;
                String name;
                try {
                    name = String.valueOf(d.getName());
                } catch (Throwable t) {
                    name = "<" + t.getClass().getSimpleName() + ">";
                }
                String uuids;
                try {
                    android.os.ParcelUuid[] us = d.getUuids();
                    uuids = us == null ? "null" : String.valueOf(us.length);
                } catch (Throwable t) {
                    uuids = "<" + t.getClass().getSimpleName() + ">";
                }
                Log.i(TAG, "STEP=B2 OK address=" + d.getAddress() + " name=" + name
                        + " bondState=" + d.getBondState() + " (expect 10=none)"
                        + " uuids=" + uuids);
            }
        });

        // -- B3: BluetoothClass via public Parcelable CREATOR ----------------
        step("B3", new Body() {
            @Override public void run() {
                Parcel p = Parcel.obtain();
                try {
                    p.writeInt(0x0680); // major=IMAGING(0x0600), device=PRINTER(0x0680)
                    p.setDataPosition(0);
                    BluetoothClass bc = BluetoothClass.CREATOR.createFromParcel(p);
                    Log.i(TAG, "STEP=B3 OK class=" + bc
                            + " major=0x" + Integer.toHexString(bc.getMajorDeviceClass())
                            + " device=0x" + Integer.toHexString(bc.getDeviceClass())
                            + " hashCode=" + bc.hashCode());
                } finally {
                    p.recycle();
                }
            }
        });

        // -- B4: Unsafe.allocateInstance(BluetoothSocket.class) --------------
        step("B4", new Body() {
            @Override public void run() throws Throwable {
                Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
                Field f = unsafeClass.getDeclaredField("theUnsafe");
                f.setAccessible(true);
                Object unsafe = f.get(null);
                Method alloc = unsafeClass.getMethod("allocateInstance", Class.class);
                Object sock = alloc.invoke(unsafe, BluetoothSocket.class);
                allocatedSocket = sock;
                Log.i(TAG, "STEP=B4 OK unsafe=" + unsafe.getClass().getName()
                        + " instance=" + sock.getClass().getName());
            }
        });

        // -- B5: isConnected() on an uninitialised BluetoothSocket -----------
        step("B5", new Body() {
            @Override public void run() throws Throwable {
                if (allocatedSocket == null) {
                    throw new IllegalStateException("no allocated socket (B4 failed)");
                }
                boolean connected = ((BluetoothSocket) allocatedSocket).isConnected();
                Log.i(TAG, "STEP=B5 OK isConnected=" + connected);
            }
        });

        // -- B6: TCP reachability + harmless DLE EOT 1 status query ----------
        step("B6", new Body() {
            @Override public void run() throws Throwable {
                if (printerHost.isEmpty()) {
                    Log.i(TAG, "STEP=B6 SKIP no printer_host supplied "
                            + "(adb shell am start ... --es printer_host <ip>)");
                    return;
                }
                Socket s = new Socket();
                try {
                    s.connect(new InetSocketAddress(printerHost, printerPort), 3000);
                    s.setSoTimeout(2000);
                    Log.i(TAG, "STEP=B6 TCP connected " + printerHost + ":" + printerPort);
                    OutputStream os = s.getOutputStream();
                    os.write(new byte[] { 0x10, 0x04, 0x01 }); // DLE EOT 1
                    os.flush();
                    Log.i(TAG, "STEP=B6 wrote=100401");
                    InputStream is = s.getInputStream();
                    int r = is.read();
                    Log.i(TAG, "STEP=B6 OK reply=" + (r < 0 ? "<eof>" : "0x" + Integer.toHexString(r)));
                } finally {
                    try {
                        s.close();
                    } catch (Throwable ignored) {
                        // best effort
                    }
                }
            }
        });

        // -- B7: can an app send ACTION_FOUND itself? ------------------------
        step("B7", new Body() {
            @Override public void run() {
                if (fakeDevice == null) {
                    throw new IllegalStateException("no device (B2 failed)");
                }
                Intent i = new Intent("android.bluetooth.device.action.FOUND");
                i.putExtra(BluetoothDevice.EXTRA_DEVICE, (Parcelable) fakeDevice);
                try {
                    sendBroadcast(i);
                    Log.i(TAG, "STEP=B7 OK sendBroadcast(ACTION_FOUND)");
                } catch (SecurityException se) {
                    Log.i(TAG, "STEP=B7 ERR SecurityException: " + se.getMessage());
                }
            }
        });

        // -- B8: bonded devices ---------------------------------------------
        step("B8", new Body() {
            @Override public void run() {
                if (adapter == null) throw new IllegalStateException("no adapter (B1 failed)");
                Set<BluetoothDevice> bonded = adapter.getBondedDevices();
                Log.i(TAG, "STEP=B8 OK count=" + (bonded == null ? "null" : String.valueOf(bonded.size())));
            }
        });

        // -- B9: adapter state ----------------------------------------------
        step("B9", new Body() {
            @Override public void run() {
                if (adapter == null) throw new IllegalStateException("no adapter (B1 failed)");
                Log.i(TAG, "STEP=B9 OK enabled=" + adapter.isEnabled()
                        + " state=" + adapter.getState());
            }
        });
    }
}
