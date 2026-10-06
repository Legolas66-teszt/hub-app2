package hu.zigbeehub.app;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * A hub címei (beállítás), a hub próbája (/api/ping) és a Tailscale app vezérlése.
 *
 * A Tailscale Android-appja más appok kérésére be- és kikapcsol (IPNReceiver: com.tailscale.ipn.CONNECT_VPN /
 * DISCONNECT_VPN). Az app csak azt kapcsolja ki, amit maga kapcsolt be ("we_started") – a kézzel bekapcsolt
 * Tailscale-hez nem nyúl.
 */
final class Hub {
    static final String TS_PKG = "com.tailscale.ipn";
    static final String TS_RCV = "com.tailscale.ipn.IPNReceiver";

    private static final String PREFS = "hub";
    private static final String K_LOCAL = "local";   /* http://192.168.0.115 – a felhasználó adja meg */
    private static final String K_TS = "ts";         /* http://100.x.y.z – a hub mondja meg (/api/ping) */
    private static final String K_ID = "id";         /* a hub azonosítója (Wi-Fi MAC) – más hálózat azonos IP-jű eszköze ellen */
    private static final String K_WE = "we_started"; /* a Tailscale-t ez az app kapcsolta be */
    private static final String K_OWN = "own_ts";    /* a Tailscale csak a hubhoz kell: az app akkor is kikapcsolja, ha kézzel kapcsolták be */

    static final class Ping {
        String id, ip, ts;
    }

    private Hub() {}

    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static String local(Context c) { return prefs(c).getString(K_LOCAL, ""); }
    static String ts(Context c) { return prefs(c).getString(K_TS, ""); }
    static boolean weStarted(Context c) { return prefs(c).getBoolean(K_WE, false); }
    static boolean ownTs(Context c) { return prefs(c).getBoolean(K_OWN, true); }
    static void setOwnTs(Context c, boolean v) { prefs(c).edit().putBoolean(K_OWN, v).apply(); }
    /** Ki kell-e kapcsolnia az appnak a Tailscale-t (otthon / a használat után). */
    static boolean offIsOurs(Context c) { return weStarted(c) || ownTs(c); }

    /** "192.168.0.115", "http://zigbee-hub.local/" → "http://192.168.0.115", "http://zigbee-hub.local"; üres, ha üres. */
    static String normalize(String s) {
        s = s == null ? "" : s.trim();
        if (s.isEmpty()) {
            return "";
        }
        if (!s.startsWith("http://") && !s.startsWith("https://")) {
            s = "http://" + s;
        }
        while (s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }

    /** Új helyi cím: a hub azonosítóját újra megtanulja (lehet, hogy másik hub). */
    static void setLocal(Context c, String url) {
        String old = local(c);
        SharedPreferences.Editor e = prefs(c).edit().putString(K_LOCAL, url);
        if (!old.equals(url)) {
            e.remove(K_ID);
        }
        e.apply();
    }

    /** A helyi címen válaszolt: ez-e a mi hubunk? Az első válasznál megjegyzi az azonosítót és a Tailscale-címet. */
    static boolean accept(Context c, Ping p) {
        if (p == null || p.id == null || p.id.isEmpty()) {
            return false;
        }
        SharedPreferences sp = prefs(c);
        String id = sp.getString(K_ID, "");
        if (!id.isEmpty() && !id.equals(p.id)) {
            return false; /* más eszköz ugyanezen a címen (idegen hálózat) */
        }
        SharedPreferences.Editor e = sp.edit().putString(K_ID, p.id);
        if (p.ts != null && !p.ts.isEmpty()) {
            e.putString(K_TS, "http://" + p.ts);
        }
        e.apply();
        return true;
    }

    /** A Tailscale-címen válaszolt: a mi hubunk-e (ha az azonosítót már ismerjük). */
    static boolean acceptTs(Context c, Ping p) {
        if (p == null || p.id == null) {
            return false;
        }
        String id = prefs(c).getString(K_ID, "");
        return id.isEmpty() || id.equals(p.id);
    }

    /** GET <base>/api/ping – null, ha nem válaszol időben / nem a hub. Háttérszálon hívandó. */
    static Ping ping(String base, int timeoutMs) {
        if (base == null || base.isEmpty()) {
            return null;
        }
        HttpURLConnection h = null;
        try {
            h = (HttpURLConnection) new URL(base + "/api/ping").openConnection();
            h.setConnectTimeout(timeoutMs);
            h.setReadTimeout(timeoutMs);
            h.setUseCaches(false);
            h.setInstanceFollowRedirects(false);
            if (h.getResponseCode() != 200) {
                return null;
            }
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            try (InputStream in = h.getInputStream()) {
                byte[] b = new byte[512];
                int n;
                while ((n = in.read(b)) > 0 && bo.size() < 4096) {
                    bo.write(b, 0, n);
                }
            }
            JSONObject j = new JSONObject(new String(bo.toByteArray(), StandardCharsets.UTF_8));
            Ping p = new Ping();
            p.id = j.optString("id", "");
            p.ip = j.optString("ip", "");
            p.ts = j.optString("ts", "");
            return p.id.isEmpty() ? null : p;
        } catch (Exception e) {
            return null;
        } finally {
            if (h != null) {
                h.disconnect();
            }
        }
    }

    static boolean tsInstalled(Context c) {
        try {
            c.getPackageManager().getPackageInfo(TS_PKG, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    /** Fut-e VPN (bármelyik) a telefonon. */
    static boolean vpnUp(Context c) {
        ConnectivityManager cm = c.getSystemService(ConnectivityManager.class);
        if (cm == null) {
            return false;
        }
        for (Network n : cm.getAllNetworks()) {
            NetworkCapabilities nc = cm.getNetworkCapabilities(n);
            if (nc != null && nc.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                return true;
            }
        }
        return false;
    }

    /** Wifin van-e a telefon (különben a helyi címet meg sem próbáljuk – mobilneten úgysem érhető el). */
    static boolean onWifi(Context c) {
        ConnectivityManager cm = c.getSystemService(ConnectivityManager.class);
        if (cm == null) {
            return true;
        }
        for (Network n : cm.getAllNetworks()) {
            NetworkCapabilities nc = cm.getNetworkCapabilities(n);
            if (nc != null && (nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                    || nc.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))) {
                return true;
            }
        }
        return false;
    }

    private static void tsSend(Context c, String action) {
        Intent i = new Intent(action);
        i.setComponent(new ComponentName(TS_PKG, TS_RCV));
        c.sendBroadcast(i);
    }

    /** Tailscale be; we = true: ez az app kapcsolta be (később ő kapcsolja ki). */
    static void tsOn(Context c, boolean we) {
        if (we) {
            prefs(c).edit().putBoolean(K_WE, true).apply();
        }
        tsSend(c, "com.tailscale.ipn.CONNECT_VPN");
    }

    /** A felhasználó a mi kérésünkre kapcsolta be (a Tailscale appban): a háttérbe kerülés után mi kapcsoljuk ki. */
    static void markOurs(Context c) {
        prefs(c).edit().putBoolean(K_WE, true).apply();
    }

    /** Tailscale ki – csak ha ez az app kapcsolta be. */
    static void tsOffIfOurs(Context c) {
        if (offIsOurs(c)) {
            prefs(c).edit().putBoolean(K_WE, false).apply();
            if (vpnUp(c)) {
                tsSend(c, "com.tailscale.ipn.DISCONNECT_VPN");
            }
        }
    }
}
