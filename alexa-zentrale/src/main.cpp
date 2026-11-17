// Alexa-Zentrale für Bose SoundTouch
//
// Ein ESP32 gibt sich gegenüber dem Echo als Philips-Hue-Lampe aus (Espalexa, rein lokal, ohne Cloud).
// Jeder gefundene SoundTouch-Lautsprecher wird zu einer "Lampe" mit seinem Namen, z. B. "Küche".
//   "Alexa, schalte Küche ein"          -> Lautsprecher einschalten
//   "Alexa, schalte Küche aus"          -> Lautsprecher in Standby
//   "Alexa, stelle Küche auf 40 Prozent" -> Lautstärke 40
//   "Alexa, stelle Küche auf 1 bis 6 Prozent" -> Senderplatz 1-6 (gut für Alexa-Routinen)
//
// Ersteinrichtung: Der Chip öffnet das WLAN "SoundTouch-Zentrale". Mit dem Handy verbinden,
// Heim-WLAN auswählen, Passwort eingeben. Danach "Alexa, suche Geräte".
// Es werden keine Daten ins Internet gesendet.

#include <Arduino.h>
#if defined(ESP8266)
#include <ESP8266WiFi.h>
#include <ESP8266mDNS.h>
#include <ESP8266HTTPClient.h>
#else
#include <WiFi.h>
#include <ESPmDNS.h>
#include <HTTPClient.h>
#endif
#include <WiFiManager.h>
#include <EEPROM.h>
#if defined(ESP8266)
#include <ESP8266WebServer.h>
typedef ESP8266WebServer StationServer;
#else
#include <WebServer.h>
typedef WebServer StationServer;
#endif
#include <Espalexa.h>

static const int MAX_SPEAKERS = 8;
static const unsigned long RESCAN_INTERVAL_MS = 10UL * 60UL * 1000UL;  // Adressen alle 10 Minuten auffrischen

struct SpeakerEntry {
    String name;
    String alexaName;
    IPAddress ip;
    int fauxmoId = -1;
};

struct PendingCommand {
    volatile bool pending = false;
    bool state = false;
    int volume = -1;  // 0-100, -1 = unverändert
    int preset = 0;   // 1-6 = Senderplatz abspielen
};

static Espalexa espalexa;
static StationServer alexaServer(80);  // Hue-Schnittstelle für Alexa, muss auf Port 80 liegen
static unsigned long alexaHits = 0;      // wie oft Alexa die Zentrale abgefragt hat

// Sender-Vermittlung für die Senderplätze 1-6: Der Lautsprecher fragt beim Abspielen eines
// LOCAL_INTERNET_RADIO-Senders diese Adresse ab und bekommt die Stream-Adresse zurück
// (früher hat das die Bose-Cloud erledigt).
static const uint16_t STATION_PORT = 8181;
static const char* STATION_PATH = "/core02/svc-bmx-adapter-orion/prod/orion/station";
static StationServer stationServer(STATION_PORT);
static SpeakerEntry speakers[MAX_SPEAKERS];
static PendingCommand pendingCommands[MAX_SPEAKERS];
static int speakerCount = 0;
static unsigned long lastScan = 0;
static volatile bool scanRequested = false;
static String lastAlexa = "noch keiner";
static bool scanningNow = false;
static const unsigned long EMPTY_RESCAN_MS = 2UL * 60UL * 1000UL;  // solange nichts gefunden: alle 2 Minuten
static const int EEPROM_SIZE = 512;

// ------------------------------------------------------------------ SoundTouch-API

static WiFiClient netClient;

static String httpGet(const IPAddress& ip, const String& path) {
    HTTPClient http;
    http.setTimeout(4000);
    http.begin(netClient, "http://" + ip.toString() + ":8090" + path);
    int code = http.GET();
    String body = code == 200 ? http.getString() : String();
    http.end();
    return body;
}

static bool httpPost(const IPAddress& ip, const String& path, const String& body) {
    HTTPClient http;
    http.setTimeout(4000);
    http.begin(netClient, "http://" + ip.toString() + ":8090" + path);
    http.addHeader("Content-Type", "text/xml");
    int code = http.POST(body);
    http.end();
    return code >= 200 && code < 300;
}

