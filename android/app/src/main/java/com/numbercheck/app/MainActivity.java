package com.numbercheck.app;

import android.app.Activity;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.provider.Settings;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.text.format.DateUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;


public class MainActivity extends Activity implements Store.Listener {
    private static final int BRAND = 0xff0f3d2e, OK = 0xff11834f, BAD = 0xffc8312b, WARN = 0xff9a6a00, MUTE = 0xff64736b;
    private Store store;
    private LinearLayout root, setupBox;
    private TextView status, result, serviceState, syncMsg;
    private EditText num, codeIn;
    private Button serviceBtn;

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

        // --- setup
        setupBox = card();
        setupBox.addView(text("Set up this phone", 16, 0xff13201a, true));
        setupBox.addView(text("Paste the setup link from your supervisor. Use Wi-Fi the first time: the list is about 1 MB.", 13, MUTE, false));
        codeIn = input("Setup link or code", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS, 15);
        setupBox.addView(codeIn);
        Button paste = button("Paste setup link", false);
        paste.setOnClickListener(new View.OnClickListener() { public void onClick(View v) {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null && cm.hasPrimaryClip() && cm.getPrimaryClip().getItemCount() > 0)
                codeIn.setText(cm.getPrimaryClip().getItemAt(0).coerceToText(MainActivity.this));
        }});
        setupBox.addView(paste);
        Button save = button("Save", true);
        save.setOnClickListener(new View.OnClickListener() { public void onClick(View v) {
            if (!store.setup(codeIn.getText().toString())) { toastMsg("That setup link is not valid"); return; }
            setupBox.setVisibility(View.GONE);
            doSync();
        }});
        setupBox.addView(save);

        // --- automatic SurveyCTO check
        LinearLayout svc = card();
        svc.addView(text("Automatic check in SurveyCTO", 16, 0xff13201a, true));
        serviceState = text("", 14, MUTE, false);
        svc.addView(serviceState);
        serviceBtn = button("Turn on", true);
        serviceBtn.setOnClickListener(new View.OnClickListener() { public void onClick(View v) {
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
        }});
        svc.addView(serviceBtn);
        CheckBox all = new CheckBox(this);
        all.setText("Also watch other apps (for testing)");
        all.setTextColor(MUTE);
        all.setChecked(store.watchAll());
        all.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(CompoundButton c, boolean v) { store.setWatchAll(v); }
        });
        svc.addView(all);

        // --- manual check
        LinearLayout chk = card();
        chk.addView(text("Check a number", 16, 0xff13201a, true));
        num = input("024 123 4567", InputType.TYPE_CLASS_PHONE, 24);
        chk.addView(num);
        result = text("", 17, OK, true);
        result.setPadding(dp(14), dp(12), dp(14), dp(12));
        result.setVisibility(View.GONE);
        chk.addView(result, lp(-1, -2, 10));
        num.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            public void onTextChanged(CharSequence s, int a, int b, int c) {}
            public void afterTextChanged(Editable s) { check(s.toString()); }
        });

        // --- status
        LinearLayout st = card();
        status = text("", 14, 0xff13201a, false);
        st.addView(status);
        Button sync = button("Update list now", true);
        sync.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { doSync(); } });
        st.addView(sync);
        syncMsg = text("", 13, MUTE, false);
        st.addView(syncMsg);
        Button change = button("Change setup link", false);
        change.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { setupBox.setVisibility(View.VISIBLE); } });
        st.addView(change);

        if (store.configured()) setupBox.setVisibility(View.GONE);
    }

    @Override protected void onResume() {
        super.onResume();
        store.listener = this;
        refresh();
        if (store.configured() && System.currentTimeMillis() - store.lastSync() > 60 * 60 * 1000) doSync();
    }

    @Override protected void onPause() { super.onPause(); if (store.listener == this) store.listener = null; }

    @Override public void changed() { runOnUiThread(new Runnable() { public void run() { refresh(); } }); }

    private void doSync() {
        if (!store.online()) { syncMsg.setText("No internet. Checking still works with the saved list."); return; }
        syncMsg.setText("Checking for updates…");
        new Thread(new Runnable() { public void run() {
            final String m = store.sync();
            runOnUiThread(new Runnable() { public void run() { syncMsg.setText(m); refresh(); check(num.getText().toString()); } });
        }}).start();
    }

    private void refresh() {
        long ls = store.lastSync();
        String up = store.updated();
        status.setText("Numbers on this phone: " + store.count()
                + "\nList from: " + (up == null ? "—" : up.replace("T", " ").replace("Z", " UTC"))
                + "\nLast checked for updates: " + (ls == 0 ? "never" : DateUtils.getRelativeTimeSpanString(ls)));
        boolean on = serviceOn();
        serviceState.setText(on ? "ON — a small alert will pop up when you type a phone number in SurveyCTO."
                : "OFF — tap Turn on, then choose NumberCheck and switch it on.\nIf it's greyed out: open App info › ⋮ › Allow restricted settings, then try again.");
        serviceState.setTextColor(on ? OK : WARN);
        serviceBtn.setVisibility(on ? View.GONE : View.VISIBLE);

    }

    private void check(String raw) {
        if (raw.replaceAll("\\D", "").isEmpty()) { result.setVisibility(View.GONE); return; }
        String n = Store.normalize(raw);
        if (n == null) { showResult(WARN, "Keep typing… (full 10-digit number)"); return; }
        int r = store.lookup(n);
        if (r < 0) showResult(WARN, "No list on this phone yet — connect and tap Update list now");
        else if (r == 1) showResult(BAD, "⚠ ALREADY REGISTERED\n" + Store.pretty(n) + " is in the system. Do not register again.");
        else showResult(OK, "✓ NEW NUMBER\n" + Store.pretty(n) + " is not in the system. OK to register.");
    }

    private void showResult(int color, String msg) {
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
        while (sp.hasNext()) { String x = sp.next(); if (x.equalsIgnoreCase(me) || x.endsWith("/.WatchService") && x.startsWith(getPackageName())) return true; }
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
    private EditText input(String hint, int type, int sp) {
        EditText e = new EditText(this);
        e.setHint(hint); e.setInputType(type); e.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        e.setSingleLine(true); e.setTextColor(0xff13201a);
        return e;
    }
    private Button button(String s, boolean primary) {
        Button b = new Button(this);
        b.setText(s); b.setAllCaps(false);
        b.setTextColor(primary ? 0xffffffff : BRAND);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(primary ? BRAND : 0xffffffff); bg.setCornerRadius(dp(12));
        if (!primary) bg.setStroke(dp(1), 0xffdfe5e0);
        b.setBackground(bg);
        b.setLayoutParams(lp(-1, dp(48), 8));
        b.setGravity(Gravity.CENTER);
        return b;
    }
    private LinearLayout.LayoutParams lp(int w, int h, int top) {
        LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(w, h); l.topMargin = dp(top); return l;
    }
    private void toastMsg(String m) { android.widget.Toast.makeText(this, m, android.widget.Toast.LENGTH_SHORT).show(); }
    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }
}
