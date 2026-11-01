package io.freemytv;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.graphics.Shader;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.format.DateFormat;
import android.view.KeyEvent;
import android.view.View;

import java.io.InputStream;
import java.util.Calendar;
import java.util.Date;

/**
 * The home screen left alone for a while (HomeView, SCENE_IDLE_MS): a full-screen scene with the big glass clock of
 * the iPhone lock screen, set *behind* the mountain. Three layers (assets/scenes, from couch-tv, made for it with an
 * image model and free to use): the photo, the clock and the date over it (the digits filled with a blurred,
 * brightened copy of the photo, so they read as frosted glass), and the scenery cut out of the photo with the sky
 * transparent, drawn last so the peak covers the bottom of the digits. Any key goes home again, and is used up.
 */
public class DepthClockActivity extends Activity {
    static final String SCENE = "night";
    /** Where the clock goes, in the scene's 1920 × 1080 frame: the digits' top and height, and the date's baseline. */
    private static final float CLOCK_TOP = 150, DIGITS = 300, DATE_BASE = 112;

    private SceneView view;

    static void open(Context c) {
        c.startActivity(new Intent(c, DepthClockActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    /** Whether the scene's pictures are in the app (the idle switch is pointless without them). */
    static boolean available(Context c) {
        try (InputStream in = c.getAssets().open("scenes/" + SCENE + ".jpg")) {
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

    static final class SceneView extends View {
        private final Handler main = new Handler(Looper.getMainLooper());
        private final Paint image = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
        private final Paint digits = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final Paint shine = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint date = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Rect frame = new Rect(), bounds = new Rect();
        private final Matrix m = new Matrix();
        private Bitmap photo, glass, front;
        private BitmapShader glassShader;
        private final Runnable minute = new Runnable() {
            @Override public void run() {
                invalidate();
                main.postDelayed(this, 60_000 - System.currentTimeMillis() % 60_000 + 50);
            }
        };

        SceneView(Context c) {
            super(c);
            photo = load(c, SCENE + ".jpg");
            glass = load(c, SCENE + "-glass.jpg");
            front = load(c, SCENE + "-fg.webp");
            if (glass != null) glassShader = new BitmapShader(glass, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            digits.setTypeface(Ui.semibold);
            digits.setTextAlign(Paint.Align.CENTER);
            digits.setTextScaleX(0.74f);      // tall and narrow, as the lock screen's clock
            rim.setTypeface(Ui.semibold);
            rim.setTextAlign(Paint.Align.CENTER);
            rim.setTextScaleX(0.74f);
            rim.setStyle(Paint.Style.STROKE);
            rim.setColor(0x66FFFFFF);
            shine.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SRC_ATOP));
            date.setTypeface(Ui.medium);
            date.setTextAlign(Paint.Align.CENTER);
            date.setColor(0xF2FFFFFF);
            main.post(minute);
        }

        void stop() {
            main.removeCallbacks(minute);
        }

        private static Bitmap load(Context c, String name) {
            try (InputStream in = c.getAssets().open("scenes/" + name)) {
                return BitmapFactory.decodeStream(in);
            } catch (Exception e) {
                AppLog.w("scene " + name + ": " + e.getMessage());
                return null;
            }
        }

        @Override protected void onDraw(Canvas c) {
            int w = getWidth(), h = getHeight();
            c.drawColor(0xFF0B0B0F);
            if (photo == null) return;
            // The scene covers the screen; everything else is placed in its 1920 × 1080 frame.
            float s = Math.max(w / (float) photo.getWidth(), h / (float) photo.getHeight());
            float ox = (w - photo.getWidth() * s) / 2, oy = (h - photo.getHeight() * s) / 2;
            float k = s * photo.getWidth() / 1920f;
            frame.set(Math.round(ox), Math.round(oy), Math.round(ox + photo.getWidth() * s), Math.round(oy + photo.getHeight() * s));
            c.drawBitmap(photo, null, frame, image);

            // The date and the weather, over the clock.
            Context ctx = getContext();
            String line = DateFormat.format(DateFormat.getBestDateTimePattern(java.util.Locale.getDefault(), "EEEEMMMMd"), new Date()).toString();
            HomeData.Weather wx = HomeData.get(ctx).weather;
            if (!Double.isNaN(wx.temp)) line += "  ·  " + HomeData.degrees(wx.temp) + " " + HomeData.condition(wx.code, wx.isDay);
            date.setTextSize(40 * k);
            date.setShadowLayer(18 * k, 0, 2 * k, 0x59000000);
            c.drawText(line, w / 2f, oy + DATE_BASE * k, date);

            // The clock: the hour and minutes, filled with the frosted copy, a light rim, brighter toward the top.
            Calendar now = Calendar.getInstance();
            int hour = now.get(DateFormat.is24HourFormat(ctx) ? Calendar.HOUR_OF_DAY : Calendar.HOUR);
            if (!DateFormat.is24HourFormat(ctx) && hour == 0) hour = 12;
            String time = hour + ":" + String.format(java.util.Locale.ROOT, "%02d", now.get(Calendar.MINUTE));
            digits.setTextSize(100);
            digits.getTextBounds("0123456789", 0, 10, bounds);
            float size = 100 * DIGITS * k / bounds.height();
            digits.setTextSize(size);
            rim.setTextSize(size);
            rim.setStrokeWidth(2.2f * k);
            digits.getTextBounds(time, 0, time.length(), bounds);
            float base = oy + CLOCK_TOP * k - bounds.top;
            int layer = c.saveLayer(0, 0, w, h, null);
            if (glassShader != null) {
                m.setScale(frame.width() / (float) glass.getWidth(), frame.height() / (float) glass.getHeight());
                m.postTranslate(frame.left, frame.top);
                glassShader.setLocalMatrix(m);
                digits.setShader(glassShader);
            } else {
                digits.setColor(0xE6FFFFFF);
            }
            c.drawText(time, w / 2f, base, digits);
            float top = base + bounds.top;
            shine.setShader(new LinearGradient(0, top, 0, top + bounds.height(), new int[] {0x8CFFFFFF, 0x1FFFFFFF, 0x00FFFFFF},
                    new float[] {0, 0.45f, 1}, Shader.TileMode.CLAMP));
            c.drawRect(0, top, w, top + bounds.height(), shine);
            c.restoreToCount(layer);
            c.drawText(time, w / 2f, base, rim);

            // The mountain in front of the clock.
            if (front != null) c.drawBitmap(front, null, frame, image);
        }
    }
}
