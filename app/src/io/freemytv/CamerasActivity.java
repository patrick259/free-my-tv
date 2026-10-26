package io.freemytv;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
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
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONException;
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
 * Cameras: the Home Assistant cameras in ha_cameras, live. The Cameras button opens them all in a grid (the first one
 * large); OK on one shows it full screen, and Back goes back to the grid. From the home screen's camera row a camera
 * opens full screen straight away (Back: home). Full screen, the names along the bottom are ours: ← → picks another,
 * OK starts it again after an error or the pause; they fade after a few seconds and any key brings them back.
 *
 * Live means WebRTC, the way Home Assistant's own dashboard plays them (its snapshots and MJPEG stream are only Ring's
 * last recorded clip): assets/cameras.html, in the TV's WebView, one stream per camera. Each live view wakes its camera
 * and Ring's battery cameras pay for it, so after five minutes everything pauses.
 */
public class CamerasActivity extends Activity {
    /** Which camera to start on full screen (its place in ha_cameras), from the home screen's camera row. */
    static final String EXTRA_CAMERA = "camera";
    /** Only one place's cameras (CameraSites), from CameraSitesActivity. */
    static final String EXTRA_SITE = "site";
    private static final int BG = 0xFF000000, INK = 0xFFF5F5F7, DIM = 0xFF98989D, LIVE = 0xFFFF453A;
    private static final long HIDE_MS = 5_000, PAUSE_MS = 5 * 60_000, SWITCH_MS = 350, CONNECT_MS = 30_000;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<String> ids = new ArrayList<>();
    private final List<String> names = new ArrayList<>();
    private final List<TextView> chips = new ArrayList<>();
    private final List<FrameLayout> tiles = new ArrayList<>();
    private final List<TextView> tileStatus = new ArrayList<>();
    private final List<View> liveBadges = new ArrayList<>();
    private WebView web;
    private boolean pageReady;
    private TextView title, message;
    private View live, bar, head, grid;
    private int current = -1, pending = -1;
    private boolean single, fromGrid, playing, paused;
    /** The grid's live camera follows the highlight, after a moment (each switch wakes a camera). */
    private final Runnable gridSwitch = () -> { if (!single && pageReady && !paused) showGrid(); };
    private float k;
    private int site = -1;

