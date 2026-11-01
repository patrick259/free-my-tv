package io.freemytv;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

/**
 * The house's thermostats (an Ecobee, or any thermostat) through Home Assistant's climate entities: what each reads and
 * is set to, and changing the mode or the temperature (ThermostatActivity). ha_thermostats lists them; when it's empty,
 * every climate entity that can cool (a pool or spa heater only heats, so it stays out).
 */
final class Thermostats {
    private Thermostats() {}

    static final class Stat {
        String id, name, mode, action;
        double current = Double.NaN, humidity = Double.NaN, target = Double.NaN, low = Double.NaN, high = Double.NaN;
        double min = 45, max = 95;
        List<String> modes = new ArrayList<>();
    }

    static boolean on() {
        return !Config.HA_URL.isEmpty() && !Config.HA_TOKEN.isEmpty();
    }

    /** Runs on a background thread. */
    static List<Stat> read() throws IOException {
        List<String> wanted = new ArrayList<>();
        for (String s : Config.HA_THERMOSTATS.split(",")) if (!s.trim().isEmpty()) wanted.add(s.trim());
        List<Stat> out = new ArrayList<>();
        try {
            JSONArray all = new JSONArray(call("GET", "/api/states", null));
            for (int i = 0; i < all.length(); i++) {
                JSONObject s = all.getJSONObject(i);
                String id = s.optString("entity_id");
                if (!id.startsWith("climate.")) continue;
                Stat t = parse(s);
                if (wanted.isEmpty() ? t.modes.contains("cool") : wanted.contains(id)) out.add(t);
            }
        } catch (org.json.JSONException e) {
            throw new IOException("Home Assistant: bad reply");
        }
        if (!wanted.isEmpty()) out.sort((a, b) -> wanted.indexOf(a.id) - wanted.indexOf(b.id));
        return out;
    }

    private static Stat parse(JSONObject s) {
        JSONObject a = s.optJSONObject("attributes");
        if (a == null) a = new JSONObject();
        Stat t = new Stat();
        t.id = s.optString("entity_id");
        t.name = a.optString("friendly_name", t.id);
        t.mode = s.optString("state");
        t.action = a.optString("hvac_action");
        t.current = a.optDouble("current_temperature");
        t.humidity = a.optDouble("current_humidity");
        t.target = a.optDouble("temperature");
        t.low = a.optDouble("target_temp_low");
        t.high = a.optDouble("target_temp_high");
        t.min = a.optDouble("min_temp", 45);
        t.max = a.optDouble("max_temp", 95);
        JSONArray m = a.optJSONArray("hvac_modes");
        for (int i = 0; m != null && i < m.length(); i++) t.modes.add(m.optString(i));
        return t;
    }

    static void setMode(String id, String mode) throws IOException {
        JSONObject b = new JSONObject();
        try { b.put("entity_id", id).put("hvac_mode", mode); } catch (org.json.JSONException ignored) {}
        call("POST", "/api/services/climate/set_hvac_mode", b);
    }

    /** One setpoint (heat or cool), or the low and high of heat_cool. */
    static void setTemperature(Stat t) throws IOException {
        JSONObject b = new JSONObject();
        try {
            b.put("entity_id", t.id);
            if ("heat_cool".equals(t.mode)) b.put("target_temp_low", t.low).put("target_temp_high", t.high);
            else b.put("temperature", t.target);
        } catch (org.json.JSONException ignored) {}
        call("POST", "/api/services/climate/set_temperature", b);
    }

    private static String call(String method, String path, JSONObject body) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(Config.HA_URL + path).openConnection();
        c.setRequestProperty("Authorization", "Bearer " + Config.HA_TOKEN);
        c.setConnectTimeout(6_000);
        c.setReadTimeout(15_000);
        c.setRequestMethod(method);
        try {
            if (body != null) {
                byte[] b = body.toString().getBytes("UTF-8");
                c.setRequestProperty("Content-Type", "application/json");
                c.setDoOutput(true);
                c.setFixedLengthStreamingMode(b.length);
                try (OutputStream o = c.getOutputStream()) { o.write(b); }
            }
            int code = c.getResponseCode();
            if (code >= 300) throw new IOException("Home Assistant: HTTP " + code);
            InputStream in = c.getInputStream();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
            return out.toString("UTF-8");
        } finally {
            c.disconnect();
        }
    }
}
