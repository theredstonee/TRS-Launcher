# TRS Relay

Relay-Dienst für **TRS World Hosting**: Ein Spieler hostet seine Einzelspielerwelt, Freunde treten ohne Portweiterleitung bei.
Die Spiele versuchen zuerst eine direkte Verbindung (P2P per NAT-Durchdringung, Kandidaten über STUN). Klappt das nicht, läuft der Verkehr über dieses Relay.

- **TCP 25503:** Rückfall-Weg. Der Host hält je Welt eine Steuerverbindung; jede Gast-Verbindung wird mit einer neuen Host-Datenverbindung gekoppelt, danach reicht das Relay rohe Bytes (Minecraft-Protokoll) durch.
- **UDP 25504:** STUN-Binding-Antworten nach RFC 5389 („Wie lautet meine öffentliche Adresse?“) und optional ein Datagramm-Relay zwischen Host und Gästen.
- **TCP 25565:** TRS Wake, ein eigener Listener im Minecraft-Protokoll, der schlafende CraftingHosting-Server beim Beitreten startet (siehe unten und [WAKE.md](WAKE.md)).
- Zugang nur mit einem **Relay-Token** der TRS API (HMAC-SHA256 mit `RELAY_SECRET`, an Raum + UUID + Rolle gebunden, höchstens 2 Minuten gültig). Die API ist die Quelle der Wahrheit für Räume, Einladungen, Beitritte und Kicks. Das Relay kennt nur, was in den Tokens steht.
- Node.js 24, TypeScript läuft direkt über Node-Type-Stripping. Es gibt **keine Laufzeit-Abhängigkeiten** und keinen Build-Schritt.

Genaues Leitungsprotokoll (Englisch, für die Client-Seite): **[PROTOCOL.md](PROTOCOL.md)**.
Vertrag der API (Räume, Einladungen, Signalisierung, Token-Ausgabe): `api/API.md`, Abschnitt „World hosting“ (Branch `website`).

## Aufbau

```
relay/
├─ src/
│  ├─ main.ts       Einstieg: Konfiguration, Start, SIGTERM/SIGINT → geordnetes Herunterfahren
│  ├─ relay.ts      startet TCP + UDP, Statistik-Zeile alle 10 min
│  ├─ config.ts     Umgebungsvariablen (streng geprüft)
│  ├─ token.ts      Relay-Token prüfen (mehrere Schlüssel für den Tausch)
│  ├─ protocol.ts   Präambel, Frames, Fehlercodes
│  ├─ tcp.ts        Steuerung, Gäste, Kopplung, Rohdaten mit Bandbreitenbremse, Zeitlimits
│  ├─ udp.ts        STUN + TRS-Datagramm-Relay
│  ├─ stun.ts       Binding Request/Response (XOR-MAPPED-ADDRESS, FINGERPRINT)
│  ├─ rooms.ts      Räume im RAM (Gäste, UDP-Bindungen, Kicks, Bandbreite)
│  ├─ limits.ts     Token-Bucket, Limits je IP
│  └─ wake/         TRS Wake
│     ├─ listener.ts  Verbindungen: Status, Login, Warten, Transfer, Limits
│     ├─ packets.ts   Handshake, Login Start/Success, Konfigurations-Pakete, IDs je Protokoll
│     ├─ mc.ts        VarInt, Strings, Paket-Rahmen, NBT-String, alter 0xFE-Ping
│     ├─ api.ts       signierte Aufrufe resolve/wake, Cache, eine Anfrage je Host
│     ├─ texts.ts     Serverlisten-Text, Meldungen
│     └─ config.ts    WAKE_PORT, TRS_API_URL, HOSTING_DOMAIN
├─ test/            node:test (Handshake, Kopplung, Tokens, Limits, Bandbreite, Zeitlimits, STUN, UDP, Shutdown, Wake)
├─ scripts/probe.ts Prüfskript gegen ein laufendes Relay
├─ scripts/wake-probe.ts  Serverlisten-Ping (Status-JSON + Pingzeit)
├─ start.sh         Startskript für Pterodactyl
└─ .env.example
```

## Entwicklung