    private final Runnable hide = () -> fade(false);
    private final Runnable pause = () -> {
        stop();
        paused = true;
        say(Ui.tr("Paused to spare the cameras' batteries. Press OK to keep watching."));
        if (single) fade(true);
    };
    private final Runnable slow = () -> {
        if (!single) return;
        stop();
        say(Ui.tr("This camera isn't answering right now (it may be asleep or busy). Press OK to try again."));
        fade(true);
    };
    private final Runnable switcher = () -> { if (single && pending >= 0 && pending != current) showSingle(pending); };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Ui.init(this);
        k = getResources().getDisplayMetrics().widthPixels / 1920f;
        site = getIntent().getIntExtra(EXTRA_SITE, -1);
        for (String id : site >= 0 && site < CameraSites.ALL.size() ? CameraSites.ALL.get(site).ids : CameraSites.ids()) {
            ids.add(id);
            names.add(CameraShots.fallbackName(id));
        }

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(BG);
        web = newWeb();
        root.addView(web, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        grid = buildGrid();
        root.addView(grid, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        head = buildHead();
        root.addView(head, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP));
        bar = buildBar();
        root.addView(bar, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));
        message = text("", 30, INK, Ui.medium);
        message.setGravity(Gravity.CENTER);
        message.setMaxWidth(px(1100));
        message.setLineSpacing(0, 1.2f);
        message.setShadowLayer(16 * k, 0, 1 * k, 0xCC000000);
        root.addView(message, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        say("");
        setContentView(root);

        if (Config.HA_URL.isEmpty() || Config.HA_TOKEN.isEmpty() || ids.isEmpty()) {
            say(Ui.tr("Add ha_url, ha_token and ha_cameras to config.properties, then build again."));
            return;
        }
        // The page with Home Assistant's address as its origin: its websockets are then same-origin.
        web.setWebViewClient(new android.webkit.WebViewClient() {
            @Override public void onPageFinished(WebView view, String url) {
                pageReady = true;
                resume();
            }

            /** The video renderer crashed (the TV's decoders gave up): a fresh WebView, instead of the whole app closing. */
            @Override public boolean onRenderProcessGone(WebView view, android.webkit.RenderProcessGoneDetail detail) {
                AppLog.w("cameras: the renderer went away (crash " + detail.didCrash() + ")");
                ViewGroup parent = (ViewGroup) view.getParent();
                int at = parent.indexOfChild(view);
                parent.removeView(view);
                view.destroy();
                pageReady = false;
                web = newWeb();
                parent.addView(web, at, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                web.setWebViewClient(this);
                web.loadDataWithBaseURL(Config.HA_URL + "/", asset("cameras.html"), "text/html", "utf-8", null);
                return true;
            }
        });
        web.loadDataWithBaseURL(Config.HA_URL + "/", asset("cameras.html"), "text/html", "utf-8", null);
        new Thread(this::readNames, "camera-names").start();
        open(getIntent());
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (intent.getIntExtra(EXTRA_SITE, -1) != site) {   // another place's cameras: built again for them
            setIntent(intent);
            recreate();
            return;
        }
        open(intent);
        resume();
    }

    /** From the camera row: that camera full screen (Back: home). From the Cameras button: the grid. */
    private void open(Intent intent) {
        int at = intent.getIntExtra(EXTRA_CAMERA, -1);
        fromGrid = at < 0;
        single = at >= 0;
        current = Math.max(0, Math.min(ids.size() - 1, at));
        layoutMode();
    }

    @Override protected void onResume() {
        super.onResume();
        web.onResume();
        resume();
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

    /** Starts what the screen shows (the grid, or the one camera). */
    private void resume() {
        if (!pageReady || ids.isEmpty()) return;
        paused = false;
        if (single) showSingle(current); else showGrid();
    }

    @Override public boolean dispatchKeyEvent(KeyEvent e) {
        int code = e.getKeyCode();
        if (code == KeyEvent.KEYCODE_BACK) {
            if (e.getAction() == KeyEvent.ACTION_UP && single && fromGrid) {
                single = false;
                layoutMode();
                showGrid();
                return true;
            }
            if (single && fromGrid) return true;    // the press's down half: the up half goes back to the grid
            return super.dispatchKeyEvent(e);
        }
        if (paused && e.getAction() == KeyEvent.ACTION_UP && (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER)) {
            resume();
            return true;
        }
        if (single) {
            // While the names are hidden the first key only brings them back.
            if (e.getAction() == KeyEvent.ACTION_DOWN && bar.getAlpha() < 0.5f) {
                fade(true);
                return true;
            }
            if (e.getAction() == KeyEvent.ACTION_DOWN) fade(true);
        }
        return super.dispatchKeyEvent(e);
    }

    @SuppressLint("SetJavaScriptEnabled")
    private WebView newWeb() {
        WebView w = new WebView(this);
        w.setBackgroundColor(BG);
        w.setFocusable(false);
        w.setFocusableInTouchMode(false);
        WebSettings s = w.getSettings();
        s.setJavaScriptEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        w.addJavascriptInterface(new Bridge(), "Cam");
        return w;
    }

    // MARK: - The two screens

    private void layoutMode() {
        // Showing or hiding a layer moves the focus on its own (a tile's listener would then change `current`).
        int keep = current;
        grid.setVisibility(single ? View.GONE : View.VISIBLE);
        head.setVisibility(single ? View.VISIBLE : View.GONE);
        bar.setVisibility(single ? View.VISIBLE : View.GONE);
        current = keep;
        main.removeCallbacks(gridSwitch);
        if (single) {
            title.setText(names.get(current));
            chips.get(current).requestFocus();
            style();
            fade(true);
        } else if (current < tiles.size()) {
            tiles.get(current).requestFocus();
        }
        say("");
    }

    /**
     * All the cameras, each in its tile, silent: the highlighted one live, the others still (the TV decodes one camera
     * stream at a time; see cameras.html).
     */
    private void showGrid() {
        main.removeCallbacks(slow);
        playing = false;
        JSONArray list = new JSONArray();
        for (int i = 0; i < ids.size(); i++) {
            float[] r = tileRect(i);
            boolean on = i == current;
            list.put(tile(ids.get(i), r[0] / 1920f, r[1] / 1080f, r[2] / 1920f, r[3] / 1080f, false, on));
            setTileStatus(i, on && !"live".equals(tileStatus.get(i).getTag()) ? Ui.tr("Connecting…") : "", on ? (String) tileStatus.get(i).getTag() : null);
            liveBadges.get(i).setVisibility(View.INVISIBLE);
        }
        web.evaluateJavascript("show(" + JSONObject.quote(Config.HA_TOKEN) + "," + list + ");sound(null)", null);
        armPause();
    }

    /** One camera full screen, with its sound (the grid's stream carries on when it was already playing). */
    private void showSingle(int index) {
        current = index;
        title.setText(names.get(index));
        style();
        playing = false;
        live.setVisibility(View.INVISIBLE);
        say(Ui.tr("Connecting…"));
        JSONArray list = new JSONArray().put(tile(ids.get(index), 0, 0, 1, 1, true, true));
        web.evaluateJavascript("show(" + JSONObject.quote(Config.HA_TOKEN) + "," + list + ");sound(" + JSONObject.quote(ids.get(index)) + ")", null);
        main.removeCallbacks(slow);
        main.postDelayed(slow, CONNECT_MS);
        armPause();
    }

    private void armPause() {
        main.removeCallbacks(pause);
        main.postDelayed(pause, PAUSE_MS);
    }

    private void stop() {
        playing = false;
        main.removeCallbacks(pause);
        main.removeCallbacks(slow);
        live.setVisibility(View.INVISIBLE);
        for (int i = 0; i < tileStatus.size(); i++) setTileStatus(i, "", null);
        if (pageReady) web.evaluateJavascript("stop()", null);
    }

    private static JSONObject tile(String entity, float l, float t, float w, float h, boolean full, boolean live) {
        try {
            return new JSONObject().put("entity", entity).put("l", l).put("t", t).put("w", w).put("h", h).put("full", full)
                    .put("live", live);
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    /** From the page: a camera is "live", or "error:<why>". */
    private final class Bridge {
        @JavascriptInterface public void state(String entity, String s) {
            main.post(() -> {
                int i = ids.indexOf(entity);
                if (i < 0) return;
                boolean ok = s.equals("live");
                if (!ok) AppLog.w("camera " + entity + ": " + s);
                String why = s.equals("error:auth") ? Ui.tr("Home Assistant turned the token down. Check ha_token in config.properties.")
                        : s.startsWith("error:socket") || s.startsWith("error:closed") ? Ui.tr("Can't reach Home Assistant right now.")
                        : Ui.tr("Not answering right now (asleep or busy).");
                setTileStatus(i, ok ? "" : why, ok ? "live" : null);
                if (i < liveBadges.size()) liveBadges.get(i).setVisibility(ok && !single ? View.VISIBLE : View.INVISIBLE);
                if (!single || i != current) return;
                if (ok) {
                    playing = true;
                    main.removeCallbacks(slow);
                    live.setVisibility(View.VISIBLE);
                    say("");
                } else {
                    stop();
                    say(why + " " + Ui.tr("Press OK to try again."));
                    fade(true);
                }
            });
        }
    }

    // MARK: - The grid

    /**
     * Each camera's tile in 1920 × 1080 design pixels {left, top, width, height}: three cameras as one large on the left
     * and two stacked on the right; otherwise two across up to four, three up to nine, then four, as many rows as it takes.
     */
    private float[] tileRect(int i) {
        float left = 80, top = 150, right = 1840, bottom = 1010, gap = 24, w = right - left, h = bottom - top;
        int n = ids.size();
        if (n == 1) return new float[] {left, top, w, h};
        if (n == 3) {
            float big = (w - gap) * 0.655f, small = w - gap - big, half = (h - gap) / 2;
            return i == 0 ? new float[] {left, top, big, h} : new float[] {left + big + gap, top + (i - 1) * (half + gap), small, half};
        }
        int cols = n <= 4 ? 2 : n <= 9 ? 3 : 4, rows = (n + cols - 1) / cols;
        float tw = (w - (cols - 1) * gap) / cols, th = (h - (rows - 1) * gap) / rows;
        return new float[] {left + (i % cols) * (tw + gap), top + (i / cols) * (th + gap), tw, th};
    }

    private View buildGrid() {
        FrameLayout layer = new FrameLayout(this);
        String place = site >= 0 && site < CameraSites.ALL.size() ? CameraSites.ALL.get(site).name : "";
        TextView heading = text(place.isEmpty() ? Ui.tr("Cameras") : place, 52, INK, Ui.displaySemibold);
        FrameLayout.LayoutParams hp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hp.leftMargin = px(80);
        hp.topMargin = px(52);
        layer.addView(heading, hp);
        for (int i = 0; i < ids.size(); i++) {
            final int index = i;
            float[] r = tileRect(i);
            FrameLayout t = new FrameLayout(this);
            t.setFocusable(true);
            t.setFocusableInTouchMode(true);
            t.setBackground(edge(false));
            t.setOnFocusChangeListener((v, has) -> {
                v.setBackground(edge(has));
                if (has && index != current) {
                    current = index;
                    main.removeCallbacks(gridSwitch);
                    main.postDelayed(gridSwitch, 600);
                }
            });
            t.setOnClickListener(v -> {
                if (paused) { resume(); return; }
                single = true;
                current = index;
                layoutMode();
                showSingle(index);
            });
            TextView name = text(names.get(i), ids.size() > 6 ? 22 : 28, INK, Ui.semibold);
            name.setPadding(px(22), px(10), px(22), px(10));
            name.setBackground(pill(0x8C000000));
            FrameLayout.LayoutParams np = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.BOTTOM | Gravity.START);
            np.leftMargin = px(18);
            np.bottomMargin = px(18);
            t.addView(name, np);
            TextView badge = text("●  " + Ui.tr("LIVE"), 18, INK, Ui.semibold);
            badge.setLetterSpacing(0.08f);
            badge.setPadding(px(12), px(5), px(14), px(5));
            badge.setBackground(pill(LIVE));
            badge.setVisibility(View.INVISIBLE);
            FrameLayout.LayoutParams bp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.TOP | Gravity.END);
            bp.topMargin = px(16);
            bp.rightMargin = px(16);
            t.addView(badge, bp);
            liveBadges.add(badge);
            TextView status = text("", ids.size() > 6 ? 20 : 24, INK, Ui.medium);
            status.setGravity(Gravity.CENTER);
            status.setPadding(px(26), px(12), px(26), px(12));
            status.setBackground(pill(0xA6000000));
            status.setVisibility(View.GONE);
            FrameLayout.LayoutParams sp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
            sp.leftMargin = sp.rightMargin = px(24);
            t.addView(status, sp);
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(px(r[2]), px(r[3]));
            lp.leftMargin = px(r[0]);
            lp.topMargin = px(r[1]);
            layer.addView(t, lp);
            tiles.add(t);
            tileStatus.add(status);
        }
        return layer;
    }

    private void setTileStatus(int i, String s, String tag) {
        if (i >= tileStatus.size()) return;
        TextView t = tileStatus.get(i);
        t.setText(s);
        t.setTag(tag);
        t.setVisibility(s.isEmpty() ? View.GONE : View.VISIBLE);
    }

    /** A tile's edge: faint at rest, white when focused. */
    private GradientDrawable edge(boolean focused) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(0x00000000);
        d.setCornerRadius(px(14));
        d.setStroke(px(focused ? 5 : 2), focused ? 0xFFFFFFFF : 0x33FFFFFF);
        return d;
    }

    // MARK: - Full screen

    private View buildHead() {
        // The camera's name and LIVE, top left, on a shade so they read over a bright picture.
        LinearLayout h = new LinearLayout(this);
        h.setGravity(Gravity.CENTER_VERTICAL);
        h.setPadding(px(80), px(52), px(80), px(80));
        h.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[] {0x99000000, 0x00000000}));
        title = text("", 44, INK, Ui.displaySemibold);
        h.addView(title);
        TextView dot = text("●  " + Ui.tr("LIVE"), 22, INK, Ui.semibold);
        dot.setLetterSpacing(0.08f);
        dot.setPadding(px(16), px(7), px(18), px(7));
        dot.setBackground(pill(LIVE));
        LinearLayout.LayoutParams dp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dp.leftMargin = px(24);
        h.addView(dot, dp);
        live = dot;
        live.setVisibility(View.INVISIBLE);
        return h;
    }

    private View buildBar() {
        // The cameras, bottom left; more than fit across scroll along with the highlight.
        FrameLayout shade = new FrameLayout(this);
        shade.setPadding(0, px(90), 0, px(52));
        shade.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, new int[] {0xB3000000, 0x00000000}));
        HorizontalScrollView scroll = new HorizontalScrollView(this);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.setClipToPadding(false);
        scroll.setPadding(px(80), 0, px(80), 0);
        shade.addView(scroll);
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        scroll.addView(row);
        for (int i = 0; i < ids.size(); i++) {
            final int index = i;
            TextView chip = text(names.get(i), 28, INK, Ui.medium);
            chip.setPadding(px(30), px(16), px(30), px(16));
            chip.setFocusable(true);
            chip.setFocusableInTouchMode(true);
            chip.setOnFocusChangeListener((v, has) -> {
                style();
                if (has && single && index != current) {
                    main.removeCallbacks(switcher);
                    pending = index;
                    main.postDelayed(switcher, SWITCH_MS);
                }
            });
            chip.setOnClickListener(v -> { if (index != current || !playing || paused) { paused = false; showSingle(index); } });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = px(16);
            row.addView(chip, lp);
            chips.add(chip);
        }
        return shade;
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
        head.animate().alpha(to).setDuration(250).start();
        if (show) main.postDelayed(hide, HIDE_MS);
    }

    // MARK: - Home Assistant

    /** The cameras' names from Home Assistant ("Front Door Live view" → "Front Door"). */
    private void readNames() {
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
                    names.set(index, n);
                    chips.get(index).setText(n);
                    View label = tiles.get(index).getChildAt(0);
                    if (label instanceof TextView) ((TextView) label).setText(n);
                    if (single && index == current) title.setText(n);
                });
            } catch (Exception e) {
                AppLog.w("camera name " + ids.get(i) + ": " + e.getMessage());
            }
        }
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
