package io.freemytv;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.os.SystemClock;
import android.view.Choreographer;
import android.view.View;

import java.util.Random;

/**
 * The weather card's moving sky, over its photo (HeroArt.weather) and under the hero's text, the way the iPhone's
 * Weather app does it: rain falling, snow drifting down, fog rolling past, lightning flashing. (Drifting clouds and a
 * turning sun glow are here too, for a sky without its photo.) Only runs while the weather card is on screen (HomeView turns it on and off); everything
 * is drawn from a few small bitmaps and lines, so a frame stays cheap on the TV. Fainter on the left, under the text.
 */
final class WeatherFx extends View implements Choreographer.FrameCallback {
    private static final long FADE_MS = 700;

    private String kind = "";
    private boolean day = true, on;
    private float alpha, from;
    private long fadeAt, lastNanos;
    private final Random rnd = new Random();
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);

    // Particles: x, y, speed, size, phase.
    private float[] px = new float[0], py = px, pv = px, ps = px, pp = px;
    // Clouds and fog banks: x, y, speed, scale, which bitmap.
    private float[] cx = new float[0], cy = cx, cv = cx, cs = cx;
    private int[] cb = new int[0];
    private Bitmap[] clouds = new Bitmap[0];
    private Bitmap flake, rays;
    private float flash, sunAngle;
    private long nextFlash;

    WeatherFx(Context c) {
        super(c);
        line.setStrokeCap(Paint.Cap.ROUND);
        setWillNotDraw(false);
    }

    /** The sky to animate: HeroArt.kind's clear, partly, overcast, fog, rain, snow, storm; and day or night. */
    void scene(String kind, boolean day) {
        if (kind.equals(this.kind) && day == this.day && getWidth() > 0 && clouds.length > 0 == needsClouds(kind)) return;
        this.kind = kind;
        this.day = day;
        build();
    }

    /** Shown (fading in) or hidden (fading out, then stopped). */
    void active(boolean show) {
        if (show == on) return;
        on = show;
        from = alpha;
        fadeAt = SystemClock.uptimeMillis();
        lastNanos = 0;
        setVisibility(VISIBLE);
        Choreographer.getInstance().removeFrameCallback(this);
        Choreographer.getInstance().postFrameCallback(this);
    }

    /** Stops at once (the home screen paused). */
    void stop() {
        on = false;
        alpha = 0;
        Choreographer.getInstance().removeFrameCallback(this);
        setVisibility(INVISIBLE);
    }

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        build();
    }

    private static boolean needsClouds(String kind) {
        return kind.equals("fog");
    }

    private void build() {
        int w = getWidth(), h = getHeight();
        if (w == 0 || h == 0 || kind.isEmpty()) return;
        float k = w / 1920f;
        rnd.setSeed(kind.hashCode());

        // Clouds (or fog banks), drawn once as soft heaps and moved.
        // The sky is a photo (HeroArt.weather): only fog banks drift over it; the rest moves as rain, snow, lightning.
        int n = kind.equals("fog") ? 5 : 0;
        int color = !day ? 0xFF4A5778 : kind.equals("partly") ? 0xFFFFFFFF : kind.equals("overcast") ? 0xFFD3D8DE
                : kind.equals("snow") ? 0xFFE8EDF2 : kind.equals("storm") ? 0xFF3A3D50 : kind.equals("fog") ? 0xFFFFFFFF : 0xFF7A8696;
        clouds = new Bitmap[Math.min(n, 3)];
        for (int i = 0; i < clouds.length; i++) {
            clouds[i] = kind.equals("fog") ? bank(Math.round(1400 * k), Math.round(260 * k)) : cloud(Math.round((520 + 140 * i) * k), color, kind.equals("partly"));
        }
        cx = new float[n]; cy = new float[n]; cv = new float[n]; cs = new float[n]; cb = new int[n];
        for (int i = 0; i < n; i++) {
            cb[i] = i % Math.max(1, clouds.length);
            cs[i] = 0.8f + rnd.nextFloat() * 0.6f;
            cx[i] = rnd.nextFloat() * (w + 600 * k) - 300 * k;
            cy[i] = kind.equals("fog") ? (250 + i * 150 + rnd.nextFloat() * 60) * k : (-40 + rnd.nextFloat() * (kind.equals("partly") ? 330 : 420)) * k;
            cv[i] = (kind.equals("fog") ? 18 : 10 + rnd.nextFloat() * 14) * k * (rnd.nextBoolean() || kind.equals("fog") ? 1 : 0.7f);
        }

        // Rain and snow.
        int count = kind.equals("rain") ? 170 : kind.equals("storm") ? 220 : kind.equals("snow") ? 150 : 0;
        px = new float[count]; py = new float[count]; pv = new float[count]; ps = new float[count]; pp = new float[count];
        for (int i = 0; i < count; i++) {
            px[i] = rnd.nextFloat() * (w + 200 * k);
            py[i] = rnd.nextFloat() * h;
            boolean snow = kind.equals("snow");
            float depth = rnd.nextFloat();     // far (small, slow, faint) to near
            pv[i] = (snow ? 40 + depth * 90 : 1300 + depth * 900) * k;
            ps[i] = snow ? (3 + depth * 7) * k : (34 + depth * 46) * k;
            pp[i] = rnd.nextFloat() * 6.28f;
        }
        if (kind.equals("snow")) flake = dot(Math.round(24 * k));
        rays = null;     // the photos have their own sun
        nextFlash = SystemClock.uptimeMillis() + 2500;
        flash = 0;
    }

    @Override public void doFrame(long nanos) {
        float dt = lastNanos == 0 ? 0 : Math.min(0.05f, (nanos - lastNanos) / 1e9f);
        lastNanos = nanos;
        long up = SystemClock.uptimeMillis();
        float e = Math.min(1, (up - fadeAt) / (float) FADE_MS);
        alpha = from + ((on ? 1 : 0) - from) * e * e * (3 - 2 * e);
        step(dt, up);
        invalidate();
        if (on || alpha > 0.001f) {
            Choreographer.getInstance().postFrameCallback(this);
        } else {
            setVisibility(INVISIBLE);
        }
    }

    private void step(float dt, long up) {
        int w = getWidth(), h = getHeight();
        float k = w / 1920f;
        for (int i = 0; i < cx.length; i++) {
            cx[i] += cv[i] * dt;
            Bitmap b = clouds.length == 0 ? null : clouds[cb[i]];
            if (b != null && cx[i] > w + 50 * k) cx[i] = -b.getWidth() * cs[i] - 50 * k;
        }
        boolean snow = kind.equals("snow");
        for (int i = 0; i < px.length; i++) {
            py[i] += pv[i] * dt;
            px[i] += (snow ? (float) Math.sin(up / 900.0 + pp[i]) * 18 : -pv[i] * 0.22f) * k * dt;
            if (py[i] > h + 40 * k) {
                py[i] = -60 * k - rnd.nextFloat() * 100 * k;
                px[i] = rnd.nextFloat() * (w + 200 * k);
            }
            if (px[i] < -100 * k) px[i] += w + 200 * k;
        }
        sunAngle += dt * 1.2f;
        if (kind.equals("storm")) {
            if (up >= nextFlash) {
                flash = 1;
                nextFlash = up + 4500 + rnd.nextInt(7000);
            }
            flash = Math.max(0, flash - dt * 2.6f);
        }
    }

    @Override protected void onDraw(Canvas c) {
        if (alpha <= 0.001f || kind.isEmpty()) return;
        int w = getWidth(), h = getHeight();
        float k = w / 1920f;
        // The sun's glow turning, behind everything (where HeroArt draws the sun).
        if (rays != null) {
            c.save();
            c.rotate(sunAngle, 1460 * k, 250 * k);
            paint.setAlpha(Math.round(255 * alpha * 0.55f));
            c.drawBitmap(rays, 1460 * k - rays.getWidth() / 2f, 250 * k - rays.getHeight() / 2f, paint);
            c.restore();
        }
        for (int i = 0; i < cx.length; i++) {
            Bitmap b = clouds[cb[i]];
            float s = cs[i];
            paint.setAlpha(Math.round(255 * alpha * fade(cx[i] + b.getWidth() * s / 2, w) * (kind.equals("fog") ? 0.5f : 0.9f)));
            c.save();
            c.translate(cx[i], cy[i]);
            c.scale(s, s);
            c.drawBitmap(b, 0, 0, paint);
            c.restore();
        }
        if (kind.equals("rain") || kind.equals("storm")) {
            line.setStrokeWidth(2.4f * k);
            for (int i = 0; i < px.length; i++) {
                float a = alpha * fade(px[i], w) * (0.18f + 0.32f * (pv[i] / (2200 * k)));
                line.setColor(Ui.alpha(0xFFDCE8F7, a));
                c.drawLine(px[i], py[i], px[i] + ps[i] * 0.22f, py[i] - ps[i], line);
            }
        } else if (flake != null) {
            for (int i = 0; i < px.length; i++) {
                float size = ps[i] * 2.6f;
                paint.setAlpha(Math.round(255 * alpha * fade(px[i], w) * (0.45f + 0.5f * (ps[i] / (10 * k)))));
                c.drawBitmap(flake, null, new android.graphics.RectF(px[i] - size / 2, py[i] - size / 2, px[i] + size / 2, py[i] + size / 2), paint);
            }
        }
        if (flash > 0.001f) {
            // Two quick pulses, like lightning behind the clouds.
            float f = flash > 0.75f ? (flash - 0.75f) * 4 : flash > 0.45f ? 0.25f : flash * 0.9f;
            c.drawColor(Ui.alpha(0xFFE8E6FF, alpha * Math.min(0.42f, f * 0.55f)));
        }
    }

    /** Fainter toward the left, where the hero's text is. */
    private static float fade(float x, int w) {
        float t = (x - w * 0.18f) / (w * 0.32f);
        t = Math.max(0, Math.min(1, t));
        return 0.25f + 0.75f * t * t * (3 - 2 * t);
    }

    // MARK: - The pieces, drawn once

    /** A heap of cloud: soft puffs, lit from above (lighter top, shaded base). */
    private Bitmap cloud(int width, int color, boolean bright) {
        int height = Math.round(width * 0.5f);
        // Room around the heap: the puffs' soft edges reach past its outline (cut off, they showed straight lines).
        int padX = Math.round(width * 0.3f), padY = Math.round(width * 0.25f);
        Bitmap b = Bitmap.createBitmap(width + 2 * padX, height + 2 * padY, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        c.translate(padX, padY);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        int puffs = 7;
        for (int i = 0; i < puffs; i++) {
            float x = width * (0.14f + 0.72f * i / (puffs - 1)), r = width * (0.11f + rnd.nextFloat() * 0.09f) * (i == 2 || i == 4 ? 1.35f : 1);
            float y = height * 0.62f - r * 0.5f;
            int top = bright ? 0xFFFFFFFF : lighten(color, 0.25f), bottom = darken(color, bright ? 0.12f : 0.3f);
            p.setShader(new RadialGradient(x, y - r * 0.35f, r * 1.25f, new int[] {Ui.alpha(top, 0.95f), Ui.alpha(color, 0.85f),
                    Ui.alpha(bottom, 0f)}, new float[] {0, 0.55f, 1}, Shader.TileMode.CLAMP));
            c.drawCircle(x, y, r * 1.25f, p);
        }
        p.setShader(new LinearGradient(0, height * 0.45f, 0, height * 0.85f, Ui.alpha(color, 0.75f), Ui.alpha(darken(color, 0.25f), 0),
                Shader.TileMode.CLAMP));
        c.drawRoundRect(width * 0.08f, height * 0.45f, width * 0.92f, height * 0.85f, height * 0.2f, height * 0.2f, p);
        return b;
    }

    /** A fog bank: a long soft band, thickest in the middle. */
    private static Bitmap bank(int width, int height) {
        Bitmap b = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setShader(new LinearGradient(0, 0, 0, height, new int[] {0x00FFFFFF, 0x99FFFFFF, 0x00FFFFFF}, null, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, width, height, p);
        p.setShader(new LinearGradient(0, 0, width, 0, new int[] {0xFF000000, 0x00000000, 0x00000000, 0xFF000000},
                new float[] {0, 0.25f, 0.75f, 1}, Shader.TileMode.CLAMP));
        p.setXfermode(new android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.DST_OUT));
        c.drawRect(0, 0, width, height, p);
        return b;
    }

    /** A soft snowflake. */
    private static Bitmap dot(int size) {
        Bitmap b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setShader(new RadialGradient(size / 2f, size / 2f, size / 2f, new int[] {0xFFFFFFFF, 0xCCFFFFFF, 0x00FFFFFF},
                new float[] {0, 0.35f, 1}, Shader.TileMode.CLAMP));
        c.drawCircle(size / 2f, size / 2f, size / 2f, p);
        return b;
    }

    /** Soft rays around the sun, turned slowly. */
    private Bitmap rays(int size) {
        Bitmap b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        float r = size / 2f;
        p.setShader(new RadialGradient(r, r, r, new int[] {0x66FFF4D0, 0x22FFE7A0, 0x00FFE7A0}, new float[] {0, 0.4f, 1}, Shader.TileMode.CLAMP));
        android.graphics.Path ray = new android.graphics.Path();
        for (int i = 0; i < 12; i++) {
            double a = i * Math.PI / 6 + rnd.nextFloat() * 0.2, spread = 0.05 + rnd.nextFloat() * 0.05;
            ray.moveTo(r, r);
            ray.lineTo(r + (float) Math.cos(a - spread) * r, r + (float) Math.sin(a - spread) * r);
            ray.lineTo(r + (float) Math.cos(a + spread) * r, r + (float) Math.sin(a + spread) * r);
            ray.close();
        }
        c.drawPath(ray, p);
        return b;
    }

    private static int lighten(int color, float f) {
        return GameActivity.mix(color, 0xFFFFFFFF, f);
    }

    private static int darken(int color, float f) {
        return GameActivity.mix(color, 0xFF000000, f);
    }
}