static String xmlText(const String& xml, const String& tag) {
    int start = xml.indexOf("<" + tag);
    if (start < 0) return "";
    start = xml.indexOf('>', start);
    int end = xml.indexOf("</" + tag + ">", start);
    if (start < 0 || end < 0) return "";
    return xml.substring(start + 1, end);
}

static String xmlUnescape(String s) {
    s.replace("&lt;", "<");
    s.replace("&gt;", ">");
    s.replace("&quot;", "\"");
    s.replace("&apos;", "'");
    s.replace("&amp;", "&");
    return s;
}

static bool isStandby(const IPAddress& ip) {
    String np = httpGet(ip, "/now_playing");
    return np.length() == 0 || np.indexOf("source=\"STANDBY\"") >= 0;
}

static void pressKey(const IPAddress& ip, const char* key) {
    httpPost(ip, "/key", String("<key state=\"press\" sender=\"Gabbo\">") + key + "</key>");
    httpPost(ip, "/key", String("<key state=\"release\" sender=\"Gabbo\">") + key + "</key>");
}

static void setVolume(const IPAddress& ip, int volume) {
    volume = constrain(volume, 0, 100);
    httpPost(ip, "/volume", "<volume>" + String(volume) + "</volume>");
}

// ------------------------------------------------------------------ Sender-Vermittlung

static int b64Value(char c) {
    if (c >= 'A' && c <= 'Z') return c - 'A';
    if (c >= 'a' && c <= 'z') return c - 'a' + 26;
    if (c >= '0' && c <= '9') return c - '0' + 52;
    if (c == '+' || c == '-' || c == ' ') return 62;
    if (c == '/' || c == '_') return 63;
    return -1;
}

// Base64 dekodieren (normales und URL-sicheres Alphabet, Auffüllzeichen optional).
static String base64Decode(const String& in) {
    String out;
    int buffer = 0, bits = 0;
    for (size_t i = 0; i < in.length(); i++) {
        int v = b64Value(in[i]);
        if (v < 0) continue;
        buffer = (buffer << 6) | v;
        bits += 6;
        if (bits >= 8) {
            bits -= 8;
            out += (char)((buffer >> bits) & 0xFF);
        }
    }
    return out;
}

// Liest einen Text-Wert aus einem kleinen JSON-Objekt.
static String jsonString(const String& json, const char* key) {
    String needle = String("\"") + key + "\"";
    int k = json.indexOf(needle);
    if (k < 0) return "";
    int colon = json.indexOf(':', k + needle.length());
    int q = json.indexOf('"', colon + 1);
    if (colon < 0 || q < 0) return "";
    String out;
    for (size_t i = q + 1; i < json.length(); i++) {
        char c = json[i];
        if (c == '\\' && i + 1 < json.length()) {
            char n = json[++i];
            out += (n == 'n') ? '\n' : n;
        } else if (c == '"') {
            break;
        } else {
            out += c;
        }
    }
    return out;
}

static String jsonEscape(const String& s) {
    String out;
    for (size_t i = 0; i < s.length(); i++) {
        char c = s[i];
        if (c == '"' || c == '\\') { out += '\\'; out += c; }
        else if ((unsigned char)c < 0x20) out += ' ';
        else out += c;
    }
    return out;
}

static void handleStation() {
    String payload = base64Decode(stationServer.arg("data"));
    String streamUrl = jsonString(payload, "streamUrl");
    if (streamUrl.length() == 0) {
        stationServer.send(400, "text/plain", "data muss eine streamUrl enthalten");
        return;
    }
    String name = jsonString(payload, "name");
    String imageUrl = jsonString(payload, "imageUrl");
    String body = String("{\"audio\":{\"hasPlaylist\":false,\"isRealtime\":true,\"streamUrl\":\"") +
        jsonEscape(streamUrl) + "\"},\"imageUrl\":\"" + jsonEscape(imageUrl) + "\",\"name\":\"" +
        jsonEscape(name) + "\",\"streamType\":\"liveRadio\"}";
    stationServer.sendHeader("Cache-Control", "no-store");
    stationServer.send(200, "application/json; charset=utf-8", body);
    Serial.printf("Sender angefragt: %s\n", name.c_str());
}

