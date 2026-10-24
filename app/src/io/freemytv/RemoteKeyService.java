package io.freemytv;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
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
 * about 5 s (stopAppSwitches), so ours comes up that long after Amazon's.
 */
public class RemoteKeyService extends AccessibilityService {
    /** Window-state events come in bursts: one redirect per burst. */
    private static final long REDIRECT_GAP = 800;
    private long lastRedirect;

    @Override protected boolean onKeyEvent(KeyEvent event) {
        return RemoteKeys.onKey(this, event);
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        CharSequence pkg = event.getPackageName();
        if (pkg == null || !HomeMode.AMAZON_LAUNCHER.contentEquals(pkg) || !HomeMode.amazonRedirect(this)) return;
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
