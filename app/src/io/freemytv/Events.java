package io.freemytv;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The few things worth going out for around here in the next two weeks (the music screen's card): concerts, games and
 * shows within 35 miles, from Ticketmaster's Discovery API (a free key: ticketmaster_key in config.properties).
 * "Worth it" is Ticketmaster's own relevance, kept to the big rooms (arenas, amphitheaters, stadiums, theaters) and
 * one event per act. Read at most every three hours.
 */
final class Events {
    static final class Event {
        String name, venue, when;
        long at;
    }

    private static final long EVERY = 3 * 3_600_000L;
    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private static volatile List<Event> events = new ArrayList<>();
    private static volatile long fetchedAt;
    private static volatile boolean fetching;

    private Events() {}

    static boolean on() {
        return !Config.TICKETMASTER_KEY.isEmpty();
    }

    /** What we have (maybe nothing yet); fetches again when stale and calls `done` on its thread when it has. */
    static List<Event> get(Context c, Runnable done) {
        if (on() && !fetching && System.currentTimeMillis() - fetchedAt > EVERY) {
            fetching = true;
            IO.execute(() -> {
                try {
                    events = fetch();
                    fetchedAt = System.currentTimeMillis();
                    if (done != null) done.run();
                } catch (Exception e) {
                    AppLog.w("events: " + e.getMessage());
                    fetchedAt = System.currentTimeMillis() - EVERY + 10 * 60_000L;   // try again in 10 minutes
                } finally {
                    fetching = false;
                }
            });
        }
        return events;
    }

    private static List<Event> fetch() throws Exception {
        SimpleDateFormat iso = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
        iso.setTimeZone(TimeZone.getTimeZone("UTC"));
        long now = System.currentTimeMillis();
        String url = "https://app.ticketmaster.com/discovery/v2/events.json?apikey=" + Config.TICKETMASTER_KEY
                + "&latlong=" + Prefs.latitude() + "," + Prefs.longitude() + "&radius=35&unit=miles&size=60"
                + "&sort=relevance,desc&classificationName=music,sports,arts%20%26%20theatre"
                + "&startDateTime=" + iso.format(new Date(now)) + "&endDateTime=" + iso.format(new Date(now + 14 * 86_400_000L));
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8_000);
        c.setReadTimeout(12_000);
        if (c.getResponseCode() != 200) throw new java.io.IOException("HTTP " + c.getResponseCode());
        JSONObject root = new JSONObject(read(c.getInputStream()));
        JSONObject embedded = root.optJSONObject("_embedded");
        JSONArray list = embedded == null ? null : embedded.optJSONArray("events");
        List<Event> out = new ArrayList<>();
        List<String> acts = new ArrayList<>();
        SimpleDateFormat day = new SimpleDateFormat("yyyy-MM-dd", Locale.US), shown = new SimpleDateFormat("EEE, MMM d", Ui.locale());
        for (int i = 0; list != null && i < list.length() && out.size() < 6; i++) {
            JSONObject e = list.optJSONObject(i);
            if (e == null) continue;
            JSONObject v = e.optJSONObject("_embedded") == null ? null
                    : e.optJSONObject("_embedded").optJSONArray("venues") == null ? null
                    : e.optJSONObject("_embedded").optJSONArray("venues").optJSONObject(0);
            String venue = v == null ? "" : v.optString("name");
            if (!bigRoom(venue)) continue;
            String act = e.optString("name").replaceAll("(?i)\\s*[-:–|].*$", "").trim();
            if (acts.contains(act.toLowerCase(Locale.US))) continue;
            acts.add(act.toLowerCase(Locale.US));
            JSONObject start = e.optJSONObject("dates") == null ? null : e.optJSONObject("dates").optJSONObject("start");
            String date = start == null ? "" : start.optString("localDate");
            if (date.isEmpty()) continue;
            Event ev = new Event();
            ev.name = e.optString("name");
            ev.venue = venue + (v != null && v.optJSONObject("city") != null ? " · " + v.optJSONObject("city").optString("name") : "");
            ev.at = day.parse(date).getTime();
            ev.when = shown.format(new Date(ev.at));
            out.add(ev);
        }
        Collections.sort(out, (a, b) -> Long.compare(a.at, b.at));
        return out;
    }

    /** The rooms the really big nights happen in (not bars and clubs). */
    private static boolean bigRoom(String venue) {
        String v = venue.toLowerCase(Locale.US);
        return v.contains("center") || v.contains("centre") || v.contains("arena") || v.contains("amphitheat")
                || v.contains("stadium") || v.contains("theatre") || v.contains("theater") || v.contains("pavilion")
                || v.contains("fairgrounds") || v.contains("park") || v.contains("hall");
    }

    private static String read(InputStream in) throws java.io.IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] b = new byte[8192];
        for (int n; (n = in.read(b)) > 0; ) out.write(b, 0, n);
        in.close();
        return out.toString("UTF-8");
    }
}
