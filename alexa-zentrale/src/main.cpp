// Alexa-Zentrale für Bose SoundTouch
//
// Ein ESP32 gibt sich gegenüber dem Echo als Philips-Hue-Lampe aus (fauxmoESP, rein lokal, ohne Cloud).
// Jeder gefundene SoundTouch-Lautsprecher wird zu einer "Lampe" mit seinem Namen, z. B. "Küche".
//   "Alexa, schalte Küche ein"          -> Lautsprecher einschalten
//   "Alexa, schalte Küche aus"          -> Lautsprecher in Standby
//   "Alexa, stelle Küche auf 40 Prozent" -> Lautstärke 40
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
#include <fauxmoESP.h>
#if defined(ESP8266)
#include <ESP8266WebServer.h>
typedef ESP8266WebServer StationServer;
#else
#include <WebServer.h>
typedef WebServer StationServer;
#endif

static const int MAX_SPEAKERS = 8;
static const unsigned long RESCAN_INTERVAL_MS = 10UL * 60UL * 1000UL;  // Adressen alle 10 Minuten auffrischen

struct SpeakerEntry {
    String name;
    IPAddress ip;
    int fauxmoId = -1;
};

struct PendingCommand {
    volatile bool pending = false;
    bool state = false;
    unsigned char value = 0;
};

static fauxmoESP fauxmo;

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
                  "<title>SoundTouch-Zentrale</title><body style='font-family:sans-serif;padding:16px'>"
                  "<h2>SoundTouch-Zentrale</h2><p>Gefundene Lautsprecher:</p><ul>";
    for (int k = 0; k < speakerCount; k++) {
        html += "<li>" + speakers[k].name + " (" + speakers[k].ip.toString() + ")</li>";
    }
    html += "</ul></body>";
    stationServer.send(200, "text/html; charset=utf-8", html);
}

// ------------------------------------------------------------------ Lautsprecher finden

// Sucht SoundTouch-Lautsprecher per mDNS und fragt ihren Namen ab.
// Bekannte Lautsprecher (gleicher Name) bekommen ihre neue Adresse; neue werden ergänzt.
static void scanSpeakers(bool allowNew) {
    int n = MDNS.queryService("soundtouch", "tcp");
    Serial.printf("mDNS: %d SoundTouch gefunden\n", n);
    for (int i = 0; i < n; i++) {
#if defined(ESP8266)
        IPAddress ip = MDNS.IP(i);
#else
        IPAddress ip = MDNS.IP(i);
#endif
        String info = httpGet(ip, "/info");
        String name = xmlUnescape(xmlText(info, "name"));
        if (name.length() == 0) name = MDNS.hostname(i);
        name.trim();
        if (name.length() == 0) continue;

        int idx = -1;
        for (int k = 0; k < speakerCount; k++) {
            if (speakers[k].name.equalsIgnoreCase(name)) { idx = k; break; }
        }
        if (idx >= 0) {
            speakers[idx].ip = ip;
        } else if (allowNew && speakerCount < MAX_SPEAKERS) {
            speakers[speakerCount].name = name;
            speakers[speakerCount].ip = ip;
            speakerCount++;
        }
        Serial.printf("  %s -> %s\n", name.c_str(), ip.toString().c_str());
    }
    lastScan = millis();
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
    stationServer.begin();
    MDNS.addService("stzentrale", "tcp", STATION_PORT);  // damit die App die Zentrale findet

    // Bis zu einer Minute nach Lautsprechern suchen
    for (int attempt = 0; attempt < 6 && speakerCount == 0; attempt++) {
        scanSpeakers(true);
        if (speakerCount == 0) delay(10000);
    }

    fauxmo.createServer(true);
    fauxmo.setPort(80);  // Pflicht für neuere Echo-Geräte
    fauxmo.enable(true);
    for (int k = 0; k < speakerCount; k++) {
        speakers[k].fauxmoId = fauxmo.addDevice(speakers[k].name.c_str());
    }

    // Wird im Netzwerk-Kontext aufgerufen: nur merken, die Arbeit passiert in loop().
    fauxmo.onSetState([](unsigned char deviceId, const char* deviceName, bool state, unsigned char value) {
        int k = speakerIndexForFauxmo(deviceId);
        if (k < 0) return;
        pendingCommands[k].state = state;
        pendingCommands[k].value = value;
        pendingCommands[k].pending = true;
        Serial.printf("Alexa: %s %s, Wert %d\n", deviceName, state ? "an" : "aus", value);
    });

    Serial.printf("Bereit mit %d Lautsprecher(n). Jetzt \"Alexa, suche Geräte\" sagen.\n", speakerCount);
}

void loop() {
    fauxmo.handle();
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

        // Alexa schickt bei "auf X Prozent" einen Wert von 1 bis 255.
        if (wantOn) {
            int volume = (pendingCommands[k].value * 100 + 127) / 255;
            if (pendingCommands[k].value < 255) setVolume(ip, volume);
        }
    }

    if (millis() - lastScan > RESCAN_INTERVAL_MS) {
        scanSpeakers(false);
    }

    if (WiFi.status() != WL_CONNECTED) {
        WiFi.reconnect();
        delay(1000);
    }
}
