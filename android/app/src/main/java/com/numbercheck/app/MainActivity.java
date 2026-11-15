package com.numbercheck.app;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.provider.Settings;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** One screen: switch on the SurveyCTO alert (once), and a box to check a number. */
public class MainActivity extends Activity implements Store.Listener {
    private static final int BRAND = 0xff0f3d2e, OK = 0xff11834f, BAD = 0xffc8312b, WARN = 0xff9a6a00, MUTE = 0xff64736b;
    private Store store;
    private LinearLayout root, svc;
    private TextView result;
    private EditText num;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        store = Store.get(this);
        getWindow().setStatusBarColor(BRAND);

        ScrollView sv = new ScrollView(this);
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xfff4f6f3);
        sv.addView(root);
        setContentView(sv);

        TextView h = text("NumberCheck", 20, 0xffffffff, true);
        h.setBackgroundColor(BRAND);
        h.setPadding(dp(18), dp(16), dp(18), dp(16));
        root.addView(h, new LinearLayout.LayoutParams(-1, -2));

        // --- switch on the alert (shown only while it's off)
        svc = card();
        svc.addView(text("Turn on the SurveyCTO alert", 16, 0xff13201a, true));
        svc.addView(text("Tap the button, choose NumberCheck and switch it on.\n"
                + "If it's greyed out: Settings › Apps › NumberCheck › ⋮ › Allow restricted settings, then try again.", 14, MUTE, false));
        Button on = button("Turn on");
        on.setOnClickListener(new View.OnClickListener() { public void onClick(View v) {
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
        }});
        svc.addView(on);

        // --- check a number
        LinearLayout chk = card();
        chk.addView(text("Customer phone number", 14, MUTE, false));
        num = new EditText(this);
        num.setHint("024 123 4567");
        num.setInputType(InputType.TYPE_CLASS_PHONE);
        num.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26);
        num.setSingleLine(true);
        num.setTextColor(0xff13201a);
        chk.addView(num);
        result = text("", 18, OK, true);
        result.setPadding(dp(14), dp(12), dp(14), dp(12));
        result.setVisibility(View.GONE);
        LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(-1, -2);
        l.topMargin = dp(10);
        chk.addView(result, l);
        num.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            public void onTextChanged(CharSequence s, int a, int b, int c) {}
            public void afterTextChanged(Editable s) { check(s.toString()); }
        });
    }

    @Override protected void onResume() {
        super.onResume();
        store.listener = this;
        svc.setVisibility(serviceOn() ? View.GONE : View.VISIBLE);
        store.syncAsync();   // quietly fetch any new numbers if there's internet
    }

    @Override protected void onPause() { super.onPause(); if (store.listener == this) store.listener = null; }

    @Override public void changed() { runOnUiThread(new Runnable() { public void run() { check(num.getText().toString()); } }); }

    private void check(String raw) {
        if (raw.replaceAll("\\D", "").isEmpty()) { result.setVisibility(View.GONE); return; }
        String n = Store.normalize(raw);
        if (n == null) { show(WARN, "Keep typing… (10 digits)"); return; }
        int r = store.lookup(n);
        if (r < 0) show(WARN, "List not ready yet. Connect to the internet once.");
        else if (r == 1) show(BAD, "⚠ ALREADY REGISTERED\n" + Store.pretty(n) + " is in the system. Do not register again.");
        else show(OK, "✓ NEW NUMBER\n" + Store.pretty(n) + " is not in the system. OK to register.");
    }

    private void show(int color, String msg) {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor((color & 0x00ffffff) | 0x22000000); bg.setCornerRadius(dp(12));
        result.setBackground(bg); result.setTextColor(color); result.setText(msg);
        result.setVisibility(View.VISIBLE);
    }

    private boolean serviceOn() {
        String s = Settings.Secure.getString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (s == null) return false;
        String me = new ComponentName(this, WatchService.class).flattenToString();
        TextUtils.SimpleStringSplitter sp = new TextUtils.SimpleStringSplitter(':'); sp.setString(s);
        while (sp.hasNext()) { String x = sp.next(); if (x.equalsIgnoreCase(me) || (x.startsWith(getPackageName()) && x.endsWith("WatchService"))) return true; }
        return false;
    }

    /* ---------- tiny view helpers ---------- */
    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(16), dp(14), dp(16), dp(14));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xffffffff); bg.setCornerRadius(dp(14)); bg.setStroke(dp(1), 0xffdfe5e0);
        c.setBackground(bg);
        LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(-1, -2);
        l.setMargins(dp(14), dp(12), dp(14), 0);
        root.addView(c, l);
        return c;
    }
    private TextView text(String s, int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s); t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp); t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(0, dp(2), 0, dp(4));
        return t;
    }
    private Button button(String s) {
        Button b = new Button(this);
        b.setText(s); b.setAllCaps(false); b.setTextColor(0xffffffff);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(BRAND); bg.setCornerRadius(dp(12));
        b.setBackground(bg);
        LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(-1, dp(48)); l.topMargin = dp(8);
        b.setLayoutParams(l);
        b.setGravity(Gravity.CENTER);
        return b;
    }
    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }
}
