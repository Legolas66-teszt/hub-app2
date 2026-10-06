# Hub app (Android)

A Zigbee hub weboldala appként – ugyanaz az oldal, mint a böngészőben. Amit az app hozzátesz:

- **Magától választ utat:** wifin előbb a hub otthoni címét próbálja (kb. 1 s). Ha a hub válaszol, otthon vagy:
  a Tailscale-t nem kapcsolja be (ha ő kapcsolta be korábban, kikapcsolja).
- **Távol:** bekapcsolja a Tailscale appot, megvárja, és a hub Tailscale-címét nyitja meg.
- **Akku:** háttérbe téve 3 perc múlva kikapcsolja a Tailscale-t – de csak ha ő kapcsolta be. A kézzel bekapcsolt
  Tailscale-hez nem nyúl.
- A hub Tailscale-címét az app otthon magától megtudja (`/api/ping`); csak az otthoni címet kell egyszer megadni.

Kell hozzá: a hubon az M22-es firmware (`/api/ping`), a telefonon a Tailscale app (bejelentkezve, egyszer már
engedélyezve a VPN-t).

## Fordítás (GitHub)

Minden feltöltés után a GitHub lefordítja (Actions → APK), a kész fájl: **Releases → latest → hub-app.apk**.

## Mi hol van

- `app/src/main/java/hu/zigbeehub/app/MainActivity.java` – a WebView, a hub megkeresése, fájlfeltöltés / mentés
- `Hub.java` – címek, `/api/ping`, a Tailscale be / ki (`com.tailscale.ipn.CONNECT_VPN` / `DISCONNECT_VPN`)
- `OffReceiver.java` – a Tailscale kikapcsolása 3 perccel a háttérbe kerülés után
- `app/hub.keystore` – az app saját aláíró kulcsa (minden fordítás ezzel ír alá, így a frissítés a régi fölé települ)
