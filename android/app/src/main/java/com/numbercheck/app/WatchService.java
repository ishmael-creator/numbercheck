package com.numbercheck.app;

import android.accessibilityservice.AccessibilityService;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.TextView;


/**
 * Invisible until a full phone number is typed in SurveyCTO, then shows a small
 * green (new) or red (already registered) pill at the top of the screen.
 * Uses an accessibility overlay, so no "draw over other apps" permission is needed.
 */
public class WatchService extends AccessibilityService {
    private static final long SYNC_EVERY_MS = 60 * 60 * 1000L; // only fetches a tiny file unless the list changed
    private final Handler ui = new Handler(Looper.getMainLooper());
    private WindowManager wm;
    private TextView pill;
    private boolean shown;
    private String lastNumber;      // number currently displayed
    private Store store;

    private final Runnable hide = new Runnable() { public void run() { hidePill(); } };
    private final Runnable periodicSync = new Runnable() {
        public void run() { store.syncAsync(); ui.postDelayed(this, SYNC_EVERY_MS); }
    };

    @Override protected void onServiceConnected() {
        store = Store.get(this);
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        pill = new TextView(this);
        pill.setTextColor(0xffffffff);
        pill.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        int pad = dp(14);
        pill.setPadding(pad, dp(10), pad, dp(10));
        pill.setElevation(dp(6));
        pill.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { hidePill(); } });
        ui.postDelayed(periodicSync, 3000);
        try {
            android.net.ConnectivityManager cm = (android.net.ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            netCb = new android.net.ConnectivityManager.NetworkCallback() {
                @Override public void onAvailable(android.net.Network n) { ui.postDelayed(new Runnable() { public void run() { store.syncAsync(); } }, 2000); }
            };
            cm.registerDefaultNetworkCallback(netCb);
        } catch (Exception ignored) {}
    }
    private android.net.ConnectivityManager.NetworkCallback netCb;

    @Override public void onAccessibilityEvent(AccessibilityEvent e) {
        if (e == null || store == null) return;
        CharSequence pkg = e.getPackageName();
        if (pkg == null || pkg.toString().equals(getPackageName())) return;
        if (!pkg.toString().toLowerCase().contains("surveycto")) {
            if (e.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && shown) hidePill();
            return;
        }
        int t = e.getEventType();
        if (t != AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED && t != AccessibilityEvent.TYPE_VIEW_FOCUSED) return;
        if (e.isPassword()) return;

        CharSequence text = null;
        AccessibilityNodeInfo src = e.getSource();
        if (src != null) {
            if (src.isEditable() || src.isFocused()) text = src.getText();
            src.recycle();
        }
        if (text == null && !e.getText().isEmpty()) text = e.getText().get(0);
        if (text == null) return;

        String n = Store.normalize(text);
        if (n == null) {
            // field edited to something that's not a full number → clear stale alert
            if (t == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED && lastNumber != null) { lastNumber = null; hidePill(); }
            return;
        }
        if (n.equals(lastNumber) && shown) return;
        lastNumber = n;
        evaluate(n);
    }

    private void evaluate(String n) {
        int r = store.lookup(n);
        if (r < 0) { show(0xff9a6a00, "NumberCheck: list not ready — open the NumberCheck app once", 5000); return; }
        if (r == 1) {
            show(0xffc8312b, "⚠ ALREADY REGISTERED\n" + Store.pretty(n) + " — do not register again", 0);
            vibrate();
        } else {
            show(0xff11834f, "✓ NEW — " + Store.pretty(n) + " is OK to register", 6000);
        }
    }

    private void show(int color, String msg, long autoHideMs) {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(color); bg.setCornerRadius(dp(16));
        pill.setBackground(bg);
        pill.setText(msg);
        if (!shown) {
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
            lp.y = dp(40);
            try { wm.addView(pill, lp); shown = true; } catch (Exception ignored) { return; }
        }
        ui.removeCallbacks(hide);
        if (autoHideMs > 0) ui.postDelayed(hide, autoHideMs);
    }

    private void hidePill() {
        ui.removeCallbacks(hide);
        if (shown) { try { wm.removeView(pill); } catch (Exception ignored) {} shown = false; }
    }

    private void vibrate() {
        try {
            Vibrator v = (Vibrator) getSystemService(VIBRATOR_SERVICE);
            if (v == null) return;
            long[] pattern = {0, 150, 80, 150};
            if (Build.VERSION.SDK_INT >= 26) v.vibrate(VibrationEffect.createWaveform(pattern, -1));
            else v.vibrate(pattern, -1);
        } catch (Exception ignored) {}
    }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    @Override public void onInterrupt() { hidePill(); }
    @Override public void onDestroy() {
        ui.removeCallbacksAndMessages(null); hidePill();
        try { if (netCb != null) ((android.net.ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE)).unregisterNetworkCallback(netCb); } catch (Exception ignored) {}
        super.onDestroy();
    }
}
