package hu.zigbeehub.app;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;

/**
 * Az app háttérbe kerülése után 2 perccel kikapcsolja a Tailscale-t – ha az app kapcsolta be, vagy a beállítás szerint
 * a Tailscale csak a hubhoz kell (alapból így).
 * Ha közben újra megnyitod, az időzítés törlődik (a VPN marad).
 */
public class OffReceiver extends BroadcastReceiver {
    static final long DELAY_MS = 2 * 60 * 1000L;

    @Override
    public void onReceive(Context c, Intent i) {
        Hub.tsOffIfOurs(c);
    }

    private static PendingIntent pi(Context c) {
        return PendingIntent.getBroadcast(c, 1, new Intent(c, OffReceiver.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    static void schedule(Context c) {
        if (!Hub.offIsOurs(c)) {
            return;
        }
        AlarmManager am = c.getSystemService(AlarmManager.class);
        if (am != null) {
            am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME, SystemClock.elapsedRealtime() + DELAY_MS, pi(c));
        }
    }

    static void cancel(Context c) {
        AlarmManager am = c.getSystemService(AlarmManager.class);
        if (am != null) {
            am.cancel(pi(c));
        }
    }
}
