package io.freemytv;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Html;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * One game from the Scores page (ESPN's game summary, no key). Before it: the odds (DraftKings, via ESPN), ESPN's win
 * chances, the preview and the teams' season numbers; during and after: the line score, team stats and every
 * player's numbers, and after it the recap; always the latest news. A Formula 1 session shows its classification (or
 * the starting grid) and F1's news. Each game in its teams' colors: a band from one to the other, logos on white
 * discs, and bars in both colors for the win chances and the team stats. ↑ ↓ scroll; live games refresh every 30 s; Back returns to Scores.
 */
public class GameActivity extends Activity {
    static final String EXTRA_PATH = "path", EXTRA_EVENT = "event", EXTRA_SESSION = "session";
    private static final int BG = 0xFF0B0B0F, CARD = 0xFF1C1C21, INK = 0xFFF5F5F7, DIM = 0xFF98989D, FAINT = 0xFF636366;
    private static final long REFRESH = 30_000;

    private final ExecutorService io = Executors.newFixedThreadPool(2);
    private final Handler main = new Handler(Looper.getMainLooper());
    private ScrollView scroll;
    private LinearLayout page;
    private String path, event;
    private int session = -1;
    private boolean live, resumed;
    private float k;

    static Intent intent(Context c, String path, String event, int session) {
        return new Intent(c, GameActivity.class).putExtra(EXTRA_PATH, path).putExtra(EXTRA_EVENT, event)
                .putExtra(EXTRA_SESSION, session);
    }

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Ui.init(this);
        k = getResources().getDisplayMetrics().widthPixels / 1920f;
        path = getIntent().getStringExtra(EXTRA_PATH);
        event = getIntent().getStringExtra(EXTRA_EVENT);
        session = getIntent().getIntExtra(EXTRA_SESSION, -1);
        scroll = new ScrollView(this);
        scroll.setBackgroundColor(BG);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setFocusable(true);
        scroll.setFocusableInTouchMode(true);     // keeps focus if Fire OS switches to touch mode (else the next arrow is eaten)
        page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(px(80), px(52), px(80), px(60));
        scroll.addView(page);
        setContentView(scroll);
        page.addView(text(Ui.tr("Loading…"), 26, DIM, Ui.medium));
        load();
    }

    @Override protected void onResume() {
        super.onResume();
        resumed = true;
        scroll.requestFocus();
        main.postDelayed(refresh, REFRESH);
    }

    @Override protected void onPause() {
        resumed = false;
        main.removeCallbacks(refresh);
        super.onPause();
    }

    @Override protected void onDestroy() {
        io.shutdownNow();
        super.onDestroy();
    }

    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            if (!resumed) return;
            if (live) load();
            main.postDelayed(this, REFRESH);
        }
    };

    /** ↑ ↓ scroll the page (it has nothing to focus). */
    @Override public boolean onKeyDown(int keyCode, KeyEvent e) {
        if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
            scroll.smoothScrollBy(0, px(320));
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
            scroll.smoothScrollBy(0, -px(320));
            return true;
        }
        return super.onKeyDown(keyCode, e);
    }

    private void load() {
        io.execute(() -> {
            try {
                if (session >= 0) {
                    JSONObject board = Sports.get(path + "/scoreboard");
                    JSONObject standings = Sports.get("https://site.api.espn.com/apis/v2/sports/" + path + "/standings");
                    JSONObject news = Sports.get(path + "/news?limit=8");
                    main.post(() -> show(f1(board, standings, news)));
                } else {
                    JSONObject summary = Sports.get(path + "/summary?event=" + event);
                    main.post(() -> show(game(summary)));
                }
            } catch (Exception e) {
                AppLog.w("game " + event + ": " + e.getMessage());
                main.post(() -> {
                    if (page.getChildCount() <= 1) {
                        page.removeAllViews();
                        page.addView(text(Ui.tr("Couldn't load this game. Trying again soon."), 26, DIM, Ui.medium));
                        live = true;
                    }
                });
            }
        });
    }

    private void show(List<View> views) {
        if (isFinishing()) return;
        int y = scroll.getScrollY();
        page.removeAllViews();
        for (View v : views) page.addView(v);
        scroll.post(() -> scroll.scrollTo(0, y));
    }

    // MARK: - Team colors

    /** The two teams' colors: bands (the header's background) and inks (bars, stripes and names on the dark page). */
    private int awayBand = CARD, homeBand = CARD, awayInk = INK, homeInk = INK;

    private void colors(JSONObject away, JSONObject home) {
        int[] a = teamColors(away), h = teamColors(home);
        awayInk = ink(a);
        homeInk = ink(h);
        // Two navy teams (Patriots and Bears): the home team's other color, so the bars tell them apart.
        if (Sports.apart(awayInk, homeInk) < 95 && Sports.apart(awayInk, other(h, homeInk)) > Sports.apart(awayInk, homeInk)) {
            homeInk = other(h, homeInk);
        }
        awayBand = band(a, awayInk);
        homeBand = band(h, homeInk);
    }

    /** {color, alternate} from ESPN (hex, either may be missing). */
    static int[] teamColors(JSONObject competitor) {
        JSONObject t = competitor == null ? null : competitor.optJSONObject("team");
        return new int[] {hex(t == null ? null : t.optString("color"), 0xFF48484A), hex(t == null ? null : t.optString("alternateColor"), 0xFF48484A)};
    }

    private static int hex(String s, int fallback) {
        try {
            return s == null || s.isEmpty() ? fallback : 0xFF000000 | Integer.parseInt(s, 16);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /** The color that reads on the dark page: the team's own, else its alternate, else its own lifted. */
    static int ink(int[] c) {
        if (luma(c[0]) >= 0.16) return c[0];
        if (luma(c[1]) >= 0.16 && luma(c[1]) < 0.92) return c[1];
        float[] hsv = new float[3];
        android.graphics.Color.colorToHSV(c[0], hsv);
        hsv[2] = Math.max(hsv[2], 0.75f);
        hsv[1] = Math.min(hsv[1], 0.75f);
        return android.graphics.Color.HSVToColor(hsv);
    }

    private static int other(int[] c, int chosen) {
        int alt = chosen == c[0] ? c[1] : c[0];
        return luma(alt) >= 0.16 ? alt : chosen;
    }

    /** A header color: the team's own, deepened (so white reads on it); near-black teams get their ink, deepened. */
    private static int band(int[] c, int ink) {
        return Sports.deepen(luma(c[0]) < 0.02 ? ink : c[0]);
    }

    private static double luma(int c) {
        return (0.2126 * ((c >> 16) & 255) + 0.7152 * ((c >> 8) & 255) + 0.0722 * (c & 255)) / 255;
    }

    /** a toward b by f. */
    static int mix(int a, int b, float f) {
        int r = Math.round(((a >> 16) & 255) + (((b >> 16) & 255) - ((a >> 16) & 255)) * f);
        int g = Math.round(((a >> 8) & 255) + (((b >> 8) & 255) - ((a >> 8) & 255)) * f);
        int bl = Math.round((a & 255) + ((b & 255) - (a & 255)) * f);
        return 0xFF000000 | (r << 16) | (g << 8) | bl;
    }

    private static final int ODDS = 0xFF30D158, STORY = 0xFFFFD60A, NEWS = 0xFF0A84FF, F1_RED = 0xFFE10600;

    // MARK: - A game

    private List<View> game(JSONObject s) {
        List<View> out = new ArrayList<>();
        JSONObject comp = s.optJSONObject("header") == null ? null : s.optJSONObject("header").optJSONArray("competitions").optJSONObject(0);
        if (comp == null) return out;
        JSONObject type = comp.optJSONObject("status") == null ? null : comp.optJSONObject("status").optJSONObject("type");
        String state = type == null ? "pre" : type.optString("state", "pre");
        live = state.equals("in");
        JSONArray teams = comp.optJSONArray("competitors");
        JSONObject away = null, home = null;
        for (int i = 0; teams != null && i < teams.length(); i++) {
            JSONObject t = teams.optJSONObject(i);
            if ("home".equals(t.optString("homeAway"))) home = t; else away = t;
        }
        if (away == null || home == null) return out;
        colors(away, home);
        JSONObject venue = s.optJSONObject("gameInfo") == null ? null : s.optJSONObject("gameInfo").optJSONObject("venue");
        out.add(header(away, home, comp, state, type, venue == null ? "" : venue.optString("fullName")));

        LinearLayout cols = new LinearLayout(this);
        cols.setPadding(0, px(32), 0, 0);
        LinearLayout left = column(), right = column();
        cols.addView(left, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.25f));
        View gap = new View(this);
        cols.addView(gap, new LinearLayout.LayoutParams(px(32), 1));
        cols.addView(right, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        out.add(cols);

        if (!state.equals("pre")) {
            View line = lineScore(away, home);
            if (line != null) left.addView(card(Ui.tr("Line score"), line, awayInk, homeInk));
            players(left, s.optJSONObject("boxscore"), away, home);
        }
        if (state.equals("pre")) {
            View odds = odds(s, away, home);
            if (odds != null) right.addView(odds);
        }
        View story = story(s.optJSONObject("article"), state);
        if (story != null) right.addView(story);
        View stats = teamStats(s.optJSONObject("boxscore"), state, away);
        if (stats != null) (state.equals("pre") ? left : right).addView(stats);
        View news = news(s.optJSONObject("news"));
        if (news != null) right.addView(news);
        if (left.getChildCount() == 0) {
            cols.removeView(left);
            cols.removeView(gap);
        }
        return out;
    }

    /**
     * A band from the away team's color to the home team's: their logos on white badges, names and records, the score
     * (the loser's dimmer) or the start, and under it the status (LIVE in red) and the game's notes, network and venue.
     */
    private View header(JSONObject away, JSONObject home, JSONObject comp, String state, JSONObject type, String venue) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(px(48), px(40), px(48), px(36));
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[] {awayBand, mix(awayBand, BG, 0.7f), mix(homeBand, BG, 0.7f), homeBand});
        bg.setCornerRadius(px(32));
        box.setBackground(bg);
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        boolean post = state.equals("post");
        row.addView(team(away, false, post && !away.optBoolean("winner")), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        LinearLayout mid = new LinearLayout(this);
        mid.setOrientation(LinearLayout.VERTICAL);
        mid.setGravity(Gravity.CENTER_HORIZONTAL);
        if (state.equals("pre")) {
            TextView t = text(start(comp.optString("date")), 48, INK, Ui.displaySemibold);
            t.setGravity(Gravity.CENTER);
            mid.addView(t);
        } else {
            android.text.SpannableStringBuilder score = new android.text.SpannableStringBuilder();
            appendScore(score, away.optString("score"), post && !away.optBoolean("winner") ? 0x80FFFFFF : INK);
            appendScore(score, "  –  ", 0x59FFFFFF);
            appendScore(score, home.optString("score"), post && !home.optBoolean("winner") ? 0x80FFFFFF : INK);
            TextView t = text(score, 92, INK, Ui.displaySemibold);
            t.setGravity(Gravity.CENTER);
            mid.addView(t);
        }
        String status = type == null ? "" : state.equals("in") ? "●  " + Ui.tr("LIVE") + "  ·  " + type.optString("shortDetail")
                : post ? type.optString("detail").toUpperCase(Ui.locale()) : "";
        if (!status.isEmpty()) {
            TextView pill = chip(status, state.equals("in") ? 0xFFFF3B30 : 0x33FFFFFF, INK);
            LinearLayout.LayoutParams pl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            pl.setMargins(0, px(14), 0, 0);
            mid.addView(pill, pl);
        }
        row.addView(mid, new LinearLayout.LayoutParams(px(400), ViewGroup.LayoutParams.WRAP_CONTENT));
        row.addView(team(home, true, post && !home.optBoolean("winner")), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        box.addView(row);

        List<String> bits = new ArrayList<>();
        JSONArray notes = comp.optJSONArray("notes");
        if (notes != null && notes.length() > 0 && !notes.optJSONObject(0).optString("headline").isEmpty()) bits.add(notes.optJSONObject(0).optString("headline"));
        JSONArray b = comp.optJSONArray("broadcasts");
        JSONObject m = b == null || b.length() == 0 ? null : b.optJSONObject(0).optJSONObject("media");
        if (m != null && !m.optString("shortName").isEmpty()) bits.add(m.optString("shortName"));
        if (!venue.isEmpty()) bits.add(venue);
        if (!bits.isEmpty()) {
            LinearLayout chips = new LinearLayout(this);
            chips.setGravity(Gravity.CENTER);
            chips.setPadding(0, px(26), 0, 0);
            for (String x : bits) {
                LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                cl.setMargins(px(6), 0, px(6), 0);
                chips.addView(chip(x, 0x40000000, 0xE6FFFFFF), cl);
            }
            box.addView(chips, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        return box;
    }

    private static void appendScore(android.text.SpannableStringBuilder b, String s, int color) {
        int at = b.length();
        b.append(s);
        b.setSpan(new android.text.style.ForegroundColorSpan(color), at, b.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    private View team(JSONObject c, boolean home, boolean lost) {
        JSONObject t = c.optJSONObject("team");
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL | (home ? Gravity.START : Gravity.END));
        LinearLayout names = new LinearLayout(this);
        names.setOrientation(LinearLayout.VERTICAL);
        names.setGravity(home ? Gravity.START : Gravity.END);
        String name = t == null ? "" : t.optString("displayName");
        int rank = c.optJSONObject("rank") != null ? c.optJSONObject("rank").optInt("current", 99) : c.optInt("rank", 99);
        if (Sports.favorite(c)) name = home ? name + "  ★" : "★  " + name;
        TextView label = text((rank > 0 && rank <= 25 ? "#" + rank + "  " : "") + name, 33, lost ? 0xB3FFFFFF : INK, Ui.semibold);
        label.setGravity(home ? Gravity.START : Gravity.END);
        names.addView(label, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        String record = "";
        JSONArray rec = c.optJSONArray("record");
        if (rec != null && rec.length() > 0) record = rec.optJSONObject(0).optString("displayValue", rec.optJSONObject(0).optString("summary"));
        if (!record.isEmpty()) {
            TextView r = text(record, 24, 0xB3FFFFFF, Ui.medium);
            r.setPadding(0, px(6), 0, 0);
            r.setGravity(home ? Gravity.START : Gravity.END);
            names.addView(r, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        String url = null;
        if (t != null) {
            JSONArray logos = t.optJSONArray("logos");
            url = logos != null && logos.length() > 0 ? logos.optJSONObject(0).optString("href") : t.optString("logo", null);
        }
        View badge = badge(url, 140);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(px(140), px(140));
        LinearLayout.LayoutParams nl = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        if (home) {
            lp.setMarginEnd(px(28));
            row.addView(badge, lp);
            row.addView(names, nl);
        } else {
            lp.setMarginStart(px(28));
            row.addView(names, nl);
            row.addView(badge, lp);
        }
        return row;
    }

    /** A logo on a white disc (team logos are drawn for light backgrounds; on its own color a logo can vanish). */
    private View badge(String url, int size) {
        android.widget.FrameLayout disc = new android.widget.FrameLayout(this);
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(0xFFFFFFFF);
        disc.setBackground(d);
        disc.setElevation(px(8));
        ImageView logo = new ImageView(this);
        logo.setScaleType(ImageView.ScaleType.FIT_CENTER);
        disc.addView(logo, new android.widget.FrameLayout.LayoutParams(px(size * 0.7f), px(size * 0.7f), Gravity.CENTER));
        image(logo, url, 2);
        return disc;
    }

    /** Innings, quarters or periods, and the total: each team's abbreviation in its color, the total on a pill. */
    private View lineScore(JSONObject away, JSONObject home) {
        JSONArray a = away.optJSONArray("linescores"), h = home.optJSONArray("linescores");
        if (a == null || h == null || a.length() == 0) return null;
        int n = Math.max(a.length(), h.length());
        LinearLayout table = new LinearLayout(this);
        table.setOrientation(LinearLayout.VERTICAL);
        List<String> head = new ArrayList<>();
        head.add("");
        for (int i = 1; i <= n; i++) head.add(String.valueOf(i));
        head.add(Ui.tr("T"));
        table.addView(tableRow(head, true, 0));
        for (JSONObject t : new JSONObject[] {away, home}) {
            JSONArray ls = t.optJSONArray("linescores");
            List<String> cells = new ArrayList<>();
            cells.add(t.optJSONObject("team") == null ? "" : t.optJSONObject("team").optString("abbreviation"));
            for (int i = 0; i < n; i++) cells.add(i < ls.length() ? ls.optJSONObject(i).optString("displayValue", "") : "");
            cells.add(t.optString("score"));
            LinearLayout row = (LinearLayout) tableRow(cells, false, 0);
            int ink = t == away ? awayInk : homeInk;
            TextView name = (TextView) row.getChildAt(0);
            name.setTextColor(ink);
            name.setTypeface(Ui.semibold);
            TextView total = (TextView) row.getChildAt(row.getChildCount() - 1);
            total.setTypeface(Ui.semibold);
            total.setTextColor(INK);
            total.setBackground(round(mix(ink, CARD, 0.65f), 12));
            table.addView(row);
        }
        return table;
    }

    /** Each team's players, one table per group (batting and pitching; passing, rushing, receiving…), in its color. */
    private void players(LinearLayout into, JSONObject box, JSONObject away, JSONObject home) {
        JSONArray teams = box == null ? null : box.optJSONArray("players");
        String awayId = away.optJSONObject("team") == null ? "" : away.optJSONObject("team").optString("id");
        for (int t = 0; teams != null && t < teams.length(); t++) {
            JSONObject team = teams.optJSONObject(t);
            JSONObject info = team.optJSONObject("team");
            String abbr = info == null ? "" : info.optString("displayName");
            int ink = info != null && info.optString("id").equals(awayId) ? awayInk : homeInk;
            JSONArray groups = team.optJSONArray("statistics");
            for (int g = 0; groups != null && g < groups.length() && g < 4; g++) {
                JSONObject group = groups.optJSONObject(g);
                JSONArray labels = group.optJSONArray("labels"), athletes = group.optJSONArray("athletes");
                if (labels == null || athletes == null || athletes.length() == 0) continue;
                int cols = Math.min(labels.length(), 7), rows = Math.min(athletes.length(), "baseball".equals(sport()) ? 10 : 5);
                LinearLayout table = new LinearLayout(this);
                table.setOrientation(LinearLayout.VERTICAL);
                List<String> head = new ArrayList<>();
                head.add("");
                for (int i = 0; i < cols; i++) head.add(labels.optString(i));
                View headRow = tableRow(head, true, 1);
                ((LinearLayout) headRow).addView(new View(this), 0, new LinearLayout.LayoutParams(px(56), 1));
                table.addView(headRow);
                int shown = 0;
                for (int i = 0; i < rows; i++) {
                    JSONObject a = athletes.optJSONObject(i);
                    JSONArray st = a.optJSONArray("stats");
                    if (st == null || st.length() == 0) continue;
                    List<String> cells = new ArrayList<>();
                    JSONObject who = a.optJSONObject("athlete");
                    cells.add(shortName(who));
                    for (int c = 0; c < cols; c++) cells.add(c < st.length() ? st.optString(c) : "");
                    LinearLayout row = (LinearLayout) tableRow(cells, false, 1);
                    ImageView face = new ImageView(this);
                    face.setScaleType(ImageView.ScaleType.CENTER_CROP);
                    GradientDrawable disc = new GradientDrawable();
                    disc.setShape(GradientDrawable.OVAL);
                    disc.setColor(mix(ink, CARD, 0.6f));
                    face.setBackground(disc);
                    face.setClipToOutline(true);
                    LinearLayout.LayoutParams fl = new LinearLayout.LayoutParams(px(44), px(44));
                    fl.setMarginEnd(px(12));
                    row.addView(face, 0, fl);
                    JSONObject shot = who == null ? null : who.optJSONObject("headshot");
                    image(face, shot == null ? null : shot.optString("href"), 8);
                    if (shown++ % 2 == 0) row.setBackground(round(0x0DFFFFFF, 10));
                    table.addView(row);
                }
                String title = abbr + "  ·  " + title(group.optString("name", group.optString("type")));
                into.addView(card(title, table, ink));
            }
        }
    }

    /** "D. Maye": ESPN's short name, or made from the full one (football's box scores have none). */
    private static String shortName(JSONObject athlete) {
        if (athlete == null) return "";
        String s = athlete.optString("shortName");
        if (!s.isEmpty()) return s;
        String first = athlete.optString("firstName"), last = athlete.optString("lastName");
        return first.isEmpty() || last.isEmpty() ? athlete.optString("displayName") : first.charAt(0) + ". " + last;
    }

    private String sport() {
        return path == null ? "" : path.substring(0, path.indexOf('/') < 0 ? path.length() : path.indexOf('/'));
    }

    private static String title(String s) {
        return s.isEmpty() ? s : s.substring(0, 1).toUpperCase(Locale.ROOT) + s.substring(1);
    }

    /** ESPN's win chances as a bar in the teams' colors, then the line, the total and the moneylines as tiles. */
    private View odds(JSONObject s, JSONObject away, JSONObject home) {
        JSONArray pc = s.optJSONArray("pickcenter");
        JSONObject p = pc == null || pc.length() == 0 ? null : pc.optJSONObject(0);
        JSONObject pred = s.optJSONObject("predictor");
        if (p == null && pred == null) return null;
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        String a = abbr(away), h = abbr(home);
        if (pred != null) {
            String ap = pct(pred.optJSONObject("awayTeam")), hp = pct(pred.optJSONObject("homeTeam"));
            if (!ap.isEmpty() && !hp.isEmpty()) {
                box.addView(text(Ui.tr("Win chance (ESPN)"), 20, DIM, Ui.semibold));
                LinearLayout ends = new LinearLayout(this);
                ends.setPadding(0, px(10), 0, px(10));
                ends.addView(text(a + "  " + ap, 34, awayInk, Ui.displaySemibold), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
                ends.addView(text(hp + "  " + h, 34, homeInk, Ui.displaySemibold));
                box.addView(ends);
                float f = (float) (pred.optJSONObject("awayTeam").optDouble("gameProjection") / 100);
                box.addView(new SplitBar(this, f, awayInk, homeInk), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(16)));
            }
        }
        if (p != null) {
            LinearLayout tiles = new LinearLayout(this);
            tiles.setPadding(0, box.getChildCount() > 0 ? px(26) : 0, 0, 0);
            if (!p.optString("details").isEmpty()) tile(tiles, Ui.tr("Line"), p.optString("details"));
            if (p.has("overUnder")) tile(tiles, Ui.tr("Over/under"), number(p.optDouble("overUnder")));
            String am = moneyline(p.optJSONObject("awayTeamOdds")), hm = moneyline(p.optJSONObject("homeTeamOdds"));
            if (!am.isEmpty() || !hm.isEmpty()) tile(tiles, Ui.tr("Moneyline"), a + " " + am + "\n" + h + " " + hm);
            if (tiles.getChildCount() > 0) box.addView(tiles);
        }
        if (box.getChildCount() == 0) return null;
        String provider = p == null || p.optJSONObject("provider") == null ? "" : p.optJSONObject("provider").optString("name");
        return card(provider.isEmpty() ? Ui.tr("Odds") : Ui.trf("Odds · {book}", "book", provider), box, ODDS);
    }

    private void tile(LinearLayout into, String label, String value) {
        LinearLayout t = new LinearLayout(this);
        t.setOrientation(LinearLayout.VERTICAL);
        t.setPadding(px(20), px(16), px(20), px(18));
        t.setBackground(round(0x12FFFFFF, 16));
        t.addView(text(label.toUpperCase(Ui.locale()), 18, DIM, Ui.semibold));
        TextView v = text(value, value.contains("\n") ? 24 : 32, INK, Ui.semibold);
        v.setSingleLine(false);
        v.setPadding(0, px(8), 0, 0);
        t.addView(v);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1);
        if (into.getChildCount() > 0) lp.setMarginStart(px(14));
        into.addView(t, lp);
    }

    private static String moneyline(JSONObject o) {
        if (o == null) return "";
        Object m = o.opt("moneyLine");
        if (m == null) return "";
        int v = (int) Math.round(o.optDouble("moneyLine"));
        return v > 0 ? "+" + v : String.valueOf(v);
    }

    private static String pct(JSONObject t) {
        if (t == null || t.optString("gameProjection").isEmpty()) return "";
        return Math.round(t.optDouble("gameProjection")) + "%";
    }

    private static String number(double v) {
        return v == Math.floor(v) ? String.valueOf((long) v) : String.valueOf(v);
    }

    private static String abbr(JSONObject c) {
        return c.optJSONObject("team") == null ? "" : c.optJSONObject("team").optString("abbreviation");
    }

    /** The preview (before) or recap (after): its photo, headline and the start of the story. */
    private View story(JSONObject article, String state) {
        if (article == null || article.optString("headline").isEmpty() || state.equals("in")) return null;
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        String photo = photo(article);
        if (photo != null) {
            ImageView img = photoView();
            LinearLayout.LayoutParams il = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(330));
            il.setMargins(0, 0, 0, px(22));
            box.addView(img, il);
            image(img, photo, 1);
        }
        TextView h = text(article.optString("headline"), 32, INK, Ui.semibold);
        h.setSingleLine(false);
        box.addView(h);
        String body = plain(article.optString("story", article.optString("description")));
        if (body.length() > 900) body = body.substring(0, body.lastIndexOf(' ', 900)) + "…";
        if (!body.isEmpty()) {
            TextView b = text(body, 24, 0xFFC7C7CC, Ui.regular);
            b.setSingleLine(false);
            b.setLineSpacing(px(8), 1);
            b.setPadding(0, px(14), 0, 0);
            box.addView(b);
        }
        return card(state.equals("post") ? Ui.tr("Recap") : Ui.tr("Preview"), box, STORY);
    }

    private static String photo(JSONObject article) {
        JSONArray images = article == null ? null : article.optJSONArray("images");
        String url = images == null || images.length() == 0 ? "" : images.optJSONObject(0).optString("url");
        return url.isEmpty() ? null : url;
    }

    /** A photo, cropped to fill, with rounded corners. */
    private ImageView photoView() {
        ImageView img = new ImageView(this);
        img.setScaleType(ImageView.ScaleType.CENTER_CROP);
        img.setBackground(round(0xFF2C2C33, 16));
        img.setOutlineProvider(new android.view.ViewOutlineProvider() {
            @Override public void getOutline(View v, android.graphics.Outline o) { o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), px(16)); }
        });
        img.setClipToOutline(true);
        return img;
    }

    private static String plain(String html) {
        String s = Html.fromHtml(html, Html.FROM_HTML_MODE_COMPACT).toString();
        return s.replaceAll("\\s*\\n\\s*", "\n\n").replaceAll("\\n{3,}", "\n\n").trim();
    }

    /**
     * The two teams side by side: season numbers before the game, the game's after it starts. Each number in its team's
     * color, with a bar of the two shares where the numbers compare.
     */
    private View teamStats(JSONObject box, String state, JSONObject away) {
        JSONArray teams = box == null ? null : box.optJSONArray("teams");
        if (teams == null || teams.length() < 2) return null;
        // The boxscore lists the teams in its own order: match the away team by id.
        String awayId = away.optJSONObject("team") == null ? "" : away.optJSONObject("team").optString("id");
        JSONObject first = teams.optJSONObject(0), second = teams.optJSONObject(1);
        if (second.optJSONObject("team") != null && second.optJSONObject("team").optString("id").equals(awayId)) {
            JSONObject x = first;
            first = second;
            second = x;
        }
        JSONArray a = first.optJSONArray("statistics"), h = second.optJSONArray("statistics");
        if (a == null || h == null) return null;
        LinearLayout table = new LinearLayout(this);
        table.setOrientation(LinearLayout.VERTICAL);
        LinearLayout head = new LinearLayout(this);
        head.setPadding(0, 0, 0, px(8));
        head.addView(text(first.optJSONObject("team").optString("abbreviation"), 24, awayInk, Ui.semibold), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        head.addView(text(second.optJSONObject("team").optString("abbreviation"), 24, homeInk, Ui.semibold));
        table.addView(head);
        int rows = 0;
        for (int i = 0; i < a.length() && i < h.length() && rows < 12; i++) {
            JSONObject x = a.optJSONObject(i), y = h.optJSONObject(i);
            String label = x.optString("label", x.optString("displayName", x.optString("name")));
            if (x.optString("displayValue").isEmpty() || label.isEmpty()) continue;
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, px(12), 0, px(6));
            row.addView(text(x.optString("displayValue"), 26, INK, Ui.semibold), new LinearLayout.LayoutParams(px(150), ViewGroup.LayoutParams.WRAP_CONTENT));
            TextView l = text(label, 22, DIM, Ui.medium);
            l.setGravity(Gravity.CENTER);
            row.addView(l, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            TextView r = text(y.optString("displayValue"), 26, INK, Ui.semibold);
            r.setGravity(Gravity.END);
            row.addView(r, new LinearLayout.LayoutParams(px(150), ViewGroup.LayoutParams.WRAP_CONTENT));
            table.addView(row);
            double va = lead(x.optString("displayValue")), vh = lead(y.optString("displayValue"));
            if (va >= 0 && vh >= 0 && va + vh > 0) {
                table.addView(new SplitBar(this, (float) (va / (va + vh)), awayInk, homeInk),
                        new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(6)));
            }
            rows++;
        }
        if (rows == 0) return null;
        return card(state.equals("pre") ? Ui.tr("Season stats") : Ui.tr("Team stats"), table, awayInk, homeInk);
    }

    private static final java.util.regex.Pattern NUMBER = java.util.regex.Pattern.compile("^\\d+(\\.\\d+)?");

    /** The number a stat starts with ("5-12" → 5, "34:21" → 34, ".271" → 0.271), or -1. */
    private static double lead(String v) {
        java.util.regex.Matcher m = NUMBER.matcher(v.startsWith(".") ? "0" + v : v);
        return m.find() ? Double.parseDouble(m.group()) : -1;
    }

    /** The latest stories: a photo beside each headline. */
    private View news(JSONObject news) {
        JSONArray articles = news == null ? null : news.optJSONArray("articles");
        if (articles == null || articles.length() == 0) return null;
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        for (int i = 0; i < articles.length() && i < 5; i++) {
            JSONObject a = articles.optJSONObject(i);
            LinearLayout row = new LinearLayout(this);
            row.setPadding(0, i == 0 ? 0 : px(18), 0, px(18));
            String photo = photo(a);
            if (photo != null) {
                ImageView img = photoView();
                LinearLayout.LayoutParams il = new LinearLayout.LayoutParams(px(192), px(108));
                il.setMarginEnd(px(22));
                row.addView(img, il);
                image(img, photo, 4);
            }
            LinearLayout words = new LinearLayout(this);
            words.setOrientation(LinearLayout.VERTICAL);
            TextView h = text(a.optString("headline"), 25, INK, Ui.semibold);
            h.setSingleLine(false);
            h.setMaxLines(2);
            words.addView(h);
            String d = a.optString("description");
            if (!d.isEmpty()) {
                TextView t = text(d, 21, DIM, Ui.regular);
                t.setSingleLine(false);
                t.setMaxLines(2);
                t.setEllipsize(TextUtils.TruncateAt.END);
                t.setPadding(0, px(6), 0, 0);
                words.addView(t);
            }
            row.addView(words, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            if (i > 0) {
                View rule = new View(this);
                rule.setBackgroundColor(0x14FFFFFF);
                box.addView(rule, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, px(1))));
            }
            box.addView(row);
        }
        return card(Ui.tr("News"), box, NEWS);
    }

    // MARK: - A Formula 1 session

    private List<View> f1(JSONObject board, JSONObject standings, JSONObject news) {
        List<View> out = new ArrayList<>();
        JSONArray events = board.optJSONArray("events");
        JSONObject race = events == null || events.length() == 0 ? null : events.optJSONObject(0);
        JSONArray sessions = race == null ? null : race.optJSONArray("competitions");
        JSONObject s = sessions == null ? null : sessions.optJSONObject(session);
        if (s == null) return out;
        JSONObject type = s.optJSONObject("status") == null ? null : s.optJSONObject("status").optJSONObject("type");
        String state = type == null ? "pre" : type.optString("state", "pre");
        live = state.equals("in");
        String abbr = s.optJSONObject("type") == null ? "" : s.optJSONObject("type").optString("abbreviation");
        String full = race.optString("name");
        String name = full.replaceFirst("^(MSC Cruises|Aramco|Heineken|Qatar Airways|Lenovo|AWS|Crypto\\.com|Louis Vuitton|Gulf Air|STC|Etihad Airways|Pirelli|Rolex|TAG Heuer|Tag Heuer|Santander|Singapore Airlines|Moët & Chandon|Formula 1)\\s+", "");

        // The band: F1 red, the country's flag, the race and session, the circuit's outline on the right.
        LinearLayout band = new LinearLayout(this);
        band.setGravity(Gravity.CENTER_VERTICAL);
        band.setPadding(px(48), px(36), px(48), px(36));
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[] {0xFF9E0500, 0xFF3A0300, CARD});
        bg.setCornerRadius(px(32));
        band.setBackground(bg);
        LinearLayout words = new LinearLayout(this);
        words.setOrientation(LinearLayout.VERTICAL);
        String flagUrl = Sports.flagUrl(full);
        LinearLayout title = new LinearLayout(this);
        title.setGravity(Gravity.CENTER_VERTICAL);
        if (flagUrl != null) {
            ImageView flag = flagView();
            LinearLayout.LayoutParams fl = new LinearLayout.LayoutParams(px(72), px(48));
            fl.setMarginEnd(px(22));
            title.addView(flag, fl);
            image(flag, flagUrl, 4);
        }
        title.addView(text(name, 56, INK, Ui.displaySemibold));
        words.addView(title);
        String what = abbr.equals("Qual") ? Ui.tr("Qualifying") : abbr.equals("Race") ? Ui.tr("Race") : abbr;
        String when = state.equals("pre") ? start(s.optString("date")) : state.equals("in") ? "●  " + Ui.tr("LIVE") : Ui.tr("Final").toUpperCase(Ui.locale());
        LinearLayout chips = new LinearLayout(this);
        chips.setPadding(0, px(20), 0, 0);
        chips.addView(chip(what, 0x40000000, INK));
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cl.setMarginStart(px(12));
        chips.addView(chip(when, state.equals("in") ? 0xFFFF3B30 : 0x33FFFFFF, INK), cl);
        words.addView(chips);
        band.addView(words, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        String track = Sports.trackUrl(full);
        if (track != null) {
            ImageView map = new ImageView(this);
            map.setScaleType(ImageView.ScaleType.FIT_CENTER);
            band.addView(map, new LinearLayout.LayoutParams(px(420), px(190)));
            image(map, track, 2);
        }
        out.add(band);

        LinearLayout cols = new LinearLayout(this);
        cols.setPadding(0, px(32), 0, 0);
        LinearLayout left = column(), right = column();
        cols.addView(left, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.25f));
        cols.addView(new View(this), new LinearLayout.LayoutParams(px(32), 1));
        cols.addView(right, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        out.add(cols);

        // Drivers' codes (for their teams' colors) come from the standings, which name them the same way.
        java.util.Map<String, Integer> colors = ScoresActivity.teamColors(standings);
        java.util.Map<String, String> codes = new java.util.HashMap<>();
        JSONArray drivers = ScoresActivity.entries(standings.optJSONArray("children"), 0);
        for (int i = 0; drivers != null && i < drivers.length(); i++) {
            JSONObject a = drivers.optJSONObject(i).optJSONObject("athlete");
            if (a != null) codes.put(a.optString("displayName"), a.optString("abbreviation"));
        }
        JSONArray c = s.optJSONArray("competitors");
        if (c != null && c.length() > 0) {
            List<JSONObject> order = new ArrayList<>();
            for (int i = 0; i < c.length(); i++) order.add(c.optJSONObject(i));
            order.sort((x, y) -> Integer.compare(x.optInt("order", 99), y.optInt("order", 99)));
            LinearLayout list = new LinearLayout(this);
            list.setOrientation(LinearLayout.VERTICAL);
            int shown = 0;
            for (JSONObject x : order) {
                JSONObject a = x.optJSONObject("athlete");
                if (a == null) continue;
                int pos = x.optInt("order");
                String code = codes.containsKey(a.optString("displayName")) ? codes.get(a.optString("displayName")) : "";
                LinearLayout row = new LinearLayout(this);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(px(12), px(8), px(16), px(8));
                if (shown++ % 2 == 0) row.setBackground(round(0x0DFFFFFF, 10));
                row.addView(position(pos), new LinearLayout.LayoutParams(px(46), px(46)));
                View stripe = new View(this);
                stripe.setBackground(round(ScoresActivity.teamColor(code, colors), 3));
                LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(px(6), px(34));
                sl.setMargins(px(18), 0, px(14), 0);
                row.addView(stripe, sl);
                ImageView flag = flagView();
                LinearLayout.LayoutParams fl = new LinearLayout.LayoutParams(px(40), px(27));
                fl.setMarginEnd(px(16));
                row.addView(flag, fl);
                JSONObject f = a.optJSONObject("flag");
                image(flag, f == null ? null : f.optString("href"), 8);
                row.addView(text(a.optString("displayName"), 28, INK, pos == 1 ? Ui.semibold : Ui.medium),
                        new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
                row.addView(text(ScoresActivity.seat(code), 22, DIM, Ui.medium));
                list.addView(row);
            }
            String heading = state.equals("pre") && abbr.equals("Race") ? Ui.tr("Starting grid")
                    : state.equals("pre") ? Ui.tr("Entry list") : Ui.tr("Classification");
            left.addView(card(heading, list, F1_RED));
        }
        View n = news(news);
        if (n != null) right.addView(n);
        return out;
    }

    /** A position: gold, silver and bronze discs for the podium, plain after. */
    private View position(int pos) {
        TextView t = text(String.valueOf(pos), 24, pos <= 3 ? 0xFF1C1C1E : DIM, Ui.semibold);
        t.setGravity(Gravity.CENTER);
        if (pos >= 1 && pos <= 3) {
            GradientDrawable d = new GradientDrawable();
            d.setShape(GradientDrawable.OVAL);
            d.setColor(pos == 1 ? 0xFFFFD60A : pos == 2 ? 0xFFD1D1D6 : 0xFFD08C4F);
            t.setBackground(d);
        }
        return t;
    }

    private ImageView flagView() {
        ImageView flag = new ImageView(this);
        flag.setScaleType(ImageView.ScaleType.CENTER_CROP);
        flag.setOutlineProvider(new android.view.ViewOutlineProvider() {
            @Override public void getOutline(View v, android.graphics.Outline o) { o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), px(4)); }
        });
        flag.setClipToOutline(true);
        return flag;
    }

    // MARK: - Pieces

    private LinearLayout column() {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        return col;
    }

    /**
     * A titled card, tinted from the top with its accent: one color, or two (a game's two teams) side by side in the
     * title's marker.
     */
    private View card(String title, View body, int... accent) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(px(32), px(26), px(32), px(30));
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[] {mix(accent[0], CARD, 0.8f), CARD, CARD});
        bg.setCornerRadius(px(24));
        bg.setStroke(Math.max(1, px(1.5f)), 0x14FFFFFF);
        card.setBackground(bg);
        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(0, 0, 0, px(18));
        for (int color : accent) {
            View mark = new View(this);
            mark.setBackground(round(color, 3));
            LinearLayout.LayoutParams ml = new LinearLayout.LayoutParams(px(6), px(24));
            ml.setMarginEnd(px(accent.length > 1 ? 5 : 14));
            head.addView(mark, ml);
        }
        if (accent.length > 1) head.addView(new View(this), new LinearLayout.LayoutParams(px(9), 1));
        TextView t = text(title.toUpperCase(Ui.locale()), 21, 0xE6FFFFFF, Ui.semibold);
        t.setLetterSpacing(0.06f);
        head.addView(t);
        card.addView(head);
        card.addView(body);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, 0, px(28));
        card.setLayoutParams(lp);
        return card;
    }

    private TextView chip(String s, int bg, int fg) {
        TextView t = text(s, 22, fg, Ui.semibold);
        t.setPadding(px(18), px(8), px(18), px(8));
        t.setBackground(round(bg, 40));
        return t;
    }

    private GradientDrawable round(int color, float radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(px(radius));
        return d;
    }

    /** A rounded bar split between two colors at `fraction` (the first's share), with a small gap. */
    private static final class SplitBar extends View {
        private final float fraction;
        private final int a, b;
        private final android.graphics.Paint paint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.RectF r = new android.graphics.RectF();

        SplitBar(Context c, float fraction, int a, int b) {
            super(c);
            this.fraction = Math.max(0, Math.min(1, fraction));
            this.a = a;
            this.b = b;
        }

        @Override protected void onDraw(android.graphics.Canvas c) {
            float w = getWidth(), h = getHeight(), gap = Math.max(2, h * 0.5f);
            float split = Math.max(h, Math.min(w - h, w * fraction));
            paint.setColor(a);
            r.set(0, 0, split - gap / 2, h);
            c.drawRoundRect(r, h / 2, h / 2, paint);
            paint.setColor(b);
            r.set(split + gap / 2, 0, w, h);
            c.drawRoundRect(r, h / 2, h / 2, paint);
        }
    }

    /**
     * A table row: the first cell (a name) wide, the rest narrow and centered. kind 0: line score; 1: players
     * (first cell wider).
     */
    private View tableRow(List<String> cells, boolean head, int kind) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(px(10), px(head ? 2 : 8), px(4), px(8));
        for (int i = 0; i < cells.size(); i++) {
            boolean first = i == 0;
            TextView t = text(cells.get(i), head ? 19 : 25, head ? FAINT : first ? INK : 0xFFD1D1D6,
                    head ? Ui.semibold : first ? Ui.medium : Ui.regular);
            t.setGravity(first ? Gravity.START : Gravity.CENTER);
            if (!first && !head) t.setPadding(0, px(4), 0, px(4));
            float w = first ? (kind == 1 ? 2.6f : 1.4f) : 1f;
            row.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, w));
        }
        return row;
    }

    private void image(ImageView view, String url, int sample) {
        if (url == null || url.isEmpty()) return;
        view.setTag(url);
        io.execute(() -> {
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
                c.setConnectTimeout(8000);
                c.setReadTimeout(15000);
                Bitmap b;
                try (InputStream in = c.getInputStream()) {
                    BitmapFactory.Options o = new BitmapFactory.Options();
                    o.inSampleSize = sample;
                    b = BitmapFactory.decodeStream(in, null, o);
                } finally {
                    c.disconnect();
                }
                if (b != null) main.post(() -> { if (url.equals(view.getTag())) view.setImageBitmap(b); });
            } catch (Exception e) {
                AppLog.w("game image " + url + ": " + e);
            }
        });
    }

    private String start(String iso) {
        long t = 0;
        for (String pattern : new String[] {"yyyy-MM-dd'T'HH:mm:ss'Z'", "yyyy-MM-dd'T'HH:mm'Z'"}) {
            try {
                java.text.SimpleDateFormat f = new java.text.SimpleDateFormat(pattern, Locale.US);
                f.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
                t = f.parse(iso).getTime();
                break;
            } catch (Exception ignored) {
            }
        }
        if (t == 0) return "";
        java.util.Date d = new java.util.Date(t);
        return new java.text.SimpleDateFormat("EEE", Ui.locale()).format(d) + "  " + android.text.format.DateFormat.getTimeFormat(this).format(d);
    }

    private TextView text(CharSequence s, float size, int color, android.graphics.Typeface face) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(color);
        t.setTypeface(face);
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, size * k);
        t.setIncludeFontPadding(false);
        t.setSingleLine();
        t.setEllipsize(TextUtils.TruncateAt.END);
        return t;
    }

    private int px(float design) {
        return Math.round(design * k);
    }
}
