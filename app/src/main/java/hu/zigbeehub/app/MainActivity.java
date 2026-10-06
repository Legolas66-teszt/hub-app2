package hu.zigbeehub.app;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * A hub weboldala egy WebView-ban – ugyanaz az oldal, mint a böngészőben.
 *
 * Indításkor / előtérbe kerüléskor: wifin előbb a helyi cím (/api/ping, ~1 s). Ha az otthoni hub válaszol, azt
 * nyitja meg (és ha az app kapcsolta be a Tailscale-t, kikapcsolja). Ha nem: bekapcsolja a Tailscale-t (ha nem fut),
 * és a hub Tailscale-címét nyitja meg. Háttérbe kerülés után 3 perccel kikapcsolja a Tailscale-t, ha ő kapcsolta be.
 */
public class MainActivity extends Activity {
    private static final int RC_FILE = 1, RC_SAVE = 2;
    private static final int LOCAL_TIMEOUT_MS = 1200;
    private static final int TS_TIMEOUT_MS = 4000;
    private static final int TS_UP_S = 10;      /* ennyi idő alatt kell a Tailscale-nek bekapcsolnia a kérésre */
    private static final int TS_WAIT_S = 45;    /* utána ennyi ideig várunk, hogy a hub válaszoljon rajta */

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService bg = Executors.newSingleThreadExecutor();

    private WebView web;
    private View cover;
    private TextView msg;
    private ProgressBar spin;
    private Button retry, tsOpen;

    private String base;            /* a WebView-ban most megnyitott cím, null = semmi */
    private volatile boolean remote;
    private volatile int gen;       /* a futó próba sorszáma: az újabb próba a régit érvényteleníti */
    private volatile boolean busy;  /* fut egy próba – a hálózatváltás ilyenkor nem indít újat (a VPN be- / kikapcsolása is az) */
    private boolean started;

    private ValueCallback<Uri[]> fileCb;
    private String saveText;

    private ConnectivityManager.NetworkCallback netCb;
    private final Runnable reprobe = () -> probe();
    /* háttérben 2 perc múlva Tailscale ki – pontosan, amíg a folyamat él; az OffReceiver ébresztője a tartalék
     * (az alvó telefonon az Android azt később is futtathatja) */
    private final Runnable offTimer = () -> bg.execute(() -> Hub.tsOffIfOurs(this));

    // ------------------------------------------------------------------ felület

