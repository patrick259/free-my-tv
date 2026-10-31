package io.freemytv;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Music for right now (the suggestions screen): playlists for the holiday coming up, the weather outside, the time of
 * day and the season, leaning toward your genre (spotify_genre, else the one your top artists share, when Spotify
 * still says); then your favorite artists and your own playlists.
 * Spotify keeps its own editorial playlists from new apps, so the moods come from searching everyone's playlists.
 * Read again after half an hour.
 */
final class MusicPicks {
    static final class Pick {
        String name, reason, image;
        String context;             // a playlist or artist to play...
        List<String> uris;          // ...or these songs
        boolean shuffle = true;
    }

    static final class Row {
        final String title;
        final List<Pick> picks = new ArrayList<>();

        Row(String title) {
            this.title = title;
        }
    }

    /** A mood to search for, and why we picked it. */
    private static final class Theme {
        final String query, reason;

        Theme(String query, String reason) {
            this.query = query;
            this.reason = reason;
        }
    }

    private static final long FRESH = 30 * 60_000L;
    private static List<Row> cached;
    private static long cachedAt;

    private MusicPicks() {}

    static synchronized List<Row> get(Context c) throws Exception {
        if (cached != null && System.currentTimeMillis() - cachedAt < FRESH) return cached;
        List<Row> rows = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        // Favorites first: they also tell us the genre to lean the moods toward.
        JSONObject artists = Spotify.api(c, "GET", "me/top/artists?limit=12&time_range=medium_term", null);
        JSONArray topArtists = artists == null ? new JSONArray() : artists.optJSONArray("items");
        String genre = Config.SPOTIFY_GENRE.isEmpty() ? genre(topArtists) : Config.SPOTIFY_GENRE;

        Row now = new Row(Ui.tr("For Right Now"));
        for (Theme t : themes(c)) {
            int added = 0;
            if (!genre.isEmpty()) added += search(c, genre + " " + t.query, t.reason, now, seen, 1);
            search(c, t.query, t.reason, now, seen, added == 0 ? 2 : 1);
            if (now.picks.size() >= 10) break;
        }
        if (!now.picks.isEmpty()) rows.add(now);

        Row favorites = new Row(Ui.tr("Your Favorites"));
        Pick top = topSongs(c);
        if (top != null) favorites.picks.add(top);
        for (int i = 0; topArtists != null && i < topArtists.length(); i++) {
            JSONObject a = topArtists.optJSONObject(i);
            if (a == null) continue;
            Pick p = new Pick();
            p.name = a.optString("name");
            p.reason = Ui.tr("One of the artists you play most");
            p.image = image(a.optJSONArray("images"));
            p.context = a.optString("uri");
            p.shuffle = false;
            favorites.picks.add(p);
        }
        if (!favorites.picks.isEmpty()) rows.add(favorites);

        Row mine = new Row(Ui.tr("Your Playlists"));
        JSONObject lists = Spotify.api(c, "GET", "me/playlists?limit=30", null);
        JSONArray items = lists == null ? null : lists.optJSONArray("items");
        for (int i = 0; items != null && i < items.length() && mine.picks.size() < 16; i++) {
            JSONObject pl = items.optJSONObject(i);
            if (pl == null || image(pl.optJSONArray("images")) == null) continue;
            Pick p = new Pick();
            p.name = pl.optString("name");
            p.reason = Ui.tr("One of your playlists");
            p.image = image(pl.optJSONArray("images"));
            p.context = pl.optString("uri");
            mine.picks.add(p);
        }
        if (!mine.picks.isEmpty()) rows.add(mine);

        cached = rows;
        cachedAt = System.currentTimeMillis();
        return rows;
    }

    /** Adds up to `max` playlists for `query` (with a cover, not seen yet); returns how many. */
    private static int search(Context c, String query, String reason, Row row, Set<String> seen, int max) {
        try {
            JSONObject r = Spotify.api(c, "GET", "search?type=playlist&limit=10&q=" + Spotify.enc(query), null);
            JSONArray items = r == null || r.optJSONObject("playlists") == null ? null : r.optJSONObject("playlists").optJSONArray("items");
            int added = 0;
            for (int i = 0; items != null && i < items.length() && added < max; i++) {
                JSONObject pl = items.optJSONObject(i);   // the list has nulls in it
                if (pl == null) continue;
                String owner = pl.optJSONObject("owner") == null ? "" : pl.optJSONObject("owner").optString("id");
                String img = image(pl.optJSONArray("images"));
                // Spotify's own playlists won't play for new apps; and a cover makes the card.
                if (img == null || owner.equals("spotify") || childish(pl.optString("name")) || !seen.add(pl.optString("id"))) continue;
                Pick p = new Pick();
                p.name = pl.optString("name").trim();
                p.reason = reason;
                p.image = img;
                p.context = pl.optString("uri");
                row.picks.add(p);
                added++;
            }
            return added;
        } catch (Exception e) {
            AppLog.w("music picks: " + query + ": " + e.getMessage());
            return 0;
        }
    }

