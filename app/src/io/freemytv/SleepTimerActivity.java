package io.freemytv;

import android.app.Activity;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.format.DateFormat;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Date;

/**
 * The sleep timer's picker (SleepTimer), from the drawer: 15 minutes to 2 hours, or off. A card over the home screen;
 * OK on a time starts it and closes the card.
 */
public class SleepTimerActivity extends Activity {
    private float k;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        Ui.init(this);
        k = getResources().getDisplayMetrics().widthPixels / 1920f;

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xB0000000);
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(px(48), px(40), px(48), px(40));
        card.setBackground(Glass.plate(card, px(30)));

        card.addView(text(Ui.tr("Sleep Timer"), 44, 0xFFF5F5F7, Ui.displaySemibold));
        long end = SleepTimer.end(this);
        String status = end == 0 ? Ui.tr("Off. The TV goes to standby when the time runs out.")
                : Ui.trf("On: the TV turns off at {time}", "time", DateFormat.getTimeFormat(this).format(new Date(end)));
        TextView sub = text(status, 24, 0xFFB0B0B5, Ui.regular);
        sub.setPadding(0, px(10), 0, px(26));
        card.addView(sub);

        View first = null;
        for (int m : SleepTimer.MINUTES) {
            String label = m < 60 || m % 60 != 0 ? Ui.trf("{n} minutes", "n", m) : Ui.plural("plural|one:{n} hour|other:{n} hours", m / 60);
            View row = row(label, () -> SleepTimer.start(this, m * 60_000L));
            card.addView(row);
            if (first == null) first = row;
        }
        if (end != 0) {
            View off = row(Ui.tr("Turn off the timer"), () -> SleepTimer.cancel(this));
            card.addView(off);
            first = off;
        }

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(px(720), FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        root.addView(card, lp);
        setContentView(root);
        first.requestFocus();
    }

    private View row(String label, Runnable action) {
        TextView t = text(label, 30, 0xFFF5F5F7, Ui.medium);
        t.setPadding(px(28), px(16), px(28), px(16));
        t.setFocusable(true);
        t.setOnFocusChangeListener((v, has) -> paint(t, has));
        t.setOnClickListener(v -> {
            action.run();
            finish();
        });
        paint(t, false);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = px(6);
        t.setLayoutParams(lp);
        return t;
    }

    private void paint(TextView t, boolean focused) {
        GradientDrawable pill = new GradientDrawable();
        pill.setCornerRadius(px(18));
        pill.setColor(focused ? 0xFFF5F5F7 : 0x00000000);
        t.setBackground(pill);
        t.setTextColor(focused ? 0xFF111114 : 0xFFF5F5F7);
    }

    private TextView text(String s, float size, int color, android.graphics.Typeface face) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(color);
        if (face != null) t.setTypeface(face);
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, size * k);
        t.setIncludeFontPadding(false);
        return t;
    }

    private int px(float v) {
        return Math.round(v * k);
    }
}