static void handleStatus() {
    String html = "<!doctype html><meta charset=utf-8><meta name=viewport content='width=device-width'>"
                  "<title>SoundTouch-Zentrale</title><body style='font-family:sans-serif;padding:16px;max-width:520px'>"
                  "<h2>SoundTouch-Zentrale</h2><p>Lautsprecher für Alexa:</p><ul>";
    for (int k = 0; k < speakerCount; k++) {
        html += "<li>" + speakers[k].name + " (" + speakers[k].ip.toString() + ") &rarr; Alexa: <b>" + speakers[k].alexaName + "</b></li>";
    }
    if (speakerCount == 0) html += "<li><b>Noch keiner gefunden.</b></li>";
    html += "</ul><p>Letzter Alexa-Befehl: " + lastAlexa + "<br>Abfragen von Alexa: " + String(alexaHits) + "</p><p><a href='/scan'>Jetzt suchen</a> (dauert bis zu 1 Minute)</p>"
            "<h3>Von Hand hinzufügen</h3><form action='/add'>"
            "<p>Name für Alexa<br><input name=name placeholder='Küche'></p>"
            "<p>IP-Adresse<br><input name=ip placeholder='192.168.178.xx'></p>"
            "<p><button>Hinzufügen</button></p></form>"
            "<p><a href='/forget' onclick=\"return confirm('Alle Lautsprecher vergessen und neu starten?')\">Liste leeren</a></p>"
            "<p style='color:#666'>Nach einer Änderung: \"Alexa, suche Geräte\".</p></body>";
    stationServer.send(200, "text/html; charset=utf-8", html);
}

static void redirectHome() {
    stationServer.sendHeader("Location", "/");
    stationServer.send(303, "text/plain", "");
}

static void addSpeaker(const String& rawName, const IPAddress& ip, bool persist, const String& rawAlexa);
static bool renameForAlexa(const String& name, const String& rawAlexa);
static bool removeSpeaker(const String& name);

static void handleAdd() {
    IPAddress ip;
    String name = stationServer.arg("name");
    if (!ip.fromString(stationServer.arg("ip")) || name.length() == 0) {
        stationServer.send(400, "text/plain; charset=utf-8", "Bitte Name und gültige IP angeben.");
        return;
    }
    addSpeaker(name, ip, true, "");
    redirectHome();
}

static void handleScan() {
    scanRequested = true;
    redirectHome();
}

static void handleForget() {
    for (int i = 0; i < EEPROM_SIZE; i++) EEPROM.write(i, 0);
    EEPROM.commit();
    stationServer.send(200, "text/plain; charset=utf-8", "Gelöscht, Neustart ...");
    delay(500);
    ESP.restart();
}

static const char* FIRMWARE_VERSION = "11";

static String statusJson() {
    String j = String("{\"version\":\"") + FIRMWARE_VERSION + "\",\"ip\":\"" + WiFi.localIP().toString() +
        "\",\"lastAlexa\":\"" + jsonEscape(lastAlexa) + "\",\"alexaHits\":" + String(alexaHits) + ",\"scanning\":" + ((scanRequested || scanningNow) ? "true" : "false") +
        ",\"speakers\":[";
    for (int k = 0; k < speakerCount; k++) {
        if (k) j += ",";
        j += "{\"name\":\"" + jsonEscape(speakers[k].name) + "\",\"alexaName\":\"" + jsonEscape(speakers[k].alexaName) +
            "\",\"ip\":\"" + speakers[k].ip.toString() + "\"}";
    }
    return j + "]}";
}

static void sendJson(int code, const String& body) {
    stationServer.sendHeader("Cache-Control", "no-store");
    stationServer.send(code, "application/json; charset=utf-8", body);
}

static void apiStatus() { sendJson(200, statusJson()); }

static void apiScan() {
    scanRequested = true;
    sendJson(200, statusJson());
}

static void apiAdd() {
    IPAddress ip;
    String name = stationServer.arg("name");
    if (!ip.fromString(stationServer.arg("ip")) || name.length() == 0) {
        sendJson(400, "{\"error\":\"Name und IP angeben\"}");
        return;
    }
    addSpeaker(name, ip, true, stationServer.arg("alexa"));
    sendJson(200, statusJson());
}

