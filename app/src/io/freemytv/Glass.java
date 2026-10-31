package io.freemytv;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.view.View;

import java.io.File;
import java.io.FileOutputStream;
import java.util.Calendar;

/**
 * Liquid Glass for the screens beside the home screen (Scores, a game, Settings, Cameras, the sleep timer…), in their
 * dark look. Behind them, the home screen's featured photo, softly blurred and darkened (the home screen keeps a small
 * copy in a file, since these screens run in another process), or else a sky for the time of day (dawn, day, dusk,
 * night). On it, glass surfaces: that same picture showing through where the surface is, a little lighter, under a
 * smoke tint, with a band along the edge showing it slightly magnified (the thick rim bending the light) and a rim
 * bright along the top. No live blur (Fire OS 7 and Android 12 have none): the picture is already blurred.
 */
final class Glass {
    private Glass() {}

    /**
     * As on the home screen's shelf, what makes it read as glass is the contrast: the page shows the picture fairly
     * sharp (darkened for the text), the glass shows it soft, brighter and richer. PAGE_DIM and GLASS_DIM: how much
     * darker each is.
     */
    private static final float PAGE_DIM = 0.42f, GLASS_DIM = 0.1f;
    private static final String FILE = "glass-backdrop.png";

    private static Bitmap sharp, soft;
    private static BitmapShader sharpShader, softShader;
    private static long imageStamp = -1, skyHour = -1;

    /** From the home screen when its featured photo changes: the glass copy (Backdrop.GW × GH), kept for the others. */
    static void save(Context c, int[] px) {
        if (px == null || px.length != Backdrop.GW * Backdrop.GH) return;
        Bitmap b = Bitmap.createBitmap(px, Backdrop.GW, Backdrop.GH, Bitmap.Config.ARGB_8888);
        File f = new File(c.getFilesDir(), FILE), part = new File(c.getFilesDir(), FILE + ".part");
        try (FileOutputStream out = new FileOutputStream(part)) {
            b.compress(Bitmap.CompressFormat.PNG, 100, out);
        } catch (Exception e) {
            AppLog.w("glass backdrop: " + e.getMessage());
            return;
        } finally {
            b.recycle();
        }
        //noinspection ResultOfMethodCallIgnored
        part.renameTo(f);
    }

    /** The picture (the home screen's latest, else this hour's sky) in its two versions, read again when it changed. */
    private static synchronized void load(Context c) {
        File f = new File(c.getApplicationContext().getFilesDir(), FILE);
        long stamp = f.exists() ? f.lastModified() : 0;
        long hour = System.currentTimeMillis() / 3_600_000;
        if (sharpShader != null && stamp == imageStamp && (stamp != 0 || hour == skyHour)) return;
        Bitmap b = stamp != 0 ? BitmapFactory.decodeFile(f.getPath()) : null;
        if (b == null) {
            b = sky();
            skyHour = hour;
        }
        imageStamp = stamp;
        sharp = b;
        soft = soften(b.copy(Bitmap.Config.ARGB_8888, true));
        sharpShader = filtered(sharp);
        softShader = filtered(soft);
    }

    private static BitmapShader filtered(Bitmap b) {
        return new BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
    }

    /**
     * Far softer than the home screen's glass copy (the screen behind these pages is a mood, not a picture): a third
     * the size, then three box blurs, so stretched to the screen it is smooth (a smaller copy alone shows its blocks).
     */
    static Bitmap soften(Bitmap src) {
        int w = Math.max(16, src.getWidth() / 3), h = Math.max(9, src.getHeight() / 3);
        Bitmap small = Bitmap.createScaledBitmap(src, w, h, true);
        if (small != src) src.recycle();
        int[] px = new int[w * h], tmp = new int[w * h];
        small.getPixels(px, 0, w, 0, 0, w, h);
        for (int pass = 0; pass < 3; pass++) {
            box(px, tmp, w, h, 6, true);
            box(tmp, px, w, h, 6, false);
        }
        // Brighter and richer, as the shelf's glass copy (Backdrop): the glass gathers the light.
        for (int i = 0; i < px.length; i++) {
            int p = px[i], r = (p >> 16) & 255, g = (p >> 8) & 255, b = p & 255;
            float gray = 0.299f * r + 0.587f * g + 0.114f * b;
            r = Math.round(Math.max(0, Math.min(255, gray + (r - gray) * 1.35f)) * 0.86f + 255 * 0.14f);
            g = Math.round(Math.max(0, Math.min(255, gray + (g - gray) * 1.35f)) * 0.86f + 255 * 0.14f);
            b = Math.round(Math.max(0, Math.min(255, gray + (b - gray) * 1.35f)) * 0.86f + 255 * 0.14f);
            px[i] = 0xFF000000 | (r << 16) | (g << 8) | b;
        }
        Bitmap out = small.isMutable() ? small : Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        out.setPixels(px, 0, w, 0, 0, w, h);
        if (out != small) small.recycle();
        return out;
    }

