package io.freemytv;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.util.LruCache;

import java.util.List;

/**
 * The hero's drawn pictures that aren't a single game (Sports.art): a day's scoreboard card (its games in a grid on
 * the right, the hero's text on the dark left) and the weather card (today's sky, the next hours and the days ahead).
 * Built on Recs' loader thread at the backdrop's size; laid out for 1920 × 1080 and scaled to it.
 */
final class HeroArt {
    private HeroArt() {}

    /** Team logos at card size (a scoreboard has up to 30; the full-size ones are 500 px). */
    private static final LruCache<String, Bitmap> LOGOS = new LruCache<>(80);
    private static final int LOGO_PX = 64;

    // MARK: - Scoreboard card

    static Bitmap board(Sports.Game g, int w, int h) {
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        float s = w / 1920f;
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
        // Night-game navy: dark under the hero's text on the left, deeper blue behind the games.
        int deep = "NFL".equals(g.league) ? 0xFF0B2350 : 0xFF2A1A4A;
        paint.setShader(new LinearGradient(w * 0.3f, 0, w, h * 0.4f, new int[] {0xFF0B0B0F, deep, Sports.deepen(deep)},
                new float[] {0, 0.6f, 1}, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, paint);
        paint.setShader(new RadialGradient(w * 0.72f, h * 0.38f, w * 0.45f, new int[] {0x26FFFFFF, 0x00FFFFFF}, null, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, paint);
        paint.setShader(null);

        List<Sports.Game> games = g.slate;
        int cols = 3, rows = Math.max(1, (games.size() + cols - 1) / cols);
        float left = 860 * s, right = 1840 * s, top = 130 * s, bottom = 760 * s, gap = 14 * s;
        float tw = (right - left - (cols - 1) * gap) / cols;
        float th = Math.min(124 * s, (bottom - top - (rows - 1) * gap) / rows);
        // Fewer rows: the grid sits in the middle of its room.
        top += (bottom - top - (rows * th + (rows - 1) * gap)) / 2;

        Paint name = text(Ui.semibold, 27 * s, 0xFFFFFFFF), score = text(Ui.semibold, 30 * s, 0xFFFFFFFF);
        score.setTextAlign(Paint.Align.RIGHT);
        Paint rank = text(Ui.medium, 19 * s, 0xA6FFFFFF), status = text(Ui.medium, 20 * s, 0xB3FFFFFF);
        Paint tile = new Paint(Paint.ANTI_ALIAS_FLAG), edge = new Paint(Paint.ANTI_ALIAS_FLAG);
        edge.setStyle(Paint.Style.STROKE);
        edge.setStrokeWidth(2 * s);
        // Each tile's corner worked out ahead, in whole pixels: computed inside the loop, Fire OS 7's compiler (ART, once
        // it had compiled this) handed back garbage for the columns after the first row.
        int[] xs = new int[cols], ys = new int[rows];
        for (int col = 0; col < cols; col++) xs[col] = Math.round(left + col * (tw + gap));
        for (int row = 0; row < rows; row++) ys[row] = Math.round(top + row * (th + gap));
        RectF r = new RectF();
        for (int i = 0; i < games.size(); i++) {
            Sports.Game m = games.get(i);
            float x = xs[i % cols], y = ys[i / cols];
            r.set(x, y, x + tw, y + th);
            boolean live = "in".equals(m.state), done = "post".equals(m.state);
            tile.setColor(live ? 0x40FF453A : 0x2E000000);
            c.drawRoundRect(r, 16 * s, 16 * s, tile);
            edge.setColor(live ? 0xCCFF453A : m.favorite ? 0x99FFD60A : 0x24FFFFFF);
            c.drawRoundRect(r, 16 * s, 16 * s, edge);

            float pad = 16 * s, logo = th * 0.26f, row1 = y + th * 0.27f, row2 = y + th * 0.58f;
            boolean awayWon = done && m.awayScore > m.homeScore, homeWon = done && m.homeScore > m.awayScore;
            team(c, paint, name, rank, score, m.awayLogo, m.awayAbbr, m.awayRank, m.awayScore, x + pad, r.right - pad, row1, logo,
                    !"pre".equals(m.state), done && !awayWon);
            team(c, paint, name, rank, score, m.homeLogo, m.homeAbbr, m.homeRank, m.homeScore, x + pad, r.right - pad, row2, logo,
                    !"pre".equals(m.state), done && !homeWon);
            String when = live ? m.detail : done ? (m.detail.isEmpty() ? Ui.tr("Final") : m.detail) : clock(m.start);
            status.setColor(live ? 0xFFFF6961 : 0xB3FFFFFF);
            c.drawText(when, x + pad, y + th * 0.89f, status);
        }
        return out;
    }

    /** One team's line: logo, short name (with its rank), and its score on the right once the game has started. */
    private static void team(Canvas c, Paint paint, Paint name, Paint rank, Paint score, String logoUrl, String abbr, int r,
                             int points, float x, float right, float cy, float size, boolean showScore, boolean lost) {
        Bitmap logo = logo(logoUrl);
        if (logo != null) {
            float lw = size * logo.getWidth() / Math.max(logo.getHeight(), logo.getWidth()), lh = size * logo.getHeight() / Math.max(logo.getHeight(), logo.getWidth());
            c.drawBitmap(logo, null, new RectF(x + (size - lw) / 2, cy - lh / 2, x + (size + lw) / 2, cy + lh / 2), paint);
        }
        float tx = x + size + 12 * (size / 32);
        int alpha = lost ? 140 : 255;
        if (r <= 25) {
            String n = String.valueOf(r);
            rank.setAlpha(lost ? 110 : 166);
            c.drawText(n, tx, cy + Ui.halfCap(name), rank);
            tx += rank.measureText(n) + 6 * (size / 32);
        }
        name.setAlpha(alpha);
        c.drawText(abbr == null ? "" : abbr, tx, cy + Ui.halfCap(name), name);
        if (showScore) {
            score.setAlpha(alpha);
            c.drawText(String.valueOf(points), right, cy + Ui.halfCap(score), score);
        }
    }

    private static Bitmap logo(String url) {
        if (url == null) return null;
        Bitmap b = LOGOS.get(url);
        if (b != null) return b;
        Bitmap full = Sports.load(url);
        if (full == null) return null;
        float k = LOGO_PX / (float) Math.max(full.getWidth(), full.getHeight());
        b = k < 1 ? Bitmap.createScaledBitmap(full, Math.max(1, Math.round(full.getWidth() * k)), Math.max(1, Math.round(full.getHeight() * k)), true) : full;
        LOGOS.put(url, b);
        return b;
    }

    // MARK: - Weather card

    /**
     * Today's weather as a scene: a sky painted for the day's forecast (sun, clouds, overcast, rain, a storm, snow, fog;
     * at night the stars), and the day's high and low, large, on the right. The hero's text says what to expect.
     */
    static Bitmap weather(Context ctx, HomeData.Weather wx, int w, int h) {
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        float s = w / 1920f;
        int code = wx.dayCode.isEmpty() ? wx.code : wx.dayCode.get(0);
        boolean day = wx.isDay;
        java.util.Random rnd = new java.util.Random(code * 31L + (day ? 1 : 0));
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
        String kind = kind(code);

        // The sky.
        int[] sky = !day ? new int[] {0xFF0A1430, 0xFF1B2D55, 0xFF2C3F6B}
                : kind.equals("clear") ? new int[] {0xFF1565C9, 0xFF4A9BEA, 0xFFA8D8F5}
                : kind.equals("partly") ? new int[] {0xFF2A6CB8, 0xFF6AA5DE, 0xFFC4DDF0}
                : kind.equals("overcast") ? new int[] {0xFF56606E, 0xFF7E8896, 0xFFA3ABB5}
                : kind.equals("fog") ? new int[] {0xFF8A949E, 0xFFAEB6BD, 0xFFCDD2D6}
                : kind.equals("snow") ? new int[] {0xFF6D7F94, 0xFF9CADBF, 0xFFD4DEE8}
                : kind.equals("storm") ? new int[] {0xFF14161F, 0xFF2A2C3C, 0xFF3E3A4E}
                : new int[] {0xFF243142, 0xFF3A4A5E, 0xFF5A6A7C};     // rain
        paint.setShader(new LinearGradient(0, 0, 0, h, sky, new float[] {0, 0.55f, 1}, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, paint);
        paint.setShader(null);

        float sunX = 1460 * s, sunY = 250 * s;
        if (!day) {
            // Stars, and the moon when it's clear enough.
            paint.setColor(0xFFFFFFFF);
            for (int i = 0; i < 140; i++) {
                float x = rnd.nextFloat() * w, y = rnd.nextFloat() * h * 0.7f, r = (0.8f + rnd.nextFloat() * 1.8f) * s;
                paint.setAlpha(60 + rnd.nextInt(170));
                c.drawCircle(x, y, r, paint);
            }
            paint.setAlpha(255);
            if (kind.equals("clear") || kind.equals("partly")) glow(c, paint, sunX, sunY, 70 * s, 360 * s, 0xFFF4F1E6, 0x40B8C8F0);
        } else if (kind.equals("clear") || kind.equals("partly")) {
            glow(c, paint, sunX, sunY, 95 * s, 720 * s, 0xFFFFFBE8, 0x80FFD27A);
        }

        // Clouds: soft heaps, more and darker as the day gets worse.
        int clouds = kind.equals("clear") ? 0 : kind.equals("partly") ? 4 : kind.equals("fog") ? 0 : 9;
        int cloudColor = !day ? 0xFF3A4766 : kind.equals("partly") ? 0xFFFFFFFF : kind.equals("overcast") ? 0xFFCBD1D8
                : kind.equals("snow") ? 0xFFE6ECF2 : kind.equals("storm") ? 0xFF2E3142 : 0xFF6B7889;
        for (int i = 0; i < clouds; i++) {
            float cx = (700 + rnd.nextFloat() * 1300) * s, cy = (60 + rnd.nextFloat() * (clouds > 4 ? 420 : 300)) * s;
            cloud(c, cx, cy, (160 + rnd.nextFloat() * 160) * s, cloudColor, kind.equals("partly") ? 0.85f : 0.7f, rnd);
        }
        if (kind.equals("fog")) {
            Paint band = new Paint(Paint.ANTI_ALIAS_FLAG);
            band.setMaskFilter(new android.graphics.BlurMaskFilter(60 * s, android.graphics.BlurMaskFilter.Blur.NORMAL));
            for (int i = 0; i < 6; i++) {
                band.setColor(0x59FFFFFF);
                float y = (180 + i * 120 + rnd.nextFloat() * 40) * s;
                c.drawRect(-100 * s, y, w + 100 * s, y + 70 * s, band);
            }
        }
        if (kind.equals("storm")) bolt(c, 1080 * s, 70 * s, s, rnd);     // above and left of the high and low
        if (kind.equals("rain") || kind.equals("storm")) {
            Paint drop = new Paint(Paint.ANTI_ALIAS_FLAG);
            drop.setStrokeWidth(2.2f * s);
            drop.setStrokeCap(Paint.Cap.ROUND);
            for (int i = 0; i < 260; i++) {
                float x = (500 + rnd.nextFloat() * 1500) * s, y = (200 + rnd.nextFloat() * 900) * s, len = (30 + rnd.nextFloat() * 40) * s;
                drop.setColor(Ui.alpha(0xFFCFE0F5, 0.18f + rnd.nextFloat() * 0.3f));
                c.drawLine(x, y, x - len * 0.25f, y + len, drop);
            }
        }
        if (kind.equals("snow")) {
            Paint flake = new Paint(Paint.ANTI_ALIAS_FLAG);
            flake.setMaskFilter(new android.graphics.BlurMaskFilter(2 * s, android.graphics.BlurMaskFilter.Blur.NORMAL));
            for (int i = 0; i < 220; i++) {
                flake.setColor(Ui.alpha(0xFFFFFFFF, 0.4f + rnd.nextFloat() * 0.5f));
                c.drawCircle((450 + rnd.nextFloat() * 1550) * s, rnd.nextFloat() * h, (2 + rnd.nextFloat() * 5) * s, flake);
            }
        }

        // Dark on the left, under the hero's text.
        paint.setShader(new LinearGradient(0, 0, w * 0.6f, 0, new int[] {0xE60B0B0F, 0x800B0B0F, 0x000B0B0F}, new float[] {0, 0.5f, 1},
                Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, paint);
        paint.setShader(null);

        // The day's high and low, large: warm and cool.
        if (!Double.isNaN(wx.max) && !Double.isNaN(wx.min)) {
            Paint label = text(Ui.semibold, 26 * s, 0xE6FFFFFF);
            label.setLetterSpacing(0.12f);
            label.setShadowLayer(18 * s, 0, 2 * s, 0x80000000);
            Paint hi = text(Ui.light, 210 * s, 0xFFFFE2A8), lo = text(Ui.light, 150 * s, 0xFFB9DCFF);
            hi.setShadowLayer(40 * s, 0, 4 * s, 0x99000000);
            lo.setShadowLayer(40 * s, 0, 4 * s, 0x99000000);
            String high = HomeData.degrees(wx.max), low = HomeData.degrees(wx.min);
            float base = 690 * s, right = 1840 * s, loW = lo.measureText(low), hiW = hi.measureText(high);
            float loX = right - loW, hiX = loX - 70 * s - hiW;
            c.drawText(Ui.tr("HIGH"), hiX + 8 * s, base - 200 * s, label);
            c.drawText(high, hiX, base, hi);
            c.drawText(Ui.tr("LOW"), loX + 6 * s, base - 200 * s, label);
            c.drawText(low, loX, base, lo);
        }
        return out;
    }

    /** The kind of sky for a WMO code: clear, partly, overcast, fog, rain, snow, storm. */
    static String kind(int code) {
        if (code <= 1) return "clear";
        if (code == 2) return "partly";
        if (code == 3) return "overcast";
        if (code == 45 || code == 48) return "fog";
        if (code >= 95) return "storm";
        if (code >= 71 && code <= 77 || code == 85 || code == 86) return "snow";
        return "rain";
    }

    /**
     * What to expect today, in a few words: rain or storms coming (and when), heat, a freeze tonight, a big swing, else
     * a calm word for the sky. Temperatures in Celsius (the forecast's own unit).
     */
    static String expect(Context ctx, HomeData.Weather wx) {
        java.util.List<String> parts = new java.util.ArrayList<>();
        boolean wetNow = wet(wx.code);
        String time = null, kindLater = null;
        for (int i = 0; i < wx.laterCode.size(); i++) {
            int k = wx.laterCode.get(i);
            if (!wet(k)) continue;
            if (time == null) {
                time = Ui.clock(wx.laterAt.get(i));
                kindLater = k >= 95 ? "storm" : (k >= 71 && k <= 77 || k == 85 || k == 86) ? "snow" : "rain";
            }
            if (k >= 95) { kindLater = "storm"; break; }
        }
        if (wetNow) parts.add(kind(wx.code).equals("snow") ? Ui.tr("Snow now, on and off today") : Ui.tr("Rain now, on and off today"));
        else if ("storm".equals(kindLater)) parts.add(Ui.trf("Thunderstorms possible from {time}", "time", time));
        else if ("snow".equals(kindLater)) parts.add(Ui.trf("Snow likely from {time}", "time", time));
        else if (kindLater != null) parts.add(Ui.trf("Rain likely from {time}", "time", time));
        else if (!wx.dayRain.isEmpty() && wx.dayRain.get(0) >= 40) parts.add(Ui.trf("{p}% chance of rain", "p", wx.dayRain.get(0)));
        double max = wx.max, min = wx.min;
        if (!Double.isNaN(max) && max >= 38) parts.add(Ui.trf("Heat warning: up to {t}", "t", HomeData.degrees(max)));
        else if (!Double.isNaN(max) && max >= 33) parts.add(Ui.trf("Hot one: up to {t}", "t", HomeData.degrees(max)));
        if (!Double.isNaN(min) && min <= 0) parts.add(Ui.tr("Freezing tonight"));
        else if (!Double.isNaN(min) && min <= 3) parts.add(Ui.tr("Frost possible tonight"));
        if (parts.isEmpty() && !Double.isNaN(max) && !Double.isNaN(min) && max - min >= 15) {
            parts.add(Ui.tr("Cool morning, warm afternoon"));
        }
        if (parts.isEmpty()) {
            int code = wx.dayCode.isEmpty() ? wx.code : wx.dayCode.get(0);
            String k = kind(code);
            parts.add(k.equals("clear") ? Ui.tr("Clear skies all day") : k.equals("partly") ? Ui.tr("Some clouds, staying dry")
                    : k.equals("fog") ? Ui.tr("Foggy, take it slow on the roads") : Ui.tr("Gray skies, staying dry"));
        }
        return android.text.TextUtils.join("  ·  ", parts);
    }

    private static boolean wet(int code) {
        return code >= 51 && code != 45 && code != 48;
    }

    /** A soft sun (or moon): a bright core in a wide glow. */
    private static void glow(Canvas c, Paint paint, float x, float y, float core, float reach, int coreColor, int glowColor) {
        paint.setShader(new RadialGradient(x, y, reach, new int[] {glowColor, glowColor & 0x00FFFFFF}, null, Shader.TileMode.CLAMP));
        c.drawCircle(x, y, reach, paint);
        paint.setShader(new RadialGradient(x, y, core * 1.4f, new int[] {coreColor, coreColor, coreColor & 0x00FFFFFF},
                new float[] {0, 0.7f, 1}, Shader.TileMode.CLAMP));
        c.drawCircle(x, y, core * 1.4f, paint);
        paint.setShader(null);
    }

    /** A soft heap of cloud: a few overlapping blurred puffs on a flat base. */
    private static void cloud(Canvas c, float cx, float cy, float size, int color, float alpha, java.util.Random rnd) {
        Paint puff = new Paint(Paint.ANTI_ALIAS_FLAG);
        puff.setMaskFilter(new android.graphics.BlurMaskFilter(size * 0.18f, android.graphics.BlurMaskFilter.Blur.NORMAL));
        puff.setColor(Ui.alpha(color, alpha));
        int n = 5 + rnd.nextInt(3);
        for (int i = 0; i < n; i++) {
            float dx = (i - (n - 1) / 2f) * size * 0.42f, r = size * (0.35f + rnd.nextFloat() * 0.3f) * (1 - Math.abs(dx) / (size * 2.2f));
            c.drawCircle(cx + dx, cy - r * 0.35f, r, puff);
        }
        c.drawRoundRect(new RectF(cx - size * 1.15f, cy - size * 0.1f, cx + size * 1.15f, cy + size * 0.32f), size * 0.2f, size * 0.2f, puff);
    }

    /** A lightning bolt with its glow. */
    private static void bolt(Canvas c, float x, float y, float s, java.util.Random rnd) {
        android.graphics.Path path = new android.graphics.Path();
        path.moveTo(x, y);
        float px = x, py = y;
        for (int i = 0; i < 5; i++) {
            px += (rnd.nextFloat() - 0.4f) * 60 * s;
            py += (45 + rnd.nextFloat() * 25) * s;
            path.lineTo(px, py);
        }
        Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
        glow.setStyle(Paint.Style.STROKE);
        glow.setStrokeJoin(Paint.Join.ROUND);
        glow.setStrokeWidth(18 * s);
        glow.setColor(0x66B9A8FF);
        glow.setMaskFilter(new android.graphics.BlurMaskFilter(22 * s, android.graphics.BlurMaskFilter.Blur.NORMAL));
        c.drawPath(path, glow);
        glow.setMaskFilter(null);
        glow.setStrokeWidth(4 * s);
        glow.setColor(0xFFF6F2FF);
        c.drawPath(path, glow);
    }

    // MARK: - Shared

    private static Paint text(android.graphics.Typeface face, float size, int color) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
        p.setTypeface(face);
        p.setTextSize(size);
        p.setColor(color);
        return p;
    }

    private static String clock(long at) {
        return Ui.clock(at);
    }
}
