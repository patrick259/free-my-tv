package io.freemytv;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextPaint;
import android.text.TextUtils;
import android.util.LruCache;
import android.view.Choreographer;
import android.view.KeyEvent;
import android.view.View;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Music suggestions (MusicPicks): rows of square covers, music for right now, your favorites, your playlists, over the
 * focused cover blurred. At the top right, where it plays: this TV or the speaker group ("Everywhere"), ↑ from the
 * first row to change it. OK starts the playlist there through Spotify Connect; on the TV, Now Playing opens.
 */
public class MusicPicksActivity extends Activity {
    private PicksView view;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        Ui.init(this);
        view = new PicksView(this);
        setContentView(view);
    }

    @Override public boolean dispatchKeyEvent(KeyEvent e) {
        return view.onKey(e) || super.dispatchKeyEvent(e);
    }

    @Override protected void onDestroy() {
        view.stop();
        super.onDestroy();
    }

    static final class PicksView extends View implements Choreographer.FrameCallback {
        private static final float TOP = 340, PITCH = 380, CARD = 240, GAP = 34, LEFT = 80;
        private static final int SHOWN = 6;     // cards across, before a row scrolls

        private final Activity activity;
        private final Handler main = new Handler(Looper.getMainLooper());
        private final ExecutorService io = Executors.newFixedThreadPool(3);
        private final LruCache<String, Bitmap> images = new LruCache<String, Bitmap>(32 * 1024 * 1024) {
            @Override protected int sizeOf(String k, Bitmap b) { return b.getByteCount(); }
        };
        private final Set<String> loading = new HashSet<>();
        private List<MusicPicks.Row> rows = new ArrayList<>();
        private int[] cols = new int[0];
        private Spring[] scroll = new Spring[0];
        private final Spring rowAt = new Spring(0.42f, 0.9f, 0), focusPop = new Spring(0.3f, 0.8f, 0);
        private int row;                 // -1: the "plays on" choice
        private boolean everywhere, busy;
        private String message, status;
        private long lastNanos;
        private float k = 1;
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
        private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Matrix m = new Matrix();
        private final RectF r = new RectF();
        private Paint headP, titleP, metaP, rowP, capP, msgP, pillP;
        private Bitmap backdrop;
        private BitmapShader backdropShader;
        private String backdropFor = "";

        PicksView(Activity a) {
            super(a);
            activity = a;
            setFocusable(true);
            rim.setStyle(Paint.Style.STROKE);
            everywhere = Spotify.everywhere(a);
            message = Ui.tr("Picking music for right now…");
            if (!Spotify.on()) {
                message = Ui.tr("Suggestions need Spotify: see the README (spotify_client_id).");
                return;
            }
            io.execute(() -> {
                try {
                    List<MusicPicks.Row> got = MusicPicks.get(a);
                    main.post(() -> {
                        rows = got;
                        cols = new int[got.size()];
                        scroll = new Spring[got.size()];
                        for (int i = 0; i < scroll.length; i++) scroll[i] = new Spring(0.36f, 0.92f, 0);
                        message = got.isEmpty() ? Ui.tr("Spotify had nothing to suggest right now.") : null;
                        focusPop.snap(0);
                        focusPop.target = 1;
                        moved();
                    });
                } catch (Exception e) {
                    AppLog.w("music picks: " + e.getMessage());
                    main.post(() -> { message = Ui.trf("Couldn't reach Spotify: {error}", "error", String.valueOf(e.getMessage())); invalidate(); });
                }
            });
        }

        void stop() {
            Choreographer.getInstance().removeFrameCallback(this);
            io.shutdownNow();
        }

        private MusicPicks.Pick focused() {
            return rows.isEmpty() ? null : rows.get(Math.max(0, row)).picks.get(cols[Math.max(0, row)]);
        }

        private void moved() {
            rowAt.target = Math.max(0, row);
            for (int i = 0; i < rows.size(); i++) scroll[i].target = Math.max(0, Math.min(cols[i] - SHOWN + 2, rows.get(i).picks.size() - SHOWN));
            MusicPicks.Pick p = focused();
            if (p != null && p.image != null && !p.image.equals(backdropFor)) {
                backdropFor = p.image;
                Bitmap b = images.get(p.image);
                if (b != null) setBackdrop(b); else load(p.image);
            }
            lastNanos = 0;
            Choreographer.getInstance().removeFrameCallback(this);
            Choreographer.getInstance().postFrameCallback(this);
        }

        /** The focused cover, small and blurred, for behind everything. */
        private void setBackdrop(Bitmap b) {
            Bitmap small = Bitmap.createScaledBitmap(b, 96, Math.max(1, Math.round(96f * b.getHeight() / b.getWidth())), true);
            backdrop = Glass.soften(small.copy(Bitmap.Config.ARGB_8888, true));
            backdropShader = new BitmapShader(backdrop, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        }

        @Override public void doFrame(long nanos) {
            float dt = lastNanos == 0 ? 1 / 60f : Math.min(0.05f, (nanos - lastNanos) / 1e9f);
            lastNanos = nanos;
            boolean moving = rowAt.step(dt) | focusPop.step(dt);
            for (Spring s : scroll) moving |= s.step(dt);
            invalidate();
            if (moving) Choreographer.getInstance().postFrameCallback(this);
        }

        boolean onKey(KeyEvent e) {
            int code = e.getKeyCode();
            boolean ours = code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN || code == KeyEvent.KEYCODE_DPAD_LEFT
                    || code == KeyEvent.KEYCODE_DPAD_RIGHT || code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER;
            if (!ours) return false;
            if (e.getAction() != KeyEvent.ACTION_DOWN || rows.isEmpty() || busy) return true;
            int before = row, col = row >= 0 ? cols[row] : 0;
            if (row < 0) {
                switch (code) {
                    case KeyEvent.KEYCODE_DPAD_LEFT:
                    case KeyEvent.KEYCODE_DPAD_RIGHT:
                    case KeyEvent.KEYCODE_DPAD_CENTER:
                    case KeyEvent.KEYCODE_ENTER:
                        everywhere = code == KeyEvent.KEYCODE_DPAD_RIGHT || code != KeyEvent.KEYCODE_DPAD_LEFT && !everywhere;
                        Spotify.setEverywhere(getContext(), everywhere);
                        status = null;
                        invalidate();
                        return true;
                    case KeyEvent.KEYCODE_DPAD_DOWN: row = 0; break;
                    default: return true;
                }
            } else {
                switch (code) {
                    case KeyEvent.KEYCODE_DPAD_UP: row--; break;
                    case KeyEvent.KEYCODE_DPAD_DOWN: row = Math.min(rows.size() - 1, row + 1); break;
                    case KeyEvent.KEYCODE_DPAD_LEFT: cols[row] = Math.max(0, col - 1); break;
                    case KeyEvent.KEYCODE_DPAD_RIGHT: cols[row] = Math.min(rows.get(row).picks.size() - 1, col + 1); break;
                    default:
                        play(focused());
                        return true;
                }
            }
            if (row != before || row >= 0 && cols[row] != col) {
                status = null;
                focusPop.snap(0.4f);
                focusPop.target = 1;
                moved();
            }
            return true;
        }

        private void play(MusicPicks.Pick p) {
            if (p == null) return;
            busy = true;
            final boolean far = everywhere;
            status = Ui.trf("Starting on {device}…", "device", far ? Config.SPOTIFY_EVERYWHERE : Ui.tr("this TV"));
            invalidate();
            io.execute(() -> {
                String done, error = null;
                try {
                    done = Spotify.play(activity, p, far);
                } catch (Exception e) {
                    done = null;
                    error = e.getMessage();
                    AppLog.w("music picks: play: " + error);
                }
                final String device = done, why = error;
                main.post(() -> {
                    busy = false;
                    if (device == null) {
                        status = Ui.trf("Couldn't play: {error}", "error", String.valueOf(why));
                    } else if (far) {
                        status = Ui.trf("Playing on {device}", "device", device);
                    } else {
                        status = null;
                        MusicActivity.open(activity);
                    }
                    invalidate();
                });
            });
        }

        @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
            k = w / 1920f;
            headP = Ui.text(Ui.semibold, 24 * k, 0xB3FFFFFF);
            headP.setLetterSpacing(0.08f);
            titleP = Ui.text(Ui.displaySemibold, 58 * k, 0xFFFFFFFF);
            metaP = Ui.text(Ui.medium, 28 * k, 0xCCFFFFFF);
            rowP = Ui.text(Ui.semibold, 28 * k, 0xE6FFFFFF);
            capP = Ui.text(Ui.medium, 22 * k, 0xB3FFFFFF);
            msgP = Ui.text(Ui.medium, 30 * k, 0xCCFFFFFF);
            pillP = Ui.text(Ui.semibold, 24 * k, 0xFFFFFFFF);
        }

        @Override protected void onDraw(Canvas c) {
            if (headP == null) return;
            int w = getWidth(), h = getHeight();
            c.drawColor(0xFF0B0B0F);
            if (backdropShader != null) {
                float s = Math.max(w / (float) backdrop.getWidth(), h / (float) backdrop.getHeight());
                m.setScale(s, s);
                m.postTranslate((w - backdrop.getWidth() * s) / 2, (h - backdrop.getHeight() * s) / 2);
                backdropShader.setLocalMatrix(m);
                fill.setShader(backdropShader);
                c.drawRect(0, 0, w, h, fill);
            }
            fill.setShader(new LinearGradient(0, 0, 0, h, new int[] {0x80000000, 0xA6000000, 0xE6000000}, new float[] {0, 0.35f, 1},
                    Shader.TileMode.CLAMP));
            c.drawRect(0, 0, w, h, fill);
            fill.setShader(null);

            c.drawText(Ui.tr("MUSIC FOR NOW"), LEFT * k, 92 * k, headP);
            drawPills(c);
            if (message != null) {
                c.drawText(message, LEFT * k, 200 * k, msgP);
                return;
            }
            MusicPicks.Pick p = focused();
            if (p != null) {
                c.drawText(fit(p.name, titleP, 1500 * k), LEFT * k, 190 * k, titleP);
                c.drawText(fit(status != null ? status : p.reason, metaP, 1500 * k), LEFT * k, 240 * k, metaP);
            }
            for (int i = 0; i < rows.size(); i++) {
                float y = (TOP + (i - rowAt.value) * PITCH) * k;
                if (y < 170 * k || y > h) continue;
                float a = Math.max(0, Math.min(1, (y - 170 * k) / (160 * k)));
                drawRow(c, i, y, a);
            }
        }

        /** Where it plays, top right: the chosen one filled white; a ring round both while you're up there. */
        private void drawPills(Canvas c) {
            String[] names = {Ui.tr("This TV"), Config.SPOTIFY_EVERYWHERE};
            float pad = 28 * k, ph = 56 * k, gap = 14 * k, right = 1840 * k, top = 52 * k;
            float x = right;
            for (int i = names.length - 1; i >= 0; i--) {
                float pw = pillP.measureText(names[i]) + pad * 2;
                x -= pw;
                r.set(x, top, x + pw, top + ph);
                boolean chosen = (i == 1) == everywhere;
                fill.setColor(chosen ? 0xF2FFFFFF : 0x29FFFFFF);
                c.drawRoundRect(r, ph / 2, ph / 2, fill);
                if (row < 0) {
                    rim.setStrokeWidth(3 * k);
                    rim.setColor(chosen ? 0xFFFFFFFF : 0x80FFFFFF);
                    c.drawRoundRect(r.left - 5 * k, r.top - 5 * k, r.right + 5 * k, r.bottom + 5 * k, ph / 2 + 5 * k, ph / 2 + 5 * k, rim);
                }
                pillP.setColor(chosen ? 0xFF111114 : 0xE6FFFFFF);
                c.drawText(names[i], x + pad, top + ph / 2 + pillP.getTextSize() * 0.36f, pillP);
                x -= gap;
            }
            capP.setAlpha(0xB3);
            String label = Ui.tr("Plays on");
            c.drawText(label, x - capP.measureText(label) - 6 * k, top + ph / 2 + capP.getTextSize() * 0.36f, capP);
        }

        private void drawRow(Canvas c, int i, float y, float alpha) {
            MusicPicks.Row rw = rows.get(i);
            rowP.setAlpha(Math.round(0xE6 * alpha));
            c.drawText(rw.title, LEFT * k, y, rowP);
            float top = y + 30 * k, step = (CARD + GAP) * k;
            float x0 = LEFT * k - scroll[i].value * step;
            int focusedCol = i == row ? cols[i] : -1;
            for (int pass = 0; pass < 2; pass++) {
                for (int j = 0; j < rw.picks.size(); j++) {
                    if ((j == focusedCol) != (pass == 1)) continue;
                    float x = x0 + j * step;
                    if (x > getWidth() || x + CARD * k < 0) continue;
                    MusicPicks.Pick p = rw.picks.get(j);
                    float s = j == focusedCol ? 1 + 0.1f * focusPop.value : 1;
                    float cs = CARD * k * s, cx = x + CARD * k / 2, cy = top + CARD * k / 2;
                    r.set(cx - cs / 2, cy - cs / 2, cx + cs / 2, cy + cs / 2);
                    drawCard(c, p, r, j == focusedCol, alpha);
                    capP.setAlpha(Math.round((j == focusedCol ? 0xFF : 0x99) * alpha));
                    c.drawText(fit(p.name, capP, CARD * k), x, top + CARD * k + 44 * k, capP);
                }
            }
        }

        private void drawCard(Canvas c, MusicPicks.Pick p, RectF r, boolean focus, float alpha) {
            float rad = 14 * k;
            if (focus) {
                fill.setColor(Ui.alpha(0xFF000000, 0.55f * alpha));
                c.drawRoundRect(r.left, r.top + 18 * k, r.right, r.bottom + 18 * k, rad, rad, fill);
            }
            Bitmap b = p.image == null ? null : images.get(p.image);
            if (b == null) {
                if (p.image != null) load(p.image);
                fill.setColor(Ui.alpha(0x33FFFFFF, alpha));
                c.drawRoundRect(r, rad, rad, fill);
                Symbols.draw(getContext(), c, "play", r.centerX(), r.centerY(), r.width() * 0.3f, Ui.alpha(0x99FFFFFF, alpha));
            } else {
                BitmapShader sh = new BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
                float s = Math.max(r.width() / b.getWidth(), r.height() / b.getHeight());
                m.setScale(s, s);
                m.postTranslate(r.centerX() - b.getWidth() * s / 2, r.centerY() - b.getHeight() * s / 2);
                sh.setLocalMatrix(m);
                fill.setShader(sh);
                fill.setAlpha(Math.round(255 * alpha));
                c.drawRoundRect(r, rad, rad, fill);
                fill.setShader(null);
                fill.setAlpha(255);
            }
            rim.setStrokeWidth((focus ? 3 : 1.5f) * k);
            rim.setShader(new LinearGradient(0, r.top, 0, r.bottom, new int[] {Ui.alpha(0xFFFFFFFF, (focus ? 0.95f : 0.4f) * alpha),
                    Ui.alpha(0xFFFFFFFF, (focus ? 0.5f : 0.08f) * alpha)}, null, Shader.TileMode.CLAMP));
            c.drawRoundRect(r, rad, rad, rim);
            rim.setShader(null);
        }

        private void load(String url) {
            if (!loading.add(url)) return;
            io.execute(() -> {
                Bitmap b = fetch(url);
                main.post(() -> {
                    loading.remove(url);
                    if (b == null) return;
                    images.put(url, b);
                    if (url.equals(backdropFor)) setBackdrop(b);
                    invalidate();
                });
            });
        }

        /** Downloads a cover once (kept in the cache). */
        private Bitmap fetch(String url) {
            File dir = new File(getContext().getCacheDir(), "musicpicks");
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
            File f = new File(dir, Integer.toHexString(url.hashCode()) + ".jpg");
            if (f.exists()) {
                Bitmap b = BitmapFactory.decodeFile(f.getPath());
                if (b != null) return b;
            }
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
                c.setConnectTimeout(8000);
                c.setReadTimeout(10000);
                if (c.getResponseCode() != 200) return null;
                File part = new File(f.getPath() + ".part");
                try (InputStream in = c.getInputStream(); FileOutputStream out = new FileOutputStream(part)) {
                    byte[] buf = new byte[16384];
                    for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
                }
                //noinspection ResultOfMethodCallIgnored
                part.renameTo(f);
                return BitmapFactory.decodeFile(f.getPath());
            } catch (Exception e) {
                return null;
            }
        }

        private static String fit(String s, Paint p, float width) {
            return TextUtils.ellipsize(s == null ? "" : s, new TextPaint(p), width, TextUtils.TruncateAt.END).toString();
        }
    }
}
