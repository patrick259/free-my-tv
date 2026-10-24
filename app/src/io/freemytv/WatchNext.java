package io.freemytv;

import android.content.Context;
import android.database.Cursor;
import android.media.tv.TvContract;
import android.net.Uri;

import java.util.Map;
import java.util.TreeMap;

/**
 * The "Continue watching" rows the streaming apps publish to Android's TV provider (Watch Next), and their channel rows
 * (preview programs). Reading other apps' rows needs READ_TV_LISTINGS, granted over adb
 * (pm grant io.freemytv android.permission.READ_TV_LISTINGS); without it the provider only returns our own (none).
 */
final class WatchNext {
    private WatchNext() {}

    /** For now only counts the rows per app, in the log: does this TV give them to us? */
    static void probe(Context c) {
        final Context app = c.getApplicationContext();
        new Thread(() -> {
            AppLog.w("watch next: " + count(app, TvContract.WatchNextPrograms.CONTENT_URI, TvContract.WatchNextPrograms.COLUMN_PACKAGE_NAME));
            AppLog.w("preview programs: " + count(app, TvContract.PreviewPrograms.CONTENT_URI, TvContract.PreviewPrograms.COLUMN_PACKAGE_NAME));
        }, "watch-next").start();
    }

    private static String count(Context c, Uri uri, String packageColumn) {
        Map<String, Integer> perApp = new TreeMap<>();
        try (Cursor cursor = c.getContentResolver().query(uri, new String[] {packageColumn}, null, null, null)) {
            if (cursor == null) return "no provider";
            while (cursor.moveToNext()) perApp.merge(String.valueOf(cursor.getString(0)), 1, Integer::sum);
        } catch (RuntimeException e) {
            return "refused (" + e.getClass().getSimpleName() + ": " + e.getMessage() + ")";
        }
        return perApp.isEmpty() ? "none" : perApp.toString();
    }
}