    /** Playlists for kids or for sleeping: not what anyone means by a Halloween party or a rainy day. */
    private static boolean childish(String name) {
        String n = name.toLowerCase(Locale.US);
        return n.contains("kid") || n.contains("child") || n.contains("baby") || n.contains("lullab") || n.contains("toddler")
                || n.contains("sleep") || n.contains("study");
    }

    /** Your 50 most played songs of late, shuffled. */
    private static Pick topSongs(Context c) {
        try {
            JSONObject r = Spotify.api(c, "GET", "me/top/tracks?limit=50&time_range=short_term", null);
            JSONArray items = r == null ? null : r.optJSONArray("items");
            if (items == null || items.length() == 0) return null;
            Pick p = new Pick();
            p.name = Ui.tr("Your Top Songs");
            p.reason = Ui.tr("What you've played most lately, shuffled");
            p.uris = new ArrayList<>();
            for (int i = 0; i < items.length(); i++) {
                JSONObject t = items.optJSONObject(i);
                if (t == null) continue;
                p.uris.add(t.optString("uri"));
                if (p.image == null && t.optJSONObject("album") != null) p.image = image(t.optJSONObject("album").optJSONArray("images"));
            }
            return p;
        } catch (Exception e) {
            AppLog.w("music picks: top songs: " + e.getMessage());
            return null;
        }
    }

    /** The genre your top artists share most ("country"), or "" when Spotify doesn't say. */
    private static String genre(JSONArray artists) {
        Map<String, Integer> count = new HashMap<>();
        String best = "";
        int bestN = 1;   // two artists at least
        for (int i = 0; artists != null && i < artists.length(); i++) {
            JSONObject a = artists.optJSONObject(i);
            JSONArray g = a == null ? null : a.optJSONArray("genres");
            Set<String> words = new HashSet<>();
            for (int j = 0; g != null && j < g.length(); j++) {
                for (String w : new String[] {"country", "rock", "pop", "hip hop", "r&b", "jazz", "indie", "folk", "worship", "latin", "edm"}) {
                    if (g.optString(j).toLowerCase(Locale.US).contains(w)) words.add(w);
                }
            }
            for (String w : words) {
                int n = count.containsKey(w) ? count.get(w) + 1 : 1;
                count.put(w, n);
                if (n > bestN) { bestN = n; best = w; }
            }
        }
        return best;
    }

    private static String image(JSONArray images) {
        if (images == null || images.length() == 0) return null;
        // The middle size: big enough for a card, not the 640 px one.
        JSONObject pick = images.optJSONObject(images.length() > 1 ? 1 : 0);
        if (pick == null) pick = images.optJSONObject(0);
        String url = pick == null ? "" : pick.optString("url");
        return url.isEmpty() ? null : url;
    }

    // MARK: - What's going on

    /** The moods for now, the most fitting first: a holiday, the weather, the hour, the season. */
    private static List<Theme> themes(Context c) {
        List<Theme> out = new ArrayList<>();
        Calendar cal = Calendar.getInstance();
        Theme h = holiday(cal);
        if (h != null) out.add(h);
        Theme w = weather(c);
        if (w != null) out.add(w);
        out.add(hour(cal));
        out.add(season(cal));
        return out;
    }

