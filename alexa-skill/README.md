# Privater Alexa-Skill „Bose Radio“

Sprachbefehle wie „Alexa, sag Bose Radio, spiel WDR 2 im Bad“ für die SoundTouch-Lautsprecher.

* `interactionModels/de-DE.json` – Sprachmodell (im Alexa Developer Console unter *Build → JSON Editor* einfügen)
* `lambda/index.js` und `lambda/package.json` – Code (unter *Code* einfügen, `SKILL_CODE` aus der SoundTouch-App eintragen)

Ablauf: Skill (Amazon-Cloud) → geheimer Kanal auf ntfy.sh → Zentrale (D1 mini, hält die Verbindung von innen) → Lautsprecher.
Keine Portfreigabe nötig. Übertragen werden nur Raum, Sender und Lautstärke.

Befehle (nach „Alexa, sag Bose Radio, …“):

| Satz | Wirkung |
| --- | --- |
| spiel WDR 2 im Bad | Sender per Name suchen und abspielen |
| spiel Musik in der Küche | Senderplatz 1 |
| spiel Platz 3 im Bad | Senderplatz 3 |
| Bad aus / schalte Küche ein | Ein/Aus |
| Küche auf 30 Prozent | Lautstärke |

Tipp: Mit einer Alexa-Routine (Auslöser „Spiel Musik auf Bad“, Aktion *Benutzerdefiniert*: „sag Bose Radio, spiel Musik im Bad“)
reicht auch der kurze Satz „Alexa, spiel Musik auf Bad“.
