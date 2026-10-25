package io.freemytv;

import android.accessibilityservice.AccessibilityService;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * The sleep timer: the TV goes to standby after 15 minutes to 2 hours (SleepTimerActivity picks). A minute before, a
 * card over whatever is playing offers "15 more minutes" and "Turn off now"; nobody answers, the TV goes to standby.
 * An ordinary app can't put Android to sleep: the accessibility "lock screen" action does it on Android 9 and later,
 * so the alarms land in RemoteKeyService's process (:keys) and it acts. The approach is CleanYourTV's sleep-timer
 * add-on (MIT, github.com/Gathin23/cleanyourtv).
 *
 * The end time is a file, not SharedPreferences: the picker (main process) writes it and :keys reads it, and each
 * process would keep its own copy of the preferences.
 */
final class SleepTimer {
    private SleepTimer() {}

    static final int[] MINUTES = {15, 30, 60, 90, 120};
    static final long WARN_MS = 60_000, MORE_MS = 15 * 60_000L;
    private static final String WARN = "io.freemytv.SLEEP_WARN", SLEEP = "io.freemytv.SLEEP_NOW";
    /** adb shell am broadcast -n io.freemytv/.SleepTimer\$Receiver -a io.freemytv.SLEEP_TEST --ei seconds 70 */
    private static final String TEST = "io.freemytv.SLEEP_TEST";

    private static File file(Context c) {
        return new File(c.getFilesDir(), "sleep-timer");
    }

    /** When the TV goes to standby (wall clock), or 0 when no timer is on. */
    static long end(Context c) {
        try {
            long end = Long.parseLong(new String(Files.readAllBytes(file(c).toPath()), StandardCharsets.UTF_8).trim());
            return end > System.currentTimeMillis() - 5_000 ? end : 0;
        } catch (IOException | NumberFormatException e) {
            return 0;
        }
    }

