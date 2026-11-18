// Privater Alexa-Skill "Bose Radio" für die SoundTouch-Zentrale.
//
// Der Skill sucht Radiosender bei radio-browser.info (freies Verzeichnis, nur der Sendername wird gesendet)
// und schickt den Befehl über einen geheimen ntfy.sh-Kanal an die Zentrale zu Hause.
// Es werden keine persönlichen Daten gespeichert oder weitergegeben.
//
// EINZIGE EINSTELLUNG: Den "Skill-Code" aus der SoundTouch-App (Alexa-Zentrale -> Alexa-Skill) hier eintragen.
const SKILL_CODE = 'HIER-DEN-SKILL-CODE-AUS-DER-APP-EINTRAGEN';

const Alexa = require('ask-sdk-core');
const https = require('https');

function request(method, url, body) {
    return new Promise((resolve, reject) => {
        const u = new URL(url);
        const req = https.request(
            {
                method,
                hostname: u.hostname,
                path: u.pathname + u.search,
                headers: { 'User-Agent': 'SoundTouch-Zentrale-Skill/1.0', 'Content-Type': 'text/plain; charset=utf-8' },
                timeout: 5000,
            },
            (res) => {
                let data = '';
                res.setEncoding('utf8');
                res.on('data', (c) => (data += c));
                res.on('end', () => (res.statusCode < 300 ? resolve(data) : reject(new Error('HTTP ' + res.statusCode))));
            },
        );
        req.on('timeout', () => req.destroy(new Error('Zeitüberschreitung')));
        req.on('error', reject);
        if (body) req.write(body);
        req.end();
    });
}

/** Schickt einen Befehl an die Zentrale, z. B. "play|bad|http://...|WDR 2". */
async function sendToZentrale(command) {
    if (!SKILL_CODE.startsWith('stz-')) throw new Error('Skill-Code fehlt');
    await request('POST', 'https://ntfy.sh/' + SKILL_CODE, command);
}

/** Sucht einen Sender; bevorzugt unverschlüsselte Streams (die verträgt der SoundTouch am besten). */
async function findStation(name) {
    const query = '/json/stations/search?limit=15&hidebroken=true&order=clickcount&reverse=true&name=' + encodeURIComponent(name);
    let list = null;
    for (const server of ['de1', 'de2', 'fi1', 'nl1', 'at1']) {
        try {
            list = JSON.parse(await request('GET', `https://${server}.api.radio-browser.info${query}`));
            break;
        } catch (e) {
            console.log(`${server} nicht erreichbar: ${e.message}`);
        }
    }
    if (!list || !list.length) return findStationTuneIn(name);
    const pick = list.find((s) => (s.url_resolved || '').startsWith('http://')) || list[0];
    return { name: (pick.name || name).trim().slice(0, 60), url: pick.url_resolved || pick.url };
}

/** Ausweichweg: TuneIn-Verzeichnis (frei abrufbar, ohne Konto), wie beim früheren Bose-Skill. */
async function findStationTuneIn(name) {
    try {
        const search = JSON.parse(await request('GET',
            'https://opml.radiotime.com/Search.ashx?render=json&types=station&query=' + encodeURIComponent(name)));
        const item = (search.body || []).find((b) => b.item === 'station' && b.URL);
        if (!item) return null;
        const tune = JSON.parse(await request('GET', item.URL.replace('http://', 'https://') + '&render=json'));
        const streams = (tune.body || []).map((b) => b.url).filter(Boolean);
        const url = streams.find((u) => u.startsWith('http://')) || streams[0];
        return url ? { name: (item.text || name).trim().slice(0, 60), url } : null;
    } catch (e) {
        console.log('TuneIn nicht erreichbar: ' + e.message);
        return null;
    }
}

function slot(handlerInput, name) {
    const s = Alexa.getSlot(handlerInput.requestEnvelope, name);
    if (!s) return '';
    const res = s.resolutions && s.resolutions.resolutionsPerAuthority && s.resolutions.resolutionsPerAuthority[0];
    if (res && res.status && res.status.code === 'ER_SUCCESS_MATCH') return res.values[0].value.name;
    return s.value || '';
}

function roomText(room) {
    return room === 'küche' ? 'in der Küche' : room ? 'im ' + room.charAt(0).toUpperCase() + room.slice(1) : '';
}

function say(handlerInput, text) {
    return handlerInput.responseBuilder.speak(text).withShouldEndSession(true).getResponse();
}

const LaunchHandler = {
    canHandle: (h) => Alexa.getRequestType(h.requestEnvelope) === 'LaunchRequest',
    handle: (h) =>
        h.responseBuilder
            .speak('Welchen Sender in welchem Raum? Zum Beispiel: spiel WDR 2 im Bad.')
            .reprompt('Sag zum Beispiel: spiel Musik in der Küche.')
            .getResponse(),
};