    /** One box blur of radius `r` along rows (`across`) or columns, from `in` to `out`, edges clamped. */
    private static void box(int[] in, int[] out, int w, int h, int r, boolean across) {
        int lines = across ? h : w, len = across ? w : h, n = 2 * r + 1;
        for (int l = 0; l < lines; l++) {
            int rs = 0, gs = 0, bs = 0;
            for (int i = -r; i <= r; i++) {
                int p = in[at(l, Math.max(0, Math.min(len - 1, i)), w, across)];
                rs += (p >> 16) & 255; gs += (p >> 8) & 255; bs += p & 255;
            }
            for (int i = 0; i < len; i++) {
                out[at(l, i, w, across)] = 0xFF000000 | ((rs / n) << 16) | ((gs / n) << 8) | (bs / n);
                int add = in[at(l, Math.min(len - 1, i + r + 1), w, across)], drop = in[at(l, Math.max(0, i - r), w, across)];
                rs += ((add >> 16) & 255) - ((drop >> 16) & 255);
                gs += ((add >> 8) & 255) - ((drop >> 8) & 255);
                bs += (add & 255) - (drop & 255);
            }
        }
    }

    private static int at(int line, int i, int w, boolean across) {
        return across ? line * w + i : i * w + line;
    }

    /** A small sky for the hour: dawn rose and gold, day blue, dusk orange and violet, night deep blue. */
    private static Bitmap sky() {
        int h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        int[] colors = h >= 5 && h < 8 ? new int[] {0xFF3B3F7A, 0xFFB0657A, 0xFFE9A15E}
                : h >= 8 && h < 17 ? new int[] {0xFF1D4E89, 0xFF3D7CC4, 0xFF8DB8E3}
                : h >= 17 && h < 20 ? new int[] {0xFF2B2156, 0xFF8E3F6E, 0xFFE07A45}
                : new int[] {0xFF050A1C, 0xFF0F1D45, 0xFF263A6E};
        Bitmap b = Bitmap.createBitmap(Backdrop.GW / 4, Backdrop.GH / 4, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Paint p = new Paint();
        p.setShader(new LinearGradient(0, 0, b.getWidth() * 0.3f, b.getHeight(), colors, new float[] {0, 0.55f, 1},
                Shader.TileMode.CLAMP));
        c.drawPaint(p);
        return b;
    }

    /** Maps the picture onto the screen (covering it), seen from a canvas whose origin is at `x`, `y` on screen. */
    private static void map(Matrix m, Bitmap image, View v, float x, float y) {
        int sw = v.getResources().getDisplayMetrics().widthPixels, sh = v.getResources().getDisplayMetrics().heightPixels;
        float s = Math.max(sw / (float) image.getWidth(), sh / (float) image.getHeight());
        m.setScale(s, s);
        m.postTranslate((sw - image.getWidth() * s) / 2 - x, (sh - image.getHeight() * s) / 2 - y);
    }

    /** The screen's background: the picture, full screen and darkened. */
    static Drawable page(View root) {
        return new Page(root);
    }

    /** Glass of corner radius `radius` px for `host` (as its background). */
    static Drawable plate(View host, float radius) {
        return new Plate(host, radius, 0, 0, null);
    }

    /** Glass tinted from `left` to `right` (a game card in its teams' colors), at `amount` (0 to 1). */
    static Drawable plate(View host, float radius, int left, int right, float amount) {
        return new Plate(host, radius, left, right, amount);
    }

    /** The same, focused: brighter, with a bright rim. */
    static Drawable focused(View host, float radius, int left, int right, float amount) {
        Plate p = new Plate(host, radius, left, right, amount);
        p.focus = true;
        return p;
    }

    /** Glass with a light outline: a focused surface that stays glass (the tabs, a selected row). */
    static Drawable focused(View host, float radius) {
        Plate p = new Plate(host, radius, 0, 0, null);
        p.focus = true;
        return p;
    }

    /**
     * After `root` scrolls: its glass is drawn again for where it now is (a scrolled view's children keep their last
     * drawing, which would show the picture from where they were).
     */
    static void refresh(View root) {
        if (root.getBackground() instanceof Plate) root.invalidate();
        if (root instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) root;
            for (int i = 0; i < g.getChildCount(); i++) refresh(g.getChildAt(i));
        }
    }

    private static final class Page extends Drawable {
        private final View host;
        private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
        private final Matrix m = new Matrix();
        private final int[] loc = new int[2];

        Page(View host) {
            this.host = host;
        }