static void apiRename() {
    if (!renameForAlexa(stationServer.arg("name"), stationServer.arg("alexa"))) {
        sendJson(400, "{\"error\":\"Lautsprecher nicht gefunden\"}");
        return;
    }
    sendJson(200, statusJson());
}

static void apiRemove() {
    if (!removeSpeaker(stationServer.arg("name"))) {
        sendJson(400, "{\"error\":\"Lautsprecher nicht gefunden\"}");
        return;
    }
    sendJson(200, statusJson());
    delay(300);
    ESP.restart();
}

// ------------------------------------------------------------------ Lautsprecher finden

// Gespeicherte Lautsprecher im Flash: "Name|IP\n..." (nur Raumname und Heimnetz-Adresse).
static int speakerIndexForFauxmo(unsigned char deviceId);

// Wird aufgerufen, wenn Alexa etwas schaltet. Nur merken, die Arbeit passiert in loop(),
// damit Alexa sofort eine Antwort bekommt.
static void onAlexa(EspalexaDevice* d) {
    if (d == nullptr) return;
    int k = speakerIndexForFauxmo(d->getId());
    if (k < 0) return;
    EspalexaDeviceProperty prop = d->getLastChangedProperty();
    bool on = prop != EspalexaDeviceProperty::off && d->getValue() > 0;
    pendingCommands[k].state = on;
    pendingCommands[k].volume = -1;
    pendingCommands[k].preset = 0;
    lastAlexa = speakers[k].alexaName + (on ? " an" : " aus");
    if (prop == EspalexaDeviceProperty::bri) {
        // Alexa schickt Prozent als Hue-Helligkeit 1-254 (Espalexa speichert Wert+1).
        int pct = ((int)(d->getValue() - 1) * 100 + 127) / 254;
        if (pct >= 1 && pct <= 6) {
            pendingCommands[k].preset = pct;  // 1-6 Prozent = Senderplatz 1-6
            lastAlexa += ", Senderplatz " + String(pct);
        } else {
            pendingCommands[k].volume = pct;
            lastAlexa += ", Lautstärke " + String(pct);
        }
    }
    pendingCommands[k].pending = true;
    Serial.println("Alexa: " + lastAlexa);
}

static void saveSpeakers() {
    String data = "ST1\n";
    for (int k = 0; k < speakerCount; k++) {
        data += speakers[k].name + "|" + speakers[k].ip.toString() + "|" + speakers[k].alexaName + "\n";
    }
    for (int i = 0; i < EEPROM_SIZE; i++) EEPROM.write(i, i < (int)data.length() ? data[i] : 0);
    EEPROM.commit();
}

static String cleanName(String n) {
    n.trim();
    n.replace("|", " ");
    n.replace("\n", " ");
    n.replace("<", "");
    n.replace(">", "");
    n.replace("\"", "");
    if (n.length() > 40) n = n.substring(0, 40);
    return n;
}

static void addSpeaker(const String& rawName, const IPAddress& ip, bool persist = true, const String& rawAlexa = "") {
    String name = cleanName(rawName);
    if (name.length() == 0) return;
    for (int k = 0; k < speakerCount; k++) {
        if (speakers[k].name.equalsIgnoreCase(name)) {
            if (speakers[k].ip != ip) {
                speakers[k].ip = ip;
                if (persist) saveSpeakers();
            }
            return;
        }
    }
    if (speakerCount >= MAX_SPEAKERS) return;
    int k = speakerCount;
    speakers[k].name = name;
    speakers[k].ip = ip;
        String alexa = cleanName(rawAlexa);
    if (alexa.length() == 0) {
        // "Bose" davor: kollidiert nicht mit Alexa-Räumen/-Gruppen, und anders als "Radio"
        // versteht Alexa es nicht als Wunsch, einen Radiosender abzuspielen.
        alexa = name.startsWith("Bose") ? name : "Bose " + name;
    }
    speakers[k].alexaName = alexa;
    EspalexaDevice* dev = new EspalexaDevice(speakers[k].alexaName, onAlexa, EspalexaDeviceType::dimmable, 0);
    espalexa.addDevice(dev);
    speakers[k].fauxmoId = dev->getId();
    speakerCount++;
    Serial.printf("  neu: %s -> %s\n", name.c_str(), ip.toString().c_str());
    if (persist) saveSpeakers();
}

