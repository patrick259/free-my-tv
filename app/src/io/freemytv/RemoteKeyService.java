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
 * On Amazon Fire OS it does one more thing. Fire OS hard-pins the physical Home button to Amazon's launcher
 * (com.amazon.tv.launcher, intent-filter priority ~950) and ignores set-home-activity, so our HomeActivity can never
 * become the real default. Instead, while Free My TV mode is on, we watch for the Amazon launcher coming to the
 * foreground and bounce straight to our home screen. It is the same approach Projectivy and "Home on Fire" use, and it
 * needs only the one Accessibility toggle the user already grants for the remote buttons -- no ADB.
 */
public class RemoteKeyService extends AccessibilityService {
    private long lastRedirect;

    @Override protected boolean onKeyEvent(KeyEvent event) {
        return RemoteKeys.onKey(this, event);
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null || event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        CharSequence pkg = event.getPackageName();
        // Only react to Amazon's launcher taking the foreground; this package exists only on Fire OS, so the redirect
        // is inert on every other platform.
        if (pkg == null || !HomeMode.AMAZON_LAUNCHER.contentEquals(pkg)) return;
        // Take over Home only when the user has turned Free My TV mode on (state is a file shared across processes).
        if (!HomeMode.FREE.equals(HomeMode.state(this).optString("mode"))) return;
        // Window-state events can arrive in bursts; one redirect per short window is enough and avoids hammering.
        long now = SystemClock.elapsedRealtime();
        if (now - lastRedirect < 800L) return;
        lastRedirect = now;
        try {
            startActivity(new Intent(this, HomeActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP));
        } catch (Exception e) {
            AppLog.w("Fire OS home redirect failed: " + e.getMessage());
        }
    }

    @Override public void onInterrupt() {}
}
