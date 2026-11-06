package pl.domowezadania.budzik;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Small helpers for screens built in code (no layouts, no libraries). */
final class Ui {
    private Ui() {}
    static int dp(Context c, int v) { return Math.round(v * c.getResources().getDisplayMetrics().density); }

    static TextView text(Context c, String s, int sp, int color, boolean bold) {
        TextView t = new TextView(c);
        t.setText(s); t.setTextSize(sp); t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(0, dp(c, 6), 0, dp(c, 6));
        return t;
    }

    static Button button(Context c, String s, int bg, int fg, View.OnClickListener l) {
        Button b = new Button(c);
        b.setText(s); b.setAllCaps(false); b.setTextSize(17); b.setTextColor(fg);
        GradientDrawable d = new GradientDrawable(); d.setColor(bg); d.setCornerRadius(dp(c, 14));
        b.setBackground(d);
        b.setPadding(dp(c, 16), dp(c, 14), dp(c, 16), dp(c, 14));
        b.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(c, 10);
        b.setLayoutParams(lp);
        b.setGravity(Gravity.CENTER);
        return b;
    }

    static final int INK = Color.rgb(31, 41, 55), MUTED = Color.rgb(100, 110, 125), ACCENT = Color.rgb(37, 99, 235),
            WARN = Color.rgb(217, 119, 6), LIGHT = Color.rgb(238, 242, 255), RED = Color.rgb(220, 38, 38);
}