static int speakerIndexByName(const String& name) {
    for (int k = 0; k < speakerCount; k++) {
        if (speakers[k].name.equalsIgnoreCase(name)) return k;
    }
    return -1;
}

static bool renameForAlexa(const String& name, const String& rawAlexa) {
    int k = speakerIndexByName(name);
    String alexa = cleanName(rawAlexa);
    if (k < 0 || alexa.length() == 0) return false;
    speakers[k].alexaName = alexa;
    espalexa.renameDevice(speakers[k].fauxmoId + 1, speakers[k].alexaName);
    saveSpeakers();
    return true;
}

// Entfernen verschiebt die Nummern in der Hue-Emulation, deshalb speichern und neu starten.
static bool removeSpeaker(const String& name) {
    int k = speakerIndexByName(name);
    if (k < 0) return false;
    for (int i = k; i < speakerCount - 1; i++) {
        speakers[i].name = speakers[i + 1].name;
        speakers[i].ip = speakers[i + 1].ip;
        speakers[i].alexaName = speakers[i + 1].alexaName;
    }
    speakerCount--;
    saveSpeakers();
    return true;
}

static void loadSpeakers() {
    String data;
    for (int i = 0; i < EEPROM_SIZE; i++) {
        char c = (char)EEPROM.read(i);
        if (c == 0 || c == (char)0xFF) break;
        data += c;
    }
    if (!data.startsWith("ST1\n")) return;
    int pos = 4;
    while (pos < (int)data.length()) {
        int nl = data.indexOf('\n', pos);
        if (nl < 0) nl = data.length();
        String line = data.substring(pos, nl);
        int bar = line.indexOf('|');
        int bar2 = bar > 0 ? line.indexOf('|', bar + 1) : -1;
        IPAddress ip;
        String ipText = bar2 > 0 ? line.substring(bar + 1, bar2) : line.substring(bar + 1);
        String alexa = bar2 > 0 ? line.substring(bar2 + 1) : "";
        if (bar > 0 && ip.fromString(ipText)) addSpeaker(line.substring(0, bar), ip, false, alexa);
        pos = nl + 1;
    }
    Serial.printf("%d Lautsprecher aus dem Speicher geladen\n", speakerCount);
}

// Fragt eine Adresse nach /info und übernimmt den Lautsprecher, wenn er antwortet.
static bool probeSpeaker(const IPAddress& ip) {
    String info = httpGet(ip, "/info");
    if (info.indexOf("<info") < 0) return false;
    addSpeaker(xmlUnescape(xmlText(info, "name")), ip);
    return true;
}

// Sucht SoundTouch-Lautsprecher per mDNS.
static int scanMdns() {
    int n = MDNS.queryService("soundtouch", "tcp");
    Serial.printf("mDNS: %d SoundTouch gefunden\n", n);
    int ok = 0;
    for (int i = 0; i < n; i++) {
        if (probeSpeaker(MDNS.IP(i))) ok++;
    }
    return ok;
}

// Ausweichweg, falls mDNS nichts liefert: alle Adressen im eigenen Netz kurz auf Port 8090 prüfen.
static int scanSubnet() {
    IPAddress self = WiFi.localIP();
    IPAddress mask = WiFi.subnetMask();
    if (mask[0] != 255 || mask[1] != 255 || mask[2] != 255) {
        Serial.println("Netz größer als /24, Netzsuche übersprungen");
        return 0;
    }
    Serial.println("Netzsuche auf Port 8090 ...");
    int ok = 0;
    for (int host = 1; host < 255; host++) {
        IPAddress ip(self[0], self[1], self[2], host);
        if (ip == self) continue;
        WiFiClient c;
#if defined(ESP8266)
        c.setTimeout(150);
        bool open = c.connect(ip, 8090);
#else
        bool open = c.connect(ip, 8090, 150);
#endif
        c.stop();
        if (open && probeSpeaker(ip)) ok++;
        espalexa.loop();
        stationServer.handleClient();  // App und Statusseite bleiben während der Suche erreichbar
        yield();
    }
    Serial.printf("Netzsuche fertig, %d Lautsprecher\n", ok);
    return ok;
}

