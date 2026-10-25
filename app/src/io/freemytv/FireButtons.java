package io.freemytv;

import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;

/**
 * Fire TV: the remote's app buttons, remapped. Fire OS handles them itself (they never reach the key filter): each
 * press starts the Appstore's app launcher (venezia's pdi.AppLaunchActivity, started by the system), which then opens
 * the app, or its store page when it isn't installed. We follow that in the system log (HomeWatcher) and open the
 * app we'd rather have over it. Opening Peacock any other way (the shelf, All Apps) doesn't go through that launcher,
 * so it's left alone.
 */
final class FireButtons {
    private FireButtons() {}

    /** {app the button opens, app to open instead}. "store" is a button whose app isn't installed (DirecTV). */
    private static final String[][] REMAP = {
        {"com.peacock.peacockfiretv", "com.cbs.ott"},                 // Peacock → Paramount+
        {"store", "com.amazon.firetv.youtube.tv"},                    // DirecTV → YouTube TV
    };
    /** After the button's launcher starts, the app (or its store page) follows within this. */
    private static final long FOLLOW_MS = 4000;
    private static long buttonAt;

    /** A line of ActivityManager's log ("START u0 {…}"). */
    static void line(Context c, String line) {
        if (!line.contains("START u0")) return;
        long now = SystemClock.elapsedRealtime();
        if (line.contains("com.amazon.venezia/.pdi.AppLaunchActivity") && line.contains("from uid 1000")) {
            buttonAt = now;
            return;
        }
        if (now - buttonAt > FOLLOW_MS) return;
        String opened = line.contains("com.amazon.venezia/.details.AppDetailsActivity") ? "store" : null;
        for (String[] r : REMAP) if (opened == null && line.contains("pkg=" + r[0] + " ")) opened = r[0];
        if (opened == null) return;
        for (String[] r : REMAP) {
            if (!r[0].equals(opened)) continue;
            buttonAt = 0;
            Intent intent = c.getPackageManager().getLeanbackLaunchIntentForPackage(r[1]);
            if (intent == null) intent = c.getPackageManager().getLaunchIntentForPackage(r[1]);
            AppLog.w("app button: " + opened + " -> " + r[1] + (intent == null ? " (not installed)" : ""));
            if (intent == null) return;
            try {
                c.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED));
            } catch (RuntimeException e) {
                AppLog.w("app button: " + e.getMessage());
            }
        }
    }
}
