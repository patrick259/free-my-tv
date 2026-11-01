package io.freemytv;

import android.app.Activity;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The thermostats (Thermostats), from the drawer: a glass card for each, what it reads inside and what it's set to,
 * tinted orange when heating, blue when cooling. ◀ ▶ pick one, ▲ ▼ change its temperature, OK its mode (off, heat,
 * cool, auto). A change goes to Home Assistant a moment after the last press, so a few presses make one change.
 */
public class ThermostatActivity extends Activity {
    private static final int INK = 0xFFF5F5F7, DIM = 0xB3F5F5F7, HEAT = 0xFFFF9F0A, COOL = 0xFF0A84FF;
    private static final List<String> MODES = Arrays.asList("off", "heat", "cool", "heat_cool");
    private static final long SEND_MS = 1300, REFRESH_MS = 30_000, SETTLE_MS = 8_000;

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Thermostats.Stat> stats = new ArrayList<>();
    private final List<Card> cards = new ArrayList<>();
    private LinearLayout row;
    private TextView status;
    private float k;
    private boolean resumed;

    private final class Card {
        Thermostats.Stat stat;
        LinearLayout view;
        TextView name, state, now, inside, target;
        LinearLayout modes;
        long touchedAt;
        boolean tempWaiting, modeWaiting;
        final Runnable sendTemp = () -> send(this, false), sendMode = () -> send(this, true);
    }

    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            load();
            main.postDelayed(this, REFRESH_MS);
        }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        Ui.init(this);
        k = getResources().getDisplayMetrics().widthPixels / 1920f;
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackground(Glass.page(root));
        root.setPadding(px(80), px(52), px(80), px(48));
        root.addView(text(Ui.tr("Thermostat"), 52, INK, Ui.displaySemibold));
        status = text(Ui.tr("Loading…"), 24, DIM, Ui.medium);
        status.setPadding(0, px(14), 0, 0);
        root.addView(status);

        row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER);
        root.addView(row, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        TextView hint = text(Ui.tr("◀ ▶  Choose        ▲ ▼  Temperature        OK  Mode"), 24, DIM, Ui.medium);
        hint.setGravity(Gravity.CENTER);
        root.addView(hint, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(root);
        if (!Thermostats.on()) status.setText(Ui.tr("The thermostat needs Home Assistant: see the README (ha_url, ha_token)."));
    }

    @Override protected void onResume() {
        super.onResume();
        resumed = true;
        if (Thermostats.on()) main.post(refresh);
    }

    @Override protected void onPause() {
        resumed = false;
        main.removeCallbacks(refresh);
        // Changes still waiting go out now, not lost with the screen.
        for (Card c : cards) {
            if (c.modeWaiting) send(c, true);
            else if (c.tempWaiting) send(c, false);
        }
        super.onPause();
    }

    @Override protected void onDestroy() {
        io.shutdown();
        super.onDestroy();
    }

    private void load() {
        io.execute(() -> {
            try {
                List<Thermostats.Stat> got = Thermostats.read();
                main.post(() -> show(got));
            } catch (Exception e) {
                main.post(() -> status.setText(Ui.trf("Couldn't reach Home Assistant: {error}", "error", String.valueOf(e.getMessage()))));
            }
        });
    }

    private void show(List<Thermostats.Stat> got) {
        if (got.isEmpty()) {
            status.setText(Ui.tr("Home Assistant has no thermostat (a climate entity that can cool)."));
            return;
        }
        status.setText(Ui.tr("Inside the house now"));
        boolean same = got.size() == cards.size();
        for (int i = 0; same && i < got.size(); i++) same = got.get(i).id.equals(cards.get(i).stat.id);
        if (!same) {
            row.removeAllViews();
            cards.clear();
            for (Thermostats.Stat s : got) cards.add(card(s));
            cards.get(0).view.requestFocus();
        }
        long now = SystemClock.uptimeMillis();
        for (int i = 0; i < got.size(); i++) {
            Card c = cards.get(i);
            // Just changed here: the thermostat may not show it yet, so what's on screen stays.
            if (same && now - c.touchedAt < SETTLE_MS) continue;
            c.stat = got.get(i);
            paint(c);
        }
    }

    private Card card(Thermostats.Stat s) {
        Card c = new Card();
        c.stat = s;
        LinearLayout v = new LinearLayout(this);
        v.setOrientation(LinearLayout.VERTICAL);
        v.setPadding(px(52), px(44), px(52), px(44));
        v.setFocusable(true);
        v.setOnFocusChangeListener((x, has) -> paint(c));
        c.view = v;
        c.name = text("", 40, INK, Ui.displaySemibold);
        c.state = text("", 26, DIM, Ui.medium);
        c.state.setPadding(0, px(10), 0, 0);
        c.now = text("", 168, INK, Ui.thin);
        c.now.setPadding(0, px(26), 0, 0);
        c.inside = text("", 24, DIM, Ui.regular);
        c.inside.setPadding(0, px(4), 0, px(30));
        c.target = text("", 56, INK, Ui.displayMedium);
        c.modes = new LinearLayout(this);
        c.modes.setPadding(0, px(30), 0, 0);
        v.addView(c.name);
        v.addView(c.state);
        v.addView(c.now);
        v.addView(c.inside);
        v.addView(text(Ui.tr("SET TO"), 20, DIM, Ui.semibold));
        v.addView(c.target);
        v.addView(c.modes);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(px(640), ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = lp.rightMargin = px(24);
        row.addView(v, lp);
        return c;
    }

    private void paint(Card c) {
        Thermostats.Stat s = c.stat;
        boolean focused = c.view.hasFocus();
        c.name.setText(s.name);
        c.now.setText(deg(s.current));
        c.inside.setText(Double.isNaN(s.humidity) ? Ui.tr("Inside")
                : Ui.trf("Inside · {n}% humidity", "n", Math.round(s.humidity)));
        c.state.setText(state(s));
        c.target.setText(target(s));
        c.target.setTextColor("off".equals(s.mode) ? DIM : INK);

        c.modes.removeAllViews();
        for (String m : MODES) {
            if (!s.modes.contains(m)) continue;
            boolean on = m.equals(s.mode);
            TextView p = text(modeName(m), 24, on ? 0xFF111114 : DIM, Ui.semibold);
            p.setPadding(px(22), px(10), px(22), px(10));
            GradientDrawable pill = new GradientDrawable();
            pill.setCornerRadius(px(24));
            pill.setColor(on ? INK : 0x1FFFFFFF);
            p.setBackground(pill);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = px(12);
            c.modes.addView(p, lp);
        }

        int left, right;
        float amount;
        switch (s.mode) {
            case "heat": left = right = HEAT; amount = 0.34f; break;
            case "cool": left = right = COOL; amount = 0.34f; break;
            case "heat_cool": left = HEAT; right = COOL; amount = 0.3f; break;
            default: left = right = 0xFF8E8E93; amount = 0.08f;
        }
        c.view.setBackground(focused ? Glass.focused(c.view, px(36), left, right, amount + 0.08f)
                : Glass.plate(c.view, px(36), left, right, amount));
    }

    private static String state(Thermostats.Stat s) {
        switch (s.action) {
            case "heating": return Ui.trf("Heating to {t}", "t", deg("heat_cool".equals(s.mode) ? s.low : s.target));
            case "cooling": return Ui.trf("Cooling to {t}", "t", deg("heat_cool".equals(s.mode) ? s.high : s.target));
            case "fan": return Ui.tr("Fan only");
        }
        switch (s.mode) {
            case "off": return Ui.tr("Off");
            case "heat": return Ui.tr("Heat · idle");
            case "cool": return Ui.tr("Cool · idle");
            case "heat_cool": return Ui.tr("Auto · idle");
            default: return modeName(s.mode);
        }
    }

    private static String target(Thermostats.Stat s) {
        if ("off".equals(s.mode)) return "—";
        if ("heat_cool".equals(s.mode)) return deg(s.low) + " – " + deg(s.high);
        return deg(s.target);
    }

    private static String modeName(String m) {
        switch (m) {
            case "off": return Ui.tr("Off");
            case "heat": return Ui.tr("Heat");
            case "cool": return Ui.tr("Cool");
            case "heat_cool": return Ui.tr("Auto");
            default: return m;
        }
    }

    private static String deg(double t) {
        return Double.isNaN(t) ? "—" : Math.round(t) + "°";
    }

    @Override public boolean dispatchKeyEvent(KeyEvent e) {
        Card c = focusedCard();
        if (c != null && e.getAction() == KeyEvent.ACTION_DOWN) {
            switch (e.getKeyCode()) {
                case KeyEvent.KEYCODE_DPAD_UP: adjust(c, 1); return true;
                case KeyEvent.KEYCODE_DPAD_DOWN: adjust(c, -1); return true;
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                    if (e.getRepeatCount() == 0) nextMode(c);
                    return true;
            }
        }
        return super.dispatchKeyEvent(e);
    }

    private Card focusedCard() {
        for (Card c : cards) if (c.view.hasFocus()) return c;
        return null;
    }

    private void adjust(Card c, int by) {
        Thermostats.Stat s = c.stat;
        if ("off".equals(s.mode)) {
            status.setText(Ui.trf("{name} is off: press OK for Heat, Cool or Auto.", "name", s.name));
            return;
        }
        double base = Double.isNaN(s.current) ? 70 : Math.round(s.current);
        if ("heat_cool".equals(s.mode)) {
            if (Double.isNaN(s.low)) s.low = base - 2;
            if (Double.isNaN(s.high)) s.high = base + 2;
            s.low = Math.max(s.min, Math.min(s.max, Math.round(s.low) + by));
            s.high = Math.max(s.min, Math.min(s.max, Math.round(s.high) + by));
        } else {
            if (Double.isNaN(s.target)) s.target = base;
            s.target = Math.max(s.min, Math.min(s.max, Math.round(s.target) + by));
        }
        c.tempWaiting = true;
        touch(c, c.sendTemp);
    }

    private void nextMode(Card c) {
        Thermostats.Stat s = c.stat;
        List<String> have = new ArrayList<>();
        for (String m : MODES) if (s.modes.contains(m)) have.add(m);
        if (have.isEmpty()) return;
        s.mode = have.get((have.indexOf(s.mode) + 1) % have.size());
        s.action = "";
        c.modeWaiting = true;
        touch(c, c.sendMode);
    }

    private void touch(Card c, Runnable send) {
        c.touchedAt = SystemClock.uptimeMillis();
        status.setText(Ui.tr("Inside the house now"));
        paint(c);
        main.removeCallbacks(send);
        main.postDelayed(send, SEND_MS);
    }

    /** Sends the card's mode (and then its temperature, if it had one waiting) or its temperature. */
    private void send(Card c, boolean mode) {
        String id = c.stat.id, name = c.stat.name, m = c.stat.mode;
        // A mode change takes a temperature change waiting with it; a temperature change waits for a mode change.
        if (!mode && c.modeWaiting) return;
        boolean temp = c.tempWaiting;
        main.removeCallbacks(c.sendTemp);
        main.removeCallbacks(c.sendMode);
        c.tempWaiting = c.modeWaiting = false;
        Thermostats.Stat copy = new Thermostats.Stat();
        copy.id = id;
        copy.mode = m;
        copy.target = c.stat.target;
        copy.low = c.stat.low;
        copy.high = c.stat.high;
        io.execute(() -> {
            try {
                if (mode) Thermostats.setMode(id, m);
                if (temp && !"off".equals(m) && !(Double.isNaN(copy.target) && Double.isNaN(copy.low))) Thermostats.setTemperature(copy);
                main.postDelayed(() -> { if (resumed) load(); }, 2500);
            } catch (Exception e) {
                AppLog.w("thermostat: " + e.getMessage());
                main.post(() -> {
                    c.touchedAt = 0;
                    status.setText(Ui.trf("Couldn't change {name}: {error}", "name", name, "error", String.valueOf(e.getMessage())));
                    load();
                });
            }
        });
    }

    private TextView text(String s, float size, int color, Typeface face) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(color);
        if (face != null) t.setTypeface(face);
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, size * k);
        t.setIncludeFontPadding(false);
        return t;
    }

    private int px(float v) {
        return Math.round(v * k);
    }
}