const PlayHandler = {
    canHandle: (h) => Alexa.getRequestType(h.requestEnvelope) === 'IntentRequest' && Alexa.getIntentName(h.requestEnvelope) === 'PlayIntent',
    async handle(h) {
        const station = slot(h, 'station');
        const room = slot(h, 'room');
        if (!station) return say(h, 'Welchen Sender soll ich spielen?');
        const found = await findStation(station);
        if (!found) return say(h, `Den Sender ${station} habe ich nicht gefunden.`);
        await sendToZentrale(['play', room, found.url, found.name.replace(/\|/g, ' ')].join('|'));
        return say(h, `${found.name} ${roomText(room)}.`);
    },
};

const MusicHandler = {
    canHandle: (h) => Alexa.getRequestType(h.requestEnvelope) === 'IntentRequest' && Alexa.getIntentName(h.requestEnvelope) === 'MusicIntent',
    async handle(h) {
        const room = slot(h, 'room');
        await sendToZentrale(`preset|${room}|1`);
        return say(h, `Musik ${roomText(room)}.`);
    },
};

const PresetHandler = {
    canHandle: (h) => Alexa.getRequestType(h.requestEnvelope) === 'IntentRequest' && Alexa.getIntentName(h.requestEnvelope) === 'PresetIntent',
    async handle(h) {
        const room = slot(h, 'room');
        const n = parseInt(slot(h, 'number'), 10);
        if (!(n >= 1 && n <= 6)) return say(h, 'Es gibt die Senderplätze eins bis sechs.');
        await sendToZentrale(`preset|${room}|${n}`);
        return say(h, `Platz ${n} ${roomText(room)}.`);
    },
};

const OffHandler = {
    canHandle: (h) => Alexa.getRequestType(h.requestEnvelope) === 'IntentRequest' && Alexa.getIntentName(h.requestEnvelope) === 'OffIntent',
    async handle(h) {
        const room = slot(h, 'room');
        await sendToZentrale(`off|${room}`);
        return say(h, `Aus ${roomText(room)}.`);
    },
};

const OnHandler = {
    canHandle: (h) => Alexa.getRequestType(h.requestEnvelope) === 'IntentRequest' && Alexa.getIntentName(h.requestEnvelope) === 'OnIntent',
    async handle(h) {
        const room = slot(h, 'room');
        await sendToZentrale(`on|${room}`);
        return say(h, `An ${roomText(room)}.`);
    },
};

const VolumeHandler = {
    canHandle: (h) => Alexa.getRequestType(h.requestEnvelope) === 'IntentRequest' && Alexa.getIntentName(h.requestEnvelope) === 'VolumeIntent',
    async handle(h) {
        const room = slot(h, 'room');
        const level = Math.max(0, Math.min(100, parseInt(slot(h, 'level'), 10) || 0));
        await sendToZentrale(`vol|${room}|${level}`);
        return say(h, `Lautstärke ${level} ${roomText(room)}.`);
    },
};

const HelpHandler = {
    canHandle: (h) => Alexa.getRequestType(h.requestEnvelope) === 'IntentRequest' &&
        ['AMAZON.HelpIntent', 'AMAZON.FallbackIntent'].includes(Alexa.getIntentName(h.requestEnvelope)),
    handle: (h) =>
        h.responseBuilder
            .speak('Sag zum Beispiel: spiel WDR 2 im Bad, spiel Musik in der Küche, Bad aus, oder Küche auf 30 Prozent.')
            .reprompt('Welchen Sender in welchem Raum?')
            .getResponse(),
};

const StopHandler = {
    canHandle: (h) => Alexa.getRequestType(h.requestEnvelope) === 'IntentRequest' &&
        ['AMAZON.StopIntent', 'AMAZON.CancelIntent', 'AMAZON.NavigateHomeIntent'].includes(Alexa.getIntentName(h.requestEnvelope)),
    handle: (h) => say(h, 'Okay.'),
};

const SessionEndedHandler = {
    canHandle: (h) => Alexa.getRequestType(h.requestEnvelope) === 'SessionEndedRequest',
    handle: (h) => h.responseBuilder.getResponse(),
};

const ErrorHandler = {
    canHandle: () => true,
    handle(h, error) {
        console.log('Fehler: ' + error.message);
        const text = error.message === 'Skill-Code fehlt'
            ? 'Im Skill fehlt noch der Skill-Code aus der SoundTouch-App.'
            : 'Das hat gerade nicht geklappt. Bitte versuch es noch einmal.';
        return say(h, text);
    },
};

exports.handler = Alexa.SkillBuilders.custom()
    .addRequestHandlers(
        LaunchHandler, PlayHandler, MusicHandler, PresetHandler, OffHandler, OnHandler, VolumeHandler,
        HelpHandler, StopHandler, SessionEndedHandler,
    )
    .addErrorHandlers(ErrorHandler)
    .withCustomUserAgent('soundtouch-zentrale/bose-radio')
    .lambda();
