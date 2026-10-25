package io.freemytv;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Cameras: the Home Assistant cameras in ha_cameras, live and full screen, one at a time (each live view wakes the
 * camera; Ring's battery cameras pay for it). Live means WebRTC, the way Home Assistant's own dashboard plays them: its
 * snapshots and MJPEG stream are only Ring's last recorded clip. assets/cameras.html does the WebRTC in the TV's
 * WebView; the names along the bottom are ours: ← → picks one, OK starts it again after an error or the pause. The
 * names fade after a few seconds and any key brings them back. After five minutes the live view pauses. Back comes home.
 */
public class CamerasActivity extends Activity {
    private static final int BG = 0xFF000000, INK = 0xFFF5F5F7, LIVE = 0xFFFF453A;
    private static final long HIDE_MS = 5_000, PAUSE_MS = 5 * 60_000, SWITCH_MS = 350, CONNECT_MS = 30_000;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<String> ids = new ArrayList<>();
    private final List<TextView> chips = new ArrayList<>();
    private WebView web;
    private boolean pageReady;
    private TextView title, message;
    private View live, bar;
    private int current = -1, pending = -1;
    private boolean playing;
    private float k;

    private final Runnable hide = () -> fade(false);
    private final Runnable pause = () -> {
        stop();
        say(Ui.tr("Paused to spare the camera's battery. Press OK to keep watching."));
    };
    private final Runnable slow = () -> {
        stop();
        say(Ui.tr("This camera isn't answering right now (it may be asleep or busy). Press OK to try again."));
        fade(true);
    };
    private final Runnable switcher = () -> { if (pending >= 0 && pending != current) start(pending); };

    @SuppressLint("SetJavaScriptEnabled")
    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Ui.init(this);
        k = getResources().getDisplayMetrics().widthPixels / 1920f;
        for (String id : Config.HA_CAMERAS.split(",")) if (!id.trim().isEmpty()) ids.add(id.trim());

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(BG);
        web = new WebView(this);
        web.setBackgroundColor(BG);
        web.setFocusable(false);
        web.setFocusableInTouchMode(false);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        web.addJavascriptInterface(new Bridge(), "Cam");
        root.addView(web, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        message = text("", 30, INK, Ui.medium);
        message.setGravity(Gravity.CENTER);
        message.setMaxWidth(px(1100));
        message.setLineSpacing(0, 1.2f);
        root.addView(message, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));

        // The camera's name and LIVE, top left, on a shade so they read over a bright picture.
        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(px(80), px(52), px(80), px(80));
        head.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[] {0x99000000, 0x00000000}));
        title = text("", 44, INK, Ui.displaySemibold);
        head.addView(title);
        TextView dot = text("●  " + Ui.tr("LIVE"), 22, INK, Ui.semibold);
        dot.setLetterSpacing(0.08f);
        dot.setPadding(px(16), px(7), px(18), px(7));
        dot.setBackground(pill(LIVE));
        LinearLayout.LayoutParams dp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dp.leftMargin = px(24);
        head.addView(dot, dp);
        live = dot;
        live.setVisibility(View.INVISIBLE);
        root.addView(head, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP));

        // The cameras, bottom left.
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(px(80), px(90), px(80), px(52));
        row.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, new int[] {0xB3000000, 0x00000000}));
        for (int i = 0; i < ids.size(); i++) {
            final int index = i;
            TextView chip = text(fallbackName(ids.get(i)), 28, INK, Ui.medium);
            chip.setPadding(px(30), px(16), px(30), px(16));
            chip.setFocusable(true);
            chip.setFocusableInTouchMode(true);
            chip.setOnFocusChangeListener((v, has) -> {
                style();
                if (has && index != current) {
                    main.removeCallbacks(switcher);
                    pending = index;
                    main.postDelayed(switcher, SWITCH_MS);
                }
            });
            chip.setOnClickListener(v -> { if (index != current || !playing) start(index); });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = px(16);
            row.addView(chip, lp);
            chips.add(chip);
        }
        bar = row;
        root.addView(row, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));
        setContentView(root);
        style();

        if (Config.HA_URL.isEmpty() || Config.HA_TOKEN.isEmpty() || ids.isEmpty()) {
            say(Ui.tr("Add ha_url, ha_token and ha_cameras to config.properties, then build again."));
            return;
        }
        // The page with Home Assistant's address as its origin: its websocket is then same-origin.
        web.setWebViewClient(new android.webkit.WebViewClient() {
            @Override public void onPageFinished(WebView view, String url) {
                pageReady = true;
                if (current >= 0 && !playing) start(current);
            }
        });
        web.loadDataWithBaseURL(Config.HA_URL + "/", asset("cameras.html"), "text/html", "utf-8", null);
        new Thread(this::names, "camera-names").start();
        current = 0;
        chips.get(0).requestFocus();
    }

    @Override protected void onResume() {
        super.onResume();
        web.onResume();
        if (pageReady && current >= 0) start(current);
        fade(true);
    }

    @Override protected void onPause() {
        stop();
        main.removeCallbacksAndMessages(null);
        web.onPause();
        super.onPause();
    }

    @Override protected void onDestroy() {
        web.destroy();
        super.onDestroy();
    }

    @Override public boolean dispatchKeyEvent(KeyEvent e) {
        if (e.getKeyCode() == KeyEvent.KEYCODE_BACK) return super.dispatchKeyEvent(e);
        // While the names are hidden the first key only brings them back.
        if (e.getAction() == KeyEvent.ACTION_DOWN && bar.getAlpha() < 0.5f) {
            fade(true);
            return true;
        }
        if (e.getAction() == KeyEvent.ACTION_DOWN) fade(true);
        return super.dispatchKeyEvent(e);
    }

    // MARK: - Playing

    private void start(int index) {
        current = index;
        title.setText(chips.get(index).getText());
        style();
        if (!pageReady) return;
        stop();
        say(Ui.tr("Connecting…"));
        web.evaluateJavascript("play(" + JSONObject.quote(Config.HA_TOKEN) + "," + JSONObject.quote(ids.get(index)) + ")", null);
        main.postDelayed(slow, CONNECT_MS);
        main.postDelayed(pause, PAUSE_MS);
    }

    private void stop() {
        playing = false;
        main.removeCallbacks(pause);
        main.removeCallbacks(slow);
        live.setVisibility(View.INVISIBLE);
        if (pageReady) web.evaluateJavascript("stop()", null);
    }

    /** From the page: "live", or "error:<why>". */
    private final class Bridge {
        @JavascriptInterface public void state(String s) {
            main.post(() -> {
                if (s.equals("live")) {
                    playing = true;
                    main.removeCallbacks(slow);
                    live.setVisibility(View.VISIBLE);
                    say("");
                    return;
                }
                AppLog.w("camera " + (current >= 0 ? ids.get(current) : "") + ": " + s);
                stop();
                say(s.equals("error:auth")
                        ? Ui.tr("Home Assistant turned the token down. Check ha_token in config.properties.")
                        : s.startsWith("error:socket") || s.startsWith("error:closed")
                        ? Ui.tr("Can't reach Home Assistant right now. Press OK to try again.")
                        : Ui.tr("This camera isn't answering right now (it may be asleep or busy). Press OK to try again."));
                fade(true);
            });
        }
    }

    private void say(String s) {
        message.setText(s);
        message.setVisibility(s.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private void style() {
        for (int i = 0; i < chips.size(); i++) {
            TextView chip = chips.get(i);
            boolean focused = chip.hasFocus(), on = i == current;
            chip.setBackground(pill(focused ? INK : on ? 0x59FFFFFF : 0x33FFFFFF));
            chip.setTextColor(focused ? 0xFF0B0B0F : INK);
        }
    }

    private void fade(boolean show) {
        main.removeCallbacks(hide);
        float to = show ? 1 : 0;
        bar.animate().alpha(to).setDuration(250).start();
        ((View) title.getParent()).animate().alpha(to).setDuration(250).start();
        if (show) main.postDelayed(hide, HIDE_MS);
    }

    // MARK: - Home Assistant

    /** The cameras' names from Home Assistant ("Front Door Live view" → "Front Door"). */
    private void names() {
        for (int i = 0; i < ids.size(); i++) {
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(Config.HA_URL + "/api/states/" + ids.get(i)).openConnection();
                c.setRequestProperty("Authorization", "Bearer " + Config.HA_TOKEN);
                c.setConnectTimeout(6_000);
                c.setReadTimeout(10_000);
                if (c.getResponseCode() != 200) continue;
                String name = new JSONObject(read(c.getInputStream())).getJSONObject("attributes").optString("friendly_name");
                name = name.replaceAll("(?i)\\s+live view$", "").trim();
                if (name.isEmpty()) continue;
                final int index = i;
                final String n = name;
                main.post(() -> {
                    chips.get(index).setText(n);
                    if (index == current) title.setText(n);
                });
            } catch (Exception e) {
                AppLog.w("camera name " + ids.get(i) + ": " + e.getMessage());
            }
        }
    }

    /** "camera.backyard_1_live_view" → "Backyard 1". */
    private static String fallbackName(String id) {
        String s = id.replaceFirst("^camera\\.", "").replaceFirst("_live_view$", "").replace('_', ' ');
        return s.isEmpty() ? id : s.substring(0, 1).toUpperCase(Locale.ROOT) + s.substring(1);
    }

    private static String read(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] b = new byte[8192];
        for (int n; (n = in.read(b)) > 0; ) out.write(b, 0, n);
        in.close();
        return out.toString("UTF-8");
    }

    private String asset(String name) {
        try {
            return read(getAssets().open(name));
        } catch (IOException e) {
            return "";
        }
    }

    // MARK: - Views

    private TextView text(String s, float size, int color, android.graphics.Typeface face) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(color);
        t.setTypeface(face);
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, size * k);
        t.setIncludeFontPadding(false);
        return t;
    }

    private GradientDrawable pill(int color) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(px(40));
        return d;
    }

    private int px(float design) {
        return Math.round(design * k);
    }
}
