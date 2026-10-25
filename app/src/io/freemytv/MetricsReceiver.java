package io.freemytv;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Fire TV: the Settings comparison, measured from a computer (tools/firetv-measure.sh), since Fire OS refuses ADB from
 * the TV itself. Only adb's shell may send it (DUMP, in the manifest):
 * am broadcast -a io.freemytv.METRICS -n io.freemytv/.MetricsReceiver --es mode original --el homeMs 900 ...
 */
public class MetricsReceiver extends BroadcastReceiver {
    private static final String[] KEYS = {"homeMs", "memAvailableMB", "homePssMB"};

    @Override public void onReceive(Context context, Intent intent) {
        String mode = intent.getStringExtra("mode");
        if (!HomeMode.FREE.equals(mode) && !HomeMode.ORIGINAL.equals(mode)) return;
        try {
            for (String key : KEYS) {
                long v = intent.getLongExtra(key, -1);
                if (v > 0) HomeMode.putMetric(context, mode, key, v);
            }
            AppLog.w("metrics for " + mode + " from the computer");
        } catch (org.json.JSONException e) {
            AppLog.w("metrics: " + e.getMessage());
        }
    }
}
