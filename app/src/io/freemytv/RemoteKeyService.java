package io.freemytv;

import android.accessibilityservice.AccessibilityService;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

/**
 * Sees the remote's keys before any app does, so the app buttons work from inside Netflix, Disney and the rest. The
 * system waits for it on every key (up to half a second), so it runs in a process of its own and does next to nothing.
 *
 * On Fire TV it also takes the Home button. Amazon's launcher keeps HOME (priority 950, a protected package, and
 * set-home-activity is ignored), so in Free My TV mode, when Amazon's home screen comes to the front, we open ours
 * over it (FireHomeActivity: HomeActivity's resumeWhilePausing leaves the remote's focus on Amazon's launcher).
 * Fire OS 7 handles Home before the key filter (it never reaches onKeyEvent), and Home holds app starts back for
 * about 5 s (stopAppSwitches), so ours comes up that long after Amazon's. At power-on it leaves the TV to come back
 * to its last input first (PowerOn), and while you're in Fire TV's settings (a page of Amazon's home screen:
 * FireSettings) it waits for Home. It also shows the game alerts (GameAlerts).
 */
public class RemoteKeyService extends AccessibilityService {
    /** Window-state events come in bursts: one redirect per burst. */
    private static final long REDIRECT_GAP = 800;
    private long lastRedirect;

    private final BroadcastReceiver screenOn = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent intent) {
            PowerOn.screenOn(RemoteKeyService.this);
        }
    };

    /** Banners over any app for the games you'd want to know about (GameAlerts). */
    private final GameAlerts alerts = new GameAlerts(this);

    /** Every Home press, also where no window changes (Amazon's home screen already in front). */
    private final HomeWatcher homeWatcher = new HomeWatcher(this, () -> {
        FireSettings.homePressed(this);
        if (HomeMode.amazonRedirect(this) && !PowerOn.holding()) redirect();
    });

    @Override protected void onServiceConnected() {
        super.onServiceConnected();
        registerReceiver(screenOn, new IntentFilter(Intent.ACTION_SCREEN_ON));
        if (HomeMode.AMAZON) {
            homeWatcher.start();
            alerts.start();
        }
        // Started by a boot rather than woken: the screen-on came before us.
        if (SystemClock.elapsedRealtime() < 3 * 60_000) PowerOn.screenOn(this);
    }

    @Override public void onDestroy() {
        homeWatcher.stop();
        alerts.stop();
        try {
            unregisterReceiver(screenOn);
        } catch (IllegalArgumentException ignored) {
        }
        super.onDestroy();
    }

    @Override protected boolean onKeyEvent(KeyEvent event) {
        return RemoteKeys.onKey(this, event);
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        CharSequence pkg = event.getPackageName();
        if (pkg != null) FireSettings.windowOf(this, pkg.toString());
        if (pkg == null || !HomeMode.AMAZON_LAUNCHER.contentEquals(pkg) || !HomeMode.amazonRedirect(this)) return;
        // Its home screen only: the launcher also shows the search other apps open (the Appstore's), title pages
        // and sign-ups, which should stay.
        CharSequence cls = event.getClassName();
        if (cls != null && !cls.toString().startsWith(HomeMode.AMAZON_LAUNCHER + ".ui.Home")) return;
        if (PowerOn.holding() || FireSettings.passing(this)) return;
        redirect();
    }

    private void redirect() {
        long now = SystemClock.elapsedRealtime();
        if (now - lastRedirect < REDIRECT_GAP) return;
        lastRedirect = now;
        try {
            startActivity(new Intent(this, FireHomeActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (RuntimeException e) {
            AppLog.w("home redirect: " + e.getMessage());
        }
    }

    @Override public void onInterrupt() {}
}
