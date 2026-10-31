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
 * What's New (Den's idea, the Apple TV app's look): rows of posters, the new movies, the shows with an episode this
 * week and what's popular, only from the TV's services (Recs.whatsNew). The focused title's backdrop fills the screen
 * behind, with its name and service above the rows. ↑ ↓ change row (the rows slide up), ← → move along one, OK opens
 * the title in its app (its own page, through Watchmode, when there's a key; else the app), Back comes home.
 */
public class WhatsNewActivity extends Activity {
    private NewView view;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        Ui.init(this);
        view = new NewView(this);
        setContentView(view);
    }

    @Override public boolean dispatchKeyEvent(KeyEvent e) {
        return view.onKey(e) || super.dispatchKeyEvent(e);
    }

    @Override protected void onDestroy() {
        view.stop();
        super.onDestroy();
    }

    static final class NewView extends View implements Choreographer.FrameCallback {
        private static final String POSTERS = "https://image.tmdb.org/t/p/w342", BACKDROPS = "https://image.tmdb.org/t/p/w780";
        private static final float TOP = 330, PITCH = 400, CARD_W = 190, CARD_H = 285, GAP = 32, LEFT = 80;
        private static final int SHOWN = 8;     // cards across, before a row scrolls

        private final Activity activity;
        private final Handler main = new Handler(Looper.getMainLooper());
        private final ExecutorService io = Executors.newFixedThreadPool(3);
        private final LruCache<String, Bitmap> images = new LruCache<String, Bitmap>(48 * 1024 * 1024) {
            @Override protected int sizeOf(String k, Bitmap b) { return b.getByteCount(); }
        };
        private final Set<String> loading = new HashSet<>();
        private List<Recs.Row> rows = new ArrayList<>();
        private int[] cols = new int[0];
        private Spring[] scroll = new Spring[0];
        private final Spring rowAt = new Spring(0.42f, 0.9f, 0), focusPop = new Spring(0.3f, 0.8f, 0);
        private int row;
        private String message;
        private boolean opening;
        private long lastNanos;
        private float k = 1;
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
        private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Matrix m = new Matrix();
        private final RectF r = new RectF();
        private Paint headP, titleP, metaP, rowP, msgP;
        private Bitmap backdrop;
        private String backdropFor = "";

        NewView(Activity a) {
            super(a);
            activity = a;
            setFocusable(true);
            rim.setStyle(Paint.Style.STROKE);
            message = Ui.tr("Finding what's new on your apps…");
            if (!Recs.hasKey(a)) {
                message = Ui.tr("What's New needs a TMDB key: see the README (config.properties).");
                return;
            }
            io.execute(() -> {
                try {
                    List<Recs.Row> got = Recs.get(a).whatsNew();
                    main.post(() -> {
                        rows = got;
                        cols = new int[got.size()];
                        scroll = new Spring[got.size()];
                        for (int i = 0; i < scroll.length; i++) scroll[i] = new Spring(0.36f, 0.92f, 0);
                        message = got.isEmpty() ? Ui.tr("Nothing new on your apps right now.") : null;
                        focusPop.snap(0);
                        focusPop.target = 1;
                        moved();
                    });
                } catch (Exception e) {
                    AppLog.w("what's new: " + e.getMessage());
                    main.post(() -> { message = Ui.trf("Couldn't reach TMDB: {error}", "error", String.valueOf(e.getMessage())); invalidate(); });
                }
            });
        }

        void stop() {
            Choreographer.getInstance().removeFrameCallback(this);
            io.shutdownNow();
        }

        private Recs.Pick focused() {
            return rows.isEmpty() ? null : rows.get(row).picks.get(cols[row]);
        }

        /** After a move: the scroll targets, the backdrop to show, the frames. */
        private void moved() {
            rowAt.target = row;
            for (int i = 0; i < rows.size(); i++) scroll[i].target = Math.max(0, Math.min(cols[i] - SHOWN + 2, rows.get(i).picks.size() - SHOWN));
            Recs.Pick p = focused();
            if (p != null && p.backdrop != null && !p.backdrop.equals(backdropFor)) {
                backdropFor = p.backdrop;
                String key = BACKDROPS + p.backdrop;
                Bitmap b = images.get(key);
                if (b != null) backdrop = b; else load(key);
            }
            lastNanos = 0;
            Choreographer.getInstance().removeFrameCallback(this);
            Choreographer.getInstance().postFrameCallback(this);
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
            if (e.getAction() != KeyEvent.ACTION_DOWN || rows.isEmpty() || opening) return true;
            int before = row, col = cols[row];
            switch (code) {
                case KeyEvent.KEYCODE_DPAD_UP: row = Math.max(0, row - 1); break;
                case KeyEvent.KEYCODE_DPAD_DOWN: row = Math.min(rows.size() - 1, row + 1); break;
                case KeyEvent.KEYCODE_DPAD_LEFT: cols[row] = Math.max(0, col - 1); break;
                case KeyEvent.KEYCODE_DPAD_RIGHT: cols[row] = Math.min(rows.get(row).picks.size() - 1, col + 1); break;
                default:
                    open(focused());
                    return true;
            }
            if (row != before || cols[row] != col) {
                focusPop.snap(0.4f);
                focusPop.target = 1;
                moved();
            }
            return true;
        }

        /** Its own page in its app when Watchmode knows it (looked up now), else the app. */
        private void open(Recs.Pick p) {
            if (p == null) return;
            opening = true;
            invalidate();
            io.execute(() -> {
                String link = Recs.get(activity).linkNow(p);
                main.post(() -> {
                    opening = false;
                    HomeApps.Item it = HomeApps.app(p.pkg, Recs.serviceName(p), link);
                    if (!HomeApps.open(activity, it, this, 0, 0, getWidth(), getHeight())) {
                        message = Ui.trf("Couldn't open {app}.", "app", it.label);
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
            msgP = Ui.text(Ui.medium, 30 * k, 0xCCFFFFFF);
        }

        @Override protected void onDraw(Canvas c) {
            if (headP == null) return;
            int w = getWidth(), h = getHeight();
            // The focused title's backdrop, darkened toward the bottom and the left for the text.
            c.drawColor(0xFF0B0B0F);
            if (backdrop != null) {
                float s = Math.max(w / (float) backdrop.getWidth(), h / (float) backdrop.getHeight());
                m.setScale(s, s);
                m.postTranslate((w - backdrop.getWidth() * s) / 2, 0);
                c.drawBitmap(backdrop, m, fill);
            }
            fill.setShader(new LinearGradient(0, 0, 0, h, new int[] {0x8C000000, 0xB3000000, 0xF2000000}, new float[] {0, 0.3f, 0.62f},
                    Shader.TileMode.CLAMP));
            c.drawRect(0, 0, w, h, fill);
            fill.setShader(new LinearGradient(0, 0, w * 0.6f, 0, 0x99000000, 0x00000000, Shader.TileMode.CLAMP));
            c.drawRect(0, 0, w, h, fill);
            fill.setShader(null);

            c.drawText(Ui.tr("WHAT'S NEW"), LEFT * k, 92 * k, headP);
            if (message != null) {
                c.drawText(message, LEFT * k, 200 * k, msgP);
                return;
            }
            Recs.Pick p = focused();
            if (p != null) {
                c.drawText(fit(p.title, titleP, 1500 * k), LEFT * k, 190 * k, titleP);
                c.drawText(opening ? Ui.trf("Opening {app}…", "app", Recs.serviceName(p)) : p.meta, LEFT * k, 240 * k, metaP);
            }
            // The rows, sliding up as you go down: the row you're on always at the same place.
            for (int i = 0; i < rows.size(); i++) {
                float y = (TOP + (i - rowAt.value) * PITCH) * k;
                if (y < 170 * k || y > h) continue;
                float a = Math.max(0, Math.min(1, (y - 170 * k) / (160 * k)));
                drawRow(c, i, y, a);
            }
        }

        private void drawRow(Canvas c, int i, float y, float alpha) {
            Recs.Row rw = rows.get(i);
            rowP.setAlpha(Math.round(0xE6 * alpha));
            c.drawText(rw.title, LEFT * k, y, rowP);
            float top = y + 30 * k, step = (CARD_W + GAP) * k;
            float x0 = LEFT * k - scroll[i].value * step;
            int focusedCol = i == row ? cols[i] : -1;
            // The focused card last, over its neighbors.
            for (int pass = 0; pass < 2; pass++) {
                for (int j = 0; j < rw.picks.size(); j++) {
                    if ((j == focusedCol) != (pass == 1)) continue;
                    float x = x0 + j * step;
                    if (x > getWidth() || x + CARD_W * k < 0) continue;
                    float s = j == focusedCol ? 1 + 0.12f * focusPop.value : 1;
                    float cw = CARD_W * k * s, ch = CARD_H * k * s, cx = x + CARD_W * k / 2, cy = top + CARD_H * k / 2;
                    r.set(cx - cw / 2, cy - ch / 2, cx + cw / 2, cy + ch / 2);
                    drawCard(c, rw.picks.get(j), r, j == focusedCol, alpha);
                }
            }
        }

        private void drawCard(Canvas c, Recs.Pick p, RectF r, boolean focus, float alpha) {
            float rad = 16 * k;
            if (focus) {
                fill.setColor(Ui.alpha(0xFF000000, 0.55f * alpha));
                c.drawRoundRect(r.left, r.top + 18 * k, r.right, r.bottom + 18 * k, rad, rad, fill);
            }
            String key = POSTERS + p.poster;
            Bitmap b = images.get(key);
            if (b == null) {
                load(key);
                fill.setColor(Ui.alpha(0x33FFFFFF, alpha));
                c.drawRoundRect(r, rad, rad, fill);
            } else {
                BitmapShader sh = new BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
                m.setScale(r.width() / b.getWidth(), r.height() / b.getHeight());
                m.postTranslate(r.left, r.top);
                sh.setLocalMatrix(m);
                fill.setShader(sh);
                fill.setAlpha(Math.round(255 * alpha));
                c.drawRoundRect(r, rad, rad, fill);
                fill.setShader(null);
                fill.setAlpha(255);
            }
            // A glass rim: bright along the top, and all round when focused.
            rim.setStrokeWidth((focus ? 3 : 1.5f) * k);
            rim.setShader(new LinearGradient(0, r.top, 0, r.bottom, new int[] {Ui.alpha(0xFFFFFFFF, (focus ? 0.95f : 0.4f) * alpha),
                    Ui.alpha(0xFFFFFFFF, (focus ? 0.5f : 0.08f) * alpha)}, null, Shader.TileMode.CLAMP));
            c.drawRoundRect(r, rad, rad, rim);
            rim.setShader(null);
        }

        /** Downloads `url` once (kept on the TV in the cache), then draws again. */
        private void load(String url) {
            if (!loading.add(url)) return;
            io.execute(() -> {
                Bitmap b = fetch(url);
                main.post(() -> {
                    loading.remove(url);
                    if (b == null) return;
                    images.put(url, b);
                    if (url.startsWith(BACKDROPS) && url.endsWith(backdropFor)) backdrop = b;
                    invalidate();
                });
            });
        }

        private Bitmap fetch(String url) {
            File dir = new File(getContext().getCacheDir(), "whatsnew");
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
            File f = new File(dir, url.substring(url.lastIndexOf('/') + 1) + (url.startsWith(BACKDROPS) ? ".b" : ".p"));
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
            return TextUtils.ellipsize(s, new TextPaint(p), width, TextUtils.TruncateAt.END).toString();
        }
    }
}
