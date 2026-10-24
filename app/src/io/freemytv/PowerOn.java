package io.freemytv;

import android.content.ContentResolver;
import android.content.Context;
import android.os.SystemClock;
import android.provider.Settings;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Fire TV: the TV's "Power On Behavior: Last input" with our Home redirect in place. At power-off Fire OS records what
 * to come back to (secure setting next_input_start: "input" or "tuner" when its own player or Live TV had the screen,
 * "home" for anything else), and at power-on it shows its home screen and then switches to that input. Two things go
 * wrong with us around: our redirect covers Amazon's switch with our home screen, and an input shown in our player
 * counts as "home". So RemoteKeyService asks here when the screen comes on: stand aside while Amazon resumes its
 * input, or bring back the one our player had (InputPlayerActivity records it in a file, read across processes).
 */
final class PowerOn {
    private PowerOn() {}

    /** How long after power-on Amazon's home screen is left alone (it shows before the input switch). */
    private static final long HOLD_MS = 12_000;
    private static long holdUntil;

    private static File file(Context c) {
        return new File(c.getFilesDir(), "resume_input");
    }

    /** The input to bring back at power-on, or null for none. */
    static void setResumeInput(Context c, String input) {
        File f = file(c);
        if (input == null) {
            f.delete();
            return;
        }
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(input.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            AppLog.w("resume input: " + e.getMessage());
        }
    }

    private static String resumeInput(Context c) {
        File f = file(c);
        if (!f.exists()) return null;
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] b = new byte[(int) f.length()];
            int n = in.read(b);
            return n > 0 ? new String(b, 0, n, StandardCharsets.UTF_8).trim() : null;
        } catch (IOException e) {
            return null;
        }
    }

    /** The TV's own setting: 0 is "Home", anything else comes back to the last input. */
    private static boolean lastInputMode(Context c) {
        return Settings.Secure.getInt(c.getContentResolver(), "power_on_launch_mode", 0) != 0;
    }

    /** The screen just came on (or the TV just booted). */
    static void screenOn(Context c) {
        if (!HomeMode.amazonRedirect(c)) return;
        ContentResolver r = c.getContentResolver();
        String next = Settings.Secure.getString(r, "next_input_start");
        String ours = resumeInput(c);
        boolean last = lastInputMode(c);
        AppLog.w("power on: mode " + (last ? "last input" : "home") + ", amazon " + next + ", ours " + ours);
        if (!last) return;
        if (ours != null) {
            holdUntil = SystemClock.elapsedRealtime() + HOLD_MS;
            setResumeInput(c, null);
            try {
                c.startActivity(InputPlayerActivity.intent(c, ours));
            } catch (RuntimeException e) {
                AppLog.w("power on: " + e.getMessage());
            }
        } else if (next != null && !"home".equals(next)) {
            holdUntil = SystemClock.elapsedRealtime() + HOLD_MS;     // Amazon is switching to its input
        }
    }

    /** Should the Home redirect wait, because the TV is coming back to an input? */
    static boolean holding() {
        return SystemClock.elapsedRealtime() < holdUntil;
    }
}
