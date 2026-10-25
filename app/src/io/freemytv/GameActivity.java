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
 * the starting grid) and F1's news. ↑ ↓ scroll; live games refresh every 30 s; Back returns to Scores.
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
                    JSONObject news = Sports.get(path + "/news?limit=8");
                    main.post(() -> show(f1(board, news)));
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
        out.add(header(away, home, comp, state, type));

        LinearLayout cols = new LinearLayout(this);
        cols.setPadding(0, px(36), 0, 0);
        LinearLayout left = column(), right = column();
        cols.addView(left, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.25f));
        View gap = new View(this);
        cols.addView(gap, new LinearLayout.LayoutParams(px(48), 1));
        cols.addView(right, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        out.add(cols);

        if (!state.equals("pre")) {
            View line = lineScore(away, home);
            if (line != null) left.addView(card(Ui.tr("Line score"), line));
            players(left, s.optJSONObject("boxscore"));
        }
        if (state.equals("pre")) {
            View odds = odds(s, away, home);
            if (odds != null) right.addView(odds);
        }
        View story = story(s.optJSONObject("article"), state);
        if (story != null) (state.equals("pre") ? right : right).addView(story);
        View stats = teamStats(s.optJSONObject("boxscore"), state);
        if (stats != null) (state.equals("pre") ? left : right).addView(stats);
        View news = news(s.optJSONObject("news"));
        if (news != null) right.addView(news);
        if (left.getChildCount() == 0) {
            cols.removeView(left);
            cols.removeView(gap);
        }
        return out;
    }

    /** Away logo, name and record · the score (or the start) · home; the status and network under it. */
    private View header(JSONObject away, JSONObject home, JSONObject comp, String state, JSONObject type) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(team(away, false), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView mid;
        if (state.equals("pre")) {
            mid = text(start(comp.optString("date")), 44, INK, Ui.displaySemibold);
        } else {
            mid = text(away.optString("score") + "  –  " + home.optString("score"), 84, INK, Ui.displaySemibold);
        }
        mid.setGravity(Gravity.CENTER);
        row.addView(mid, new LinearLayout.LayoutParams(px(460), ViewGroup.LayoutParams.WRAP_CONTENT));
        row.addView(team(home, true), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        box.addView(row);
        String detail = type == null ? "" : state.equals("pre") ? "" : type.optString("detail");
        String note = "";
        JSONArray notes = comp.optJSONArray("notes");
        if (notes != null && notes.length() > 0) note = notes.optJSONObject(0).optString("headline");
        String net = "";
        JSONArray b = comp.optJSONArray("broadcasts");
        if (b != null && b.length() > 0) {
            JSONObject m = b.optJSONObject(0).optJSONObject("media");
            net = m == null ? "" : m.optString("shortName");
        }
        List<String> bits = new ArrayList<>();
        for (String x : new String[] {note, detail, net}) if (!x.isEmpty()) bits.add(x);
        TextView sub = text(TextUtils.join("  ·  ", bits), 26, state.equals("in") ? 0xFFFF453A : DIM, Ui.medium);
        sub.setGravity(Gravity.CENTER);
        sub.setPadding(0, px(14), 0, 0);
        box.addView(sub, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return box;
    }

    private View team(JSONObject c, boolean home) {
        JSONObject t = c.optJSONObject("team");
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL | (home ? Gravity.START : Gravity.END));
        ImageView logo = new ImageView(this);
        logo.setScaleType(ImageView.ScaleType.FIT_CENTER);
        LinearLayout names = new LinearLayout(this);
        names.setOrientation(LinearLayout.VERTICAL);
        names.setGravity(home ? Gravity.START : Gravity.END);
        String name = t == null ? "" : t.optString("displayName");
        int rank = c.optJSONObject("rank") != null ? c.optJSONObject("rank").optInt("current", 99) : c.optInt("rank", 99);
        names.addView(text((rank > 0 && rank <= 25 ? rank + "  " : "") + name, 34, INK, Ui.semibold));
        String record = "";
        JSONArray rec = c.optJSONArray("record");
        if (rec != null && rec.length() > 0) record = rec.optJSONObject(0).optString("displayValue", rec.optJSONObject(0).optString("summary"));
        if (!record.isEmpty()) names.addView(text(record, 24, DIM, Ui.medium));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(px(112), px(112));
        if (home) {
            lp.setMarginEnd(px(24));
            row.addView(logo, lp);
            row.addView(names);
        } else {
            lp.setMarginStart(px(24));
            row.addView(names);
            row.addView(logo, lp);
        }
        String url = null;
        if (t != null) {
            JSONArray logos = t.optJSONArray("logos");
            url = logos != null && logos.length() > 0 ? logos.optJSONObject(0).optString("href") : t.optString("logo", null);
        }
        image(logo, url, 2);
        return row;
    }

    /** Innings, quarters or periods, and the total. */
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
            table.addView(tableRow(cells, false, 0));
        }
        return table;
    }

    /** Each team's players, one table per group (batting and pitching; passing, rushing, receiving…). */
    private void players(LinearLayout into, JSONObject box) {
        JSONArray teams = box == null ? null : box.optJSONArray("players");
        for (int t = 0; teams != null && t < teams.length(); t++) {
            JSONObject team = teams.optJSONObject(t);
            String abbr = team.optJSONObject("team") == null ? "" : team.optJSONObject("team").optString("displayName");
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
                table.addView(tableRow(head, true, 1));
                for (int i = 0; i < rows; i++) {
                    JSONObject a = athletes.optJSONObject(i);
                    JSONArray st = a.optJSONArray("stats");
                    if (st == null || st.length() == 0) continue;
                    List<String> cells = new ArrayList<>();
                    cells.add(a.optJSONObject("athlete") == null ? "" : a.optJSONObject("athlete").optString("shortName"));
                    for (int c = 0; c < cols; c++) cells.add(c < st.length() ? st.optString(c) : "");
                    table.addView(tableRow(cells, false, 1));
                }
                String title = abbr + "  ·  " + title(group.optString("name", group.optString("type")));
                into.addView(card(title, table));
            }
        }
    }

    private String sport() {
        return path == null ? "" : path.substring(0, path.indexOf('/') < 0 ? path.length() : path.indexOf('/'));
    }

    private static String title(String s) {
        return s.isEmpty() ? s : s.substring(0, 1).toUpperCase(Locale.ROOT) + s.substring(1);
    }

    /** The line and the totals (DraftKings via ESPN), and ESPN's win chances. */
    private View odds(JSONObject s, JSONObject away, JSONObject home) {
        JSONArray pc = s.optJSONArray("pickcenter");
        JSONObject p = pc == null || pc.length() == 0 ? null : pc.optJSONObject(0);
        JSONObject pred = s.optJSONObject("predictor");
        if (p == null && pred == null) return null;
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        String a = abbr(away), h = abbr(home);
        if (p != null) {
            if (!p.optString("details").isEmpty()) box.addView(pair(Ui.tr("Line"), p.optString("details")));
            if (p.has("overUnder")) box.addView(pair(Ui.tr("Over/under"), number(p.optDouble("overUnder"))));
            String am = moneyline(p.optJSONObject("awayTeamOdds")), hm = moneyline(p.optJSONObject("homeTeamOdds"));
            if (!am.isEmpty() || !hm.isEmpty()) box.addView(pair(Ui.tr("Moneyline"), a + " " + am + "   " + h + " " + hm));
        }
        if (pred != null) {
            String ap = pct(pred.optJSONObject("awayTeam")), hp = pct(pred.optJSONObject("homeTeam"));
            if (!ap.isEmpty() && !hp.isEmpty()) box.addView(pair(Ui.tr("Win chance (ESPN)"), a + " " + ap + "   " + h + " " + hp));
        }
        if (box.getChildCount() == 0) return null;
        String provider = p == null || p.optJSONObject("provider") == null ? "" : p.optJSONObject("provider").optString("name");
        return card(provider.isEmpty() ? Ui.tr("Odds") : Ui.trf("Odds · {book}", "book", provider), box);
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

    /** The preview (before) or recap (after): its headline and the start of the story. */
    private View story(JSONObject article, String state) {
        if (article == null || article.optString("headline").isEmpty() || state.equals("in")) return null;
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        TextView h = text(article.optString("headline"), 30, INK, Ui.semibold);
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
        return card(state.equals("post") ? Ui.tr("Recap") : Ui.tr("Preview"), box);
    }

    private static String plain(String html) {
        String s = Html.fromHtml(html, Html.FROM_HTML_MODE_COMPACT).toString();
        return s.replaceAll("\\s*\\n\\s*", "\n\n").replaceAll("\\n{3,}", "\n\n").trim();
    }

    /** The two teams side by side: season numbers before the game, the game's after it starts. */
    private View teamStats(JSONObject box, String state) {
        JSONArray teams = box == null ? null : box.optJSONArray("teams");
        if (teams == null || teams.length() < 2) return null;
        JSONArray a = teams.optJSONObject(0).optJSONArray("statistics"), h = teams.optJSONObject(1).optJSONArray("statistics");
        if (a == null || h == null) return null;
        LinearLayout table = new LinearLayout(this);
        table.setOrientation(LinearLayout.VERTICAL);
        List<String> head = new ArrayList<>();
        head.add("");
        head.add(teams.optJSONObject(0).optJSONObject("team").optString("abbreviation"));
        head.add(teams.optJSONObject(1).optJSONObject("team").optString("abbreviation"));
        table.addView(tableRow(head, true, 2));
        int rows = 0;
        for (int i = 0; i < a.length() && i < h.length() && rows < 12; i++) {
            JSONObject x = a.optJSONObject(i), y = h.optJSONObject(i);
            String label = x.optString("label", x.optString("displayName", x.optString("name")));
            if (x.optString("displayValue").isEmpty() || label.isEmpty()) continue;
            List<String> cells = new ArrayList<>();
            cells.add(label);
            cells.add(x.optString("displayValue"));
            cells.add(y.optString("displayValue"));
            table.addView(tableRow(cells, false, 2));
            rows++;
        }
        if (rows == 0) return null;
        return card(state.equals("pre") ? Ui.tr("Season stats") : Ui.tr("Team stats"), table);
    }

    private View news(JSONObject news) {
        JSONArray articles = news == null ? null : news.optJSONArray("articles");
        if (articles == null || articles.length() == 0) return null;
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        for (int i = 0; i < articles.length() && i < 5; i++) {
            JSONObject a = articles.optJSONObject(i);
            TextView h = text(a.optString("headline"), 26, INK, Ui.medium);
            h.setSingleLine(false);
            h.setPadding(0, i == 0 ? 0 : px(18), 0, 0);
            box.addView(h);
            String d = a.optString("description");
            if (!d.isEmpty()) {
                TextView t = text(d, 22, DIM, Ui.regular);
                t.setSingleLine(false);
                t.setMaxLines(2);
                t.setEllipsize(TextUtils.TruncateAt.END);
                t.setPadding(0, px(6), 0, 0);
                box.addView(t);
            }
        }
        return card(Ui.tr("News"), box);
    }

    // MARK: - A Formula 1 session

    private List<View> f1(JSONObject board, JSONObject news) {
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
        String name = race.optString("name").replaceFirst("^(MSC Cruises|Aramco|Heineken|Qatar Airways|Lenovo|AWS|Crypto\\.com|Louis Vuitton|Gulf Air|STC|Etihad Airways|Pirelli|Rolex|TAG Heuer|Tag Heuer|Santander|Singapore Airlines|Moët & Chandon|Formula 1)\\s+", "");
        out.add(text(name, 52, INK, Ui.displaySemibold));
        String what = abbr.equals("Qual") ? Ui.tr("Qualifying") : abbr.equals("Race") ? Ui.tr("Race") : abbr;
        String when = state.equals("pre") ? start(s.optString("date")) : state.equals("in") ? Ui.tr("Live") : Ui.tr("Final");
        TextView sub = text(what + "  ·  " + when, 28, state.equals("in") ? 0xFFFF453A : DIM, Ui.medium);
        sub.setPadding(0, px(10), 0, px(24));
        out.add(sub);

        LinearLayout cols = new LinearLayout(this);
        LinearLayout left = column(), right = column();
        cols.addView(left, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.25f));
        cols.addView(new View(this), new LinearLayout.LayoutParams(px(48), 1));
        cols.addView(right, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        out.add(cols);

        JSONArray c = s.optJSONArray("competitors");
        if (c != null && c.length() > 0) {
            List<JSONObject> order = new ArrayList<>();
            for (int i = 0; i < c.length(); i++) order.add(c.optJSONObject(i));
            order.sort((x, y) -> Integer.compare(x.optInt("order", 99), y.optInt("order", 99)));
            LinearLayout list = new LinearLayout(this);
            list.setOrientation(LinearLayout.VERTICAL);
            for (JSONObject x : order) {
                JSONObject a = x.optJSONObject("athlete");
                if (a == null) continue;
                LinearLayout row = new LinearLayout(this);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(0, px(8), 0, px(8));
                TextView pos = text(String.valueOf(x.optInt("order")), 26, x.optInt("order") <= 3 ? INK : DIM, Ui.semibold);
                pos.setGravity(Gravity.END);
                row.addView(pos, new LinearLayout.LayoutParams(px(50), ViewGroup.LayoutParams.WRAP_CONTENT));
                ImageView flag = new ImageView(this);
                flag.setScaleType(ImageView.ScaleType.CENTER_CROP);
                LinearLayout.LayoutParams fl = new LinearLayout.LayoutParams(px(40), px(27));
                fl.setMargins(px(22), 0, px(16), 0);
                row.addView(flag, fl);
                JSONObject f = a.optJSONObject("flag");
                image(flag, f == null ? null : f.optString("href"), 8);
                row.addView(text(a.optString("displayName"), 28, INK, x.optInt("order") == 1 ? Ui.semibold : Ui.medium));
                list.addView(row);
            }
            String title = state.equals("pre") && abbr.equals("Race") ? Ui.tr("Starting grid")
                    : state.equals("pre") ? Ui.tr("Entry list") : Ui.tr("Classification");
            left.addView(card(title, list));
        }
        View n = news(news);
        if (n != null) right.addView(n);
        return out;
    }

    // MARK: - Pieces

    private LinearLayout column() {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        return col;
    }

    /** A titled card. */
    private View card(String title, View body) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(px(32), px(26), px(32), px(30));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(CARD);
        bg.setCornerRadius(px(24));
        card.setBackground(bg);
        TextView t = text(title.toUpperCase(Ui.locale()), 20, DIM, Ui.semibold);
        t.setPadding(0, 0, 0, px(16));
        card.addView(t);
        card.addView(body);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, 0, px(28));
        card.setLayoutParams(lp);
        return card;
    }

    private View pair(String label, String value) {
        LinearLayout row = new LinearLayout(this);
        row.setPadding(0, px(8), 0, px(8));
        row.addView(text(label, 26, DIM, Ui.medium), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        row.addView(text(value, 26, INK, Ui.semibold));
        return row;
    }

    /**
     * A table row: the first cell (a name) wide, the rest narrow and centered. kind 0: line score; 1: players
     * (first cell wider); 2: two teams compared (label wide, two values).
     */
    private View tableRow(List<String> cells, boolean head, int kind) {
        LinearLayout row = new LinearLayout(this);
        row.setPadding(0, px(head ? 2 : 8), 0, px(head ? 8 : 8));
        for (int i = 0; i < cells.size(); i++) {
            boolean first = i == 0;
            TextView t = text(cells.get(i), head ? 20 : first ? 25 : 25, head ? FAINT : first ? INK : 0xFFD1D1D6,
                    head ? Ui.semibold : first ? Ui.medium : Ui.regular);
            t.setGravity(first ? Gravity.START : Gravity.CENTER);
            float w = first ? (kind == 2 ? 3f : kind == 1 ? 2.6f : 1.4f) : 1f;
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

    private TextView text(String s, float size, int color, android.graphics.Typeface face) {
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