```bash
cd relay
npm install          # nur typescript + @types/node für die Typprüfung
npm run typecheck
npm test             # node --test, alles lokal auf 127.0.0.1 mit freien Ports
RELAY_SECRET=$(openssl rand -base64 48) BIND_HOST=127.0.0.1 npm start
```

## Wake-on-join (TRS Wake)

Jeder kostenlose CraftingHosting-Server aus dem TRS Launcher hat die Adresse `<name>.trs-launcher.de`. Läuft er nicht, zeigt der SRV-Eintrag auf `<name>.trs-launcher.de:25565`, also auf dieses Relay. Vertrag (DNS, Verhalten, API-Signatur): **[WAKE.md](WAKE.md)**.

- **TCP 25565** (`WAKE_PORT`, `0` = aus), eigener Listener neben 25503/25504 im selben Prozess. Leitet nie Spielverkehr weiter.
- **Serverliste:** fragt die API (`resolve`, 10 s gecacht, eine Anfrage je Host gleichzeitig) und zeigt den Zustand („Schläft – Beitreten startet ihn“, „Startet …“, „Pause bis HH:MM“, „Nicht verfügbar“) plus Fußzeile. Alter 0xFE-Ping (bis 1.6) bekommt eine Minimal-Antwort.
- **Beitreten ab 1.20.5:** `wake` → Login Success → Konfigurationsphase mit Keep-Alive alle 10 s, `resolve` alle 3 s; sobald der Server läuft, **Transfer** zum CraftingHosting-Knoten. Nach 150 s Abbruch mit Meldung.
- **Ältere Versionen:** `wake`, dann Disconnect mit „Server startet … in etwa einer Minute erneut verbinden“.
- Paket-IDs je Protokollbereich stehen in `src/wake/packets.ts` (`TRANSFER_PROTOCOLS`). Neue Minecraft-Version: IDs prüfen und den Bereich erweitern; neuere Releases nutzen bis dahin den letzten Eintrag, Snapshots bekommen den Disconnect-Weg.
- Grenzen: 10 gleichzeitig und 30 neue Verbindungen pro Minute je IP, 1000 insgesamt, höchstens 200 Wartende (danach Disconnect-Weg), Handshake/Login 5 s, Pakete bis 32 KiB. Spieler-IPs nur im RAM fürs Limit, nie im Log.

| Variable | Standard | |
|---|---|---|
| `WAKE_PORT` | `25565` | `0` schaltet TRS Wake ab |
| `TRS_API_URL` | `https://trs-launcher.theredstonee.de` | nur `https://` (`http://127.0.0.1` für Tests) |
| `HOSTING_DOMAIN` | `trs-launcher.de` | gültig ist nur `<name>.<HOSTING_DOMAIN>` |

Signiert wird mit dem **ersten** Wert aus `RELAY_SECRET`; die API (`RELAY_SECRET` dort) prüft alle.

**Pterodactyl:** Die Allocation **25565** (TCP) ist dem Server „TRS Relay“ schon zugewiesen, es muss nichts im Panel geändert werden. Neue `src/` hochladen, `.env` um die Werte oben ergänzen (Standardwerte passen), neu starten. Im Log steht dann zusätzlich `[trs-wake] listening tcp=0.0.0.0:25565 …`. Prüfen:

```bash
node scripts/wake-probe.ts <name>.trs-launcher.de                       # über DNS/SRV-Ziel
node scripts/wake-probe.ts <name>.trs-launcher.de 25565 774 135.125.185.232   # direkt aufs Relay
```

## Sicherheit

