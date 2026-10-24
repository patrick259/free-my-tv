package io.freemytv;

import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;

import java.io.File;

/**
 * Fire TV's settings grid is a page of Amazon's home screen (the settings request opens HomeActivity on its Settings
 * tab), so our Home redirect would bounce straight back from it: both look the same to us ("Home"). Opening TV
 * Settings from our drawer therefore starts a pass (a file, read by RemoteKeyService in its own process) that lets
 * Amazon's home screen stay until Home is pressed (HomeWatcher), another app comes up, or half an hour at most.
 */
final class FireSettings {
    private FireSettings() {}

    private static final long PASS_MS = 30 * 60_000L;
    /** Home pressed this recently: the next Amazon home screen is the real one. */
    private static final long HOME_MS = 8_000L;
    private static long homeAt;

    private static File file(Context c) {
        return new File(c.getFilesDir(), "tv_settings_pass");
    }

    /** The settings request, with the pass. */
    static Intent open(Context c) {
        try {
            file(c).createNewFile();
            file(c).setLastModified(System.currentTimeMillis());
        } catch (java.io.IOException e) {
            AppLog.w("tv settings pass: " + e.getMessage());
        }
        return new Intent(android.provider.Settings.ACTION_SETTINGS);
    }

    /** Home was pressed: the pass ends. */
    static void homePressed(Context c) {
        homeAt = SystemClock.elapsedRealtime();
        file(c).delete();
    }

    /** A window came up: another app (not Amazon's settings screens) ends the pass. */
    static void windowOf(Context c, String pkg) {
        if (pkg.equals(HomeMode.AMAZON_LAUNCHER) || pkg.startsWith("com.amazon.tv.settings") || pkg.startsWith("com.amazon.tv.inputpreference")
                || pkg.equals("com.amazon.tv.quicksettings") || pkg.equals("android") || pkg.startsWith("com.amazon.tv.devicecontrol")) return;
        File f = file(c);
        if (f.exists()) f.delete();
    }

    /** Should Amazon's home screen stay (we're in its settings)? */
    static boolean passing(Context c) {
        if (SystemClock.elapsedRealtime() - homeAt < HOME_MS) return false;
        File f = file(c);
        if (!f.exists()) return false;
        if (System.currentTimeMillis() - f.lastModified() > PASS_MS) {
            f.delete();
            return false;
        }
        return true;
    }
}