    static void start(Context c, long ms) {
        long end = System.currentTimeMillis() + ms;
        try (FileOutputStream out = new FileOutputStream(file(c))) {
            out.write(Long.toString(end).getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            AppLog.w("sleep timer: " + e.getMessage());
            return;
        }
        schedule(c, WARN, Math.max(System.currentTimeMillis(), end - WARN_MS));
        schedule(c, SLEEP, end);
        AppLog.w("sleep timer: " + ms / 60_000 + " min");
    }

    static void cancel(Context c) {
        //noinspection ResultOfMethodCallIgnored
        file(c).delete();
        AlarmManager am = c.getSystemService(AlarmManager.class);
        am.cancel(pending(c, WARN));
        am.cancel(pending(c, SLEEP));
    }

    /** Only where RemoteKeyService is on: it's what puts the TV to sleep. */
    static boolean available(Context c) {
        return Build.VERSION.SDK_INT >= 28 && HomeMode.remoteServiceEnabled(c);
    }

    private static void schedule(Context c, String action, long at) {
        AlarmManager am = c.getSystemService(AlarmManager.class);
        PendingIntent pi = pending(c, action);
        if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
        else am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
    }

    private static PendingIntent pending(Context c, String action) {
        return PendingIntent.getBroadcast(c, 0, new Intent(c, Receiver.class).setAction(action),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    /** The two alarms, in :keys: a minute early the card, at the end standby. */
    public static final class Receiver extends BroadcastReceiver {
        @Override public void onReceive(Context c, Intent intent) {
            if (TEST.equals(intent.getAction())) {
                // From adb only (the receiver needs DUMP): a timer of a few seconds, to try it out.
                start(c, intent.getIntExtra("seconds", 70) * 1000L);
                return;
            }
            long end = end(c);
            if (end == 0) return;                                   // turned off, or pushed back since
            if (!((PowerManager) c.getSystemService(Context.POWER_SERVICE)).isInteractive()) {
                cancel(c);                                          // the TV is already off
                return;
            }
            RemoteKeyService s = RemoteKeyService.running;
            if (s == null) {
                AppLog.w("sleep timer: the remote buttons service isn't running");
                return;
            }
            if (WARN.equals(intent.getAction())) s.sleepWarning.show();
            else if (SLEEP.equals(intent.getAction()) && System.currentTimeMillis() >= end - 1_000) s.sleepWarning.sleepNow();
        }
    }

    /** The last minute's card, bottom center, drawn by the accessibility service over any app. It takes the remote
     *  while it shows, like a dialog (Left/Right pick, OK acts, Back closes it and the timer goes on): Fire OS doesn't
     *  hand the remote's keys to the service's key filter. */
    static final class Warning {
        private final AccessibilityService service;
        private final Handler main = new Handler(Looper.getMainLooper());
        private View card;
        private TextView title;
        private final TextView[] buttons = new TextView[2];
        private int focus;

        Warning(AccessibilityService service) {
            this.service = service;
        }

        boolean showing() {
            return card != null;
        }

        void show() {
            if (card != null) return;
            float k = service.getResources().getDisplayMetrics().widthPixels / 1920f;
            LinearLayout box = new LinearLayout(service);
            box.setOrientation(LinearLayout.VERTICAL);
            box.setGravity(Gravity.CENTER_HORIZONTAL);
            box.setPadding(Math.round(40 * k), Math.round(28 * k), Math.round(40 * k), Math.round(30 * k));
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(0xF0141418);
            bg.setCornerRadius(26 * k);
            bg.setStroke(Math.round(2 * k), 0x33FFFFFF);
            box.setBackground(bg);
            box.addView(text(Ui.tr("SLEEP TIMER"), 18 * k, 0xFF9DB4FF, Typeface.DEFAULT_BOLD));
            title = text("", 34 * k, 0xFFF5F5F7, Typeface.DEFAULT_BOLD);
            title.setPadding(0, Math.round(10 * k), 0, Math.round(22 * k));
            box.addView(title);
            LinearLayout row = new LinearLayout(service);
            row.setOrientation(LinearLayout.HORIZONTAL);
            String[] labels = {Ui.tr("15 more minutes"), Ui.tr("Turn off now")};
            for (int i = 0; i < 2; i++) {
                TextView b = text(labels[i], 24 * k, 0, Typeface.DEFAULT_BOLD);
                b.setPadding(Math.round(30 * k), Math.round(14 * k), Math.round(30 * k), Math.round(14 * k));
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT);
                if (i > 0) lp.leftMargin = Math.round(18 * k);
                row.addView(b, lp);
                buttons[i] = b;
            }
            box.addView(row);
            focus = 0;
            paint(k);

            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
            box.setFocusable(true);
            box.setFocusableInTouchMode(true);
            box.setOnKeyListener((v, code, e) -> onKey(e));
            lp.y = Math.round(90 * k);
            try {
                wm().addView(box, lp);
            } catch (RuntimeException e) {
                AppLog.w("sleep warning: " + e.getMessage());
                return;
            }
            card = box;
            box.requestFocus();
            box.setAlpha(0);
            box.setTranslationY(40 * k);
            box.animate().alpha(1).translationY(0).setDuration(350).start();
            main.post(tick);
        }

        private final Runnable tick = new Runnable() {
            @Override public void run() {
                if (card == null) return;
                long left = end(service) - System.currentTimeMillis();
                if (end(service) == 0) {                 // turned off from the picker meanwhile
                    hide();
                    return;
                }
                long s = Math.max(0, (left + 999) / 1000);
                title.setText(Ui.trf("The TV turns off in {time}", "time", s / 60 + ":" + String.format(java.util.Locale.ROOT, "%02d", s % 60)));
                main.postDelayed(this, 1000);
            }
        };

        private void paint(float k) {
            for (int i = 0; i < 2; i++) {
                GradientDrawable pill = new GradientDrawable();
                pill.setCornerRadius(40 * k);
                pill.setColor(i == focus ? 0xFFF5F5F7 : 0x26FFFFFF);
                buttons[i].setBackground(pill);
                buttons[i].setTextColor(i == focus ? 0xFF111114 : 0xFFF5F5F7);
            }
        }

        /** The card's keys: true when the key was ours. */
        boolean onKey(KeyEvent e) {
            int code = e.getKeyCode();
            boolean ok = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_NUMPAD_ENTER;
            boolean ours = ok || code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT
                    || code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN;
            if (!ours) return false;
            if (e.getAction() != KeyEvent.ACTION_DOWN) return true;
            float k = service.getResources().getDisplayMetrics().widthPixels / 1920f;
            if (code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT) {
                focus = code == KeyEvent.KEYCODE_DPAD_LEFT ? 0 : 1;
                paint(k);
            } else if (code == KeyEvent.KEYCODE_BACK) hide();
            else if (ok && e.getRepeatCount() == 0) {
                if (focus == 0) {
                    long end = end(service);
                    start(service, Math.max(0, end - System.currentTimeMillis()) + MORE_MS);
                    hide();
                } else {
                    sleepNow();
                }
            }
            return true;
        }

        /** Standby now: the lock-screen action, which on Android 9 and later sleeps the device. */
        void sleepNow() {
            hide();
            cancel(service);
            boolean done = Build.VERSION.SDK_INT >= 28 && service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN);
            AppLog.w("sleep timer: standby" + (done ? "" : " refused"));
        }

        void hide() {
            main.removeCallbacks(tick);
            if (card == null) return;
            try {
                wm().removeView(card);
            } catch (RuntimeException ignored) {
            }
            card = null;
        }

        private WindowManager wm() {
            return (WindowManager) service.getSystemService(Context.WINDOW_SERVICE);
        }

        private TextView text(String s, float px, int color, Typeface face) {
            TextView t = new TextView(service);
            t.setText(s);
            t.setTextColor(color);
            t.setTypeface(face);
            t.setTextSize(TypedValue.COMPLEX_UNIT_PX, px);
            t.setIncludeFontPadding(false);
            t.setSingleLine();
            return t;
        }
    }
}
