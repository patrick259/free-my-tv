package io.freemytv;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.LruCache;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Scores: the leagues in season (ESPN's public scoreboards, no key), Formula 1 first while its season runs. A tab per
 * league (← →); for F1 the race weekend's sessions and the driver and constructor standings, for the others today's and
 * tomorrow's games (logos, score, status, network). Live scores refresh every 30 s. Back comes home.
 */
public class ScoresActivity extends Activity {
    /** {tab name, ESPN path}: the order of the tabs (F1 first). */
    private static final String[][] LEAGUES = {
        {"F1", "racing/f1"},
        {"NFL", "football/nfl"},
        {"College Football", "football/college-football"},
        {"MLB", "baseball/mlb"},
        {"NHL", "hockey/nhl"},
    };
    private static final int BG = 0xFF0B0B0F, CARD = 0xFF1C1C21, CARD_FOCUS = 0xFFF5F5F7, INK = 0xFFF5F5F7, DIM = 0xFF98989D;
    private static final long REFRESH = 30_000;

    private final ExecutorService io = Executors.newFixedThreadPool(3);
    private final Handler main = new Handler(Looper.getMainLooper());
    private final LruCache<String, Bitmap> logos = new LruCache<>(80);
    private final List<String[]> inSeason = new ArrayList<>();
    private LinearLayout tabs;
    private FrameLayout body;
    private TextView status;
    private int current = -1;
    private boolean resumed;
    private float k;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Ui.init(this);
        k = getResources().getDisplayMetrics().widthPixels / 1920f;
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        root.setPadding(px(80), px(52), px(80), px(40));
        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = text(Ui.tr("Scores"), 52, INK, Ui.displaySemibold);
        head.addView(title);
        tabs = new LinearLayout(this);
        tabs.setPadding(px(48), 0, 0, 0);
        head.addView(tabs);
        root.addView(head);
        status = text("", 24, DIM, Ui.medium);
        status.setPadding(0, px(14), 0, px(20));
        root.addView(status);
        body = new FrameLayout(this);
        root.addView(body, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        setContentView(root);
        status.setText(Ui.tr("Loading…"));
        io.execute(this::findSeasons);
    }

    @Override protected void onResume() {
        super.onResume();
        resumed = true;
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
            if (current >= 0) load(current, false);
            main.postDelayed(this, REFRESH);
        }
    };

    // MARK: - Leagues in season

    /** A league is in season in its regular season or postseason, inside its calendar; F1 between its first and last race. */
    private void findSeasons() {
        List<String[]> found = new ArrayList<>();
        long now = System.currentTimeMillis();
        for (String[] league : LEAGUES) {
            try {
                JSONObject board = Sports.get(league[1] + "/scoreboard");
                int type = board.optJSONObject("season") == null ? 0 : board.getJSONObject("season").optInt("type");
                JSONObject l = board.optJSONArray("leagues") == null ? null : board.getJSONArray("leagues").optJSONObject(0);
                long start = l == null ? 0 : time(l.optString("calendarStartDate")), end = l == null ? Long.MAX_VALUE : time(l.optString("calendarEndDate"));
                JSONArray calendar = l == null ? null : l.optJSONArray("calendar");
                if (league[0].equals("F1") && calendar != null && calendar.length() > 0) {
                    JSONObject first = calendar.optJSONObject(0), last = calendar.optJSONObject(calendar.length() - 1);
                    if (first != null) start = time(first.optString("startDate")) - 7 * 86_400_000L;
                    if (last != null) end = time(last.optString("endDate")) + 86_400_000L;
                }
                if ((type == 2 || type == 3) && now >= start && now <= end) found.add(league);
            } catch (Exception e) {
                AppLog.w("scores " + league[0] + ": " + e.getMessage());
            }
        }
        main.post(() -> {
            inSeason.clear();
            inSeason.addAll(found);
            buildTabs();
            if (inSeason.isEmpty()) status.setText(Ui.tr("No league in season right now (or no connection)."));
            else select(0);
        });
    }

    private void buildTabs() {
        tabs.removeAllViews();
        for (int i = 0; i < inSeason.size(); i++) {
            final int index = i;
            TextView tab = text(inSeason.get(i)[0], 28, INK, Ui.semibold);
            tab.setPadding(px(26), px(10), px(26), px(10));
            tab.setFocusable(true);
            tab.setOnFocusChangeListener((v, has) -> { if (has) select(index); style(); });
            tab.setOnClickListener(v -> select(index));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMarginEnd(px(10));
            tabs.addView(tab, lp);
        }
        if (tabs.getChildCount() > 0) tabs.getChildAt(0).requestFocus();
    }

    /** The selected tab white (dark text), focused or not; the others plain. */
    private void style() {
        for (int i = 0; i < tabs.getChildCount(); i++) {
            TextView t = (TextView) tabs.getChildAt(i);
            boolean on = i == current, focused = t.isFocused();
            t.setBackground(on ? pill(focused ? CARD_FOCUS : 0x33FFFFFF) : null);
            t.setTextColor(on && focused ? BG : on ? INK : DIM);
        }
    }

    private void select(int index) {
        if (index == current) return;
        current = index;
        style();
        body.removeAllViews();
        status.setText(Ui.tr("Loading…"));
        load(index, true);
    }

    // MARK: - Loading

    private void load(int index, boolean first) {
        final String[] league = inSeason.get(index);
        io.execute(() -> {
            try {
                if (league[0].equals("F1")) {
                    JSONObject board = Sports.get(league[1] + "/scoreboard");
                    JSONObject standings = Sports.get("https://site.api.espn.com/apis/v2/sports/" + league[1] + "/standings");
                    main.post(() -> show(index, f1(board, standings), first));
                } else {
                    List<JSONObject> events = new ArrayList<>();
                    Calendar day = Calendar.getInstance();
                    for (int d = 0; d < 2; d++) {
                        String path = league[1] + "/scoreboard?dates=" + ymd(day);
                        JSONArray e = Sports.get(path).optJSONArray("events");
                        for (int i = 0; e != null && i < e.length(); i++) events.add(e.getJSONObject(i));
                        day.add(Calendar.DAY_OF_YEAR, 1);
                    }
                    if (events.isEmpty()) {   // nothing today or tomorrow: ESPN's own pick (this week's games)
                        JSONArray e = Sports.get(league[1] + "/scoreboard").optJSONArray("events");
                        for (int i = 0; e != null && i < e.length(); i++) events.add(e.getJSONObject(i));
                    }
                    main.post(() -> show(index, games(events), first));
                }
            } catch (Exception e) {
                AppLog.w("scores " + league[0] + ": " + e.getMessage());
                main.post(() -> { if (index == current) status.setText(Ui.tr("Couldn't load the scores. Trying again soon.")); });
            }
        });
    }

    private void show(int index, View view, boolean first) {
        if (index != current || isFinishing()) return;
        // A refresh keeps the scroll position (and the focus, when it was on a card) where it was.
        int scroll = !first && body.getChildCount() > 0 && body.getChildAt(0) instanceof ScrollView ? body.getChildAt(0).getScrollY() : 0;
        body.removeAllViews();
        body.addView(view);
        if (scroll > 0) view.post(() -> view.scrollTo(0, scroll));
        String time = android.text.format.DateFormat.getTimeFormat(this).format(new java.util.Date());
        status.setText(Ui.trf("Updated {time} · ESPN", "time", time));
    }

    // MARK: - Team leagues: game cards

    private View games(List<JSONObject> events) {
        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setClipToPadding(false);
        GridLayout grid = new GridLayout(this);
        grid.setColumnCount(3);
        // Live first, then upcoming by start, then finished.
        events.sort((a, b) -> {
            int sa = order(a), sb = order(b);
            return sa != sb ? Integer.compare(sa, sb) : a.optString("date").compareTo(b.optString("date"));
        });
        int cardW = (getResources().getDisplayMetrics().widthPixels - px(160) - 2 * px(24)) / 3;
        for (JSONObject e : events) {
            View card = card(e);
            if (card == null) continue;
            GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
            lp.width = cardW;
            lp.setMargins(0, 0, px(24), px(24));
            grid.addView(card, lp);
        }
        scroll.addView(grid);
        return scroll;
    }

    private static int order(JSONObject e) {
        String state = e.optJSONObject("status") == null ? "" : e.optJSONObject("status").optJSONObject("type") == null ? ""
                : e.optJSONObject("status").optJSONObject("type").optString("state");
        return state.equals("in") ? 0 : state.equals("pre") ? 1 : 2;
    }

    private View card(JSONObject e) {
        try {
            JSONObject c = e.getJSONArray("competitions").getJSONObject(0);
            JSONArray teams = c.getJSONArray("competitors");
            JSONObject home = null, away = null;
            for (int i = 0; i < teams.length(); i++) {
                JSONObject t = teams.getJSONObject(i);
                if ("home".equals(t.optString("homeAway"))) home = t; else away = t;
            }
            if (home == null || away == null) return null;
            JSONObject type = e.getJSONObject("status").getJSONObject("type");
            String state = type.optString("state");
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding(px(24), px(20), px(24), px(20));
            card.setFocusable(true);
            card.setBackground(round(CARD));
            card.setOnFocusChangeListener((v, has) -> {
                v.setBackground(round(has ? 0xFF2C2C33 : CARD));
                v.animate().scaleX(has ? 1.03f : 1).scaleY(has ? 1.03f : 1).setDuration(150).start();
            });
            boolean pre = state.equals("pre");
            card.addView(teamRow(away, pre, winner(away, home, state)));
            card.addView(teamRow(home, pre, winner(home, away, state)));
            String when = pre ? start(e.optString("date")) : type.optString("shortDetail");
            String net = network(c);
            TextView foot = text(when + (net.isEmpty() ? "" : "  ·  " + net), 22, state.equals("in") ? 0xFFFF453A : DIM, Ui.medium);
            foot.setPadding(0, px(12), 0, 0);
            card.addView(foot);
            String note = c.optJSONArray("notes") != null && c.getJSONArray("notes").length() > 0
                    ? c.getJSONArray("notes").getJSONObject(0).optString("headline") : "";
            if (!note.isEmpty()) card.addView(text(note, 20, DIM, Ui.regular));
            return card;
        } catch (Exception ex) {
            return null;
        }
    }

    private static boolean winner(JSONObject team, JSONObject other, String state) {
        if (!state.equals("post")) return true;
        try {
            return Double.parseDouble(team.optString("score", "0")) >= Double.parseDouble(other.optString("score", "0"));
        } catch (NumberFormatException e) {
            return true;
        }
    }

    private View teamRow(JSONObject competitor, boolean pre, boolean bright) throws org.json.JSONException {
        JSONObject team = competitor.getJSONObject("team");
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, px(6), 0, px(6));
        ImageView logo = new ImageView(this);
        logo.setScaleType(ImageView.ScaleType.FIT_CENTER);
        row.addView(logo, new LinearLayout.LayoutParams(px(48), px(48)));
        logo(logo, team.optString("logo", null));
        int rank = competitor.optJSONObject("curatedRank") == null ? 99 : competitor.getJSONObject("curatedRank").optInt("current", 99);
        String name = (rank <= 25 ? rank + "  " : "") + team.optString("shortDisplayName", team.optString("displayName"));
        TextView n = text(name, 28, bright ? INK : DIM, Ui.semibold);
        n.setSingleLine();
        n.setEllipsize(TextUtils.TruncateAt.END);
        n.setPadding(px(16), 0, px(8), 0);
        row.addView(n, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        String right = pre ? record(competitor) : competitor.optString("score");
        row.addView(text(right, pre ? 22 : 32, pre ? DIM : bright ? INK : DIM, pre ? Ui.medium : Ui.semibold));
        return row;
    }

    private static String record(JSONObject competitor) {
        JSONArray records = competitor.optJSONArray("records");
        return records == null || records.length() == 0 ? "" : records.optJSONObject(0).optString("summary");
    }

    private static String network(JSONObject c) {
        JSONArray b = c.optJSONArray("broadcasts");
        List<String> names = new ArrayList<>();
        for (int i = 0; b != null && i < b.length(); i++) {
            JSONArray n = b.optJSONObject(i).optJSONArray("names");
            for (int j = 0; n != null && j < n.length(); j++) if (!names.contains(n.optString(j))) names.add(n.optString(j));
        }
        return TextUtils.join(", ", names);
    }

    // MARK: - Formula 1: the weekend and the standings

    private View f1(JSONObject board, JSONObject standings) {
        LinearLayout cols = new LinearLayout(this);
        // Left: this race weekend's sessions (times; the winner of finished ones when ESPN has the order).
        LinearLayout weekend = column();
        JSONArray events = board.optJSONArray("events");
        JSONObject race = events == null || events.length() == 0 ? null : events.optJSONObject(0);
        if (race != null) {
            weekend.addView(text(race.optString("name").replaceFirst("^(MSC Cruises|Aramco|Heineken|Qatar Airways|Lenovo|AWS|Crypto\\.com|Louis Vuitton|Gulf Air|STC|Etihad Airways|Pirelli|Rolex|TAG Heuer|Santander|Formula 1)\\s+", ""), 34, INK, Ui.displaySemibold));
            JSONObject circuit = race.optJSONObject("circuit");
            if (circuit != null) weekend.addView(text(circuit.optString("fullName"), 24, DIM, Ui.medium));
            JSONArray sessions = race.optJSONArray("competitions");
            for (int i = 0; sessions != null && i < sessions.length(); i++) {
                JSONObject s = sessions.optJSONObject(i);
                String abbr = s.optJSONObject("type") == null ? "" : s.optJSONObject("type").optString("abbreviation");
                String name = abbr.equals("Qual") ? Ui.tr("Qualifying") : abbr.equals("Race") ? Ui.tr("Race") : abbr;
                JSONObject st = s.optJSONObject("status") == null ? null : s.optJSONObject("status").optJSONObject("type");
                String state = st == null ? "pre" : st.optString("state");
                String detail = state.equals("pre") ? start(s.optString("date")) : state.equals("in") ? Ui.tr("Live") : leader(s);
                weekend.addView(row(name, detail, state.equals("in") ? 0xFFFF453A : DIM));
            }
        }
        cols.addView(weekend, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1));
        // Middle and right: drivers (top 12) and constructors.
        JSONArray groups = standings.optJSONArray("children");
        for (int g = 0; groups != null && g < groups.length() && g < 2; g++) {
            JSONObject group = groups.optJSONObject(g);
            LinearLayout col = column();
            col.addView(text(g == 0 ? Ui.tr("Drivers") : Ui.tr("Constructors"), 34, INK, Ui.displaySemibold));
            JSONArray entries = group.optJSONObject("standings") == null ? null : group.optJSONObject("standings").optJSONArray("entries");
            for (int i = 0; entries != null && i < entries.length() && i < (g == 0 ? 12 : 10); i++) {
                JSONObject entry = entries.optJSONObject(i);
                String who = entry.optJSONObject("athlete") != null ? entry.optJSONObject("athlete").optString("displayName")
                        : entry.optJSONObject("team") != null ? entry.optJSONObject("team").optString("displayName") : "";
                String rank = stat(entry, "rank"), pts = stat(entry, "championshipPts");
                if (pts.isEmpty()) pts = stat(entry, "points");     // constructors
                col.addView(row(rank + "   " + who, pts.isEmpty() ? "" : pts + " " + Ui.tr("pts"), DIM));
            }
            cols.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1));
        }
        return cols;
    }

    private static String leader(JSONObject session) {
        JSONArray c = session.optJSONArray("competitors");
        if (c == null || c.length() == 0) return Ui.tr("Final");
        JSONObject first = null;
        for (int i = 0; i < c.length(); i++) if (c.optJSONObject(i).optInt("order", 99) == 1) first = c.optJSONObject(i);
        if (first == null) first = c.optJSONObject(0);
        JSONObject athlete = first.optJSONObject("athlete");
        return athlete == null ? Ui.tr("Final") : "P1 " + athlete.optString("shortName", athlete.optString("displayName"));
    }

    private static String stat(JSONObject entry, String name) {
        JSONArray stats = entry.optJSONArray("stats");
        for (int i = 0; stats != null && i < stats.length(); i++) {
            if (name.equals(stats.optJSONObject(i).optString("name"))) return stats.optJSONObject(i).optString("displayValue");
        }
        return "";
    }

    /** A focusable column (so ↓ scrolls through it with the remote). */
    private LinearLayout column() {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(0, 0, px(48), 0);
        return col;
    }

    private View row(String left, String right, int rightColor) {
        LinearLayout row = new LinearLayout(this);
        row.setPadding(px(4), px(12), px(4), px(12));
        row.setFocusable(true);
        row.setOnFocusChangeListener((v, has) -> v.setBackground(has ? round(0xFF2C2C33) : null));
        TextView l = text(left, 26, INK, Ui.medium);
        l.setSingleLine();
        l.setEllipsize(TextUtils.TruncateAt.END);
        row.addView(l, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        row.addView(text(right, 24, rightColor, Ui.medium));
        return row;
    }

    // MARK: - Helpers

    private void logo(ImageView view, String url) {
        if (url == null) return;
        Bitmap cached = logos.get(url);
        if (cached != null) {
            view.setImageBitmap(cached);
            return;
        }
        view.setTag(url);
        io.execute(() -> {
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
                c.setConnectTimeout(8000);
                c.setReadTimeout(15000);
                Bitmap b;
                try (InputStream in = c.getInputStream()) {
                    BitmapFactory.Options o = new BitmapFactory.Options();
                    o.inSampleSize = 4;     // ESPN's 500 px logos, shown at about 48
                    b = BitmapFactory.decodeStream(in, null, o);
                } finally {
                    c.disconnect();
                }
                if (b == null) return;
                logos.put(url, b);
                main.post(() -> { if (url.equals(view.getTag())) view.setImageBitmap(b); });
            } catch (Exception ignored) {
            }
        });
    }

    private String start(String iso) {
        long t = time(iso);
        Calendar start = Calendar.getInstance(), now = Calendar.getInstance();
        start.setTimeInMillis(t);
        String time = android.text.format.DateFormat.getTimeFormat(this).format(start.getTime());
        int days = start.get(Calendar.DAY_OF_YEAR) - now.get(Calendar.DAY_OF_YEAR) + 365 * (start.get(Calendar.YEAR) - now.get(Calendar.YEAR));
        if (days == 0) return Ui.tr("Today") + " " + time;
        if (days == 1) return Ui.tr("Tomorrow") + " " + time;
        return new SimpleDateFormat("EEE", Ui.locale()).format(start.getTime()) + " " + time;
    }

    private static long time(String iso) {
        if (iso == null || iso.isEmpty()) return 0;
        for (String pattern : new String[] {"yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", "yyyy-MM-dd'T'HH:mm:ss'Z'", "yyyy-MM-dd'T'HH:mm'Z'"}) {
            try {
                SimpleDateFormat f = new SimpleDateFormat(pattern, Locale.US);
                f.setTimeZone(TimeZone.getTimeZone("UTC"));
                return f.parse(iso).getTime();
            } catch (Exception ignored) {
            }
        }
        return 0;
    }

    private static String ymd(Calendar c) {
        return String.format(Locale.US, "%04d%02d%02d", c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH));
    }

    private TextView text(String s, float size, int color, android.graphics.Typeface face) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(color);
        t.setTypeface(face);
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, size * k);
        t.setIncludeFontPadding(false);
        return t;
    }

    private GradientDrawable round(int color) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(px(18));
        return d;
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

    @Override public boolean onKeyDown(int keyCode, KeyEvent event) {
        // From the cards, ↑ past the first row goes back to the tabs (on the selected one).
        if (keyCode == KeyEvent.KEYCODE_DPAD_UP && getCurrentFocus() != null && getCurrentFocus().getParent() != tabs
                && getCurrentFocus().focusSearch(View.FOCUS_UP) == null && current >= 0 && current < tabs.getChildCount()) {
            tabs.getChildAt(current).requestFocus();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }
}
