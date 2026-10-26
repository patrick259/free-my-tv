package io.freemytv;

import android.content.Context;
import android.media.AudioManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.ViewConfiguration;

/**
 * Holding Volume Up or Down. Android repeats a held key itself, but not one that went through an accessibility key filter
 * (RemoteKeyService's): with ours on, holding volume moved it one step. So while the key is down we do the repeats, at
 * Android's own pace, through the audio service (which also forwards them over HDMI-CEC to a sound bar). The press itself
 * still goes on to the system, so a short press is exactly as before.
 */
final class VolumeHold {
    /** Never more than this many repeats from one press, should its release go missing. */
    private static final int MAX_STEPS = 100;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final AudioManager audio;
    private int key, steps;

    VolumeHold(Context c) {
        audio = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
    }

    private final Runnable step = new Runnable() {
        @Override public void run() {
            audio.adjustSuggestedStreamVolume(key == KeyEvent.KEYCODE_VOLUME_UP ? AudioManager.ADJUST_RAISE : AudioManager.ADJUST_LOWER,
                    AudioManager.USE_DEFAULT_STREAM_TYPE, AudioManager.FLAG_SHOW_UI);
            if (++steps < MAX_STEPS) main.postDelayed(this, ViewConfiguration.getKeyRepeatDelay());
        }
    };

    /** Sees every key from the filter; never consumes one. */
    void onKey(KeyEvent e) {
        int code = e.getKeyCode();
        boolean volume = code == KeyEvent.KEYCODE_VOLUME_UP || code == KeyEvent.KEYCODE_VOLUME_DOWN;
        if (e.getAction() == KeyEvent.ACTION_DOWN && volume) {
            stop();
            if (e.getRepeatCount() > 0) return;     // the system repeats this one after all: leave it to it
            key = code;
            main.postDelayed(step, Build.VERSION.SDK_INT >= 28 ? ViewConfiguration.getKeyRepeatTimeout() : 500);
        } else if (code == key || e.getAction() == KeyEvent.ACTION_DOWN) {
            stop();     // released, or another key pressed
        }
    }

    void stop() {
        main.removeCallbacks(step);
        key = 0;
        steps = 0;
    }
}
