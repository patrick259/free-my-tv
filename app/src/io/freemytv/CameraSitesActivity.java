package io.freemytv;

import android.app.Activity;
import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Cameras with more than one place in ha_cameras (CameraSites): a card for each, side by side. OK opens that place's
 * cameras (CamerasActivity), and Back from there comes back here.
 */
public class CameraSitesActivity extends Activity {
    private static final int INK = 0xFFF5F5F7, DIM = 0xFF98989D;
    private float k;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        Ui.init(this);
        k = getResources().getDisplayMetrics().widthPixels / 1920f;

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF000000);
        TextView heading = text(Ui.tr("Cameras"), 52, INK, Ui.displaySemibold);
        FrameLayout.LayoutParams hp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hp.leftMargin = px(80);
        hp.topMargin = px(52);
        root.addView(heading, hp);

        int n = CameraSites.ALL.size();
        float gap = 40, width = (1760 - gap * (n - 1)) / n;
        for (int i = 0; i < n; i++) {
            CameraSites.Site site = CameraSites.ALL.get(i);
            final int index = i;
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setGravity(Gravity.CENTER);
            card.setFocusable(true);
            card.setFocusableInTouchMode(true);
            card.setBackground(edge(false));
            card.setOnFocusChangeListener((v, has) -> v.setBackground(edge(has)));
            card.setOnClickListener(v -> startActivity(new Intent(this, CamerasActivity.class)
                    .putExtra(CamerasActivity.EXTRA_SITE, index)));
            card.addView(text(site.name.isEmpty() ? Ui.tr("Cameras") : site.name, 60, INK, Ui.displaySemibold));
            TextView count = text(Ui.plural("plural|one:{n} camera|other:{n} cameras", site.ids.size()), 30, DIM, Ui.medium);
            count.setPadding(0, px(18), 0, 0);
            card.addView(count);
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(px(width), px(560), Gravity.CENTER_VERTICAL);
            lp.leftMargin = px(80 + i * (width + gap));
            lp.topMargin = px(40);
            root.addView(card, lp);
            if (i == 0) card.requestFocus();
        }
        setContentView(root);
    }

    /** A card's edge and fill: faint at rest, white when focused (as the camera tiles). */
    private GradientDrawable edge(boolean focused) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(focused ? 0xFF1C1C21 : 0xFF111114);
        d.setCornerRadius(px(14));
        d.setStroke(px(focused ? 5 : 2), focused ? 0xFFFFFFFF : 0x33FFFFFF);
        return d;
    }

    private TextView text(String s, float size, int color, android.graphics.Typeface face) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(color);
        t.setTypeface(face);
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, size * k);
        t.setIncludeFontPadding(false);
        return t;
    }

    private int px(float v) {
        return Math.round(v * k);
    }
}