        @Override public void draw(Canvas c) {
            load(host.getContext());
            BitmapShader s = sharpShader;
            host.getLocationOnScreen(loc);
            map(m, sharp, host, loc[0], loc[1]);
            s.setLocalMatrix(m);
            paint.setShader(s);
            c.drawRect(getBounds(), paint);
            paint.setShader(null);
            c.drawColor(Ui.alpha(0xFF000000, PAGE_DIM));
        }

        @Override public void setAlpha(int alpha) {}
        @Override public void setColorFilter(ColorFilter cf) {}
        @Override public int getOpacity() { return PixelFormat.OPAQUE; }
    }

    /** A glass drawn by hand in a custom view (Settings), whose panels have their own corners. */
    interface Radius {
        void radius(float r);
    }

    private static final class Plate extends Drawable implements Radius {
        private final View host;
        private float radius;
        private final int left, right;
        private final Float amount;
        boolean focus;
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
        private final Paint band = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Matrix m = new Matrix();
        private final RectF r = new RectF();
        private final int[] loc = new int[2];
        private int alpha = 255;

        Plate(View host, float radius, int left, int right, Float amount) {
            this.host = host;
            this.radius = radius;
            this.left = left;
            this.right = right;
            this.amount = amount;
            band.setStyle(Paint.Style.STROKE);
            rim.setStyle(Paint.Style.STROKE);
        }

        @Override public void draw(Canvas c) {
            Rect b = getBounds();
            if (b.isEmpty()) return;
            float k = host.getResources().getDisplayMetrics().widthPixels / 1920f, a = alpha / 255f;
            float rad = Math.min(radius, Math.min(b.width(), b.height()) / 2f);
            r.set(b);
            load(host.getContext());
            BitmapShader s = softShader;
            host.getLocationOnScreen(loc);
            map(m, soft, host, loc[0], loc[1]);
            s.setLocalMatrix(m);
            // The picture, soft and lighter than the page around it.
            fill.setShader(s);
            fill.setAlpha(alpha);
            c.drawRoundRect(r, rad, rad, fill);
            fill.setShader(null);
            fill.setColor(Ui.alpha(0xFF000000, GLASS_DIM * a));
            c.drawRoundRect(r, rad, rad, fill);
            // The tint: a breath of white, or the teams' colors.
            if (amount != null) {
                fill.setShader(new LinearGradient(r.left, 0, r.right, 0, new int[] {Ui.alpha(left, amount * a),
                        Ui.alpha(0xFFFFFFFF, (focus ? 0.14f : 0.04f) * a), Ui.alpha(right, amount * a)}, null, Shader.TileMode.CLAMP));
                c.drawRoundRect(r, rad, rad, fill);
                fill.setShader(null);
            } else {
                fill.setColor(Ui.alpha(0xFFFFFFFF, (focus ? 0.2f : 0.1f) * a));
                c.drawRoundRect(r, rad, rad, fill);
            }
            // The thick edge: the picture slightly magnified, in a band along the rim.
            float w = Math.max(2, Math.min(14 * k, Math.min(b.width(), b.height()) / 6f));
            m.postScale(1.06f, 1.06f, r.centerX(), r.centerY());
            s.setLocalMatrix(m);
            band.setShader(s);
            band.setStrokeWidth(w);
            band.setAlpha(Math.round(200 * a));
            RectF in = new RectF(r);
            in.inset(w / 2, w / 2);
            float ir = Math.max(0, rad - w / 2);
            c.drawRoundRect(in, ir, ir, band);
            band.setShader(null);
            band.setColor(Ui.alpha(0xFFFFFFFF, 0.05f * a));
            c.drawRoundRect(in, ir, ir, band);
            // The rim: bright along the top, faint below (brighter, and a little wider, when focused).
            rim.setStrokeWidth(Math.max(1.5f, (focus ? 3 : 2) * k));
            rim.setShader(new LinearGradient(0, r.top, 0, r.bottom, new int[] {Ui.alpha(0xFFFFFFFF, (focus ? 0.95f : 0.6f) * a),
                    Ui.alpha(0xFFFFFFFF, (focus ? 0.4f : 0.12f) * a), Ui.alpha(0xFFFFFFFF, (focus ? 0.6f : 0.22f) * a)},
                    new float[] {0, 0.55f, 1}, Shader.TileMode.CLAMP));
            in.set(r);
            float half = rim.getStrokeWidth() / 2;
            in.inset(half, half);
            c.drawRoundRect(in, Math.max(0, rad - half), Math.max(0, rad - half), rim);
            rim.setShader(null);
        }

        @Override public void radius(float r) {
            radius = r;
        }

        @Override public void setAlpha(int a) {
            alpha = a;
            invalidateSelf();
        }

        @Override public void setColorFilter(ColorFilter cf) {}
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }
}
