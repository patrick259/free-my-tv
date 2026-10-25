package io.freemytv;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.media.tv.TvContract;
import android.media.tv.TvView;
import android.os.Bundle;
import android.os.PowerManager;
import android.view.KeyEvent;
import android.view.WindowManager;

/**
 * An HDMI input, full screen (Fire TV). The only app there that answers the standard request for an input is Amazon's
 * Live TV, which doesn't switch to it (it opens its settings, then whatever live channel it last had), and Amazon's own
 * input player needs a permission only Amazon's apps get. So we show the input ourselves, in a TvView, as that player
 * does. Back (or Home) leaves it.
 *
 * Fire OS remembers the last input for power-on only when its own player had it, so we remember ours
 * ({@link PowerOn}): turning the TV off here brings this input back when it comes on.
 */
public class InputPlayerActivity extends Activity {
    static final String EXTRA_INPUT = "input";

    private TvView tv;
    private AudioManager audio;
    private String input;

    static Intent intent(Context c, String input) {
        return new Intent(c, InputPlayerActivity.class).putExtra(EXTRA_INPUT, input)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    }

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        tv = new TvView(this);
        // MediaTek's HDMI session keeps the sound at 0 until it's given a volume, and applies it once there's a picture.
        tv.setCallback(new TvView.TvInputCallback() {
            @Override public void onVideoAvailable(String inputId) { tv.setStreamVolume(1f); }
        });
        setContentView(tv);
        // Watching a box on HDMI presses no Fire TV buttons: without this the screensaver comes on over the show
        // after the screen-off timeout (5 min). Amazon's own input player keeps the screen on the same way.
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        audio = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        tune();
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        tune();
    }

    private void tune() {
        input = getIntent().getStringExtra(EXTRA_INPUT);
        if (input == null) {
            finish();
            return;
        }
        AppLog.w("input: " + input);
        // Audio focus, then a volume: what Amazon's player does (TvViewAudioManager).
        audio.requestAudioFocus(null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN);
        tv.tune(input, TvContract.buildChannelUriForPassthroughInput(input));
        tv.setStreamVolume(1f);
    }

    /** The remote's keys go to the input (HDMI-CEC: the device's own menus), except Back, which comes home. */
    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getKeyCode() == KeyEvent.KEYCODE_BACK) return super.dispatchKeyEvent(event);
        return tv.dispatchKeyEvent(event) || super.dispatchKeyEvent(event);
    }

    @Override protected void onStop() {
        super.onStop();
        // Stopped by the TV going off: come back to this input when it's on again. Left any other way: don't.
        boolean off = !((PowerManager) getSystemService(Context.POWER_SERVICE)).isInteractive();
        PowerOn.setResumeInput(this, off ? input : null);
        tv.reset();     // the input's session ends: the TV stops showing it once we're gone
        audio.abandonAudioFocus(null);
        finish();
    }
}
