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
 * league (← →); for F1 the race weekend (circuit map, countdown, sessions and winners), the drivers with their team
 * colors and flags, and the constructors as bars; for the others today's and
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

    /** 2026 seats (ESPN's driver codes): a driver's team color in the standings, which list drivers without teams. */
    private static final String[][] SEATS = {
        {"Mercedes", "RUS", "ANT"}, {"Ferrari", "LEC", "HAM"}, {"McLaren", "NOR", "PIA"}, {"Red Bull", "VER", "HAD"},
        {"Racing Bulls", "LAW", "LIN"}, {"Aston Martin", "ALO", "STR"}, {"Williams", "ALB", "SAI"},
        {"Alpine", "GAS", "COL"}, {"Haas", "OCO", "BEA"}, {"Audi", "HUL", "BOR"}, {"Cadillac", "PER", "BOT"},
    };
    /** Team colors where ESPN's don't read on a dark screen (Williams white, Haas and Red Bull too dark). */
    private static final String[][] TEAM_COLORS = {
        {"Williams", "64C4FF"}, {"Haas", "B6BABD"}, {"Red Bull", "3671C6"}, {"Aston Martin", "229971"},
    };
    private static final int F1_RED = 0xFFE10600;

    private View f1(JSONObject board, JSONObject standings) {
        // Team colors by name (ESPN's constructors, with our overrides).
        java.util.Map<String, Integer> colors = new java.util.HashMap<>();
        JSONArray groups = standings.optJSONArray("children");
        JSONArray drivers = entries(groups, 0), teams = entries(groups, 1);
        for (int i = 0; teams != null && i < teams.length(); i++) {
            JSONObject t = teams.optJSONObject(i).optJSONObject("team");
            if (t == null) continue;
            try {
                colors.put(t.optString("displayName"), 0xFF000000 | Integer.parseInt(t.optString("color", "888888"), 16));
            } catch (NumberFormatException ignored) {
            }
        }
        for (String[] c : TEAM_COLORS) colors.put(c[0], 0xFF000000 | Integer.parseInt(c[1], 16));

        LinearLayout cols = new LinearLayout(this);
        JSONArray events = board.optJSONArray("events");
        JSONObject race = events == null || events.length() == 0 ? null : events.optJSONObject(0);
        if (race != null) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(px(600), ViewGroup.LayoutParams.MATCH_PARENT);
            lp.setMarginEnd(px(48));
            cols.addView(weekend(board, race), lp);
        }
        LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.1f);
        dl.setMarginEnd(px(48));
        cols.addView(drivers(drivers, colors), dl);
        cols.addView(constructors(teams, colors), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1));
        return cols;
    }

    private static JSONArray entries(JSONArray groups, int g) {
        JSONObject group = groups == null ? null : groups.optJSONObject(g);
        return group == null || group.optJSONObject("standings") == null ? null
                : group.optJSONObject("standings").optJSONArray("entries");
    }

    /** The race weekend: round, circuit map, name, a countdown to the next session, and every session in order. */
    private View weekend(JSONObject board, JSONObject race) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(px(36), px(32), px(36), px(32));
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[] {0xFF9E0500, 0xFF3A0300, CARD});
        bg.setCornerRadius(px(28));
        card.setBackground(bg);
        String name = race.optString("name");
        JSONObject circuit = race.optJSONObject("circuit");
        String city = circuit == null || circuit.optJSONObject("address") == null ? "" : circuit.optJSONObject("address").optString("city");
        int round = round(board, name);
        String over = (round > 0 ? Ui.trf("Round {n}", "n", String.valueOf(round)) : "Formula 1") + (city.isEmpty() ? "" : "  ·  " + city);
        card.addView(text(over.toUpperCase(Ui.locale()), 20, 0xCCFFFFFF, Ui.semibold));

        String track = Sports.trackUrl(name);
        if (track != null) {
            ImageView map = new ImageView(this);
            map.setScaleType(ImageView.ScaleType.FIT_CENTER);
            map.setAlpha(0.95f);
            LinearLayout.LayoutParams ml = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(200));
            ml.setMargins(0, px(24), 0, px(20));
            card.addView(map, ml);
            image(map, track, 2);
        }
        TextView title = text(name.replaceFirst("^(MSC Cruises|Aramco|Heineken|Qatar Airways|Lenovo|AWS|Crypto\\.com|Louis Vuitton|Gulf Air|STC|Etihad Airways|Pirelli|Rolex|TAG Heuer|Tag Heuer|Santander|Singapore Airlines|Moët & Chandon|Formula 1)\\s+", ""), 40, INK, Ui.displaySemibold);
        title.setPadding(0, track == null ? px(20) : 0, 0, px(6));
        card.addView(title);
        if (circuit != null) card.addView(text(circuit.optString("fullName"), 22, 0xB3FFFFFF, Ui.medium));

        JSONArray sessions = race.optJSONArray("competitions");
        JSONObject next = null;
        for (int i = 0; sessions != null && i < sessions.length(); i++) {
            JSONObject s = sessions.optJSONObject(i);
            String st = sessionState(s);
            if (st.equals("in") || st.equals("pre")) { next = s; break; }
        }
        if (next != null) {
            boolean live = sessionState(next).equals("in");
            LinearLayout box = new LinearLayout(this);
            box.setOrientation(LinearLayout.VERTICAL);
            box.setPadding(px(24), px(18), px(24), px(18));
            box.setBackground(round(0x33000000));
            box.addView(text((sessionName(next) + (live ? "" : "  ·  " + start(next.optString("date")))).toUpperCase(Ui.locale()), 20, 0xCCFFFFFF, Ui.semibold));
            TextView big = text(live ? Ui.tr("Live now") : countdown(time(next.optString("date"))), 46, live ? 0xFFFF453A : INK, Ui.displaySemibold);
            big.setPadding(0, px(8), 0, 0);
            box.addView(big);
            LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            bl.setMargins(0, px(24), 0, px(16));
            card.addView(box, bl);
        }
        for (int i = 0; sessions != null && i < sessions.length(); i++) card.addView(session(sessions.optJSONObject(i), sessions.optJSONObject(i) == next));
        return card;
    }

    /** One session: a dot (done, live, next, later), its name, and its winner with flag or its start. */
    private View session(JSONObject s, boolean isNext) {
        String state = sessionState(s);
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(px(4), px(9), px(4), px(9));
        row.setFocusable(true);
        row.setOnFocusChangeListener((v, has) -> v.setBackground(has ? round(0x33FFFFFF) : null));
        View dot = new View(this);
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        if (state.equals("post")) d.setColor(INK);
        else if (state.equals("in")) d.setColor(0xFFFF453A);
        else d.setStroke(px(3), isNext ? INK : 0x80FFFFFF);
        dot.setBackground(d);
        LinearLayout.LayoutParams dp = new LinearLayout.LayoutParams(px(16), px(16));
        dp.setMarginEnd(px(18));
        row.addView(dot, dp);
        row.addView(text(sessionName(s), 26, state.equals("pre") && !isNext ? 0xB3FFFFFF : INK, Ui.semibold),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        JSONObject winner = state.equals("post") ? winner(s) : null;
        if (winner != null) {
            JSONObject a = winner.optJSONObject("athlete");
            JSONObject flag = a == null ? null : a.optJSONObject("flag");
            if (flag != null) row.addView(flag(flag.optString("href")));
            String who = a == null ? "" : a.optString("shortName", a.optString("displayName"));
            row.addView(text(who, 24, INK, Ui.medium));
        } else {
            row.addView(text(state.equals("in") ? Ui.tr("Live") : state.equals("post") ? Ui.tr("Final") : start(s.optString("date")),
                    24, state.equals("in") ? 0xFFFF453A : 0xB3FFFFFF, Ui.medium));
        }
        return row;
    }

    private View drivers(JSONArray entries, java.util.Map<String, Integer> colors) {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.addView(heading(Ui.tr("Drivers")));
        double leader = 0;
        for (int i = 0; entries != null && i < entries.length() && i < 12; i++) {
            JSONObject entry = entries.optJSONObject(i);
            JSONObject a = entry.optJSONObject("athlete");
            if (a == null) continue;
            double pts = number(entry, "championshipPts");
            if (i == 0) leader = pts;
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(px(16), px(10), px(20), px(10));
            row.setFocusable(true);
            final int bg = i == 0 ? 0xFF26262C : 0;
            row.setBackground(bg == 0 ? null : round(bg));
            row.setOnFocusChangeListener((v, has) -> v.setBackground(has ? round(0xFF34343C) : bg == 0 ? null : round(bg)));
            TextView rank = text(stat(entry, "rank"), 24, DIM, Ui.semibold);
            rank.setGravity(Gravity.END);
            row.addView(rank, new LinearLayout.LayoutParams(px(40), ViewGroup.LayoutParams.WRAP_CONTENT));
            View stripe = new View(this);
            GradientDrawable sd = new GradientDrawable();
            sd.setColor(teamColor(a.optString("abbreviation"), colors));
            sd.setCornerRadius(px(3));
            stripe.setBackground(sd);
            LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(px(6), px(34));
            sl.setMargins(px(18), 0, px(14), 0);
            row.addView(stripe, sl);
            JSONObject flag = a.optJSONObject("flag");
            if (flag != null) row.addView(flag(flag.optString("href")));
            TextView n = text(a.optString("displayName"), 27, INK, i == 0 ? Ui.semibold : Ui.medium);
            n.setSingleLine();
            n.setEllipsize(TextUtils.TruncateAt.END);
            row.addView(n, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            if (i > 0 && leader > pts) {
                TextView gap = text("−" + Math.round(leader - pts), 20, DIM, Ui.medium);
                gap.setPadding(px(12), 0, px(16), 0);
                row.addView(gap);
            }
            row.addView(text(String.valueOf(Math.round(pts)), 27, INK, Ui.semibold));
            col.addView(row);
        }
        return col;
    }

    /** Constructors as bars: each team's points against the leader's, in its color. */
    private View constructors(JSONArray entries, java.util.Map<String, Integer> colors) {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.addView(heading(Ui.tr("Constructors")));
        double max = 0;
        for (int i = 0; entries != null && i < entries.length(); i++) max = Math.max(max, number(entries.optJSONObject(i), "points"));
        for (int i = 0; entries != null && i < entries.length() && i < 11; i++) {
            JSONObject entry = entries.optJSONObject(i);
            JSONObject t = entry.optJSONObject("team");
            if (t == null) continue;
            String team = t.optString("displayName");
            double pts = number(entry, "points");
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding(px(16), px(8), px(16), px(8));
            row.setFocusable(true);
            row.setOnFocusChangeListener((v, has) -> v.setBackground(has ? round(0xFF34343C) : null));
            LinearLayout line = new LinearLayout(this);
            TextView n = text(team, 24, INK, Ui.medium);
            line.addView(n, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            line.addView(text(String.valueOf(Math.round(pts)), 24, INK, Ui.semibold));
            row.addView(line);
            Integer color = colors.get(team);
            Bar bar = new Bar(this, max > 0 ? (float) (pts / max) : 0, color == null ? DIM : color);
            LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(10));
            bp.setMargins(0, px(8), 0, 0);
            row.addView(bar, bp);
            col.addView(row);
        }
        return col;
    }

    /** A rounded bar `fraction` of the width (at least a sliver), on a faint track. */
    private static final class Bar extends View {
        private final float fraction;
        private final android.graphics.Paint paint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final int color;

        Bar(android.content.Context c, float fraction, int color) {
            super(c);
            this.fraction = fraction;
            this.color = color;
        }

        @Override protected void onDraw(android.graphics.Canvas c) {
            float h = getHeight(), r = h / 2;
            paint.setColor(0x1FFFFFFF);
            c.drawRoundRect(0, 0, getWidth(), h, r, r, paint);
            paint.setColor(color);
            c.drawRoundRect(0, 0, Math.max(h, getWidth() * fraction), h, r, r, paint);
        }
    }

    private TextView heading(String s) {
        TextView t = text(s, 34, INK, Ui.displaySemibold);
        t.setPadding(px(16), 0, 0, px(16));
        return t;
    }

    private ImageView flag(String url) {
        ImageView f = new ImageView(this);
        f.setScaleType(ImageView.ScaleType.CENTER_CROP);
        f.setClipToOutline(true);
        f.setOutlineProvider(new android.view.ViewOutlineProvider() {
            @Override public void getOutline(View v, android.graphics.Outline o) { o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), px(4)); }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(px(38), px(26));
        lp.setMarginEnd(px(14));
        f.setLayoutParams(lp);
        image(f, url, 8);
        return f;
    }

    private static int teamColor(String driver, java.util.Map<String, Integer> colors) {
        for (String[] seat : SEATS) {
            if (seat[1].equals(driver) || seat[2].equals(driver)) {
                Integer c = colors.get(seat[0]);
                return c == null ? DIM : c;
            }
        }
        return 0xFF48484A;
    }

    /** This race's round in the season (ESPN's calendar), or 0. */
    private static int round(JSONObject board, String name) {
        JSONArray leagues = board.optJSONArray("leagues");
        JSONArray cal = leagues == null || leagues.length() == 0 ? null : leagues.optJSONObject(0).optJSONArray("calendar");
        for (int i = 0; cal != null && i < cal.length(); i++) if (name.equals(cal.optJSONObject(i).optString("label"))) return i + 1;
        return 0;
    }

    private static String sessionState(JSONObject s) {
        JSONObject st = s.optJSONObject("status") == null ? null : s.optJSONObject("status").optJSONObject("type");
        return st == null ? "pre" : st.optString("state", "pre");
    }

    private static String sessionName(JSONObject s) {
        String abbr = s.optJSONObject("type") == null ? "" : s.optJSONObject("type").optString("abbreviation");
        switch (abbr) {
            case "FP1": return Ui.tr("Practice 1");
            case "FP2": return Ui.tr("Practice 2");
            case "FP3": return Ui.tr("Practice 3");
            case "Qual": return Ui.tr("Qualifying");
            case "Race": return Ui.tr("Race");
            default: return abbr.toLowerCase(Locale.ROOT).startsWith("sprint") || abbr.equals("SR") ? Ui.tr("Sprint")
                    : abbr.equals("SQ") || abbr.equals("SS") ? Ui.tr("Sprint Qualifying") : abbr;
        }
    }

    /** The session's P1 (ESPN's order 1), or null. */
    private static JSONObject winner(JSONObject session) {
        JSONArray c = session.optJSONArray("competitors");
        for (int i = 0; c != null && i < c.length(); i++) if (c.optJSONObject(i).optInt("order", 99) == 1) return c.optJSONObject(i);
        return null;
    }

    /** "in 5 h 12 min", "in 2 days". */
    private static String countdown(long at) {
        long m = Math.max(0, (at - System.currentTimeMillis()) / 60_000);
        if (m >= 48 * 60) return Ui.trf("in {n} days", "n", String.valueOf(Math.round(m / 1440.0)));
        if (m >= 60) return Ui.trf("in {h} h {m} min", "h", String.valueOf(m / 60), "m", String.valueOf(m % 60));
        return Ui.trf("in {m} min", "m", String.valueOf(m));
    }

    private static double number(JSONObject entry, String name) {
        JSONArray stats = entry == null ? null : entry.optJSONArray("stats");
        for (int i = 0; stats != null && i < stats.length(); i++) {
            if (name.equals(stats.optJSONObject(i).optString("name"))) return stats.optJSONObject(i).optDouble("value", 0);
        }
        return 0;
    }

    private static String stat(JSONObject entry, String name) {
        JSONArray stats = entry.optJSONArray("stats");
        for (int i = 0; stats != null && i < stats.length(); i++) {
            if (name.equals(stats.optJSONObject(i).optString("name"))) return stats.optJSONObject(i).optString("displayValue");
        }
        return "";
    }

    // MARK: - Helpers

    private void logo(ImageView view, String url) {
        image(view, url, 4);     // ESPN's 500 px logos, shown at about 48
    }

    private void image(ImageView view, String url, int sample) {
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
                    o.inSampleSize = sample;
                    b = BitmapFactory.decodeStream(in, null, o);
                } finally {
                    c.disconnect();
                }
                if (b == null) {
                    AppLog.w("scores image undecodable: " + url);
                    return;
                }
                logos.put(url, b);
                main.post(() -> { if (url.equals(view.getTag())) view.setImageBitmap(b); });
            } catch (Exception e) {
                AppLog.w("scores image " + url + ": " + e);
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
