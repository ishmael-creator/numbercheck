package com.numbercheck.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.Arrays;

/**
 * Keeps the scrambled number list on the phone (5 bytes per number, sorted) and updates it
 * from the website: a tiny manifest first, then only the day's changes.
 * Checks never use the network.
 */
final class Store {
    static final int HB = 5;
    private static Store inst;
    static synchronized Store get(Context c) {
        if (inst == null) inst = new Store(c.getApplicationContext());
        return inst;
    }

    final Context ctx;
    final SharedPreferences p;
    private long[] list = new long[0];   // sorted
    private volatile boolean syncing;
    interface Listener { void changed(); }
    Listener listener;

    private Store(Context c) {
        ctx = c;
        p = c.getSharedPreferences("nc2", Context.MODE_PRIVATE);
        list = readFile(new File(ctx.getFilesDir(), "list.bin"));
    }

    /* ---------- setup ---------- */
    boolean configured() { return p.getString("salt", null) != null; }
    boolean watchAll() { return p.getBoolean("watchAll", false); }
    void setWatchAll(boolean b) { p.edit().putBoolean("watchAll", b).apply(); }
    String site() { return p.getString("site", ""); }

    /** Accepts the setup code, or the whole setup link (https://…/#setup=CODE). */
    boolean setup(String code) {
        try {
            code = code.trim();
            String site = null;
            int i = code.indexOf("#setup=");
            if (i > 0 && code.startsWith("https://")) site = code.substring(0, i).replaceAll("/+$", "");
            i = code.indexOf("setup=");
            if (i >= 0) code = code.substring(i + 6);
            code = code.replaceAll("[^A-Za-z0-9_\\-=]", "");
            JSONObject o = new JSONObject(new String(Base64.decode(code, Base64.URL_SAFE | Base64.NO_WRAP), "UTF-8"));
            String s = o.getString("s");
            if (o.has("u")) site = o.getString("u").replaceAll("/+$", "");
            if (site == null || !site.startsWith("https://")) return false;
            p.edit().putString("salt", s).putString("site", site).remove("version").remove("updated").apply();
            synchronized (this) { list = new long[0]; }
            new File(ctx.getFilesDir(), "list.bin").delete();
            return true;
        } catch (Exception e) { return false; }
    }

