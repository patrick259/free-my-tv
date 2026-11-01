package io.freemytv;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.PlaybackState;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;
import android.text.format.DateFormat;
import android.view.KeyEvent;
import android.view.View;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Calendar;
import java.util.Date;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The home screen left alone for a while (HomeView, SCENE_IDLE_MS): full-screen nature scenes taking turns, each with
 * the big glass clock of the iPhone lock screen set *behind* its scenery, as on tvOS. A scene is three layers
 * (assets/scenes, from couch-tv, made for it with an image model and free to use): the photo, the clock and the date
 * over it (the digits filled with a blurred, brightened copy of the photo, so they read as frosted glass), and the
 * scenery cut out of the photo with the sky transparent, drawn last so the peaks and hills cover the bottom of the
 * digits. With music on (Spotify here or on a speaker group, else the TV's music app), bottom left: the cover, the
 * song, its progress and what's up next. Any key goes home again, and is used up.
 */
public class DepthClockActivity extends Activity {
    /** One scene and where its clock goes, in its 1920 × 1080 frame (couch-tv's app.css). */
    private static final class Scene {
        final String id;
        final boolean front, dark;
        final float top, digits, dateBase, left;   // left < 0: centered

        Scene(String id, boolean front, float top, float digits, float dateBase, float left, boolean dark) {
            this.id = id;
            this.front = front;
            this.top = top;
            this.digits = digits;
            this.dateBase = dateBase;
            this.left = left;
            this.dark = dark;
        }
    }

    private static final Scene[] SCENES = {
        new Scene("night", true, 150, 300, 127, -1, false),     // the lake, the clock behind the peak
        new Scene("golden", true, 152, 292, 137, -1, false),    // the volcano's tip rising into the digits
        new Scene("water", false, 268, 315, 247, -1, false),    // the clock sitting on the water
        new Scene("fog", true, 172, 352, 147, 96, true),        // big and dark on the left, sinking into the mist
        new Scene("dawn", true, 230, 300, 207, -1, false),      // low, the ridges crossing it
    };
    private static final long SCENE_MS = 45_000, FADE_MS = 2_500, MUSIC_MS = 8_000;
    /** The scene shown last, so the next visit goes on with the one after it. */
    private static int lastShown = -1;

    private SceneView view;

    static void open(Context c) {
        c.startActivity(new Intent(c, DepthClockActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    /** Whether the scenes' pictures are in the app (the idle switch is pointless without them). */
    static boolean available(Context c) {
        try (InputStream in = c.getAssets().open("scenes/" + SCENES[0].id + ".jpg")) {
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        Ui.init(this);
        view = new SceneView(this);
        view.setAlpha(0);
        setContentView(view);
        view.animate().alpha(1).setDuration(1200).start();
    }

    @Override public boolean dispatchKeyEvent(KeyEvent e) {
        int code = e.getKeyCode();
        // The volume and the TV's own keys still work; anything else only wakes the home screen.
        if (code == KeyEvent.KEYCODE_VOLUME_UP || code == KeyEvent.KEYCODE_VOLUME_DOWN || code == KeyEvent.KEYCODE_VOLUME_MUTE
                || code == KeyEvent.KEYCODE_POWER || code == KeyEvent.KEYCODE_HOME) return super.dispatchKeyEvent(e);
        if (e.getAction() == KeyEvent.ACTION_UP) finish();
        return true;
    }

    @Override protected void onStop() {
        super.onStop();
        finish();   // never left behind the home screen, to come back later with a stale time
    }

    @Override protected void onDestroy() {
        view.stop();
        super.onDestroy();
    }

    @Override public void finish() {
        super.finish();
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
    }

    /** A scene's pictures, decoded. */
    private static final class Layers {
        final Scene scene;
        Bitmap photo, glass, front;
        BitmapShader glassShader;

        Layers(Context c, Scene s) {
            scene = s;
            photo = load(c, s.id + ".jpg", 1, Bitmap.Config.RGB_565);
            glass = load(c, s.id + "-glass.jpg", 2, Bitmap.Config.RGB_565);     // blurred: half the size is plenty
            if (s.front) front = load(c, s.id + "-fg.webp", 1, Bitmap.Config.ARGB_8888);
            if (glass != null) glassShader = new BitmapShader(glass, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        }

        private static Bitmap load(Context c, String name, int sample, Bitmap.Config config) {
            try (InputStream in = c.getAssets().open("scenes/" + name)) {
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inSampleSize = sample;
                o.inPreferredConfig = config;
                return BitmapFactory.decodeStream(in, null, o);
            } catch (Exception e) {
                AppLog.w("scene " + name + ": " + e.getMessage());
                return null;
            }
        }
    }

    /** What's playing, from Spotify or the TV's music app. */
    private static final class Song {
        String title, artist, where, nextTitle, nextArtist, imageKey;
        Bitmap cover;
        boolean playing;
        long progress, duration, readAt;
    }

    static final class SceneView extends View {
        private final Handler main = new Handler(Looper.getMainLooper());
        private final ExecutorService io = Executors.newSingleThreadExecutor();
        private final Paint image = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
        private final Paint digits = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final Paint shine = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint date = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final Paint title = new Paint(Paint.ANTI_ALIAS_FLAG), small = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final ColorMatrixColorFilter darken;
        private final Rect frame = new Rect(), bounds = new Rect();
        private final RectF r = new RectF();
        private final Matrix m = new Matrix();
        private Layers now, next;
        private int index;
        private long fadeAt;
        private Song song;
        private float songShown, songTarget;
        private long songStep;

        private final Runnable turn = new Runnable() {
            @Override public void run() {
                int at = (index + 1) % SCENES.length;
                io.execute(() -> {
                    Layers l = new Layers(getContext(), SCENES[at]);
                    main.post(() -> {
                        next = l;
                        index = at;
                        lastShown = at;
                        fadeAt = SystemClock.uptimeMillis();
                        invalidate();
                    });
                });
                main.postDelayed(this, SCENE_MS);
            }
        };
        /** Redraws for the minute, the song's progress, and while something fades. */
        private final Runnable tick = new Runnable() {
            @Override public void run() {
                invalidate();
                main.postDelayed(this, song != null && song.playing ? 1000 : 60_000 - System.currentTimeMillis() % 60_000 + 50);
            }
        };
        private final Runnable music = new Runnable() {
            @Override public void run() {
                io.execute(() -> {
                    Song s = readSong();
                    main.post(() -> setSong(s));
                });
                main.postDelayed(this, MUSIC_MS);
            }
        };

        SceneView(Context c) {
            super(c);
            index = (lastShown + 1) % SCENES.length;
            lastShown = index;
            now = new Layers(c, SCENES[index]);
            for (Paint p : new Paint[] {digits, rim}) {
                p.setTypeface(Ui.semibold);
                p.setTextScaleX(0.74f);      // tall and narrow, as the lock screen's clock
            }
            rim.setStyle(Paint.Style.STROKE);
            shine.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SRC_ATOP));
            date.setTypeface(Ui.medium);
            title.setTypeface(Ui.semibold);
            title.setColor(0xFFFFFFFF);
            small.setTypeface(Ui.medium);
            ColorMatrix dim = new ColorMatrix();
            dim.setSaturation(0.8f);
            ColorMatrix dark = new ColorMatrix();
            dark.setScale(0.46f, 0.46f, 0.46f, 1);
            dim.postConcat(dark);
            darken = new ColorMatrixColorFilter(dim);
            main.post(tick);
            main.postDelayed(turn, SCENE_MS);
            main.post(music);
        }

        void stop() {
            main.removeCallbacksAndMessages(null);
            io.shutdownNow();
        }

        // MARK: - Music

        private Song readSong() {
            Context c = getContext();
            Song s = new Song();
            try {
                if (Spotify.on()) {
                    Spotify.Now n = Spotify.now(c);
                    if (n != null) {
                        s.title = n.title;
                        s.artist = n.artist;
                        s.playing = n.playing;
                        s.progress = n.progress;
                        s.duration = n.duration;
                        s.readAt = n.readAt;
                        s.nextTitle = n.nextTitle;
                        s.nextArtist = n.nextArtist;
                        if (!n.device.isEmpty() && !n.device.equalsIgnoreCase(Spotify.tvName(c))) s.where = n.device;
                        s.imageKey = n.image;
                        s.cover = cover(n.image);
                        return s;
                    }
                }
            } catch (Exception e) {
                AppLog.w("scene music: " + e.getMessage());
            }
            // Another music app on the TV: what its session says (no queue).
            MediaController mc = Music.session(c);
            MediaMetadata md = mc == null ? null : mc.getMetadata();
            if (md == null) return null;
            PlaybackState ps = mc.getPlaybackState();
            s.title = md.getString(MediaMetadata.METADATA_KEY_TITLE);
            s.artist = md.getString(MediaMetadata.METADATA_KEY_ARTIST);
            s.playing = ps != null && ps.getState() == PlaybackState.STATE_PLAYING;
            s.progress = ps == null ? 0 : ps.getPosition();
            s.duration = md.getLong(MediaMetadata.METADATA_KEY_DURATION);
            s.readAt = SystemClock.uptimeMillis();
            s.cover = md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
            if (s.cover == null) s.cover = md.getBitmap(MediaMetadata.METADATA_KEY_ART);
            s.imageKey = s.title;
            return s;
        }

        private Bitmap cover(String url) {
            if (url == null) return null;
            if (song != null && url.equals(song.imageKey) && song.cover != null) return song.cover;
            try {
                HttpURLConnection h = (HttpURLConnection) new URL(url).openConnection();
                h.setConnectTimeout(6_000);
                h.setReadTimeout(10_000);
                try (InputStream in = h.getInputStream()) {
                    return BitmapFactory.decodeStream(in);
                } finally {
                    h.disconnect();
                }
            } catch (Exception e) {
                return null;
            }
        }

        private void setSong(Song s) {
            boolean show = s != null && s.playing && s.title != null;
            if (show) song = s;
            songTarget = show ? 1 : 0;
            songStep = SystemClock.uptimeMillis();
            main.removeCallbacks(tick);
            main.post(tick);
        }

        // MARK: - Drawing

        @Override protected void onDraw(Canvas c) {
            int w = getWidth(), h = getHeight();
            c.drawColor(0xFF0B0B0F);
            long t = SystemClock.uptimeMillis();
            drawScene(c, now, w, h);
            boolean moving = false;
            if (next != null) {
                float f = Math.min(1, (t - fadeAt) / (float) FADE_MS);
                f = f * f * (3 - 2 * f);
                int layer = c.saveLayerAlpha(0, 0, w, h, Math.round(255 * f));
                drawScene(c, next, w, h);
                c.restoreToCount(layer);
                if (f >= 1) {
                    now = next;
                    next = null;
                } else {
                    moving = true;
                }
            }
            // The song's card fades in and out over half a second.
            float step = (t - songStep) / 500f;
            songShown = songTarget > songShown ? Math.min(songTarget, songShown + step) : Math.max(songTarget, songShown - step);
            songStep = t;
            if (songShown != songTarget) moving = true;
            if (songShown > 0.01f && song != null) drawSong(c, w, h, songShown);
            if (moving) postInvalidateOnAnimation();
        }

        private void drawScene(Canvas c, Layers l, int w, int h) {
            if (l.photo == null) return;
            Scene s = l.scene;
            // The scene covers the screen; everything else is placed in its 1920 × 1080 frame.
            float sc = Math.max(w / (float) l.photo.getWidth(), h / (float) l.photo.getHeight());
            float ox = (w - l.photo.getWidth() * sc) / 2, oy = (h - l.photo.getHeight() * sc) / 2;
            float k = sc * l.photo.getWidth() / 1920f;
            frame.set(Math.round(ox), Math.round(oy), Math.round(ox + l.photo.getWidth() * sc), Math.round(oy + l.photo.getHeight() * sc));
            c.drawBitmap(l.photo, null, frame, image);

            Paint.Align align = s.left < 0 ? Paint.Align.CENTER : Paint.Align.LEFT;
            float x = s.left < 0 ? w / 2f : ox + s.left * k;

            // The date and the weather, over the clock.
            Context ctx = getContext();
            String line = DateFormat.format(DateFormat.getBestDateTimePattern(java.util.Locale.getDefault(), "EEEEMMMMd"), new Date()).toString();
            HomeData.Weather wx = HomeData.get(ctx).weather;
            if (!Double.isNaN(wx.temp)) line += "  ·  " + HomeData.degrees(wx.temp) + " " + HomeData.condition(wx.code, wx.isDay);
            date.setTextAlign(align);
            date.setTextSize(36 * k);
            date.setColor(s.dark ? 0xFF16201D : 0xF2FFFFFF);
            if (s.dark) date.clearShadowLayer();
            else date.setShadowLayer(18 * k, 0, 2 * k, 0x59000000);
            c.drawText(line, s.left < 0 ? x : x + 8 * k, oy + s.dateBase * k, date);

            // The clock: the hour and minutes, filled with the frosted copy, a light rim, brighter toward the top.
            Calendar cal = Calendar.getInstance();
            boolean h24 = DateFormat.is24HourFormat(ctx);
            int hour = cal.get(h24 ? Calendar.HOUR_OF_DAY : Calendar.HOUR);
            if (!h24 && hour == 0) hour = 12;
            String time = hour + ":" + String.format(java.util.Locale.ROOT, "%02d", cal.get(Calendar.MINUTE));
            digits.setTextAlign(align);
            rim.setTextAlign(align);
            digits.setTextSize(100);
            digits.getTextBounds("0123456789", 0, 10, bounds);
            float size = 100 * s.digits * k / bounds.height();
            digits.setTextSize(size);
            rim.setTextSize(size);
            rim.setStrokeWidth(2.2f * k);
            rim.setColor(s.dark ? 0x99FFFFFF : 0x66FFFFFF);
            digits.getTextBounds(time, 0, time.length(), bounds);
            float base = oy + s.top * k - bounds.top;
            int layer = c.saveLayer(0, 0, w, h, null);
            if (l.glassShader != null) {
                m.setScale(frame.width() / (float) l.glass.getWidth(), frame.height() / (float) l.glass.getHeight());
                m.postTranslate(frame.left, frame.top);
                l.glassShader.setLocalMatrix(m);
                digits.setShader(l.glassShader);
                digits.setColorFilter(s.dark ? darken : null);
                digits.setAlpha(s.dark ? 205 : 255);
            } else {
                digits.setShader(null);
                digits.setColor(0xE6FFFFFF);
            }
            c.drawText(time, x, base, digits);
            if (!s.dark) {
                float top = base + bounds.top;
                shine.setShader(new LinearGradient(0, top, 0, top + bounds.height(), new int[] {0x8CFFFFFF, 0x1FFFFFFF, 0x00FFFFFF},
                        new float[] {0, 0.45f, 1}, Shader.TileMode.CLAMP));
                c.drawRect(0, top, w, top + bounds.height(), shine);
            }
            c.restoreToCount(layer);
            c.drawText(time, x, base, rim);

            // The scenery in front of the clock.
            if (l.front != null) c.drawBitmap(l.front, null, frame, image);
        }

        /** Bottom left: the cover, the song and artist (and where it plays, if not here), its progress, up next. */
        private void drawSong(Canvas c, int w, int h, float a) {
            float k = w / 1920f;
            Song s = song;
            fill.setShader(new LinearGradient(0, h * 0.5f, 0, h, 0x00000000, 0xB3000000, Shader.TileMode.CLAMP));
            fill.setAlpha(Math.round(255 * a));
            c.drawRect(0, h * 0.5f, w, h, fill);
            fill.setShader(null);

            float side = 210 * k, left = 80 * k, top = h - 80 * k - side;
            r.set(left, top, left + side, top + side);
            int alpha = Math.round(255 * a);
            if (s.cover != null) {
                BitmapShader cs = new BitmapShader(s.cover, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
                float sc = Math.max(side / s.cover.getWidth(), side / s.cover.getHeight());
                m.setScale(sc, sc);
                m.postTranslate(left, top);
                cs.setLocalMatrix(m);
                fill.setShader(cs);
                fill.setAlpha(alpha);
                c.drawRoundRect(r, 18 * k, 18 * k, fill);
                fill.setShader(null);
            } else {
                fill.setColor(0x33FFFFFF);
                fill.setAlpha(Math.round(0x33 * a));
                c.drawRoundRect(r, 18 * k, 18 * k, fill);
            }

            float tx = left + side + 36 * k, room = 760 * k;
            title.setTextSize(46 * k);
            title.setAlpha(alpha);
            c.drawText(TextUtils.ellipsize(s.title, new android.text.TextPaint(title), room, TextUtils.TruncateAt.END).toString(), tx, top + 58 * k, title);
            small.setTextSize(30 * k);
            small.setColor(0xD9FFFFFF);
            small.setAlpha(Math.round(0xD9 * a));
            String by = s.artist == null ? "" : s.artist;
            if (s.where != null) by += (by.isEmpty() ? "" : "  ·  ") + s.where;
            c.drawText(TextUtils.ellipsize(by, new android.text.TextPaint(small), room, TextUtils.TruncateAt.END).toString(), tx, top + 104 * k, small);

            // Progress, moving on between reads.
            if (s.duration > 0) {
                long pos = Math.min(s.duration, s.progress + (s.playing ? SystemClock.uptimeMillis() - s.readAt : 0));
                float bw = 520 * k, by0 = top + 132 * k, bh = 6 * k;
                fill.setColor(0x40FFFFFF);
                fill.setAlpha(Math.round(0x40 * a));
                r.set(tx, by0, tx + bw, by0 + bh);
                c.drawRoundRect(r, bh / 2, bh / 2, fill);
                fill.setColor(0xFFFFFFFF);
                fill.setAlpha(alpha);
                r.set(tx, by0, tx + bw * pos / s.duration, by0 + bh);
                c.drawRoundRect(r, bh / 2, bh / 2, fill);
            }

            if (s.nextTitle != null && !s.nextTitle.isEmpty()) {
                small.setTextSize(20 * k);
                small.setColor(0x99FFFFFF);
                small.setAlpha(Math.round(0x99 * a));
                String label = Ui.tr("UP NEXT");
                float y = top + side - 6 * k;
                c.drawText(label, tx, y, small);
                float lx = tx + small.measureText(label) + 14 * k;
                small.setTextSize(26 * k);
                small.setColor(0xCCFFFFFF);
                small.setAlpha(Math.round(0xCC * a));
                String nx = s.nextTitle + (s.nextArtist == null || s.nextArtist.isEmpty() ? "" : " — " + s.nextArtist);
                c.drawText(TextUtils.ellipsize(nx, new android.text.TextPaint(small), room - (lx - tx), TextUtils.TruncateAt.END).toString(), lx, y, small);
            }
        }
    }
}
