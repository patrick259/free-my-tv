package io.freemytv;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.PlaybackState;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;

import java.util.ArrayList;
import java.util.List;

/**
 * Now Playing: what the music app is playing, our way instead of its own screen. The cover large on the left, the
 * title, the artist and the time played below it; the cover, blurred, all over the screen behind. On the right a glass
 * card that turns every CARD_MS between the weather, your teams' games and the big nights out nearby (Events). From
 * the drawer, or by itself when music plays and the home screen is left alone (HomeView). OK plays or pauses, ← → the
 * previous or next song, ↑ ↓ the next card, Back home. The screen stays on while it shows.
 */
public class MusicActivity extends Activity {
    private MusicView view;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        Ui.init(this);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        view = new MusicView(this);
        setContentView(view);
    }

    @Override protected void onResume() {
        super.onResume();
        view.start();
    }

    @Override protected void onPause() {
        view.stop();
        super.onPause();
    }

    @Override public boolean dispatchKeyEvent(KeyEvent e) {
        return view.onKey(e) || super.dispatchKeyEvent(e);
    }

    static final class MusicView extends View {
        private static final long CARD_MS = 12_000, FADE_MS = 700;
        private static final int WEATHER = 0, SCORES = 1, EVENTS = 2;

        private final Activity activity;
        private final Handler main = new Handler(Looper.getMainLooper());
        private final Sports sports;
        private MediaController controller;
        private String title = "", artist = "", album = "", artKey = "";
        private Bitmap art, sharp, soft;
        private BitmapShader artShader, sharpShader, softShader;
        private long duration, position, positionAt;
        private float speed;
        private boolean playing;
        private int card, cardFrom = -1;
        private long cardAt;
        private float k = 1;
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
        private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Matrix m = new Matrix();
        private final RectF r = new RectF();
        private Paint titleP, artistP, albumP, timeP, clockP, headP, bigP, rowP, rowDim, smallP;

        private final MediaController.Callback callback = new MediaController.Callback() {
            @Override public void onMetadataChanged(MediaMetadata md) { read(); }
            @Override public void onPlaybackStateChanged(PlaybackState s) { read(); }
            @Override public void onSessionDestroyed() { connect(); }
        };

        private final Runnable tick = new Runnable() {
            @Override public void run() {
                if (controller == null) connect();
                long up = SystemClock.uptimeMillis();
                if (up - cardAt >= CARD_MS) nextCard(1);
                HomeData.get(getContext()).refreshIfStale();
                sports.refreshIfStale();
                Events.get(getContext(), () -> main.post(MusicView.this::invalidate));
                invalidate();
                main.postDelayed(this, 1000);
            }
        };

        MusicView(Activity a) {
            super(a);
            activity = a;
            sports = new Sports(a, this::invalidate);
            setFocusable(true);
            rim.setStyle(Paint.Style.STROKE);
        }

        void start() {
            connect();
            cardAt = SystemClock.uptimeMillis();
            main.removeCallbacks(tick);
            main.post(tick);
        }

        void stop() {
            main.removeCallbacksAndMessages(null);
            if (controller != null) controller.unregisterCallback(callback);
            controller = null;
        }

        private void connect() {
            if (controller != null) controller.unregisterCallback(callback);
            controller = Music.session(getContext());
            if (controller != null) controller.registerCallback(callback, main);
            read();
        }

        /** The song, the cover and where it is, from the session. */
        private void read() {
            MediaMetadata md = controller == null ? null : controller.getMetadata();
            PlaybackState s = controller == null ? null : controller.getPlaybackState();
            title = md == null ? "" : text(md, MediaMetadata.METADATA_KEY_TITLE);
            artist = md == null ? "" : first(text(md, MediaMetadata.METADATA_KEY_ARTIST), text(md, MediaMetadata.METADATA_KEY_ALBUM_ARTIST));
            album = md == null ? "" : text(md, MediaMetadata.METADATA_KEY_ALBUM);
            duration = md == null ? 0 : md.getLong(MediaMetadata.METADATA_KEY_DURATION);
            playing = s != null && s.getState() == PlaybackState.STATE_PLAYING;
            position = s == null ? 0 : s.getPosition();
            positionAt = s == null ? 0 : s.getLastPositionUpdateTime();
            speed = s == null ? 0 : s.getPlaybackSpeed();
            String key = title + "|" + artist + "|" + album;
            if (!key.equals(artKey)) {
                artKey = key;
                // The album's own picture; else its address (some apps send only their icon as a picture), else the icon.
                Bitmap b = md == null ? null : cover(md);
                String uri = md == null ? "" : first(text(md, MediaMetadata.METADATA_KEY_ALBUM_ART_URI), text(md, MediaMetadata.METADATA_KEY_ART_URI));
                Bitmap icon = md == null ? null : md.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON);
                setArt(b != null ? b : uri.startsWith("http") ? null : icon);
                if (b == null && uri.startsWith("http")) new Thread(() -> {
                    Bitmap got = Sports.load(uri);
                    main.post(() -> { if (key.equals(artKey)) setArt(got != null ? got : icon); });
                }, "cover").start();
            }
            invalidate();
        }

        private static Bitmap cover(MediaMetadata md) {
            for (String k : new String[] {MediaMetadata.METADATA_KEY_ALBUM_ART, MediaMetadata.METADATA_KEY_ART}) {
                Bitmap b = md.getBitmap(k);
                if (b != null) return b;
            }
            return null;
        }

        /** The cover, and the two blurred copies behind: fairly sharp for the screen, soft and bright for the glass. */
        private void setArt(Bitmap b) {
            art = b;
            if (b == null) {
                artShader = sharpShader = softShader = null;
                sharp = soft = null;
                return;
            }
            artShader = new BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            Bitmap small = Bitmap.createScaledBitmap(b, 96, Math.max(1, Math.round(96f * b.getHeight() / b.getWidth())), true);
            sharp = Glass.soften(small.copy(Bitmap.Config.ARGB_8888, true));
            soft = Glass.soften(Bitmap.createScaledBitmap(b, 48, Math.max(1, Math.round(48f * b.getHeight() / b.getWidth())), true));
            sharpShader = new BitmapShader(sharp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            softShader = new BitmapShader(soft, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        }

        private static String text(MediaMetadata md, String key) {
            CharSequence t = md.getText(key);
            return t == null ? "" : t.toString().trim();
        }

        private static String first(String a, String b) {
            return a.isEmpty() ? b : a;
        }

        boolean onKey(KeyEvent e) {
            if (e.getAction() != KeyEvent.ACTION_DOWN) {
                return e.getKeyCode() != KeyEvent.KEYCODE_BACK && e.getKeyCode() != KeyEvent.KEYCODE_HOME
                        && !e.isSystem();
            }
            MediaController.TransportControls t = controller == null ? null : controller.getTransportControls();
            switch (e.getKeyCode()) {
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                    if (t != null) { if (playing) t.pause(); else t.play(); }
                    return true;
                case KeyEvent.KEYCODE_DPAD_LEFT:
                    if (t != null) t.skipToPrevious();
                    return true;
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    if (t != null) t.skipToNext();
                    return true;
                case KeyEvent.KEYCODE_DPAD_UP:
                    nextCard(-1);
                    return true;
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    nextCard(1);
                    return true;
                case KeyEvent.KEYCODE_BACK:
                    activity.finish();
                    return true;
                default:
                    return false;
            }
        }

        /** The cards there is something to show on, in order. */
        private List<Integer> cards() {
            List<Integer> out = new ArrayList<>();
            if (!Double.isNaN(HomeData.get(getContext()).weather.temp)) out.add(WEATHER);
            if (!games().isEmpty()) out.add(SCORES);
            if (!Events.get(getContext(), null).isEmpty()) out.add(EVENTS);
            return out;
        }

        private void nextCard(int step) {
            List<Integer> list = cards();
            if (list.isEmpty()) return;
            int at = list.indexOf(card);
            int next = list.get(((at < 0 ? 0 : at + step) % list.size() + list.size()) % list.size());
            if (next != card || at < 0) {
                cardFrom = card;
                card = next;
            }
            cardAt = SystemClock.uptimeMillis();
            postInvalidateOnAnimation();
        }

        /** Your teams' games and the big ones, today: live first. */
        private List<Sports.Game> games() {
            List<Sports.Game> out = new ArrayList<>();
            for (Recs.Pick p : sports.now()) {
                Sports.Game g = p.game;
                if (g == null || g.slate != null || "F1".equals(g.league)) continue;
                if (g.favorite || "in".equals(g.state) || g.weight >= Sports.POSTSEASON) out.add(g);
                if (out.size() == 4) break;
            }
            return out;
        }

        @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
            k = w / 1920f;
            titleP = Ui.text(Ui.displaySemibold, 54 * k, 0xFFFFFFFF);
            artistP = Ui.text(Ui.medium, 34 * k, 0xD9FFFFFF);
            albumP = Ui.text(Ui.regular, 26 * k, 0x99FFFFFF);
            timeP = Ui.text(Ui.medium, 22 * k, 0x99FFFFFF);
            clockP = Ui.text(Ui.medium, 34 * k, 0xE6FFFFFF);
            headP = Ui.text(Ui.semibold, 21 * k, 0xB3FFFFFF);
            headP.setLetterSpacing(0.08f);
            bigP = Ui.text(Ui.thin, 140 * k, 0xFFFFFFFF);
            rowP = Ui.text(Ui.semibold, 32 * k, 0xFFFFFFFF);
            rowDim = Ui.text(Ui.medium, 24 * k, 0xA6FFFFFF);
            smallP = Ui.text(Ui.medium, 26 * k, 0xD9FFFFFF);
        }

        @Override protected void onDraw(Canvas c) {
            if (titleP == null) return;
            int w = getWidth(), h = getHeight();
            // The cover, blurred, over the whole screen, darkened for the text.
            if (sharpShader != null) {
                cover(m, sharp, w, h, 0, 0);
                sharpShader.setLocalMatrix(m);
                fill.setShader(sharpShader);
                c.drawRect(0, 0, w, h, fill);
                fill.setShader(null);
                c.drawColor(0x8C000000);
            } else {
                fill.setShader(new LinearGradient(0, 0, w, h, 0xFF1B1530, 0xFF07070C, Shader.TileMode.CLAMP));
                c.drawRect(0, 0, w, h, fill);
                fill.setShader(null);
            }

            // The clock, top right.
            String clock = Ui.clock(System.currentTimeMillis());
            c.drawText(clock, 1800 * k - clockP.measureText(clock), 110 * k, clockP);

            // The cover, with its shadow.
            r.set(140 * k, 170 * k, 760 * k, 790 * k);
            fill.setColor(0x66000000);
            c.drawRoundRect(r.left, r.top + 24 * k, r.right, r.bottom + 24 * k, 30 * k, 30 * k, fill);
            if (artShader != null) {
                m.setScale(r.width() / art.getWidth(), r.height() / art.getHeight());
                m.postTranslate(r.left, r.top);
                artShader.setLocalMatrix(m);
                fill.setShader(artShader);
                c.drawRoundRect(r, 30 * k, 30 * k, fill);
                fill.setShader(null);
            } else {
                fill.setColor(0x33FFFFFF);
                c.drawRoundRect(r, 30 * k, 30 * k, fill);
                Symbols.draw(getContext(), c, "speaker", r.centerX(), r.centerY(), 180 * k, 0x99FFFFFF);
            }

            // The song.
            float x = 140 * k, tw = 620 * k;
            if (title.isEmpty()) {
                c.drawText(Ui.tr("Nothing playing"), x, 880 * k, titleP);
                c.drawText(Ui.tr("Play something in Spotify, then come back."), x, 930 * k, albumP);
            } else {
                c.drawText(fit(title, titleP, tw), x, 870 * k, titleP);
                c.drawText(fit(artist, artistP, tw), x, 920 * k, artistP);
                if (!album.isEmpty()) c.drawText(fit(album, albumP, tw), x, 960 * k, albumP);
                drawProgress(c, x, 1000 * k, tw);
            }

            // The card on the right, cross-fading from the last one.
            float e = Math.min(1, (SystemClock.uptimeMillis() - cardAt) / (float) FADE_MS);
            r.set(900 * k, 170 * k, 1800 * k, 790 * k);
            glass(c, r, 36 * k);
            List<Integer> list = cards();
            if (list.isEmpty()) {
                c.drawText(Ui.tr("Weather, scores and what's on nearby show here."), r.left + 50 * k, r.centerY(), smallP);
            } else {
                if (!list.contains(card)) card = list.get(0);
                if (e < 1 && cardFrom >= 0 && cardFrom != card) drawCard(c, cardFrom, 1 - e);
                drawCard(c, card, e);
                if (e < 1) postInvalidateOnAnimation();
            }
        }

        private void drawProgress(Canvas c, float x, float y, float width) {
            long pos = position + (playing ? (long) ((SystemClock.elapsedRealtime() - positionAt) * speed) : 0);
            if (duration > 0) pos = Math.max(0, Math.min(duration, pos));
            float f = duration > 0 ? pos / (float) duration : 0;
            fill.setColor(0x40FFFFFF);
            c.drawRoundRect(x, y, x + width, y + 8 * k, 4 * k, 4 * k, fill);
            fill.setColor(0xF2FFFFFF);
            c.drawRoundRect(x, y, x + Math.max(8 * k, width * f), y + 8 * k, 4 * k, 4 * k, fill);
            String left = time(pos), right = duration > 0 ? "−" + time(duration - pos) : "";
            c.drawText(left, x, y + 44 * k, timeP);
            c.drawText(right, x + width - timeP.measureText(right), y + 44 * k, timeP);
            // Paused: two bars between the times.
            if (!playing) {
                fill.setColor(0xCCFFFFFF);
                float cx = x + width / 2, top = y + 22 * k;
                c.drawRoundRect(cx - 14 * k, top, cx - 4 * k, top + 26 * k, 3 * k, 3 * k, fill);
                c.drawRoundRect(cx + 4 * k, top, cx + 14 * k, top + 26 * k, 3 * k, 3 * k, fill);
            }
        }

        private void drawCard(Canvas c, int which, float alpha) {
            if (alpha <= 0.01f) return;
            int a = Math.round(255 * alpha);
            float x = r.left + 56 * k, top = r.top + 76 * k, right = r.right - 56 * k;
            Paint[] ps = {headP, bigP, rowP, rowDim, smallP};
            int[] base = new int[ps.length];
            for (int i = 0; i < ps.length; i++) {
                base[i] = ps[i].getAlpha();
                ps[i].setAlpha(Math.round(base[i] * alpha));
            }
            if (which == WEATHER) {
                HomeData.Weather wx = HomeData.get(getContext()).weather;
                c.drawText(Ui.tr("WEATHER") + "  ·  " + Prefs.city().toUpperCase(Ui.locale()), x, top, headP);
                Symbols.draw(getContext(), c, Symbols.weather(wx.code, wx.isDay), x + 70 * k, top + 150 * k, 120 * k, Ui.alpha(0xFFFFFFFF, alpha));
                c.drawText(HomeData.degrees(wx.temp), x + 170 * k, top + 200 * k, bigP);
                c.drawText(HomeData.condition(wx.code, wx.isDay), x + 470 * k, top + 130 * k, rowP);
                c.drawText(HomeData.highLow(wx), x + 470 * k, top + 180 * k, rowDim);
                // The next five hours.
                int n = Math.min(5, wx.hours.size());
                float col = (right - x) / 5;
                for (int i = 0; i < n; i++) {
                    float cx = x + col * i + col / 2, y = top + 330 * k;
                    String hour = android.text.format.DateFormat.getTimeFormat(getContext()).format(new java.util.Date(wx.hours.get(i)));
                    c.drawText(hour, cx - rowDim.measureText(hour) / 2, y, rowDim);
                    Symbols.draw(getContext(), c, Symbols.weather(wx.hourCode.get(i), wx.hourDay.get(i)), cx, y + 60 * k, 44 * k, Ui.alpha(0xFFFFFFFF, alpha));
                    String t = HomeData.degrees(wx.hourTemp.get(i));
                    c.drawText(t, cx - smallP.measureText(t) / 2, y + 128 * k, smallP);
                }
            } else if (which == SCORES) {
                c.drawText(Ui.tr("SCORES"), x, top, headP);
                List<Sports.Game> games = games();
                for (int i = 0; i < games.size(); i++) {
                    Sports.Game g = games.get(i);
                    float y = top + (90 + i * 118) * k;
                    boolean pre = "pre".equals(g.state);
                    String away = first(nz(g.awayAbbr), g.away), home = first(nz(g.homeAbbr), g.home);
                    String line = pre ? away + "  at  " + home : away + "  " + g.awayScore + "  –  " + g.homeScore + "  " + home;
                    c.drawText(fit(line, rowP, right - x - 40 * k), x + ("in".equals(g.state) ? 30 * k : 0), y, rowP);
                    if ("in".equals(g.state)) {
                        fill.setColor(Ui.alpha(0xFFFF453A, alpha));
                        c.drawCircle(x + 9 * k, y - 11 * k, 8 * k, fill);
                    }
                    String detail = (g.league == null ? "" : g.league + "  ·  ") + when(g);
                    c.drawText(fit(detail, rowDim, right - x), x + ("in".equals(g.state) ? 30 * k : 0), y + 40 * k, rowDim);
                }
            } else {
                c.drawText(Ui.tr("COMING UP NEARBY"), x, top, headP);
                List<Events.Event> list = Events.get(getContext(), null);
                for (int i = 0; i < Math.min(4, list.size()); i++) {
                    Events.Event ev = list.get(i);
                    float y = top + (90 + i * 118) * k;
                    c.drawText(fit(ev.name, rowP, right - x), x, y, rowP);
                    c.drawText(fit(ev.when + "  ·  " + ev.venue, rowDim, right - x), x, y + 40 * k, rowDim);
                }
            }
            for (int i = 0; i < ps.length; i++) ps[i].setAlpha(base[i]);
        }

        /** Glass over the blurred cover: soft and brighter inside, with a magnified edge and a rim bright along the top. */
        private void glass(Canvas c, RectF r, float radius) {
            if (softShader != null) {
                cover(m, soft, getWidth(), getHeight(), 0, 0);
                softShader.setLocalMatrix(m);
                fill.setShader(softShader);
                c.drawRoundRect(r, radius, radius, fill);
                fill.setShader(null);
                fill.setColor(0x38000000);
                c.drawRoundRect(r, radius, radius, fill);
            }
            fill.setColor(0x1FFFFFFF);
            c.drawRoundRect(r, radius, radius, fill);
            rim.setStrokeWidth(2.5f * k);
            rim.setShader(new LinearGradient(0, r.top, 0, r.bottom, new int[] {0xB3FFFFFF, 0x26FFFFFF, 0x47FFFFFF},
                    new float[] {0, 0.55f, 1}, Shader.TileMode.CLAMP));
            c.drawRoundRect(r.left + 1, r.top + 1, r.right - 1, r.bottom - 1, radius, radius, rim);
            rim.setShader(null);
        }

        /** `image` covering a `w` × `h` screen. */
        private static void cover(Matrix m, Bitmap image, int w, int h, float x, float y) {
            float s = Math.max(w / (float) image.getWidth(), h / (float) image.getHeight());
            m.setScale(s, s);
            m.postTranslate((w - image.getWidth() * s) / 2 - x, (h - image.getHeight() * s) / 2 - y);
        }

        private static String fit(String s, Paint p, float width) {
            return TextUtils.ellipsize(s, new TextPaint(p), width, TextUtils.TruncateAt.END).toString();
        }

        /** Live: ESPN's clock ("Q3 4:12"); to come: today's or tomorrow's local time; over: Final. */
        private String when(Sports.Game g) {
            if ("in".equals(g.state)) return nz(g.detail);
            if ("post".equals(g.state)) return Ui.tr("Final");
            String time = android.text.format.DateFormat.getTimeFormat(getContext()).format(new java.util.Date(g.start));
            java.util.Calendar a = java.util.Calendar.getInstance(), b = java.util.Calendar.getInstance();
            b.setTimeInMillis(g.start);
            boolean today = a.get(java.util.Calendar.DAY_OF_YEAR) == b.get(java.util.Calendar.DAY_OF_YEAR);
            return (today ? Ui.tr("Today") : Ui.tr("Tomorrow")) + " " + time;
        }

        private static String nz(String s) {
            return s == null ? "" : s;
        }

        private static String time(long ms) {
            long s = Math.max(0, ms / 1000);
            return s / 60 + ":" + String.format(java.util.Locale.US, "%02d", s % 60);
        }
    }

    /** Opens it from anywhere (the drawer, the home screen when left alone with music on). */
    static void open(Context c) {
        c.startActivity(new android.content.Intent(c, MusicActivity.class).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
    }
}