    /* ---------- normalisation & scrambling (must match scripts/build_list.py and the web app) ---------- */
    static String normalize(CharSequence raw) {
        if (raw == null) return null;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < raw.length(); i++) { char c = raw.charAt(i); if (c >= '0' && c <= '9') sb.append(c); }
        String d = sb.toString();
        if (d.startsWith("00233")) d = d.substring(5);
        else if (d.startsWith("233") && d.length() >= 12) d = d.substring(3);
        if (d.length() == 9 && d.charAt(0) != '0') d = "0" + d;
        return d.matches("0\\d{9}") ? d : null;
    }

    private static byte[] sha(String s) throws Exception {
        return MessageDigest.getInstance("SHA-256").digest(s.getBytes("UTF-8"));
    }

    private long scramble(String n) throws Exception { return read5(sha("ncx1:" + p.getString("salt", "") + ":" + n), 0); }

    private String saltCheck() throws Exception {
        byte[] b = sha("check:" + p.getString("salt", ""));
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 6; i++) sb.append(String.format("%02x", b[i] & 0xff));
        return sb.toString();
    }

    static long read5(byte[] b, int o) {
        long v = 0;
        for (int i = 0; i < HB; i++) v = (v << 8) | (b[o + i] & 0xff);
        return v;
    }

    static String pretty(String n) { return n.substring(0, 3) + " " + n.substring(3, 6) + " " + n.substring(6); }

    /** 1 = already registered, 0 = new, -1 = no list on this phone yet. Instant, offline. */
    synchronized int lookup(String n) {
        if (!configured() || list.length == 0) return -1;
        try { return Arrays.binarySearch(list, scramble(n)) >= 0 ? 1 : 0; } catch (Exception e) { return -1; }
    }

    int count() { return list.length; }
    long lastSync() { return p.getLong("lastSync", 0); }
    String updated() { return p.getString("updated", null); }

    /* ---------- storage ---------- */
    private static long[] readFile(File f) {
        if (!f.exists()) return new long[0];
        try { return decode(readAll(new FileInputStream(f)), 0, (int) (f.length() / HB)); }
        catch (Exception e) { return new long[0]; }
    }

    private static long[] decode(byte[] b, int off, int count) {
        long[] a = new long[count];
        for (int i = 0; i < count; i++) a[i] = read5(b, off + i * HB);
        return a;
    }

    private void saveList(long[] a) throws Exception {
        File tmp = new File(ctx.getFilesDir(), "list.tmp");
        try (OutputStream o = new BufferedOutputStream(new FileOutputStream(tmp))) {
            byte[] buf = new byte[HB];
            for (long v : a) { for (int j = HB - 1; j >= 0; j--) { buf[j] = (byte) (v & 0xff); v >>>= 8; } o.write(buf); }
        }
        if (!tmp.renameTo(new File(ctx.getFilesDir(), "list.bin"))) throw new Exception("could not save list");
    }

    /* ---------- update ---------- */
    boolean online() {
        ConnectivityManager cm = (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo ni = cm == null ? null : cm.getActiveNetworkInfo();
        return ni != null && ni.isConnected();
    }

    void syncAsync() {
        if (!configured() || syncing || !online()) return;
        new Thread(new Runnable() { public void run() { sync(); } }).start();
    }

    /** Blocking — call from a background thread. Returns a short message for the agent. */
    String sync() {
        if (!configured()) return "Not set up";
        if (syncing) return "Already updating";
        syncing = true;
        try {
            String base = site() + "/data/";
            JSONObject m = new JSONObject(new String(get(base + "manifest.json"), "UTF-8"));
            if (!saltCheck().equals(m.getString("saltCheck"))) return "Setup code is out of date. Ask your supervisor for the new one.";
            long target = m.getLong("version"), mine = p.getLong("version", -1);
            String msg;
            if (mine == target && list.length > 0) {
                msg = "List is up to date";
            } else {
                // follow the chain of daily changes from our version; otherwise download the full list
                JSONArray ds = m.getJSONArray("deltas");
                java.util.List<JSONObject> chain = new java.util.ArrayList<>();
                long v = list.length > 0 ? mine : -1;
                while (v != -1 && v != target) {
                    JSONObject next = null;
                    for (int i = 0; i < ds.length(); i++) if (ds.getJSONObject(i).getLong("from") == v) next = ds.getJSONObject(i);
                    if (next == null) { chain = null; break; }
                    chain.add(next); v = next.getLong("to");
                }
                long[] result;
                if (chain != null && !chain.isEmpty()) {
                    result = list;
                    long bytes = 0;
                    for (JSONObject d : chain) {
                        byte[] b = get(base + d.getString("file"));
                        bytes += b.length;
                        int na = readInt(b, 0);
                        long[] adds = decode(b, 4, na);
                        int nr = readInt(b, 4 + na * HB);
                        long[] rem = decode(b, 8 + na * HB, nr);
                        Arrays.sort(adds); Arrays.sort(rem);
                        result = apply(result, adds, rem);
                    }
                    msg = "Updated (" + bytes + " bytes downloaded)";
                } else {
                    byte[] b = get(base + m.getString("full"));
                    result = decode(b, 0, b.length / HB);
                    Arrays.sort(result);
                    msg = "Full list downloaded (" + (b.length / 1024) + " KB)";
                }
                saveList(result);
                synchronized (this) { list = result; }
                p.edit().putLong("version", target).putString("updated", m.optString("updated", null)).apply();
            }
            p.edit().putLong("lastSync", System.currentTimeMillis()).apply();
            return msg;
        } catch (Exception e) {
            return "Could not update (" + e.getClass().getSimpleName() + "). Checking still works with the saved list.";
        } finally {
            syncing = false;
            Listener l = listener; if (l != null) l.changed();
        }
    }

    /** sorted(list ∪ adds) minus removes — all arrays sorted, no extra memory beyond the result. */
    static long[] apply(long[] list, long[] adds, long[] rem) {
        long[] out = new long[list.length + adds.length];
        int i = 0, j = 0, k = 0, n = 0;
        while (i < list.length || j < adds.length) {
            long v;
            if (j >= adds.length || (i < list.length && list[i] <= adds[j])) { v = list[i++]; if (j < adds.length && adds[j] == v) j++; }
            else v = adds[j++];
            while (k < rem.length && rem[k] < v) k++;
            if (k < rem.length && rem[k] == v) continue;
            if (n > 0 && out[n - 1] == v) continue;
            out[n++] = v;
        }
        return Arrays.copyOf(out, n);
    }

    private static int readInt(byte[] b, int o) {
        return ((b[o] & 0xff) << 24) | ((b[o + 1] & 0xff) << 16) | ((b[o + 2] & 0xff) << 8) | (b[o + 3] & 0xff);
    }

    private static byte[] get(String url) throws Exception {
        for (int hop = 0; hop < 5; hop++) {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(20000); c.setReadTimeout(60000);
            c.setUseCaches(false);
            c.setRequestProperty("Cache-Control", "no-cache");
            int code = c.getResponseCode();
            if (code >= 300 && code < 400) { url = c.getHeaderField("Location"); c.disconnect(); continue; }
            if (code != 200) { c.disconnect(); throw new Exception("HTTP " + code); }
            byte[] b = readAll(c.getInputStream());
            c.disconnect();
            return b;
        }
        throw new Exception("Too many redirects");
    }

    private static byte[] readAll(InputStream in) throws Exception {
        try (InputStream i = in) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[16384]; int r;
            while ((r = i.read(buf)) > 0) bo.write(buf, 0, r);
            return bo.toByteArray();
        }
    }
}
