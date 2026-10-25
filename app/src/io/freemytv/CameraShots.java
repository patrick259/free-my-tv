package io.freemytv;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * The home screen's camera row (the drawer, under the shortcuts): each camera in ha_cameras with its latest picture
 * from Home Assistant (for Ring, the last recorded event's) and when that was (Ring's "Last activity"). Read again when
 * the drawer opens, at most once a minute; the pictures are kept small, card sized.
 */
final class CameraShots {
    private CameraShots() {}

    static final class Shot {
        final String id;
        String name;
        Bitmap image;       // null: none yet, or the camera didn't give one
        long at;            // when the picture is from (ms), 0 unknown

        Shot(String id) {
            this.id = id;
            name = fallbackName(id);
        }
    }

    private static final long AGAIN_MS = 60_000;
    private static final int THUMB_W = 640;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final List<Shot> SHOTS = new ArrayList<>();
    private static long readAt;
    private static boolean reading;

    static {
        if (on()) for (String id : Config.HA_CAMERAS.split(",")) if (!id.trim().isEmpty()) SHOTS.add(new Shot(id.trim()));
    }

    static boolean on() {
        return HomeApps.CAMERAS_ON && !Config.HA_TOKEN.isEmpty();
    }

    /** The cameras, in ha_cameras order (read on the main thread). */
    static List<Shot> all() {
        return Collections.unmodifiableList(SHOTS);
    }

    /** Reads the names, pictures and times again if they are a minute old; `changed` runs on the main thread after. */
    static void refreshIfStale(Runnable changed) {
        if (SHOTS.isEmpty() || reading) return;
        long now = SystemClock.uptimeMillis();
        if (readAt != 0 && now - readAt < AGAIN_MS) return;
        reading = true;
        readAt = now;
        List<String> ids = new ArrayList<>();
        for (Shot s : SHOTS) ids.add(s.id);
        new Thread(() -> {
            for (int i = 0; i < ids.size(); i++) {
                String id = ids.get(i);
                String name = null;
                long at = 0;
                Bitmap image = null;
                try {
                    JSONObject st = json("/api/states/" + id);
                    if (st != null) name = st.getJSONObject("attributes").optString("friendly_name").replaceAll("(?i)\\s+live view$", "").trim();
                    // Ring: camera.front_door_live_view → sensor.front_door_last_activity
                    String base = id.replaceFirst("^camera\\.", "").replaceFirst("_live_view$", "");
                    JSONObject last = json("/api/states/sensor." + base + "_last_activity");
                    if (last != null) at = time(last.optString("state"));
                    image = picture(id);
                } catch (Exception e) {
                    AppLog.w("camera shot " + id + ": " + e.getMessage());
                }
                final int index = i;
                final String n = name;
                final long t = at;
                final Bitmap b = image;
                MAIN.post(() -> {
                    Shot s = SHOTS.get(index);
                    if (n != null && !n.isEmpty()) s.name = n;
                    if (t > 0) s.at = t;
                    if (b != null) s.image = b;
                    changed.run();
                });
            }
            MAIN.post(() -> reading = false);
        }, "camera-shots").start();
    }

    private static HttpURLConnection open(String path) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(Config.HA_URL + path).openConnection();
        c.setRequestProperty("Authorization", "Bearer " + Config.HA_TOKEN);
        c.setConnectTimeout(6_000);
        c.setReadTimeout(20_000);
        return c;
    }

    private static JSONObject json(String path) throws Exception {
        HttpURLConnection c = open(path);
        try {
            if (c.getResponseCode() != 200) return null;
            return new JSONObject(new String(bytes(c.getInputStream()), "UTF-8"));
        } finally {
            c.disconnect();
        }
    }

    /** The camera's picture, scaled down to card size. */
    private static Bitmap picture(String id) throws IOException {
        HttpURLConnection c = open("/api/camera_proxy/" + id);
        try {
            if (c.getResponseCode() != 200) return null;
            byte[] b = bytes(c.getInputStream());
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(b, 0, b.length, o);
            o.inJustDecodeBounds = false;
            o.inSampleSize = 1;
            while (o.outWidth / (o.inSampleSize * 2) >= THUMB_W) o.inSampleSize *= 2;
            Bitmap full = BitmapFactory.decodeByteArray(b, 0, b.length, o);
            if (full == null || full.getWidth() <= THUMB_W) return full;
            Bitmap small = Bitmap.createScaledBitmap(full, THUMB_W, Math.round(full.getHeight() * (THUMB_W / (float) full.getWidth())), true);
            if (small != full) full.recycle();
            return small;
        } finally {
            c.disconnect();
        }
    }

    private static byte[] bytes(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] b = new byte[16 * 1024];
        for (int n; (n = in.read(b)) > 0; ) out.write(b, 0, n);
        in.close();
        return out.toByteArray();
    }

    /** Home Assistant's "2026-10-25T15:31:11+00:00" (or with fractions of a second), or 0. */
    private static long time(String s) {
        try {
            java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US);
            return f.parse(s.replaceFirst("\\.\\d+", "")).getTime();
        } catch (Exception e) {
            return 0;
        }
    }

    /** "camera.backyard_1_live_view" → "Backyard 1". */
    static String fallbackName(String id) {
        String s = id.replaceFirst("^camera\\.", "").replaceFirst("_live_view$", "").replace('_', ' ');
        return s.isEmpty() ? id : s.substring(0, 1).toUpperCase(Locale.ROOT) + s.substring(1);
    }

    /** When, for the card: "8:16 AM" today, "Yesterday 6:07 PM", else "Oct 23". */
    static String when(long at) {
        if (at <= 0) return "";
        java.util.Calendar now = java.util.Calendar.getInstance(), then = java.util.Calendar.getInstance();
        then.setTimeInMillis(at);
        String clock = Ui.clock(at);
        if (now.get(java.util.Calendar.YEAR) == then.get(java.util.Calendar.YEAR)) {
            int days = now.get(java.util.Calendar.DAY_OF_YEAR) - then.get(java.util.Calendar.DAY_OF_YEAR);
            if (days == 0) return clock;
            if (days == 1) return Ui.trf("Yesterday {time}", "time", clock);
        }
        return new java.text.SimpleDateFormat("MMM d", Locale.getDefault()).format(new java.util.Date(at));
    }
}
