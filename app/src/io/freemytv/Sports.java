package io.freemytv;

import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Big games for the featured hero, most important first, from ESPN's public scoreboards (no key): Formula 1's
 * qualifying, sprint and race, then the postseason (the World Series, NFL playoffs, the College Football Playoff),
 * Monday Night Football, your teams' games (favorite_teams) and college games between two ranked teams; ordinary
 * games stay out, and so does anything no app on the TV carries. Each shows on its day until it ends (the postseason
 * and F1 from the day before); Watch
 * opens the app that carries it (a TV network → YouTube TV).
 * The hero's picture is drawn here: the two teams' colors and logos (the league's logo for F1).
 */
final class Sports {
    private static final String API = "https://site.api.espn.com/apis/site/v2/sports/";
    private static final long EVERY = 10 * 60_000L, AHEAD = 24 * 3600_000L, MAX_LIVE = 5 * 3600_000L;
    private static final int MAX_NFL = 2, MAX_COLLEGE = 2;
    /** Title sponsors in front of F1 race names ("MSC Cruises United States Grand Prix"); others keep their full name. */
    private static final String F1_SPONSORS = "^(MSC Cruises|Aramco|Heineken|Qatar Airways|Lenovo|AWS|Crypto\\.com|Louis Vuitton"
            + "|Gulf Air|STC|Etihad Airways|Pirelli|Rolex|TAG Heuer|Santander|Formula 1|FORMULA 1)\\s+";

    /**
     * F1's own circuit outlines (white on transparent, media.formula1.com), by the words in ESPN's race name. Checked
     * in this order: "Bahrain Grand Prix in Malaysia" and "Qatar Airways Australian" before plain Bahrain and Qatar.
     */
    private static final String[][] TRACKS = {
        {"in Malaysia", "sepang", "mas"}, {"Australian", "melbourne", "aus"}, {"Chinese", "shanghai", "chn"},
        {"Japanese", "suzuka", "jpn"}, {"Bahrain", "sakhir", "bhr"}, {"Saudi", "jeddah", "ksa"}, {"Miami", "miami", "usa"},
        {"Canadian", "montreal", "can"}, {"Monaco", "montecarlo", "mon"}, {"Barcelona", "catalunya", "esp"},
        {"Austrian", "spielberg", "aut"}, {"British", "silverstone", "gbr"}, {"Belgian", "spafrancorchamps", "bel"},
        {"Hungarian", "hungaroring", "hun"}, {"Dutch", "zandvoort", "ned"}, {"Italian", "monza", "ita"},
        {"Emilia", "imola", "ita"}, {"Spanish", "madring", "esp"}, {"Azerbaijan", "baku", "aze"},
        {"Singapore", "singapore", "sgp"}, {"United States", "austin", "usa"}, {"Mexico", "mexicocity", "mex"},
        {"São Paulo", "interlagos", "bra"}, {"Sao Paulo", "interlagos", "bra"}, {"Las Vegas", "lasvegas", "usa"},
        {"Qatar Grand Prix", "lusail", "qat"}, {"Abu Dhabi", "yasmarinacircuit", "uae"},
    };

    /** The flag of the race's country (ESPN's flags), or null. */
    static String flagUrl(String race) {
        for (String[] t : TRACKS) {
            if (race.contains(t[0])) return "https://a.espncdn.com/i/teamlogos/countries/500/" + t[2] + ".png";
        }
        return null;
    }

    /** The circuit's outline for this race ("MSC Cruises United States Grand Prix"), or null if we don't know it. */
    static String trackUrl(String race) {
        for (String[] t : TRACKS) {
            if (!race.contains(t[0])) continue;
            int year = Calendar.getInstance().get(Calendar.YEAR);
            return "https://media.formula1.com/image/upload/f_png,c_fit,h_704/q_auto/common/f1/" + year + "/track/"
                    + year + "track" + t[1] + "whiteoutline.png";
        }
        return null;
    }

    /** Apple TV's Formula 1 channel (where apple.co/f1onappletv leads): Watch opens it rather than the app's home. */
    private static final String F1_ON_APPLE_TV = "https://tv.apple.com/us/channel/formula-1/tvs.sbd.241000";

    /** Network (ESPN's name, lowercase) → app on the TV (key for Recs' service names, package). First installed wins. */
    private static final String[][] NETWORKS = {
        {"prime video", "prime", "com.amazon.firebat"},
        {"prime video", "prime", "com.amazon.amazonvideo.livingroom"},
        {"apple tv", "appletv", "com.apple.atve.amazon.appletv"},
        {"apple tv", "appletv", "com.apple.atve.androidtv.appletv"},
        {"netflix", "netflix", "com.netflix.ninja"},
        {"peacock", "peacock", "com.peacock.peacockfiretv"},
        {"peacock", "peacock", "com.peacocktv.peacockandroid"},
        {"paramount+", "paramount", "com.cbs.ott"},
    };
    /** Broadcast and cable networks YouTube TV carries. */
    private static final String[] LIVE_TV = {"fox", "cbs", "nbc", "abc", "espn", "espn2", "espnu", "fs1", "fs2", "tbs",
            "tnt", "trutv", "nfl net", "nfl network", "mlb net", "mlb network", "sec network", "secn", "acc network",
            "accn", "big ten network", "btn", "cw", "usa net", "usa network",
            // Roseville's regional sports networks (YouTube TV carries both there)
            "nbcs-ca", "nbc sports california", "nbcs california", "nbcs-ba", "nbc sports ba", "nbc sports bay area"};
    private static final String[] YOUTUBE_TV = {"com.amazon.firetv.youtube.tv", "com.google.android.youtube.tvunplugged"};

    /** One game (or F1 session) as the hero needs it. */
    static final class Game {
        String id, league, away, home, awayLogo, homeLogo, leagueLogo, place;
        String track, flag;     // F1: the circuit's outline, the country's flag
        String photo;           // F1 without a known circuit: a photo from this race weekend (ESPN's news)
        int awayColor, homeColor;
        int awayAlt, homeAlt;   // the teams' second colors: the background when a logo would vanish in the first
        long start;
        int weight;             // importance in the hero: the postseason, Monday Night Football, two ranked teams
        String state, detail;   // "pre", "in", "post"; ESPN's "Top 5th", "Q3 4:12"
    }

    private final Context context;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable changed;
    private volatile List<Recs.Pick> picks = new ArrayList<>();
    private volatile long fetchedAt;
    private volatile boolean fetching;

    /** Logos, circuits and flags, kept on the TV once downloaded (they don't change during a season). */
    private static volatile java.io.File images;

    Sports(Context c, Runnable changed) {
        context = c.getApplicationContext();
        images = new java.io.File(context.getCacheDir(), "sports");
        this.changed = changed;
    }

    private static boolean sameDay(long a, long b) {
        Calendar x = Calendar.getInstance(), y = Calendar.getInstance();
        x.setTimeInMillis(a);
        y.setTimeInMillis(b);
        return x.get(Calendar.YEAR) == y.get(Calendar.YEAR) && x.get(Calendar.DAY_OF_YEAR) == y.get(Calendar.DAY_OF_YEAR);
    }

    /** The games to show now (finished ones and ones too far ahead left out), live first, then by start. */
    List<Recs.Pick> now() {
        if (!Config.SHOW_SPORTS) return new ArrayList<>();
        long t = System.currentTimeMillis();
        List<Recs.Pick> out = new ArrayList<>();
        for (Recs.Pick p : picks) {
            Game g = p.game;
            boolean live = "in".equals(g.state) || (g.start <= t && t - g.start < MAX_LIVE && !"post".equals(g.state));
            // Coming up: today's games only, except the postseason and Formula 1 (its weekend), up to a day ahead.
            boolean soon = "pre".equals(g.state) && g.start - t < AHEAD
                    && ("F1".equals(g.league) || g.weight >= POSTSEASON || sameDay(g.start, t));
            if (live || soon) out.add(p);
        }
        Collections.sort(out, (a, b) -> {
            boolean fa = "F1".equals(a.game.league), fb = "F1".equals(b.game.league);
            if (fa != fb) return fa ? -1 : 1;       // a Formula 1 session always leads, then the biggest games
            if (a.game.weight != b.game.weight) return Integer.compare(b.game.weight, a.game.weight);
            boolean la = "in".equals(a.game.state), lb = "in".equals(b.game.state);
            return la != lb ? (la ? -1 : 1) : Long.compare(a.game.start, b.game.start);
        });
        return out;
    }

    void refreshIfStale() {
        if (!Config.SHOW_SPORTS || fetching || System.currentTimeMillis() - fetchedAt < EVERY) return;
        fetching = true;
        io.execute(() -> {
            try {
                List<Recs.Pick> list = new ArrayList<>();
                Calendar day = Calendar.getInstance();
                String today = ymd(day);
                day.add(Calendar.DAY_OF_YEAR, 1);
                String tomorrow = ymd(day);
                for (String d : new String[] {today, tomorrow}) {
                    mlb(list, get("baseball/mlb/scoreboard?dates=" + d));
                    teamGames(list, get("football/nfl/scoreboard?dates=" + d), "NFL", false, MAX_NFL);
                    teamGames(list, get("football/college-football/scoreboard?dates=" + d + "&groups=80"), Ui.tr("COLLEGE FOOTBALL"), true, MAX_COLLEGE);
                    teamGames(list, get("hockey/nhl/scoreboard?dates=" + d), "NHL", false, MAX_NFL);
                }
                f1(list, get("racing/f1/scoreboard"));
                picks = list;
                fetchedAt = System.currentTimeMillis();
            } catch (Exception e) {
                AppLog.w("sports: " + e.getMessage());
                fetchedAt = System.currentTimeMillis() - EVERY + 60_000L;   // try again in a minute
            } finally {
                fetching = false;
                main.post(changed);
            }
        });
    }

    // MARK: - Choosing the games

    /**
     * The postseason only: the World Series, the league championships ("World Series - Game 2"). Each game's own season
     * type: a scoreboard asked for by date (?dates=) has no season of its own.
     */
    private void mlb(List<Recs.Pick> out, JSONObject board) throws JSONException {
        JSONArray events = board.optJSONArray("events");
        for (int i = 0; events != null && i < events.length(); i++) {
            JSONObject e = events.getJSONObject(i), c = e.getJSONArray("competitions").getJSONObject(0);
            JSONObject season = e.optJSONObject("season") != null ? e.optJSONObject("season") : board.optJSONObject("season");
            boolean post = season != null && season.optInt("type") == 3;
            if (!post && !hasFavorite(c.optJSONArray("competitors"))) continue;     // the regular season: your teams only
            String note = note(c);
            Recs.Pick p = teamPick(e, c, !post ? "MLB" : note.isEmpty() ? Ui.tr("MLB POSTSEASON") : note.toUpperCase(Ui.locale()), false);
            if (p == null) continue;
            p.game.weight = !post ? FAVORITE : POSTSEASON + (note.startsWith("World Series") ? FINAL : 0);
            add(out, p);
        }
    }

    /** Hero weights: the postseason, a championship on top of it, Monday Night Football, two ranked college teams. */
    private static final int POSTSEASON = 5000, FINAL = 1000, MONDAY_NIGHT = 3000, FAVORITE = 2500, RANKED_PAIR = 2000;

    /** One of your teams (favorite_teams: ESPN's full names, "Boston Red Sox")? */
    static boolean favorite(JSONObject competitor) {
        JSONObject team = competitor == null ? null : competitor.optJSONObject("team");
        if (team == null || Config.FAVORITE_TEAMS.isEmpty()) return false;
        String name = team.optString("displayName").trim();
        for (String f : Config.FAVORITE_TEAMS.split(",")) if (!f.trim().isEmpty() && f.trim().equalsIgnoreCase(name)) return true;
        return false;
    }

    private static boolean hasFavorite(JSONArray competitors) {
        for (int i = 0; competitors != null && i < competitors.length(); i++) if (favorite(competitors.optJSONObject(i))) return true;
        return false;
    }

    /**
     * Football and hockey, only the games worth the hero: the postseason (the Super Bowl, the College Football Playoff
     * and its title game first), Monday Night Football, your teams, and college games between two ranked teams (the
     * better pair first). At most `max` of each league a day.
     */
    private void teamGames(List<Recs.Pick> out, JSONObject board, String league, boolean college, int max) throws JSONException {
        JSONArray events = board.optJSONArray("events");
        List<Recs.Pick> chosen = new ArrayList<>();
        for (int i = 0; events != null && i < events.length(); i++) {
            JSONObject e = events.getJSONObject(i), c = e.getJSONArray("competitions").getJSONObject(0);
            JSONArray teams = c.getJSONArray("competitors");
            int ranks = 0, sum = 0;
            for (int t = 0; t < teams.length(); t++) {
                int r = teams.getJSONObject(t).optJSONObject("curatedRank") == null ? 99
                        : teams.getJSONObject(t).getJSONObject("curatedRank").optInt("current", 99);
                if (r <= 25) ranks++;
                sum += r;
            }
            String note = note(c);
            JSONObject season = e.optJSONObject("season") != null ? e.optJSONObject("season") : board.optJSONObject("season");
            boolean post = season != null && season.optInt("type") == 3;
            Calendar local = Calendar.getInstance();
            local.setTimeInMillis(time(e.optString("date")));
            boolean monday = "NFL".equals(league) && local.get(Calendar.DAY_OF_WEEK) == Calendar.MONDAY && local.get(Calendar.HOUR_OF_DAY) >= 16;
            boolean fav = hasFavorite(teams);
            int weight;
            if (post) {
                // Bowls are the postseason too: only the Playoff (and the title game) count as big, the rest when ranked.
                String n = note.toLowerCase(Locale.ROOT);
                boolean big = !college || n.contains("playoff") || n.contains("championship");
                if (!big && ranks < 2 && !fav) continue;
                weight = (big ? POSTSEASON : RANKED_PAIR) + (n.contains("super bowl") || n.contains("national championship") ? FINAL : 0);
            } else if (monday) {
                weight = MONDAY_NIGHT;
            } else if (fav) {
                weight = FAVORITE;
            } else if (college && ranks == 2) {
                weight = RANKED_PAIR + 100 - sum;     // #3 vs #5 above #18 vs #22
            } else {
                continue;
            }
            String label = monday ? Ui.tr("MONDAY NIGHT FOOTBALL") : note.isEmpty() ? league : (league + " · " + note).toUpperCase(Ui.locale());
            Recs.Pick p = teamPick(e, c, label, college);
            if (p == null) continue;
            p.game.weight = weight;
            chosen.add(p);
        }
        Collections.sort(chosen, (x, y) -> Integer.compare(y.game.weight, x.game.weight));
        for (int i = 0; i < chosen.size() && i < max; i++) add(out, chosen.get(i));
    }

    /** Formula 1: this weekend's qualifying, sprint and race (not the practice sessions). */
    private void f1(List<Recs.Pick> out, JSONObject board) throws JSONException, IOException {
        JSONArray events = board.optJSONArray("events");
        String logo = leagueLogo(board);
        JSONObject news = null;
        for (int i = 0; events != null && i < events.length(); i++) {
            JSONObject e = events.getJSONObject(i);
            JSONArray sessions = e.getJSONArray("competitions");
            for (int s = 0; s < sessions.length(); s++) {
                JSONObject c = sessions.getJSONObject(s);
                String type = c.optJSONObject("type") == null ? "" : c.getJSONObject("type").optString("abbreviation");
                String session = type.equals("Race") ? Ui.tr("Race") : type.equals("Qual") ? Ui.tr("Qualifying")
                        : type.toLowerCase(Locale.ROOT).startsWith("sprint") || type.equals("SR") ? Ui.tr("Sprint") : null;
                if (session == null) continue;
                String[] app = app(c);
                if (app == null) continue;
                Game g = new Game();
                g.id = "f1-" + e.optString("id") + "-" + type;
                g.league = "F1";
                g.leagueLogo = logo;
                g.place = e.optJSONObject("circuit") == null ? "" : e.getJSONObject("circuit").optString("fullName");
                g.homeColor = 0xFFE10600;
                g.awayColor = 0xFF15151E;
                state(g, c, c.optString("date"));
                Recs.Pick p = pick(g, app);
                p.title = e.optString("name").replaceFirst(F1_SPONSORS, "");
                g.track = trackUrl(e.optString("name"));
                g.flag = flagUrl(e.optString("name"));
                if (g.track == null) {
                    if (news == null) news = get("racing/f1/news?limit=12");
                    g.photo = racePhoto(news, p.title);
                }
                p.label = ("FORMULA 1 · " + session).toUpperCase(Ui.locale());
                if ("appletv".equals(app[1])) p.link = F1_ON_APPLE_TV;
                p.meta = when(g) + (g.place.isEmpty() ? "" : " · " + g.place) + " · " + app[3];
                add(out, p);
            }
        }
    }

    /**
     * The newest wide news photo whose story names this race ("United States Grand Prix" → "United States"); else the
     * newest wide one. Null without one (the league's logo then).
     */
    private static String racePhoto(JSONObject news, String race) throws JSONException {
        String where = race.replaceAll("(?i)\\s*(grand prix|gp)$", "").trim().toLowerCase(Locale.ROOT);
        JSONArray articles = news.optJSONArray("articles");
        String any = null;
        for (int i = 0; articles != null && i < articles.length(); i++) {
            JSONObject a = articles.getJSONObject(i);
            JSONArray images = a.optJSONArray("images");
            JSONObject img = images == null || images.length() == 0 ? null : images.optJSONObject(0);
            if (img == null || img.optInt("width") < 1200 || img.optString("url", "").isEmpty()) continue;
            if (any == null) any = img.optString("url");
            if (!where.isEmpty() && a.optString("headline").toLowerCase(Locale.ROOT).contains(where)) return img.optString("url");
        }
        return any;
    }

    private Recs.Pick teamPick(JSONObject e, JSONObject c, String label, boolean ranks) throws JSONException {
        String[] app = app(c);
        if (app == null) return null;
        JSONArray teams = c.getJSONArray("competitors");
        JSONObject home = null, away = null;
        for (int t = 0; t < teams.length(); t++) {
            JSONObject team = teams.getJSONObject(t);
            if ("home".equals(team.optString("homeAway"))) home = team; else away = team;
        }
        if (home == null || away == null) return null;
        Game g = new Game();
        g.id = e.optString("id");
        g.away = name(away, ranks);
        g.home = name(home, ranks);
        g.awayLogo = away.getJSONObject("team").optString("logo", null);
        g.homeLogo = home.getJSONObject("team").optString("logo", null);
        g.awayColor = color(away);
        g.homeColor = color(home);
        g.awayAlt = color(away, "alternateColor");
        g.homeAlt = color(home, "alternateColor");
        state(g, c, e.optString("date"));
        Recs.Pick p = pick(g, app);
        boolean neutral = c.optBoolean("neutralSite");
        p.title = g.away + (neutral ? " vs " : " at ") + g.home;
        p.label = label;
        String score = "pre".equals(g.state) ? "" : " · " + away.getJSONObject("team").optString("abbreviation") + " "
                + away.optString("score") + ", " + home.getJSONObject("team").optString("abbreviation") + " " + home.optString("score");
        String series = series(c, teams);
        p.meta = when(g) + score + (series.isEmpty() ? "" : " · " + series) + " · " + app[3];
        return p;
    }

    /**
     * A best-of series in the postseason (baseball, hockey, basketball): "Brewers lead 1–0", "Series tied 2–2",
     * "Brewers win 4–2". Empty for a single game.
     */
    private static String series(JSONObject c, JSONArray teams) throws JSONException {
        JSONObject series = c.optJSONObject("series");
        JSONArray wins = series == null ? null : series.optJSONArray("competitors");
        if (wins == null || wins.length() != 2 || series.optInt("totalCompetitions") < 2) return "";
        String[] names = new String[2];
        int[] w = new int[2];
        for (int i = 0; i < 2; i++) {
            JSONObject s = wins.getJSONObject(i);
            w[i] = s.optInt("wins");
            for (int t = 0; t < teams.length(); t++) {
                JSONObject team = teams.getJSONObject(t).getJSONObject("team");
                if (team.optString("id").equals(s.optString("id"))) names[i] = team.optString("shortDisplayName", team.optString("displayName"));
            }
            if (names[i] == null) return "";
        }
        int lead = w[0] >= w[1] ? 0 : 1, high = w[lead], low = w[1 - lead];
        if (high == low) return Ui.trf("Series tied {a}–{b}", "a", high, "b", low);
        return series.optBoolean("completed") ? Ui.trf("{team} win {a}–{b}", "team", names[lead], "a", high, "b", low)
                : Ui.trf("{team} lead {a}–{b}", "team", names[lead], "a", high, "b", low);
    }

    private Recs.Pick pick(Game g, String[] app) {
        Recs.Pick p = new Recs.Pick();
        p.kind = "sport";
        p.id = g.id.hashCode();
        p.game = g;
        p.app = app[1];
        p.pkg = app[2];
        p.backdrop = "sports:" + g.id;
        p.detailed = true;
        return p;
    }

    private static void add(List<Recs.Pick> out, Recs.Pick p) {
        for (Recs.Pick q : out) if (q.backdrop.equals(p.backdrop)) return;
        out.add(p);
    }

    /** The app for the game's networks: a streaming service the TV has, or YouTube TV for a TV network. */
    private String[] app(JSONObject c) throws JSONException {
        List<String> names = new ArrayList<>();
        JSONArray b = c.optJSONArray("broadcasts");
        for (int i = 0; b != null && i < b.length(); i++) {
            JSONArray n = b.getJSONObject(i).optJSONArray("names");
            for (int j = 0; n != null && j < n.length(); j++) names.add(n.optString(j));
        }
        PackageManager pm = context.getPackageManager();
        for (String name : names) {
            String n = name.toLowerCase(Locale.ROOT);
            for (String[] s : NETWORKS) {
                if (n.equals(s[0]) && HomeApps.launchIntent(pm, s[2]) != null) return new String[] {name, s[1], s[2], name};
            }
            for (String tv : LIVE_TV) {
                if (!n.equals(tv)) continue;
                for (String pkg : YOUTUBE_TV) {
                    if (HomeApps.launchIntent(pm, pkg) != null) return new String[] {name, "youtubetv", pkg, name};
                }
            }
        }
        return null;
    }

    private static String name(JSONObject competitor, boolean ranks) throws JSONException {
        JSONObject team = competitor.getJSONObject("team");
        String n = team.optString("shortDisplayName", team.optString("displayName"));
        int r = competitor.optJSONObject("curatedRank") == null ? 99 : competitor.getJSONObject("curatedRank").optInt("current", 99);
        return ranks && r <= 25 ? "#" + r + " " + n : n;
    }

    private static int color(JSONObject competitor) {
        return color(competitor, "color");
    }

    private static int color(JSONObject competitor, String field) {
        try {
            return 0xFF000000 | Integer.parseInt(competitor.getJSONObject("team").optString(field, "333333"), 16);
        } catch (NumberFormatException | JSONException e) {
            return 0xFF333333;
        }
    }

    private static String note(JSONObject c) {
        JSONArray notes = c.optJSONArray("notes");
        return notes == null || notes.length() == 0 ? "" : notes.optJSONObject(0).optString("headline");
    }

    private static String leagueLogo(JSONObject board) {
        JSONArray leagues = board.optJSONArray("leagues");
        JSONArray logos = leagues == null || leagues.length() == 0 ? null : leagues.optJSONObject(0).optJSONArray("logos");
        return logos == null || logos.length() == 0 ? null : logos.optJSONObject(0).optString("href", null);
    }

    private static void state(Game g, JSONObject c, String date) throws JSONException {
        g.start = time(date);
        JSONObject type = c.optJSONObject("status") == null ? null : c.getJSONObject("status").optJSONObject("type");
        g.state = type == null ? "pre" : type.optString("state", "pre");
        g.detail = type == null ? "" : type.optString("shortDetail");
    }

    /** "Live · Top 5th", "Tonight 5:08 PM", "Tomorrow 10:00 AM", "Sun 1:25 PM". */
    private String when(Game g) {
        if ("in".equals(g.state)) return Ui.tr("Live") + (g.detail.isEmpty() ? "" : " · " + g.detail);
        Calendar start = Calendar.getInstance(), now = Calendar.getInstance();
        start.setTimeInMillis(g.start);
        String time = android.text.format.DateFormat.getTimeFormat(context).format(start.getTime());
        int days = start.get(Calendar.DAY_OF_YEAR) - now.get(Calendar.DAY_OF_YEAR)
                + 365 * (start.get(Calendar.YEAR) - now.get(Calendar.YEAR));
        if (days == 0) return (start.get(Calendar.HOUR_OF_DAY) >= 17 ? Ui.tr("Tonight") : Ui.tr("Today")) + " " + time;
        if (days == 1) return Ui.tr("Tomorrow") + " " + time;
        return new SimpleDateFormat("EEE", Ui.locale()).format(start.getTime()) + " " + time;
    }

    private static long time(String iso) {
        try {
            SimpleDateFormat f = new SimpleDateFormat(iso.length() > 17 ? "yyyy-MM-dd'T'HH:mm:ss'Z'" : "yyyy-MM-dd'T'HH:mm'Z'", Locale.US);
            f.setTimeZone(TimeZone.getTimeZone("UTC"));
            return f.parse(iso).getTime();
        } catch (Exception e) {
            return 0;
        }
    }

    private static String ymd(Calendar c) {
        return String.format(Locale.US, "%04d%02d%02d", c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH));
    }

    static JSONObject get(String path) throws IOException, JSONException {
        HttpURLConnection c = (HttpURLConnection) new URL(path.startsWith("https://") ? path : API + path).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(15000);
        try {
            if (c.getResponseCode() != 200) throw new IOException("ESPN returned HTTP " + c.getResponseCode());
            return new JSONObject(new String(read(c.getInputStream()), StandardCharsets.UTF_8));
        } finally {
            c.disconnect();
        }
    }

    private static byte[] read(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[16384];
        int n;
        while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
        return out.toByteArray();
    }

    // MARK: - The hero's picture

    /**
     * The game's picture at w × h: dark on the left (the hero's text), the two teams' colors meeting on the right with
     * their logos, larger toward the viewer; for F1, the league's logo on red. Built on Recs' loader thread.
     */
    static Bitmap art(Game g, int w, int h) throws IOException {
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
        c.drawColor(0xFF0B0B0F);
        boolean teams = g.track == null && g.photo == null && g.awayLogo != null;
        Bitmap awayLogo = teams ? load(g.awayLogo) : null, homeLogo = teams ? load(g.homeLogo) : null;
        // Each team's color behind its logo, or its second color when the logo would vanish in the first (Oregon's
        // green O on Oregon green); a soft light behind the logo when neither stands apart.
        int away = deepen(g.awayColor), home = deepen(g.homeColor);
        boolean awayGlow = false, homeGlow = false;
        if (awayLogo != null) {
            int ink = ink(awayLogo);
            if (apart(ink, away) < APART && g.awayAlt != 0 && apart(ink, deepen(g.awayAlt)) > apart(ink, away)) away = deepen(g.awayAlt);
            awayGlow = apart(ink, away) < APART;
        }
        if (homeLogo != null) {
            int ink = ink(homeLogo);
            if (apart(ink, home) < APART && g.homeAlt != 0 && apart(ink, deepen(g.homeAlt)) > apart(ink, home)) home = deepen(g.homeAlt);
            homeGlow = apart(ink, home) < APART;
        }
        paint.setShader(new LinearGradient(w * 0.38f, 0, w, h * 0.3f, new int[] {0xFF0B0B0F, away, home},
                new float[] {0, 0.45f, 1}, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, paint);
        paint.setShader(new RadialGradient(w * 0.72f, h * 0.4f, w * 0.42f, new int[] {0x33FFFFFF, 0x00FFFFFF},
                null, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, paint);
        paint.setShader(null);
        float size = h * 0.34f, cy = h * 0.4f;
        if (g.track != null && track(c, paint, g, w, h)) return out;
        if (g.photo != null && photo(c, paint, g.photo, w, h)) {
            // The league's logo on a dark badge, low on the right (above the shelf): clear of the title, the corner
            // buttons and the busy middle of most race photos.
            if (g.leagueLogo != null) badge(c, paint, g.leagueLogo, w * 0.85f, h * 0.64f, h * 0.24f);
            return out;
        }
        if (g.leagueLogo != null && g.awayLogo == null) {
            drawLogo(c, paint, g.leagueLogo, w * 0.72f, cy, h * 0.5f);
        } else {
            drawLogo(c, paint, awayLogo, w * 0.6f, cy, size, awayGlow);
            drawLogo(c, paint, homeLogo, w * 0.84f, cy, size, homeGlow);
        }
        return out;
    }

    /** How far apart two colors look (RGB distance, weighted for the eye); under APART a logo gets lost. */
    private static final double APART = 95;

    private static double apart(int a, int b) {
        double dr = ((a >> 16) & 255) - ((b >> 16) & 255), dg = ((a >> 8) & 255) - ((b >> 8) & 255), db = (a & 255) - (b & 255);
        return Math.sqrt(2 * dr * dr + 4 * dg * dg + 3 * db * db) / 3;
    }

    /** A logo's main color: the average of its opaque pixels. */
    private static int ink(Bitmap b) {
        long r = 0, gr = 0, bl = 0, n = 0;
        int step = Math.max(1, Math.min(b.getWidth(), b.getHeight()) / 60);
        for (int y = 0; y < b.getHeight(); y += step) {
            for (int x = 0; x < b.getWidth(); x += step) {
                int p = b.getPixel(x, y);
                if ((p >>> 24) < 200) continue;
                r += (p >> 16) & 255;
                gr += (p >> 8) & 255;
                bl += p & 255;
                n++;
            }
        }
        return n == 0 ? 0xFF808080 : 0xFF000000 | (int) (r / n) << 16 | (int) (gr / n) << 8 | (int) (bl / n);
    }

    /**
     * F1: the circuit's outline, large on the right, on F1 red fading to black under the hero's text, with faint
     * speed lines, and the F1 logo on its badge low on the right. False if the outline couldn't be loaded.
     */
    private static boolean track(Canvas c, Paint paint, Game g, int w, int h) {
        Bitmap outline = load(g.track);
        if (outline == null) return false;
        paint.setShader(new LinearGradient(w * 0.3f, h, w, 0, new int[] {0xFF0B0B0F, 0xFF4A0300, 0xFFC00500},
                new float[] {0, 0.5f, 1}, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, paint);
        paint.setShader(null);
        paint.setColor(0x0FFFFFFF);
        paint.setStrokeWidth(h * 0.006f);
        for (float x = w * 0.35f; x < w * 1.3f; x += h * 0.06f) c.drawLine(x, 0, x - h * 0.5f, h, paint);
        Bitmap mark = trim(outline);
        // Clear of the clock and buttons above and the F1 badge below; never wider than the right half.
        float th = h * 0.4f, tw = th * mark.getWidth() / mark.getHeight();
        if (tw > w * 0.36f) {
            tw = w * 0.36f;
            th = tw * mark.getHeight() / mark.getWidth();
        }
        float cx = w * 0.7f, cy = h * 0.42f;
        paint.setColor(0xFFFFFFFF);
        paint.setAlpha(235);
        c.drawBitmap(mark, null, new RectF(cx - tw / 2, cy - th / 2, cx + tw / 2, cy + th / 2), paint);
        paint.setAlpha(255);
        if (mark != outline) mark.recycle();
        outline.recycle();
        if (g.leagueLogo != null) badge(c, paint, g.leagueLogo, w * 0.88f, h * 0.68f, h * 0.17f);
        if (g.flag != null) flag(c, paint, g.flag, w * 0.765f, h * 0.68f, h * 0.075f);
        return true;
    }

    /** The race country's flag, `height` tall with rounded corners and a faint edge, centered at (cx, cy). */
    private static void flag(Canvas c, Paint paint, String url, float cx, float cy, float height) {
        Bitmap b = load(url);
        if (b == null) return;
        Bitmap f = trim(b);
        float fw = height * f.getWidth() / f.getHeight(), r = height * 0.14f;
        RectF box = new RectF(cx - fw / 2, cy - height / 2, cx + fw / 2, cy + height / 2);
        paint.setShader(new android.graphics.BitmapShader(f, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP));
        android.graphics.Matrix m = new android.graphics.Matrix();
        m.setRectToRect(new RectF(0, 0, f.getWidth(), f.getHeight()), box, android.graphics.Matrix.ScaleToFit.FILL);
        paint.getShader().setLocalMatrix(m);
        c.drawRoundRect(box, r, r, paint);
        paint.setShader(null);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(height * 0.03f);
        paint.setColor(0x40FFFFFF);
        c.drawRoundRect(box, r, r, paint);
        paint.setStyle(Paint.Style.FILL);
        if (f != b) f.recycle();
        b.recycle();
    }

    private static Bitmap load(String url) {
        java.io.File dir = images, file = dir == null ? null : new java.io.File(dir, Integer.toHexString(url.hashCode()) + ".img");
        if (file != null && file.exists()) {
            Bitmap b = BitmapFactory.decodeFile(file.getPath());
            if (b != null) return b;
        }
        try {
            HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
            con.setConnectTimeout(8000);
            con.setReadTimeout(15000);
            byte[] data;
            try (InputStream in = con.getInputStream()) {
                data = read(in);
            } finally {
                con.disconnect();
            }
            Bitmap b = BitmapFactory.decodeByteArray(data, 0, data.length);
            if (b != null && file != null) {
                //noinspection ResultOfMethodCallIgnored
                dir.mkdirs();
                try (java.io.FileOutputStream out = new java.io.FileOutputStream(file)) {
                    out.write(data);
                }
            }
            return b;
        } catch (IOException | RuntimeException e) {
            AppLog.w("image " + url + ": " + e.getMessage());
            return null;
        }
    }

    /** A team color darkened a little (a bright yellow or white would wash out the text and the glass). */
    private static int deepen(int color) {
        float[] hsv = new float[3];
        android.graphics.Color.colorToHSV(color, hsv);
        hsv[2] = Math.min(hsv[2], 0.62f);
        return android.graphics.Color.HSVToColor(hsv);
    }

    /** A logo `size` wide on a rounded smoked badge centered at (cx, cy). */
    private static void badge(Canvas c, Paint paint, String url, float cx, float cy, float size) {
        try {
            Bitmap logo = load(url);
            if (logo == null) return;
            // The logo file is square with space around the mark: trim to the mark first.
            Bitmap mark = trim(logo);
            float lw = size, lh = size * mark.getHeight() / mark.getWidth(), pad = size * 0.16f;
            RectF back = new RectF(cx - lw / 2 - pad, cy - lh / 2 - pad, cx + lw / 2 + pad, cy + lh / 2 + pad);
            paint.setShader(null);
            paint.setColor(0xA6000000);
            c.drawRoundRect(back, pad, pad, paint);
            paint.setColor(0xFFFFFFFF);
            c.drawBitmap(mark, null, new RectF(cx - lw / 2, cy - lh / 2, cx + lw / 2, cy + lh / 2), paint);
            if (mark != logo) mark.recycle();
            logo.recycle();
        } catch (RuntimeException e) {
            AppLog.w("league badge: " + e.getMessage());
        }
    }

    /** The bitmap cut to its opaque pixels. */
    private static Bitmap trim(Bitmap b) {
        int w = b.getWidth(), h = b.getHeight(), left = w, top = h, right = -1, bottom = -1;
        int[] row = new int[w];
        for (int y = 0; y < h; y++) {
            b.getPixels(row, 0, w, 0, y, w, 1);
            for (int x = 0; x < w; x++) {
                if ((row[x] >>> 24) < 16) continue;
                left = Math.min(left, x);
                right = Math.max(right, x);
                top = Math.min(top, y);
                bottom = Math.max(bottom, y);
            }
        }
        return right < 0 ? b : Bitmap.createBitmap(b, left, top, right - left + 1, bottom - top + 1);
    }

    /** The race photo over the whole picture, center-cropped; false if it couldn't be loaded. */
    private static boolean photo(Canvas c, Paint paint, String url, int w, int h) {
        try {
            HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
            con.setConnectTimeout(8000);
            con.setReadTimeout(15000);
            Bitmap b;
            try (InputStream in = con.getInputStream()) {
                b = BitmapFactory.decodeStream(in);
            } finally {
                con.disconnect();
            }
            if (b == null) return false;
            float scale = Math.max((float) w / b.getWidth(), (float) h / b.getHeight());
            float bw = b.getWidth() * scale, bh = b.getHeight() * scale;
            c.drawBitmap(b, null, new RectF((w - bw) / 2, (h - bh) / 2, (w + bw) / 2, (h + bh) / 2), paint);
            b.recycle();
            return true;
        } catch (IOException | RuntimeException e) {
            AppLog.w("race photo: " + e.getMessage());
            return false;
        }
    }

    private static void drawLogo(Canvas c, Paint paint, String url, float cx, float cy, float size) throws IOException {
        drawLogo(c, paint, url == null ? null : load(url), cx, cy, size, false);
    }

    /** A logo `size` across at (cx, cy), on a soft light when it would vanish in the background; recycled after. */
    private static void drawLogo(Canvas c, Paint paint, Bitmap logo, float cx, float cy, float size, boolean glow) {
        if (logo == null) return;
        if (glow) {
            paint.setShader(new RadialGradient(cx, cy, size * 0.75f, new int[] {0x8CFFFFFF, 0x40FFFFFF, 0x00FFFFFF},
                    new float[] {0, 0.55f, 1}, Shader.TileMode.CLAMP));
            c.drawCircle(cx, cy, size * 0.75f, paint);
            paint.setShader(null);
        }
        float scale = size / Math.max(logo.getWidth(), logo.getHeight());
        float lw = logo.getWidth() * scale, lh = logo.getHeight() * scale;
        c.drawBitmap(logo, null, new RectF(cx - lw / 2, cy - lh / 2, cx + lw / 2, cy + lh / 2), paint);
        logo.recycle();
    }
}
