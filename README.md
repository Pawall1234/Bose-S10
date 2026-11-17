# SoundTouch Ersatz

Android-App als Ersatz für die eingestellte Bose-SoundTouch-App. Sie steuert SoundTouch-Lautsprecher (getestet wird mit dem SoundTouch 10) direkt im Heimnetz über die von Bose offengelegte Web-API (HTTP, Port 8090).

Kein Konto, keine Cloud, keine Telemetrie. Die App spricht nur mit den Lautsprechern im eigenen WLAN.

## Funktionen (Version 0.1)

- **Lautsprecher mit WLAN verbinden:** Einrichtungs-Assistent über den Setup-Modus des Lautsprechers.
- **Lautsprecher im Heimnetz finden** (mDNS) oder per IP-Adresse eintragen.
- **Steuern:** Ein/Aus, Play/Pause, Vor/Zurück, Lautstärke, Quelle AUX oder Bluetooth, Anzeige des aktuellen Titels.

## Installation

1. Unter **Actions** den neuesten Lauf von „APK bauen“ öffnen und das Artefakt `SoundTouch-Ersatz-APK` herunterladen (oder unter **Releases**, sobald eine Version veröffentlicht ist).
2. Die ZIP-Datei auf dem Handy entpacken und `SoundTouch-Ersatz.apk` öffnen.
3. Android fragt, ob Apps aus dieser Quelle installiert werden dürfen. Einmal erlauben.

## Lautsprecher ins WLAN bringen

1. Lautsprecher einschalten.
2. Tasten **2** und **Lautstärke −** gleichzeitig halten, bis die WLAN-Anzeige orange leuchtet. Der Lautsprecher öffnet ein eigenes WLAN „Bose SoundTouch …“.
3. In der App auf **Lautsprecher mit WLAN verbinden** tippen, Namen vergeben (z. B. Küche), Verbindung bestätigen.
4. Heim-WLAN auswählen, Passwort eingeben, **WLAN-Daten senden**. Das Passwort geht nur an den Lautsprecher und wird nicht gespeichert.
5. Nach etwa 30 Sekunden ist der Lautsprecher im Heim-WLAN. Für den zweiten Lautsprecher wiederholen.

Klappt das Senden nicht, gibt es im Assistenten den Knopf **Einrichtungsseite des Lautsprechers öffnen**. Das ist die im Lautsprecher eingebaute Web-Einrichtung (http://192.0.2.1), die als Ausweichweg der Original-App gedacht war.

### Wie die Original-App das gemacht hat

- Beim SoundTouch 10 nutzte die Bose-App bevorzugt **Bluetooth Low Energy** (internes „Gabbo“-Protokoll), um WLAN-Name und Passwort zu übertragen. Dieses Protokoll ist nicht öffentlich dokumentiert.
- Ausweichweg war der **Access-Point-Modus**: Der Lautsprecher öffnet das offene WLAN „Bose SoundTouch …“, hat dort die Adresse `192.0.2.1` und gibt dem Handy `192.0.2.2`. Auf Port 80 läuft eine Einrichtungsseite, auf Port 8090 die normale API.
- Ablauf: `GET /info` (erreichbar?), optional `POST /performWirelessSiteSurvey`, dann `POST /addWirelessProfile` mit `securityType="wpa_or_wpa2"`. Der erste Versuch hängt oft, ein zweiter kurz danach klappt. Danach verlässt der Lautsprecher den Setup-Modus und ist nach 15–30 s im Heim-WLAN.
- Danach hat die Original-App den Lautsprecher mit dem Bose-Konto verknüpft. Dieser Schritt entfällt, weil die Bose-Cloud abgeschaltet ist.

Falls nichts hilft: Werksreset mit den Tasten **1** und **Lautstärke −** (ca. 10 Sekunden halten).

## Technik

- Kotlin, Jetpack Compose, minSdk 29 (Android 10)
- Verbindung zum Einrichtungs-WLAN über `WifiNetworkSpecifier`, danach `POST /addWirelessProfile` an den Lautsprecher
- Quellen: [Bose SoundTouch Web API (PDF)](https://assets.bosecreative.com/m/496577402d128874/original/SoundTouch-Web-API.pdf), [Community-Doku zur Ersteinrichtung](https://gesellix.github.io/Bose-SoundTouch/docs/guides/DEVICE-INITIAL-SETUP/)

## Geplant

- Radio-Senderplätze 1–6 (RadioBrowser)
- Gruppen (Küche + Bad gemeinsam)
- Homescreen-Widget
- Alexa-Anbindung
