package dev.blacksheep.grabnetprinter;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Reads the user's in-app settings from the module app's {@link ConfigProvider}.
 *
 * <p>Runs inside the <b>target app process</b> (the hooked app), which cannot read the module
 * app's private storage. A read-only exported {@link android.content.ContentProvider} is the
 * clean, SELinux-safe way to hand configuration across that boundary: no root, no world-readable
 * files, works on enforcing devices (unlike the legacy {@code /data/local/tmp} file).</p>
 *
 * <p>The provider exposes only keys the user actually saved; any failure (provider missing,
 * package visibility, target app restrictions) returns an empty map so {@link Config} falls back
 * to the legacy file and compiled defaults.</p>
 */
final class ConfigProviderClient {

    private ConfigProviderClient() {
    }

    /** Query the provider for the saved settings. Never throws; returns an empty map on failure. */
    static Map<String, String> read(Context context) {
        Map<String, String> values = new LinkedHashMap<String, String>();
        if (context == null) {
            return values;
        }
        Cursor cursor = null;
        try {
            ContentResolver resolver = context.getContentResolver();
            cursor = resolver.query(ConfigProvider.CONTENT_URI, new String[] {"key", "value"},
                    null, null, null);
            if (cursor == null || !cursor.moveToFirst()) {
                return values;
            }
            int keyIndex = cursor.getColumnIndex("key");
            int valueIndex = cursor.getColumnIndex("value");
            if (keyIndex < 0 || valueIndex < 0) {
                return values;
            }
            do {
                String key = cursor.getString(keyIndex);
                String value = cursor.getString(valueIndex);
                if (key != null && value != null) {
                    values.put(key, value);
                }
            } while (cursor.moveToNext());
        } catch (Throwable ignored) {
            // Provider missing / visibility / target restrictions: legacy file fallback applies.
        } finally {
            if (cursor != null) {
                try {
                    cursor.close();
                } catch (Throwable ignored) {
                    // ignore
                }
            }
        }
        return values;
    }

    /** Convenience for diagnostics: the provider URI as a string. */
    static String describe() {
        return Uri.parse("content://" + ConfigProvider.AUTHORITY + "/config").toString();
    }
}