static void scanSpeakers(bool full) {
    scanningNow = true;
    int found = scanMdns();
    if (found == 0 && (full || speakerCount == 0)) scanSubnet();
    lastScan = millis();
    scanningNow = false;
}

static int speakerIndexForFauxmo(unsigned char deviceId) {
    for (int k = 0; k < speakerCount; k++) {
        if (speakers[k].fauxmoId == deviceId) return k;
    }
    return -1;
}

// ------------------------------------------------------------------ Setup / Loop

void setup() {
    Serial.begin(115200);
    delay(200);
    Serial.println("\nSoundTouch-Zentrale startet");

    WiFi.mode(WIFI_STA);
    // Name, unter dem die Zentrale im Router erscheint
#if defined(ESP8266)
    WiFi.hostname("soundtouch-zentrale");
#else
    WiFi.setHostname("soundtouch-zentrale");
#endif
    WiFiManager wm;
    wm.setHostname("soundtouch-zentrale");
    wm.setTitle("SoundTouch-Zentrale");
    wm.setConfigPortalTimeout(600);
    if (!wm.autoConnect("SoundTouch-Zentrale")) {
        Serial.println("Keine WLAN-Daten erhalten, Neustart");
        ESP.restart();
    }
#if defined(ESP8266)
    WiFi.setSleepMode(WIFI_NONE_SLEEP);
#else
    WiFi.setSleep(false);
#endif
    Serial.printf("WLAN verbunden, IP %s\n", WiFi.localIP().toString().c_str());

    MDNS.begin("soundtouch-zentrale");

    stationServer.on(STATION_PATH, handleStation);
    stationServer.on("/", handleStatus);
    stationServer.on("/add", handleAdd);
    stationServer.on("/scan", handleScan);
    stationServer.on("/forget", handleForget);
    stationServer.on("/api/status", apiStatus);
    stationServer.on("/api/scan", apiScan);
    stationServer.on("/api/add", apiAdd);
    stationServer.on("/api/rename", apiRename);
    stationServer.on("/api/remove", apiRemove);
    stationServer.begin();
    MDNS.addService("stzentrale", "tcp", STATION_PORT);  // damit die App die Zentrale findet

    // Hue-Emulation zuerst starten, damit Alexa die Zentrale immer findet.
    espalexa.begin(&alexaServer);
    alexaServer.onNotFound([]() {
        alexaHits++;
        if (!espalexa.handleAlexaApiCall(alexaServer.uri(), alexaServer.arg(0))) {
            alexaServer.send(404, "text/plain", "Not Found");
        }
    });

    EEPROM.begin(EEPROM_SIZE);
    loadSpeakers();  // bekannte Lautsprecher sofort anmelden
    scanSpeakers(true);

    Serial.printf("Bereit mit %d Lautsprecher(n). Jetzt \"Alexa, suche Geräte\" sagen.\n", speakerCount);
}

void loop() {
    espalexa.loop();
    stationServer.handleClient();
#if defined(ESP8266)
    MDNS.update();
#endif

    for (int k = 0; k < speakerCount; k++) {
        if (!pendingCommands[k].pending) continue;
        pendingCommands[k].pending = false;
        const IPAddress ip = speakers[k].ip;
        const bool wantOn = pendingCommands[k].state;
        const bool standby = isStandby(ip);

        if (wantOn && standby) pressKey(ip, "POWER");
        if (!wantOn && !standby) pressKey(ip, "POWER");

        if (wantOn && pendingCommands[k].preset > 0) {
            if (standby) delay(1500);  // Lautsprecher kurz hochfahren lassen
            pressKey(ip, ("PRESET_" + String(pendingCommands[k].preset)).c_str());
        } else if (wantOn && pendingCommands[k].volume >= 0) {
            setVolume(ip, pendingCommands[k].volume);
        }
    }

    unsigned long interval = speakerCount == 0 ? EMPTY_RESCAN_MS : RESCAN_INTERVAL_MS;
    if (scanRequested || millis() - lastScan > interval) {
        bool full = scanRequested;
        scanRequested = false;
        scanSpeakers(full);
    }

    if (WiFi.status() != WL_CONNECTED) {
        WiFi.reconnect();
        delay(1000);
    }
}
