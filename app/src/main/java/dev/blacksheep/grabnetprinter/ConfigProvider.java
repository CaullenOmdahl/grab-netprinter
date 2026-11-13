package dev.blacksheep.grabnetprinter;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;

import java.util.Map;

/**
 * Read-only exported provider that hands the user's saved settings to the hooked target app.
 *
 * <p>The hooked app runs in a different process with a different uid, so it cannot read this
 * app's private SharedPreferences. Exposing the settings as {@code key/value} rows through a
 * provider avoids root, world-readable files and SELinux permissive mode. The data is not
 * sensitive (LAN printer address + cosmetic Bluetooth identity), and there is intentionally no
 * write API: configuration is changed only from the settings screen.</p>
 */
public final class ConfigProvider extends ContentProvider {

    public static final String AUTHORITY = "dev.blacksheep.grabnetprinter.config";
    public static final Uri CONTENT_URI = Uri.parse("content://" + AUTHORITY + "/config");

    private static final String[] COLUMNS = new String[] {"key", "value"};

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs,
                        String sortOrder) {
        MatrixCursor cursor = new MatrixCursor(COLUMNS);
        try {
            if (getContext() == null) {
                return cursor;
            }
            Map<String, String> values = SettingsStore.savedOverrides(getContext());
            for (Map.Entry<String, String> entry : values.entrySet()) {
                cursor.addRow(new Object[] {entry.getKey(), entry.getValue()});
            }
        } catch (Throwable ignored) {
            // Return an empty cursor: the hooked app falls back to the legacy file/defaults.
        }
        return cursor;
    }

    @Override
    public String getType(Uri uri) {
        return "vnd.android.cursor.dir/vnd.grabnetprinter.setting";
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException("GrabNetPrinter settings are read-only");
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("GrabNetPrinter settings are read-only");
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("GrabNetPrinter settings are read-only");
    }
}
