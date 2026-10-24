package io.freemytv;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;

import java.io.BufferedReader;
import java.io.InputStreamReader;

/**
 * Fire TV: sees every press of Home. Fire OS keeps Home from apps (it never reaches the key filter, and its "homekey"
 * broadcast needs an Amazon permission), and on Amazon's home screen itself a press changes no window, so the
 * redirect alone misses it (in Fire TV's settings, which are a page of that screen, or right after an update). The
 * system logs each press as a start of the home screen by the system ("cat=[android.intent.category.HOME] ... from
 * uid 1000"), so with READ_LOGS (granted over adb by tools/firetv-install.sh) we follow ActivityManager's log.
 * Without the permission it does nothing.
 */
final class HomeWatcher {
    private final Context context;
    private final Runnable onHome;
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile Process process;
    private volatile boolean running;

    HomeWatcher(Context context, Runnable onHome) {
        this.context = context;
        this.onHome = onHome;
    }

    void start() {
        if (context.checkSelfPermission(android.Manifest.permission.READ_LOGS) != PackageManager.PERMISSION_GRANTED) {
            AppLog.w("home watcher: no READ_LOGS");
            return;
        }
        running = true;
        Thread t = new Thread(this::follow, "home-watcher");
        t.setDaemon(true);
        t.start();
    }

    void stop() {
        running = false;
        Process p = process;
        if (p != null) p.destroy();
    }

    private void follow() {
        while (running) {
            try {
                // From now on (-T 1: the newest line only), ActivityManager alone.
                process = new ProcessBuilder("logcat", "-v", "brief", "-T", "1", "ActivityManager:I", "*:S")
                        .redirectErrorStream(true).start();
                try (BufferedReader in = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                    String line;
                    boolean first = true;
                    while (running && (line = in.readLine()) != null) {
                        if (first) {    // the line from before we started
                            first = false;
                            continue;
                        }
                        if (line.contains("START u0") && line.contains("category.HOME") && line.contains("from uid 1000")) {
                            main.post(onHome);
                        }
                    }
                }
            } catch (Exception e) {
                AppLog.w("home watcher: " + e.getMessage());
            }
            if (running) {
                try {
                    Thread.sleep(5000);     // logcat ended (it restarts with the log buffer): follow again
                } catch (InterruptedException e) {
                    return;
                }
            }
        }
    }
}
