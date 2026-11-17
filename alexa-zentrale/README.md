# Alexa-Zentrale für Bose SoundTouch

Ein ESP32 gibt sich gegenüber dem Echo als Philips-Hue-Lampe aus. Jeder SoundTouch im Heimnetz erscheint in Alexa als Gerät mit seinem Namen (z. B. „Küche“, „Bad“). Alles bleibt im Heimnetz, keine Cloud.

| Sprachbefehl | Wirkung |
|---|---|
| „Alexa, schalte Küche ein“ | Lautsprecher einschalten |
| „Alexa, schalte Küche aus“ | Lautsprecher in Standby |
| „Alexa, stelle Küche auf 40 Prozent“ | Lautstärke 40 |

## Einkaufsliste

- ESP32-Board mit USB, z. B. „ESP32 DevKitC“ oder „ESP32-C3 Mini“
- USB-Datenkabel
- USB-Netzteil für den Dauerbetrieb

## Aufspielen (einmalig, am PC oder Laptop)

1. Unter **Releases** die neueste „Alexa-Zentrale“ öffnen und die passende Datei laden: `Zentrale-ESP32.bin` oder `Zentrale-ESP32-C3.bin`.
2. In Chrome oder Edge https://espressif.github.io/esptool-js/ öffnen, Board per USB anschließen, **Connect** klicken und den Anschluss wählen.
3. Bei **Flash Address** `0x0` eintragen, die Datei wählen und **Program** klicken.

## Einrichten

1. Board an das Netzteil stecken. Es öffnet das WLAN **SoundTouch-Zentrale**.
2. Mit dem Handy verbinden, Heim-WLAN auswählen, Passwort eingeben, speichern.
3. Die Lautsprecher müssen eingeschaltet bzw. im Standby und im WLAN sein. Die Zentrale findet sie selbst.
4. „Alexa, suche Geräte“ sagen. Küche und Bad erscheinen als Lampen.

Kommt später ein Lautsprecher dazu: Board kurz vom Strom trennen und neu starten, dann erneut „Alexa, suche Geräte“.