    private int dp(float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()));
    }

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(getColor(R.color.bg));

        web = new WebView(this);
        web.setBackgroundColor(getColor(R.color.bg));
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        web.addJavascriptInterface(new Bridge(), "HubApp");
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                Uri u = r.getUrl();
                if (base != null && u.toString().startsWith(base)) {
                    return false; /* a hub saját oldala */
                }
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, u)); /* minden más a böngészőben */
                } catch (Exception ignored) {
                }
                return true;
            }

            @Override
            public void onReceivedError(WebView v, WebResourceRequest r, android.webkit.WebResourceError e) {
                if (r.isForMainFrame()) {
                    base = null; /* az oldal nem töltött be – az Újra gomb újra megkeresi a hubot */
                    status("Az oldal nem töltött be.", false);
                }
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            /* a megerősítő kérdések (confirm / alert) az alap WebChromeClient párbeszédablakai */
            @Override
            public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> cb, FileChooserParams p) {
                if (fileCb != null) {
                    fileCb.onReceiveValue(null);
                }
                fileCb = cb;
                Intent i = new Intent(Intent.ACTION_GET_CONTENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("*/*");
                try {
                    startActivityForResult(Intent.createChooser(i, "Fájl kiválasztása"), RC_FILE);
                } catch (Exception e) {
                    fileCb = null;
                    return false;
                }
                return true;
            }
        });
        root.addView(web, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        /* takaró: kapcsolódás közben / hibánál */
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setGravity(Gravity.CENTER);
        c.setPadding(dp(32), dp(32), dp(32), dp(32));
        c.setBackgroundColor(getColor(R.color.bg));
        c.setClickable(true);
        spin = new ProgressBar(this);
        c.addView(spin, new LinearLayout.LayoutParams(dp(40), dp(40)));
        msg = new TextView(this);
        msg.setTextColor(getColor(R.color.fg));
        msg.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        msg.setGravity(Gravity.CENTER);
        msg.setLineSpacing(0, 1.2f);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(20);
        c.addView(msg, lp);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        retry = button("Újra", true);
        retry.setOnClickListener(v -> {
            base = null;
            probe();
        });
        Button set = button("Címek", false);
        set.setOnClickListener(v -> settings());
        tsOpen = button("Tailscale megnyitása", true);
        tsOpen.setOnClickListener(v -> openTailscale());
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tp.topMargin = dp(24);
        c.addView(tsOpen, tp);
        row.addView(retry);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bp.leftMargin = dp(12);
        row.addView(set, bp);
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rp.topMargin = dp(24);
        c.addView(row, rp);
        cover = c;
        root.addView(cover, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        setContentView(root);
        status("Kapcsolódás a hubhoz…", true);
    }

    private Button button(String text, boolean primary) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        if (primary) {
            b.setBackgroundColor(getColor(R.color.acc));
            b.setTextColor(Color.WHITE);
        }
        b.setPadding(dp(20), 0, dp(20), 0);
        return b;
    }

    /** A takaró szövege; spinning: forgó jel, különben az Újra / Címek gombok (withTs: + „Tailscale megnyitása”). */
    private void status(String text, boolean spinning) {
        status(text, spinning, false);
    }

    private void status(String text, boolean spinning, boolean withTs) {
        cover.setVisibility(View.VISIBLE);
        msg.setText(text);
        spin.setVisibility(spinning ? View.VISIBLE : View.GONE);
        ((View) retry.getParent()).setVisibility(spinning ? View.GONE : View.VISIBLE);
        tsOpen.setVisibility(!spinning && withTs ? View.VISIBLE : View.GONE);
    }

    /** A Tailscale app megnyitása (ha a kérésre nem kapcsolt be): ott egy koppintás, utána vissza ide. Mivel mi kértük,
     *  a háttérbe kerülés után mi is kapcsoljuk ki. */
    private void openTailscale() {
        Intent i = getPackageManager().getLaunchIntentForPackage(Hub.TS_PKG);
        if (i == null) {
            Toast.makeText(this, "A Tailscale app nincs telepítve", Toast.LENGTH_LONG).show();
            return;
        }
        Hub.markOurs(this);
        startActivity(i);
    }

    private void post(int g, Runnable r) {
        ui.post(() -> {
            if (g == gen && !isFinishing()) {
                r.run();
            }
        });
    }

    private void show(String url, boolean viaTs) {
        remote = viaTs;
        cover.setVisibility(View.GONE);
        if (!url.equals(base)) {
            base = url;
            web.loadUrl(url + "/");
            if (viaTs) {
                Toast.makeText(this, "Távolról, a Tailscale-en át", Toast.LENGTH_SHORT).show();
            }
        }
    }

    // ------------------------------------------------------------------ a hub megkeresése

    /** Háttérben: otthon vagyok-e, ha nem, Tailscale. A már megnyitott, működő oldalt nem takarja el feleslegesen. */
    private void probe() {
        ui.removeCallbacks(reprobe);
        final int g = ++gen;
        final String local = Hub.local(this);
        if (local.isEmpty()) {
            status("Add meg a hub otthoni címét – amit otthon a böngészőbe írsz (pl. 192.168.0.115).", false);
            settings();
            return;
        }
        final boolean shown = base != null;
        bg.execute(() -> {
            busy = true;
            try {
                find(g, local, shown);
            } finally {
                busy = false;
            }
        });
    }

    private static boolean nap(long ms) {
        try {
            Thread.sleep(ms);
            return true;
        } catch (InterruptedException e) {
            return false;
        }
    }

    /** A próba a háttérszálon. */
    private void find(final int g, final String local, final boolean shown) {
        {
            /* 1. otthon: wifin a helyi cím (kétszer, a képernyő bekapcsolása utáni lassú wifi miatt) */
            if (Hub.onWifi(this)) {
                for (int i = 0; i < 2 && g == gen; i++) {
                    Hub.Ping p = Hub.ping(local, LOCAL_TIMEOUT_MS);
                    if (p != null && Hub.accept(this, p)) {
                        post(g, () -> show(local, false));
                        OffReceiver.cancel(this);
                        Hub.tsOffIfOurs(this); /* otthon nem kell */
                        return;
                    }
                }
            }
            if (g != gen) {
                return;
            }
            /* 2. távol: Tailscale */
            final String ts = Hub.ts(this);
            if (ts.isEmpty()) {
                post(g, () -> status("Az otthoni hálózaton nem érem el a hubot.\n\nTávoli eléréshez az appot egyszer "
                        + "otthon kell megnyitni, amikor a hubon a Tailscale be van kapcsolva – akkor megjegyzi a címét.", false));
                return;
            }
            if (!Hub.tsInstalled(this)) {
                post(g, () -> status("Otthonról nem érem el a hubot, a Tailscale app pedig nincs telepítve.", false));
                return;
            }
            Hub.Ping p = Hub.vpnUp(this) ? Hub.ping(ts, TS_TIMEOUT_MS) : null;
            if (p != null && Hub.acceptTs(this, p)) {
                post(g, () -> show(ts, true));
                return;
            }
            final boolean quiet = shown && remote; /* a távoli oldal már látszik: ne takarjuk el */
            if (!Hub.vpnUp(this)) {
                if (!quiet) {
                    post(g, () -> status("Tailscale bekapcsolása…", true));
                }
                Hub.tsOn(this, true);
                long upEnd = System.currentTimeMillis() + TS_UP_S * 1000L;
                while (g == gen && !Hub.vpnUp(this) && System.currentTimeMillis() < upEnd) {
                    if (!nap(500)) {
                        return;
                    }
                }
                if (g != gen) {
                    return;
                }
                if (!Hub.vpnUp(this)) {
                    post(g, () -> status("A Tailscale nem kapcsolt be magától.\n\nNyisd meg, kapcsold be, és gyere vissza – "
                            + "a hub magától betölt.\n\n(Ha a Tailscale értesítést küldött, koppints rá.)", false, true));
                    return;
                }
            }
            long end = System.currentTimeMillis() + TS_WAIT_S * 1000L;
            while (g == gen && System.currentTimeMillis() < end) {
                final long left = Math.max(0, (end - System.currentTimeMillis()) / 1000);
                if (!quiet) {
                    post(g, () -> status("Kapcsolódás a hubhoz a Tailscale-en…\n(" + left + " s)", true));
                }
                p = Hub.ping(ts, TS_TIMEOUT_MS);
                if (p != null && Hub.acceptTs(this, p)) {
                    post(g, () -> show(ts, true));
                    return;
                }
                if (!nap(1000)) {
                    return;
                }
            }
            post(g, () -> status("A Tailscale fut, de a hub nem válaszol rajta (" + ts.substring(7) + ").\n\nNézd meg a "
                    + "Tailscale appban, hogy a hub (zigbee-hub) a listában elérhető-e.", false, true));
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        started = true;
        ui.removeCallbacks(offTimer);
        OffReceiver.cancel(this);
        web.onResume();
        web.resumeTimers();
        probe();
        ConnectivityManager cm = getSystemService(ConnectivityManager.class);
        if (cm != null && netCb == null) {
            netCb = new ConnectivityManager.NetworkCallback() {
                /* hálózatváltás (haza értem / elmentem / bejött a VPN): kis szünet után újra próba */
                @Override
                public void onAvailable(Network n) { later(); }

                @Override
                public void onLost(Network n) { later(); }
            };
            try {
                cm.registerDefaultNetworkCallback(netCb);
            } catch (Exception e) {
                netCb = null;
            }
        }
    }

    private void later() {
        ui.post(() -> {
            if (started && !busy) {
                ui.removeCallbacks(reprobe);
                ui.postDelayed(reprobe, 2000);
            }
        });
    }

    @Override
    protected void onStop() {
        super.onStop();
        started = false;
        ui.removeCallbacks(reprobe);
        ConnectivityManager cm = getSystemService(ConnectivityManager.class);
        if (cm != null && netCb != null) {
            try {
                cm.unregisterNetworkCallback(netCb);
            } catch (Exception ignored) {
            }
            netCb = null;
        }
        /* háttérben az oldal ne kérdezze tovább a hubot (akku, és így a Tailscale is pihenhet) */
        web.onPause();
        web.pauseTimers();
        gen++; /* a futó próba se folytatódjon */
        if (Hub.offIsOurs(this)) {
            ui.postDelayed(offTimer, OffReceiver.DELAY_MS);
            OffReceiver.schedule(this); /* tartalék */
        }
    }

    @Override
    protected void onDestroy() {
        gen++;
        ui.removeCallbacks(offTimer); /* a kikapcsolást innentől az OffReceiver ébresztője végzi */
        bg.shutdownNow();
        web.destroy();
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (web.canGoBack()) {
            web.goBack();
        } else {
            moveTaskToBack(true); /* ne záródjon be – a következő megnyitás azonnali */
        }
    }

    // ------------------------------------------------------------------ címek

    private void settings() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(dp(24), dp(8), dp(24), 0);
        TextView t1 = new TextView(this);
        t1.setText("A hub otthoni címe – amit otthon a böngészőbe írsz:");
        l.addView(t1);
        EditText in = new EditText(this);
        in.setSingleLine(true);
        in.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        in.setHint("pl. 192.168.0.115");
        String cur = Hub.local(this);
        in.setText(cur.startsWith("http://") ? cur.substring(7) : cur);
        l.addView(in);
        TextView t2 = new TextView(this);
        String ts = Hub.ts(this);
        t2.setText(ts.isEmpty()
                ? "\nTávoli elérés: a hub Tailscale-címét az app otthon magától megtudja (ha a hubon be van kapcsolva)."
                : "\nTávoli elérés (Tailscale): " + ts.substring(7) + "\nAz app otthon magától frissíti.");
        t2.setTextColor(getColor(R.color.mut));
        l.addView(t2);
        android.widget.CheckBox own = new android.widget.CheckBox(this);
        own.setText("A Tailscale-t csak a hubhoz használom: az app kapcsolja ki otthon és 2 perccel a használat után "
                + "(akkor is, ha kézzel kapcsoltam be)");
        own.setChecked(Hub.ownTs(this));
        LinearLayout.LayoutParams op = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        op.topMargin = dp(12);
        l.addView(own, op);
        new AlertDialog.Builder(this)
                .setTitle("A hub címe")
                .setView(l)
                .setPositiveButton("Mentés", (d, w) -> {
                    Hub.setOwnTs(this, own.isChecked());
                    String u = Hub.normalize(in.getText().toString());
                    if (!u.isEmpty()) {
                        Hub.setLocal(this, u);
                        base = null;
                    }
                    probe();
                })
                .setNegativeButton("Mégse", null)
                .show();
    }

    // ------------------------------------------------------------------ fájlok (feltöltés, mentés letöltése)

    @Override
    protected void onActivityResult(int rc, int res, Intent data) {
        super.onActivityResult(rc, res, data);
        if (rc == RC_FILE) {
            if (fileCb != null) {
                Uri u = res == RESULT_OK && data != null ? data.getData() : null;
                fileCb.onReceiveValue(u != null ? new Uri[] { u } : null);
                fileCb = null;
            }
        } else if (rc == RC_SAVE) {
            String text = saveText;
            saveText = null;
            Uri u = res == RESULT_OK && data != null ? data.getData() : null;
            if (u == null || text == null) {
                return;
            }
            try (OutputStream o = getContentResolver().openOutputStream(u)) {
                if (o == null) {
                    throw new java.io.IOException();
                }
                o.write(text.getBytes(StandardCharsets.UTF_8));
                Toast.makeText(this, "Mentve", Toast.LENGTH_SHORT).show();
            } catch (Exception e) {
                Toast.makeText(this, "A mentés nem sikerült", Toast.LENGTH_LONG).show();
            }
        }
    }

    private void save(String name, String text) {
        saveText = text;
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("application/json");
        i.putExtra(Intent.EXTRA_TITLE, name);
        try {
            startActivityForResult(i, RC_SAVE);
        } catch (Exception e) {
            saveText = null;
            Toast.makeText(this, "Nincs fájlkezelő a mentéshez", Toast.LENGTH_LONG).show();
        }
    }

    /** A weboldal ezt látja: window.HubApp (index.html: mentés letöltése, „Az app beállításai”). */
    private final class Bridge {
        @JavascriptInterface
        public void saveText(String name, String text) {
            ui.post(() -> save(name, text));
        }

        @JavascriptInterface
        public void settings() {
            ui.post(MainActivity.this::settings);
        }

        @JavascriptInterface
        public String mode() {
            return remote ? "ts" : "local";
        }
    }
}
