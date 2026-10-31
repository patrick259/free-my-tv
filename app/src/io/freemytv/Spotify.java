package io.freemytv;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;

/**
 * The Spotify Web API for the music suggestions (MusicPicks): your favorites and playlists, search, and starting a
 * playlist on a Spotify Connect device (this TV's Spotify app, or a speaker group such as an Alexa "Everywhere").
 * Signs in with the refresh token tools/spotify-login.py put in config.properties (PKCE, no client secret). Spotify
 * may hand back a new refresh token when we use it: the newest is kept in our own preferences.
 */
final class Spotify {
    static final String APP = "com.spotify.tv.android";

    static final class Device {
        String id, name, type;
        boolean active;
    }

    private static String access;
    private static long accessUntil;

    private Spotify() {}

    static boolean on() {
        return !Config.SPOTIFY_CLIENT_ID.isEmpty() && !Config.SPOTIFY_REFRESH_TOKEN.isEmpty();
    }

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences("spotify", Context.MODE_PRIVATE);
    }

    /** Play on the speaker group instead of this TV (the choice on the suggestions screen). */
    static boolean everywhere(Context c) {
        return sp(c).getBoolean("everywhere", false);
    }

    static void setEverywhere(Context c, boolean on) {
        sp(c).edit().putBoolean("everywhere", on).apply();
    }

    /** The refresh token to use: ours when Spotify gave us a newer one for the token this build came with. */
    private static String refreshToken(Context c) {
        SharedPreferences p = sp(c);
        if (!Config.SPOTIFY_REFRESH_TOKEN.equals(p.getString("seed", ""))) return Config.SPOTIFY_REFRESH_TOKEN;
        return p.getString("refresh", Config.SPOTIFY_REFRESH_TOKEN);
    }

    private static synchronized String token(Context c) throws IOException {
        if (access != null && System.currentTimeMillis() < accessUntil) return access;
        String body = "grant_type=refresh_token&refresh_token=" + enc(refreshToken(c)) + "&client_id=" + enc(Config.SPOTIFY_CLIENT_ID);
        HttpURLConnection h = open("https://accounts.spotify.com/api/token", "POST");
        h.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        write(h, body);
        int code = h.getResponseCode();
        if (code != 200) throw new IOException(code == 400 ? "Spotify sign-in expired: run tools/spotify-login.py again" : "Spotify sign-in: HTTP " + code);
        try {
            JSONObject t = new JSONObject(read(h.getInputStream()));
            String newer = t.optString("refresh_token");
            if (!newer.isEmpty()) sp(c).edit().putString("seed", Config.SPOTIFY_REFRESH_TOKEN).putString("refresh", newer).apply();
            access = t.getString("access_token");
            accessUntil = System.currentTimeMillis() + (t.optLong("expires_in", 3600) - 120) * 1000;
            return access;
        } catch (org.json.JSONException e) {
            throw new IOException("Spotify sign-in: bad reply");
        }
    }

    /** One call to the Web API: its JSON, or null when it answers with no body. */
    static JSONObject api(Context c, String method, String path, JSONObject body) throws IOException {
        for (int attempt = 0; ; attempt++) {
            HttpURLConnection h = open("https://api.spotify.com/v1/" + path, method);
            h.setRequestProperty("Authorization", "Bearer " + token(c));
            if (body != null) {
                h.setRequestProperty("Content-Type", "application/json");
                write(h, body.toString());
            } else if (!method.equals("GET")) {
                write(h, "");   // Spotify wants a Content-Length even with nothing to send
            }
            int code = h.getResponseCode();
            if (code == 401 && attempt == 0) {
                synchronized (Spotify.class) { access = null; }
                continue;
            }
            if (code == 204 || code == 202) return null;
            if (code >= 300) {
                String why = "";
                try {
                    InputStream err = h.getErrorStream();
                    if (err != null) why = new JSONObject(read(err)).optJSONObject("error").optString("message");
                } catch (Exception ignored) {}
                throw new HttpError(code, why);
            }
            String text = read(h.getInputStream());
            try {
                return text.trim().isEmpty() ? null : new JSONObject(text);
            } catch (org.json.JSONException e) {
                return null;
            }
        }
    }

    static final class HttpError extends IOException {
        final int code;

        HttpError(int code, String why) {
            super(why.isEmpty() ? "HTTP " + code : why);
            this.code = code;
        }
    }

    static List<Device> devices(Context c) throws IOException {
        List<Device> out = new ArrayList<>();
        JSONObject r = api(c, "GET", "me/player/devices", null);
        JSONArray list = r == null ? null : r.optJSONArray("devices");
        for (int i = 0; list != null && i < list.length(); i++) {
            JSONObject d = list.optJSONObject(i);
            if (d == null || d.optString("id").isEmpty()) continue;
            Device dv = new Device();
            dv.id = d.optString("id");
            dv.name = d.optString("name");
            dv.type = d.optString("type");
            dv.active = d.optBoolean("is_active");
            out.add(dv);
        }
        return out;
    }

    /** This TV's name, which its Spotify app shows in Spotify Connect. */
    static String tvName(Context c) {
        String n = android.provider.Settings.Global.getString(c.getContentResolver(), "device_name");
        return n == null ? "" : n.trim();
    }

    private static Device find(List<Device> list, String name) {
        for (Device d : list) if (d.name.equalsIgnoreCase(name)) return d;
        return null;
    }

    /**
     * Starts `pick` on this TV or on the speaker group; returns the device's name. On the TV, Spotify's app is started
     * first when Spotify Connect can't see the TV yet. Runs on a background thread (it waits for the app).
     */
    static String play(Context c, MusicPicks.Pick pick, boolean everywhere) throws IOException, InterruptedException {
        String want = everywhere ? Config.SPOTIFY_EVERYWHERE : tvName(c);
        Device d = find(devices(c), want);
        if (d == null && !everywhere) {
            Intent launch = c.getPackageManager().getLeanbackLaunchIntentForPackage(APP);
            if (launch == null) launch = c.getPackageManager().getLaunchIntentForPackage(APP);
            if (launch == null) throw new IOException("Spotify isn't installed on this TV");
            c.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            for (int i = 0; i < 15 && d == null; i++) {
                Thread.sleep(1000);
                d = find(devices(c), want);
                if (d == null) for (Device x : devices(c)) if (x.active && "TV".equalsIgnoreCase(x.type)) d = x;
            }
        }
        if (d == null) throw new IOException(everywhere ? "Spotify can't see \"" + want + "\" right now" : "Spotify on this TV didn't answer");

        JSONObject body = new JSONObject();
        try {
            if (pick.context != null) body.put("context_uri", pick.context);
            else body.put("uris", new JSONArray(pick.uris));
        } catch (org.json.JSONException ignored) {}
        String dev = "?device_id=" + enc(d.id);
        try {
            api(c, "PUT", "me/player/play" + dev, body);
        } catch (HttpError e) {
            // A sleeping speaker: move the session to it first, then play.
            if (e.code != 404 && e.code != 502) throw e;
            JSONObject move = new JSONObject();
            try { move.put("device_ids", new JSONArray().put(d.id)).put("play", false); } catch (org.json.JSONException ignored) {}
            api(c, "PUT", "me/player", move);
            Thread.sleep(1500);
            api(c, "PUT", "me/player/play" + dev, body);
        }
        try {
            api(c, "PUT", "me/player/shuffle?state=" + pick.shuffle + "&device_id=" + enc(d.id), null);
        } catch (IOException ignored) {}
        return d.name;
    }

    // MARK: - HTTP

    private static HttpURLConnection open(String url, String method) throws IOException {
        HttpURLConnection h = (HttpURLConnection) new URL(url).openConnection();
        h.setConnectTimeout(8_000);
        h.setReadTimeout(12_000);
        h.setRequestMethod(method);
        return h;
    }

    private static void write(HttpURLConnection h, String body) throws IOException {
        byte[] b = body.getBytes("UTF-8");
        h.setDoOutput(true);
        h.setFixedLengthStreamingMode(b.length);
        try (OutputStream out = h.getOutputStream()) {
            out.write(b);
        }
    }

    static String enc(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8").replace("+", "%20");
        } catch (java.io.UnsupportedEncodingException e) {
            return s;
        }
    }

    private static String read(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] b = new byte[8192];
        for (int n; (n = in.read(b)) > 0; ) out.write(b, 0, n);
        in.close();
        return out.toString("UTF-8");
    }
}