- **Bewusste Ausnahme zur 127.0.0.1-Regel:** Das Relay bindet an `0.0.0.0`, weil Spieler es direkt per TCP/UDP erreichen müssen (wie die Kunden-DB-Engines). Ein Reverse-Proxy oder Cloudflare davor ist für rohes TCP/UDP nicht möglich.
- Ohne gültigen Token geht nichts: Signatur (zeitkonstant, mehrere Schlüssel), fester Aufbau, Rolle (Host ⇒ u = h, Gast ⇒ u ≠ h), Ablaufzeit (±5 s Uhrabweichung). Host-Datenverbindungen weisen sich mit einer zufälligen 128-Bit-Paar-ID aus, die nur die Steuerverbindung des Hosts kennt.
- Grenzen: 10 Spieler je Welt, 3 Verbindungen je Gast, 1000 insgesamt, 20 gleichzeitig und 60 neue pro Minute je IP, 30 Fehlversuche je IP in 10 min, **20 Mbit/s je Welt** (TCP wird gebremst, UDP verworfen), Zeitlimits für Handshake (10 s), Kopplung (10 s), Steuerung (45 s ohne Frame) und Leerlauf (5 min).
- **Kein Protokollieren von Nutzdaten, Tokens oder IP-Adressen.** Das Log enthält nur Start, Fehler und alle 10 Minuten Zähler (Räume, Verbindungen, Bytes). Nichts wird auf die Platte geschrieben.
- Der Host erfährt über das Relay nie die IP-Adresse eines Gasts (und umgekehrt). Bei direktem P2P sehen sich die Spiele natürlich gegenseitig, das steht in der Datenschutzerklärung.

## Deploy auf Pterodactyl („TRS Relay“)

Server: **TRS Relay**, Node **GER-Hessen-2**, öffentliche IP **135.125.185.232**, Allocations **25503** (primär) und **25504**, jeweils TCP **und** UDP. Egg „node.js generic“, Image `ghcr.io/parkervcp/yolks:nodejs_24`, Startbefehl `bash /home/container/start.sh`.

1. **Secret erzeugen:** `openssl rand -base64 48`. Denselben Wert als `RELAY_SECRET` in die `.env` der **TRS API** und in die `.env` des Relays eintragen. Er gehört nie ins Git oder in einen Chat.
2. **Dateien hochladen** (Dateimanager oder SFTP) nach `/home/container/`: `src/`, `package.json`, `start.sh`. `node_modules` wird nicht gebraucht.
3. **`.env` anlegen** (Vorlage `.env.example`), mindestens:
   ```
   RELAY_SECRET=<gleicher Wert wie in der API>
   PUBLIC_HOST=relay.theredstonee.de
   UDP_PORT=25504
   ```
   Der TCP-Port kommt aus `SERVER_PORT` (primäre Allocation 25503).
4. **Neustart** im Panel. Im Log steht `listening tcp=0.0.0.0:25503 udp=0.0.0.0:25504 secrets=1`.
5. **DNS:** A-Record `relay.theredstonee.de` → `135.125.185.232`, **ohne** Cloudflare-Proxy (graue Wolke), weil rohes TCP/UDP nicht über den Proxy geht.
6. **API-Konfiguration:** In der API-`.env` (Server „TRS API“) eintragen und die API neu starten:
   ```
   RELAY_SECRET=<gleicher Wert wie im Relay>
   RELAY_HOST=relay.theredstonee.de
   RELAY_TCP_PORT=25503
   RELAY_UDP_PORT=25504
   HOSTING_STUN=            # leer = nur das Relay als STUN-Server
   ```
   Ohne `RELAY_SECRET`/`RELAY_HOST` antwortet die API auf alle `/v1/hosting/*`-Aufrufe mit `503 hosting_unavailable`.
7. **Prüfen** von einem anderen Rechner:
   ```bash
   node scripts/probe.ts relay.theredstonee.de 25503 25504
   # STUN  … → <deine öffentliche IP>:<port> (fingerprint ok)
   # TCP   … → ERROR bad_token
   RELAY_SECRET=<secret> node scripts/probe.ts relay.theredstonee.de   # zusätzlich kompletter Durchlauf
   ```
   Das Skript zählt als ein fehlgeschlagener Handshake für deine IP (Limit 30 in 10 min).

**Update:** neue `src/` hochladen und neu starten. Laufende Welten verlieren dabei nur den Relay-Weg; die Spiele verbinden sich mit neuen Tokens neu.

**Schlüsseltausch:** `RELAY_SECRET=NEU,ALT` im Relay, Relay neu starten, danach die API auf `NEU` umstellen. Nach ein paar Minuten (Tokens leben höchstens 2 min) `ALT` im Relay entfernen.
