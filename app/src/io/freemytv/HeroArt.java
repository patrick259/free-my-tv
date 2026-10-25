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
     * The weather card's still sky, the way the iPhone's Weather app paints it: a gradient for the forecast and the time
     * of day (dawn and dusk warm at the horizon, night deep blue with stars and the moon), the sun's glow on a clear
     * day, a low ceiling of cloud when it's gray, a haze for fog. What moves (clouds, rain, snow, fog banks, lightning)
     * is WeatherFx, over this; the temperature and the rest are the hero's text (HomeView).
     */
    static Bitmap weather(Context ctx, HomeData.Weather wx, int w, int h) {
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        float s = w / 1920f;
        int code = wx.dayCode.isEmpty() ? wx.code : wx.dayCode.get(0);
        String kind = kind(code);
        boolean day = wx.isDay;
        long now = System.currentTimeMillis();
        boolean golden = wx.sunrise > 0 && (Math.abs(now - wx.sunrise) < 50 * 60_000L || Math.abs(now - wx.sunset) < 50 * 60_000L);
        java.util.Random rnd = new java.util.Random(code * 31L + (day ? 1 : 0));
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);

        // A photo for the sky (assets/weather, made for this card), filling the frame; the painted sky below is the
        // fallback when one is missing.
        Bitmap photo = photo(ctx, photoName(kind, day, golden));
        if (photo != null) {
            float scale = Math.max(w / (float) photo.getWidth(), h / (float) photo.getHeight());
            float pw = photo.getWidth() * scale, ph = photo.getHeight() * scale;
            c.drawBitmap(photo, null, new RectF((w - pw) / 2, (h - ph) / 2, (w + pw) / 2, (h + ph) / 2), paint);
            photo.recycle();
            paint.setShader(new LinearGradient(0, 0, w * 0.62f, 0, new int[] {0xCC0B0B0F, 0x660B0B0F, 0x000B0B0F}, new float[] {0, 0.5f, 1},
                    Shader.TileMode.CLAMP));
            c.drawRect(0, 0, w, h, paint);
            paint.setShader(null);
            return out;
        }

        // Top to horizon.
        int[] sky;
        if (!day) {
            sky = kind.equals("clear") || kind.equals("partly") ? new int[] {0xFF050A1C, 0xFF0E1B3D, 0xFF24365E}
                    : new int[] {0xFF0B0F18, 0xFF1A2130, 0xFF2B3446};
        } else if (golden && (kind.equals("clear") || kind.equals("partly"))) {
            sky = new int[] {0xFF2B4C8C, 0xFF8F7FB0, 0xFFF6A86A};
        } else {
            sky = kind.equals("clear") ? new int[] {0xFF0F5FC7, 0xFF3D8FE6, 0xFF9CD0F7}
                    : kind.equals("partly") ? new int[] {0xFF2668B6, 0xFF5E9BDA, 0xFFB6D4EE}
                    : kind.equals("overcast") ? new int[] {0xFF5B6574, 0xFF838D9A, 0xFFA9B0B9}
                    : kind.equals("fog") ? new int[] {0xFF8E979F, 0xFFB3BAC0, 0xFFD3D7DA}
                    : kind.equals("snow") ? new int[] {0xFF74869A, 0xFFA2B2C3, 0xFFD6DFE8}
                    : kind.equals("storm") ? new int[] {0xFF101219, 0xFF262838, 0xFF3C3A4C}
                    : new int[] {0xFF263243, 0xFF3D4C60, 0xFF5D6C7E};     // rain
        }
        paint.setShader(new LinearGradient(0, 0, 0, h, sky, new float[] {0, 0.55f, 1}, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, paint);
        paint.setShader(null);

        float sunX = 1460 * s, sunY = 250 * s;
        if (!day) {
            if (kind.equals("clear") || kind.equals("partly")) {
                paint.setColor(0xFFFFFFFF);
                for (int i = 0; i < 220; i++) {
                    float x = rnd.nextFloat() * w, y = rnd.nextFloat() * h * 0.75f, r = (0.7f + rnd.nextFloat() * 1.9f) * s;
                    paint.setAlpha(50 + rnd.nextInt(190));
                    c.drawCircle(x, y, r, paint);
                }
                paint.setAlpha(255);
                glow(c, paint, sunX, sunY, 62 * s, 420 * s, 0xFFF3EFE2, 0x4DAFC2EE);
            }
        } else if (kind.equals("clear") || kind.equals("partly")) {
            glow(c, paint, sunX, sunY, 90 * s, 800 * s, golden ? 0xFFFFF0D0 : 0xFFFFFCEB, golden ? 0xA6FFB070 : 0x8CFFE08A);
        }
        // A low ceiling of cloud on gray days (the drifting clouds are WeatherFx's).
        if (kind.equals("overcast") || kind.equals("rain") || kind.equals("storm") || kind.equals("snow")) {
            int ceiling = !day ? 0x99202634 : kind.equals("storm") ? 0xCC1C1E2A : kind.equals("rain") ? 0x99454F5E
                    : kind.equals("snow") ? 0x80DDE3EA : 0x80C3C9D0;
            paint.setShader(new LinearGradient(0, 0, 0, h * 0.45f, ceiling, ceiling & 0x00FFFFFF, Shader.TileMode.CLAMP));
            c.drawRect(0, 0, w, h * 0.45f, paint);
            paint.setShader(null);
        }
        if (kind.equals("fog")) {
            paint.setShader(new LinearGradient(0, h * 0.2f, 0, h, 0x00FFFFFF, 0x8CFFFFFF, Shader.TileMode.CLAMP));
            c.drawRect(0, 0, w, h, paint);
            paint.setShader(null);
        }
        // Dark on the left, under the hero's text.
        paint.setShader(new LinearGradient(0, 0, w * 0.62f, 0, new int[] {0xD90B0B0F, 0x730B0B0F, 0x000B0B0F}, new float[] {0, 0.5f, 1},
                Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, paint);
        paint.setShader(null);
        return out;
    }

    /** The sky photo for the forecast and the hour: dawn and dusk warm, the night's own. */
    static String photoName(String kind, boolean day, boolean golden) {
        if (!day) return kind + "_night";
        if (golden && kind.equals("clear")) return "clear_golden";
        if (golden && kind.equals("partly")) return "dusk_clouds";
        return kind + "_day";
    }

    private static Bitmap photo(Context ctx, String name) {
        try (java.io.InputStream in = ctx.getAssets().open("weather/" + name + ".jpg")) {
            return android.graphics.BitmapFactory.decodeStream(in);
        } catch (java.io.IOException e) {
            return null;
        }
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
