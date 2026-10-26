package io.freemytv;

import android.accessibilityservice.AccessibilityService;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;

/**
 * A banner over whatever is playing (Apple TV, YouTube TV, Netflix…) for the games you'd want to know about:
 * your teams (favorite_teams), Formula 1's sessions, and the postseason. It says when one starts in 10 minutes, when
 * it's close at the end (baseball from the 8th within a run, hockey's 3rd period within a goal, football's 4th quarter
 * within 8 points), and your teams' final scores. Each once per game. Runs in RemoteKeyService (its process stays up,
 * and an accessibility service may draw over other apps), so wherever that service is on: Fire TV, and the Google TVs
 * whose remote buttons it handles (RemoteKeys). The games come from Sports, as in the hero.
 */
final class GameAlerts {
    private static final long POLL_MS = 30_000, SHOW_MS = 8_000, SOON_MS = 10 * 60_000L;

    private final AccessibilityService service;
    private final Handler main = new Handler(Looper.getMainLooper());
    private SharedPreferences sent;
    private final Map<String, String> states = new HashMap<>();   // game id → state last seen ("in" before "post": a final)
    private final ArrayDeque<String[]> queue = new ArrayDeque<>();
    private Sports sports;
    private View banner;

    GameAlerts(AccessibilityService service) {
        this.service = service;     // made with the service, before it has a context: nothing else here yet
    }

    void start() {
        if (!Config.SHOW_SPORTS) return;
        sent = service.getSharedPreferences("game-alerts", Context.MODE_PRIVATE);
        sports = new Sports(service, this::check);
        main.post(poll);
    }

    void stop() {
        main.removeCallbacksAndMessages(null);
        hide();
    }

    private final Runnable poll = new Runnable() {
        @Override public void run() {
            sports.refreshIfStale();
            main.postDelayed(this, POLL_MS);
        }
    };

    private void check() {
        long t = System.currentTimeMillis();
        for (Recs.Pick p : sports.all()) {
            Sports.Game g = p.game;
            if (g == null || !(g.favorite || "f1".equals(g.sport) || g.weight >= Sports.POSTSEASON)) continue;
            String was = states.put(g.id, g.state);
            String where = p.app == null ? "" : Recs.serviceName(p);
            if (where.isEmpty() && p.meta != null) where = p.meta.substring(p.meta.lastIndexOf('·') + 1).trim();
            if ("pre".equals(g.state) && g.start - t <= SOON_MS && g.start - t > -60_000L) {
                long min = Math.max(1, Math.round((g.start - t) / 60_000.0));
                alert(g.id + ":soon10", label(p, Ui.tr("STARTING SOON")), p.title,
                        Ui.trf("In {n} min", "n", min) + (where.isEmpty() ? "" : " · " + where));
            } else if ("in".equals(g.state) && close(g)) {
                alert(g.id + ":close", label(p, Ui.tr("CLOSE GAME")), score(g), g.detail + (where.isEmpty() ? "" : " · " + where));
            } else if ("post".equals(g.state) && "in".equals(was) && g.favorite) {
                alert(g.id + ":final", label(p, Ui.tr("FINAL")), score(g), g.detail);
            }
        }
    }

    /** The end of a game, and close. */
    private static boolean close(Sports.Game g) {
        int diff = Math.abs(g.awayScore - g.homeScore);
        switch (g.sport) {
            case "baseball": return g.period >= 8 && diff <= 1;
            case "hockey": return g.period >= 3 && diff <= 1;
            case "football": return g.period >= 4 && diff <= 8;
            default: return false;
        }
    }

    private static String score(Sports.Game g) {
        return g.away + "  " + g.awayScore + " – " + g.homeScore + "  " + g.home;
    }

    private static String label(Recs.Pick p, String what) {
        return p.label == null || p.label.isEmpty() ? what : what + "  ·  " + p.label;
    }

    /** A sample banner, to see it on a TV: adb shell am broadcast -n io.freemytv/.GameAlerts\$Test */
    public static final class Test extends BroadcastReceiver {
        @Override public void onReceive(Context c, Intent intent) {
            RemoteKeyService s = RemoteKeyService.running;
            if (s == null) {
                AppLog.w("game alert test: the remote service is off");
                return;
            }
            s.alerts.queue.add(new String[] {"CLOSE GAME  ·  TEST", "49ers  20 – 21  Falcons", "4th 2:00 · YouTube TV"});
            if (s.alerts.banner == null) s.alerts.next();
        }
    }

    private void alert(String key, String label, String title, String detail) {
        if (sent.getLong(key, 0) != 0) return;
        sent.edit().putLong(key, System.currentTimeMillis()).apply();
        AppLog.w("game alert: " + label + " / " + title);
        queue.add(new String[] {label, title, detail});
        if (banner == null) next();
    }

    private void next() {
        String[] a = queue.poll();
        if (a == null) return;
        if (!((PowerManager) service.getSystemService(Context.POWER_SERVICE)).isInteractive()) {
            next();     // the TV is off: not shown
            return;
        }
        show(a[0], a[1], a[2]);
    }

    /** The banner, top right: a dark card with a red edge; it slides in, stays SHOW_MS, and fades out. */
    private void show(String label, String title, String detail) {
        float k = service.getResources().getDisplayMetrics().widthPixels / 1920f;
        LinearLayout card = new LinearLayout(service);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(Math.round(30 * k), Math.round(22 * k), Math.round(36 * k), Math.round(24 * k));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xF0141418);
        bg.setCornerRadius(22 * k);
        bg.setStroke(Math.round(2 * k), 0x33FFFFFF);
        card.setBackground(bg);
        card.addView(text(label, 18 * k, 0xFFFF453A, Typeface.DEFAULT_BOLD));
        TextView t = text(title, 32 * k, 0xFFF5F5F7, Typeface.DEFAULT_BOLD);
        t.setPadding(0, Math.round(8 * k), 0, Math.round(6 * k));
        card.addView(t);
        if (detail != null && !detail.isEmpty()) card.addView(text(detail, 22 * k, 0xFFB0B0B5, Typeface.DEFAULT));

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                Math.round(620 * k), WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.END;
        lp.x = Math.round(48 * k);
        lp.y = Math.round(118 * k);     // below the home screen's corner buttons
        WindowManager wm = (WindowManager) service.getSystemService(Context.WINDOW_SERVICE);
        try {
            wm.addView(card, lp);
        } catch (RuntimeException e) {
            AppLog.w("game alert: " + e.getMessage());
            return;
        }
        banner = card;
        card.setAlpha(0);
        card.setTranslationY(-40 * k);
        card.animate().alpha(1).translationY(0).setDuration(350).start();
        main.postDelayed(() -> card.animate().alpha(0).setDuration(400).withEndAction(() -> {
            hide();
            next();
        }).start(), SHOW_MS);
    }

    private void hide() {
        if (banner == null) return;
        try {
            ((WindowManager) service.getSystemService(Context.WINDOW_SERVICE)).removeView(banner);
        } catch (RuntimeException ignored) {
        }
        banner = null;
    }

    private TextView text(String s, float px, int color, Typeface face) {
        TextView t = new TextView(service);
        t.setText(s);
        t.setTextColor(color);
        t.setTypeface(face);
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, px);
        t.setIncludeFontPadding(false);
        t.setSingleLine();
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        return t;
    }
}