    private static Theme holiday(Calendar now) {
        int y = now.get(Calendar.YEAR), m = now.get(Calendar.MONTH), d = now.get(Calendar.DAY_OF_MONTH);
        int day = now.get(Calendar.DAY_OF_YEAR);
        if (m == Calendar.DECEMBER && d == 31) return new Theme("new years eve party", Ui.tr("It's New Year's Eve"));
        if (m == Calendar.JANUARY && d == 1) return new Theme("new year", Ui.tr("Happy New Year"));
        if (m == Calendar.FEBRUARY && d >= 7 && d <= 14) return new Theme("love songs", Ui.tr("Valentine's Day is coming"));
        if (m == Calendar.MARCH && d >= 12 && d <= 17) return new Theme("st patricks day", Ui.tr("St. Patrick's Day"));
        if (m == Calendar.MAY && d == 5) return new Theme("cinco de mayo", Ui.tr("Cinco de Mayo"));
        int memorial = lastWeekday(y, Calendar.MAY, Calendar.MONDAY);
        if (day >= memorial - 3 && day <= memorial) return new Theme("bbq", Ui.tr("Memorial Day weekend"));
        if (m == Calendar.JUNE && d >= 27 || m == Calendar.JULY && d <= 4) return new Theme("4th of july", Ui.tr("The Fourth of July"));
        int labor = nthWeekday(y, Calendar.SEPTEMBER, Calendar.MONDAY, 1);
        if (day >= labor - 3 && day <= labor) return new Theme("bbq", Ui.tr("Labor Day weekend"));
        if (m == Calendar.OCTOBER && d == 31) return new Theme("halloween party", Ui.tr("Happy Halloween"));
        if (m == Calendar.OCTOBER && d >= 18) return new Theme("halloween", Ui.tr("Halloween is coming"));
        if (m == Calendar.OCTOBER) return new Theme("spooky season", Ui.tr("Spooky season"));
        int thanks = nthWeekday(y, Calendar.NOVEMBER, Calendar.THURSDAY, 4);
        if (day >= thanks - 6 && day <= thanks) return new Theme("thanksgiving", Ui.tr("Thanksgiving week"));
        if (day > thanks && m == Calendar.NOVEMBER || m == Calendar.DECEMBER && d <= 26) return new Theme("christmas", Ui.tr("The Christmas season"));
        return null;
    }

    private static Theme weather(Context c) {
        HomeData.Weather wx = HomeData.get(c).weather;
        if (wx.code < 0) return null;
        String where = Prefs.city().replaceAll(",.*$", "");
        String in = where.isEmpty() ? "" : " " + Ui.trf("in {city}", "city", where);
        switch (HeroArt.kind(wx.code)) {
            case "rain": return new Theme("rainy day", Ui.tr("Rain outside") + in);
            case "storm": return new Theme("thunderstorm", Ui.tr("Storms outside") + in);
            case "snow": return new Theme("snow day", Ui.tr("Snow outside") + in);
            case "fog": return new Theme("foggy morning", Ui.tr("Fog outside") + in);
            case "overcast": return new Theme("cloudy day", Ui.tr("A gray day") + in);
            default:
                if (wx.temp >= 29) return new Theme("hot summer day", Ui.tr("A hot one") + in);
                if (!wx.isDay) return new Theme("clear night", Ui.tr("A clear night") + in);
                return new Theme("sunny day", Ui.tr("Sunshine") + in);
        }
    }

    private static Theme hour(Calendar now) {
        int h = now.get(Calendar.HOUR_OF_DAY), dow = now.get(Calendar.DAY_OF_WEEK);
        boolean weekend = dow == Calendar.SATURDAY || dow == Calendar.SUNDAY;
        if (h >= 5 && h < 11) return weekend ? new Theme("sunday morning", Ui.tr("A slow weekend morning"))
                : new Theme("morning coffee", Ui.tr("Good morning"));
        if (h < 16 && h >= 11) return new Theme(weekend ? "weekend" : "feel good", Ui.tr("This afternoon"));
        if (h < 20 && h >= 16) return dow == Calendar.FRIDAY ? new Theme("friday night", Ui.tr("Friday night"))
                : new Theme("dinner", Ui.tr("Dinner time"));
        return dow == Calendar.FRIDAY || dow == Calendar.SATURDAY ? new Theme("saturday night", Ui.tr("A night in"))
                : new Theme("chill evening", Ui.tr("Winding down"));
    }

    private static Theme season(Calendar now) {
        int m = now.get(Calendar.MONTH);
        if (m == Calendar.DECEMBER || m <= Calendar.FEBRUARY) return new Theme("cozy winter", Ui.tr("Winter"));
        if (m <= Calendar.MAY) return new Theme("spring", Ui.tr("Spring"));
        if (m <= Calendar.AUGUST) return new Theme("summer", Ui.tr("Summer"));
        return new Theme("fall", Ui.tr("Fall"));
    }

    private static int nthWeekday(int year, int month, int weekday, int n) {
        Calendar c = Calendar.getInstance();
        c.clear();
        c.set(year, month, 1);
        while (c.get(Calendar.DAY_OF_WEEK) != weekday) c.add(Calendar.DAY_OF_MONTH, 1);
        c.add(Calendar.DAY_OF_MONTH, 7 * (n - 1));
        return c.get(Calendar.DAY_OF_YEAR);
    }

    private static int lastWeekday(int year, int month, int weekday) {
        Calendar c = Calendar.getInstance();
        c.clear();
        c.set(year, month, 1);
        c.set(Calendar.DAY_OF_MONTH, c.getActualMaximum(Calendar.DAY_OF_MONTH));
        while (c.get(Calendar.DAY_OF_WEEK) != weekday) c.add(Calendar.DAY_OF_MONTH, -1);
        return c.get(Calendar.DAY_OF_YEAR);
    }
}
