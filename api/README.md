# TRS API

Server für den **TRS Launcher** und den **TRS Client** (In-Game-Mod): Anmeldung über die Mojang-Session, Profile, TRS-Abzeichen, Umhänge, Kosmetik (Hüte, Flügel, Rücken, Aura), Emotes, Live-Ereignisse für sichtbare Spieler, Skin-Abfrage, Freunde und Online-Status.

- Nuxt 4 / Nitro (`node-server`), Node 24 LTS, SQLite (`node:sqlite`, WAL) im Volume `/data`.
- Betrieb zu Hause in Docker hinter einem **Cloudflare Tunnel**. Es gibt keinen offenen Port und keine Portweiterleitung am Router.
- Öffentliche Adresse: **https://api.theredstonee.de**
- Schnittstellen-Vertrag (für Launcher und Mod): **[API.md](API.md)**

## Aufbau

```
api/
├─ server/
│  ├─ routes/v1/…        Endpunkte (je Datei eine Route, z. B. capes/redeem.post.ts)
│  ├─ middleware/        Sicherheits-Header, CORS, globales Rate-Limit
│  ├─ plugins/           Start: Konfiguration, DB + Migrationen, Umhang-Katalog, Aufräum-Timer
│  ├─ lib/               Logik: auth, capes, codes, png, friends, lookup, admin, db, ratelimit …
│  └─ error-handler.ts   einheitliche Fehler, niemals Stack-Traces an Clients
├─ assets/capes/         Standard-Umhänge: catalog.json + PNGs (im Build enthalten)
├─ assets/cosmetics/     Kosmetik: templates.json (3D-Vorlagen) + catalog.json + PNGs (im Build enthalten)
├─ assets/cosmetic-previews/  erzeugte Vorschauen (nicht im Git, nicht im Build)
├─ tests/                vitest (Validierung, Login, Codes, PNG-Prüfung, Freunde, Lookup, Kosmetik, Emotes, Ereignisse, Skins, Migration)
├─ scripts/smoke.mjs     End-to-End-Test gegen den gebauten Server (mit Mojang-Mock)
├─ scripts/generate-*.mjs  Generatoren für die mitgelieferten Umhänge und Kosmetik-Teile
├─ Dockerfile, docker-compose.yml, docker-compose.local.yml, .env.example
└─ API.md                genauer Vertrag mit Beispielen
```

Das Projekt ist **unabhängig vom Launcher**. Es hat eine eigene `package.json`, eine eigene Lockfile und einen eigenen `pnpm-workspace.yaml`. Der Launcher-Build und seine Tests fassen `api/` nicht an.

## Entwicklung

Du brauchst Node ≥ 24.11 und pnpm 11.

```bash
cd api
pnpm install
pnpm typecheck && pnpm lint && pnpm test
pnpm build && node scripts/smoke.mjs   # End-to-End gegen .output (Mojang gemockt)
```

Lokal ohne Docker:

```bash
DATA_DIR=./.data SECRET_KEY=$(openssl rand -base64 48) pnpm dev
```

`pnpm dev` hört nur auf `127.0.0.1:3000`.

## Deployment (Docker + Cloudflare Tunnel)

### 1. Tunnel in Cloudflare anlegen

1. Öffne https://one.dash.cloudflare.com und gehe zu **Networks → Tunnels → Create a tunnel**. Wähle **Cloudflared** und einen Namen, z. B. `trs-api`.
2. Unter „Install and run a connector" wählst du **Docker**. Aus dem angezeigten Befehl kopierst du **nur den Token** (die lange Zeichenkette hinter `--token`).
3. Lege unter **Public Hostname** diesen Eintrag an:

   | Feld | Wert |
   |---|---|
   | Subdomain | `api` |
   | Domain | `theredstonee.de` |
   | Path | leer |
   | Service Type | `HTTP` |
   | URL | `api:3000` |

   `api` ist der Dienstname aus `docker-compose.yml`. cloudflared erreicht die API über das interne Docker-Netz, die Verbindung bleibt dabei im Rechner. HTTPS zum Nutzer macht Cloudflare, den Tunnel verschlüsselt cloudflared selbst.
4. Unter **Additional application settings → HTTP Settings** lässt du „HTTP Host Header" leer. „No TLS Verify" ist nicht nötig.

### 2. HTTPS erzwingen + HSTS (Cloudflare-Dashboard → Domain `theredstonee.de`)

- **SSL/TLS → Edge Certificates**
  - **Always Use HTTPS: An.** Damit wird jedes HTTP auf HTTPS umgeleitet.
  - **HTTP Strict Transport Security (HSTS): Aktivieren.**
    - Max-Age 12 Monate
    - „Apply HSTS policy to subdomains" an
    - „Preload" an, wenn alle Subdomains HTTPS können
    - „No-Sniff" an
  - **Minimum TLS Version: 1.2**
  - **TLS 1.3: An**
- **Speed → Optimization → Protocol Optimization:** HTTP/2 und **HTTP/3 (QUIC)** an.
- Die API sendet `Strict-Transport-Security` zusätzlich selbst. So gilt HSTS auch, wenn die Cloudflare-Einstellung einmal fehlt.
- Optional: unter **Security → WAF** eine Rate-Limiting-Regel für `/v1/auth/*` als zweite Stufe. Die API begrenzt selbst schon, siehe API.md §1.3.

### 3. Server einrichten

```bash
git clone https://github.com/theredstonee/TRS-Launcher.git
cd TRS-Launcher/api
cp .env.example .env
nano .env            # TUNNEL_TOKEN, SECRET_KEY, ADMIN_UUIDS, ADMIN_API_KEY eintragen
docker compose up -d --build
docker compose ps    # api muss "healthy" sein, dann startet cloudflared
docker compose logs -f
```

Prüfen:

```bash
curl -s https://api.theredstonee.de/v1/health
```

Erwartet: `{"status":"ok",…}`

### `.env`-Schlüssel

| Schlüssel | Pflicht | Bedeutung |
|---|---|---|
| `TUNNEL_TOKEN` | ja | Token des Cloudflare-Tunnels. Nur cloudflared bekommt ihn. |
| `SECRET_KEY` | ja | ≥ 32 zufällige Zeichen (`openssl rand -base64 48`). Schlüssel für die Hashes der Einlösecodes. **Nach dem ersten Start nicht mehr ändern**, sonst werden offene Codes ungültig. |
| `ADMIN_UUIDS` | nein | Admin-Konten (Minecraft-UUIDs, kommagetrennt). Die UUID von „Theredstonee" steht als Kommentar in `.env.example`. |
| `ADMIN_API_KEY` | nein | ≥ 32 Zeichen, für Skripte über den Header `X-Admin-Key`. Leer heißt: nur Admin-Sitzungen. |
| `PUBLIC_BASE_URL` | nein | Standard `https://api.theredstonee.de`. Wird für die Umhang-URLs in Antworten verwendet. |
| `CORS_ORIGINS` | nein | Nur nötig, falls eine Webseite die API im Browser aufruft. Exakte `https://`-Origins, kein `*`. |
| `TRUST_PROXY` | nein | `cloudflare` (Standard): Die Client-IP kommt aus `CF-Connecting-IP`. Das ist sicher, weil nur cloudflared die API erreicht. |
| `LOG_REQUESTS` | nein | `true` schreibt ein Zugriffslog ohne IPs und Tokens. |

`.env` ist in `.gitignore` und darf **nie** ins Repo.

### Sicherheit im Container

- Die API hat **keinen veröffentlichten Port**.
  - Das Netz `tunnel` ist `internal`: Darüber sprechen nur API und cloudflared.
  - Über das Netz `egress` gehen ausgehende Verbindungen (cloudflared zu Cloudflare, API zu `sessionserver.mojang.com`).
- Im Container lauscht die API auf `0.0.0.0:3000`. Von außen ist sie trotzdem nicht erreichbar, weil nichts nach außen gemappt ist.
- Nicht-root-Benutzer `node`, `read_only`-Dateisystem (beschreibbar sind nur `/data` und `/tmp`), `cap_drop: ALL`, `no-new-privileges`, Speicher- und Prozess-Limits, Healthcheck auf `/v1/health`.
- Sicherheits-Header auf jeder Antwort:
  - HSTS
  - `X-Content-Type-Options: nosniff`
  - `X-Frame-Options: DENY`
  - `Referrer-Policy: no-referrer`
  - `Permissions-Policy`
  - strenge CSP (`default-src 'none'`; die Statusseite erlaubt nur ihren eingebetteten Stil)
  - COOP und CORP
- Jede Eingabe wird mit zod geprüft; unbekannte Felder werden abgelehnt.
- Alle SQL-Abfragen laufen über Prepared Statements. Body-Größen sind begrenzt, alle Endpunkte haben Rate-Limits. Clients sehen nur generische Fehlermeldungen.

### Lokal testen ohne Tunnel

```bash
docker compose -f docker-compose.yml -f docker-compose.local.yml up --build api
curl -s http://127.0.0.1:3000/v1/health
```

Die API ist dann **nur** auf `127.0.0.1:3000` des eigenen Rechners erreichbar. cloudflared startet nicht, weil er im Profil `tunnel` liegt.

### Update

```bash
git pull
docker compose up -d --build
```

Schema-Migrationen laufen beim Start automatisch. Jede Migration läuft genau einmal und in einer Transaktion.

## Standard-Umhänge

Die Umhänge liegen in `assets/capes/`: `catalog.json` plus die PNGs.

Format von `catalog.json`:

```json
[{ "id": "team", "name": "Team", "file": "team.png", "unlock": "admin", "animated": true, "frames": 8, "frameTimeMs": 150, "scale": 2 }]
```

- Jedes Frame ist `64·scale × 32·scale` groß (Vanilla-Umhanglayout).
- Bei Animationen liegen die Frames senkrecht übereinander.
- `unlock` ist einer dieser Werte:
  - `free`: für alle
  - `code`: per Einlösecode
  - `admin`: nur per Admin-Zuteilung oder Code

Beim Start spielt die API den Katalog in die DB ein und kopiert die PNGs nach `/data/capes`. Designs, die im Katalog fehlen, werden „ausgemustert": Nutzer, die sie schon tragen, behalten sie.

## Kosmetik und Emotes

Alles liegt in `assets/cosmetics/`:

- `templates.json`: die festen 3D-Vorlagen. Das sind Voxel-Würfel mit UV-Netz oder Partikel-Definitionen. Das genaue Format mit Koordinatensystem und UV-Layout steht in API.md §11.
- `catalog.json` + PNGs: die mitgelieferten Teile.

Regeln:

- **Vorlagen nie ändern**, nur neue anhängen. Hochgeladene Texturen hängen an Form und Texturgröße.
- Beim Start prüft die API alle Vorlagen: Jedes Netz muss in der Textur liegen, und keine zwei Netze dürfen sich überlappen. Danach spielt sie den Katalog in die DB ein und kopiert die PNGs nach `/data/cosmetics`.
- Fehlt `templates.json`, startet die API ohne Kosmetik und ändert nichts am Bestand.
- Die Emote-Liste steht fest im Code (`server/lib/emotes.ts`). IDs nie umbenennen, nur anhängen: Launcher und Mod bringen die Animationen mit.

Neu erzeugen (Pixel-Art, scale 2, animierte Teile als senkrechte Streifen):

```bash
node scripts/generate-cosmetics.mjs   # oder: pnpm cosmetics
```

Das schreibt `assets/cosmetics/*.png` und `catalog.json`. Die Vorschauen landen in `assets/cosmetic-previews/`: Frontansicht ×8, Flügel und Rucksack von hinten, die Spur von oben.

Mitgeliefert:

| Teil | Freischaltung |
|---|---|
| Redstone-Krone | Code |
| Team-Krone | **nur Admin** |
| TRS-Cap | frei |
| Redstone-Lampen-Helm | frei |
| Zylinder | frei |
| Redstone-Flügel | Code |
| Drachenflügel | frei |
| Rucksack | frei |
| Heiligenschein | Code |
| Redstone-Partikel-Aura | frei |
| Fußspuren | frei |

Emotes:

- Frei: winken, klatschen, jubeln, verbeugen, facepalm, schulterzucken, daumen_hoch
- Per Code: tanzen, salutieren, luftgitarre
- Nur Admin: redstone_tanz

Uploads von Nutzern:

- Eine Textur für eine Vorlage, scale 1 oder 2, bis zu 16 Frames.
- Die Textur wird neu kodiert. Alles außerhalb des UV-Netzes wird gelöscht.
- Uploads warten auf Freigabe, genau wie Umhang-Uploads.

## Admin-Beispiele

```bash
KEY=…   # ADMIN_API_KEY
API=https://api.theredstonee.de
```

Wartende Uploads anzeigen:

```bash
curl -s -H "X-Admin-Key: $KEY" "$API/v1/admin/capes?status=pending"
```

Upload freigeben:

```bash
curl -s -X POST -H "X-Admin-Key: $KEY" "$API/v1/admin/capes/<id>/approve"
```

10 Einmal-Codes für den Tester-Umhang erzeugen. Der Klartext erscheint nur in dieser Antwort:

```bash
curl -s -X POST -H "X-Admin-Key: $KEY" -H "Content-Type: application/json" \
  -d '{"capeId":"tester","count":10,"maxUses":1,"note":"Beta-Tester"}' "$API/v1/admin/codes"
```

Team-Umhang direkt zuteilen. Der Nutzer muss sich einmal angemeldet haben:

```bash
curl -s -X POST -H "X-Admin-Key: $KEY" -H "Content-Type: application/json" \
  -d '{"capeId":"team"}' "$API/v1/admin/users/<uuid>/capes"
```

Konto sperren:

```bash
curl -s -X POST -H "X-Admin-Key: $KEY" -H "Content-Type: application/json" \
  -d '{"reason":"Spam"}' "$API/v1/admin/users/<uuid>/ban"
```

Wartende Kosmetik-Uploads anzeigen und freigeben:

```bash
curl -s -H "X-Admin-Key: $KEY" "$API/v1/admin/cosmetics?status=pending"
curl -s -X POST -H "X-Admin-Key: $KEY" "$API/v1/admin/cosmetics/<id>/approve"
```

Codes für Kosmetik oder Emotes (`cosmeticId` statt `capeId`):

```bash
curl -s -X POST -H "X-Admin-Key: $KEY" -H "Content-Type: application/json" \
  -d '{"cosmeticId":"redstone_crown","count":5,"note":"Giveaway"}' "$API/v1/admin/codes"
curl -s -X POST -H "X-Admin-Key: $KEY" -H "Content-Type: application/json" \
  -d '{"cosmeticId":"tanzen","count":1}' "$API/v1/admin/codes"
```

Team-Krone direkt zuteilen:

```bash
curl -s -X POST -H "X-Admin-Key: $KEY" -H "Content-Type: application/json" \
  -d '{"cosmeticId":"team_crown"}' "$API/v1/admin/users/<uuid>/cosmetics"
```

## Backups

Alle Daten liegen im Docker-Volume `trs-api_trs-data`:

- `trs.db` mit den WAL-Dateien `trs.db-wal` und `trs.db-shm`
- `capes/*.png`
- `cosmetics/*.png`

**Variante A (Online-Backup, ohne Stopp).** SQLite erzeugt dabei eine konsistente Kopie trotz WAL:

```bash
docker compose exec api node -e "const {DatabaseSync,backup}=require('node:sqlite');backup(new DatabaseSync('/data/trs.db',{readOnly:true}),'/data/backup-trs.db').then(()=>console.log('ok'))"
mkdir -p backups
docker compose cp api:/data/backup-trs.db backups/trs-$(date +%F).db
docker compose cp api:/data/capes backups/capes-$(date +%F)
docker compose cp api:/data/cosmetics backups/cosmetics-$(date +%F)
docker compose exec api rm /data/backup-trs.db
```

**Variante B (kurz stoppen, ganzes Volume sichern):**

```bash
docker compose stop api
docker run --rm -v trs-api_trs-data:/data:ro -v "$PWD/backups":/backup alpine \
  tar czf /backup/trs-data-$(date +%F).tar.gz -C /data .
docker compose start api
```

**Zurückspielen:**

1. `docker compose stop api`
2. Das Archiv ins Volume entpacken, z. B. `tar xzf … -C /data` über denselben `docker run`-Aufruf.
3. `docker compose start api`

Empfehlung:

- Nächtlich per cron sichern und mindestens 14 Stände aufheben.
- Eine Kopie außer Haus lagern, z. B. verschlüsselt mit `restic` oder `age`.
- Die Backups enthalten personenbezogene Daten (UUIDs, Namen, Freundeslisten). Behandle sie entsprechend.

## Datenschutz (DSGVO)

- **Gespeichert wird:**
  - UUID und zuletzt gesehener Minecraft-Name
  - Einstellungen
  - Freundschaften, Anfragen und Blockaden
  - hochgeladene Umhänge und Kosmetik-Texturen, ausgerüstete Kosmetik, freigeschaltete Teile
  - Sitzungen: nur der SHA-256-Hash des Tokens, Zeitpunkte
  - Einlösungen und Meldungen
- **Nur im Arbeitsspeicher** liegen:
  - der Online-Status (verfällt nach 3 Minuten)
  - die Rate-Limit-Zähler (mit IPs)
  - die Beobachter-Listen der Spieler-Streams (welche UUIDs ein Client gerade sieht)
  - der Skin-Cache (öffentliche Mojang-Profildaten, höchstens 10 Minuten)
- Emotes werden nicht gespeichert, nur weitergeleitet.
- **Im Log** steht keine IP.
- **Löschung:** `DELETE /v1/me` entfernt sofort alle Daten des Kontos (Art. 17). Nur ein bestehender Sperr-Eintrag bleibt; das ist ein berechtigtes Interesse, damit Sperren nicht per Neuanmeldung umgangen werden.
- **Cloudflare ist Auftragsverarbeiter:** Sämtlicher Verkehr läuft über Cloudflare, einschließlich IP-Adressen und TLS-Terminierung. Deshalb musst du im Cloudflare-Dashboard unter **Manage Account → Configurations → Privacy** (bzw. im Rahmen der Self-Serve Subscription Agreement) das **Data Processing Addendum (DPA / AVV)** von Cloudflare **akzeptieren**. Außerdem gehört Cloudflare in die Datenschutzerklärung (`PRIVACY.md` im Repo-Root): Zweck, Empfänger, Drittlandübermittlung (EU-US Data Privacy Framework / Standardvertragsklauseln).
- Mojang/Microsoft bekommt bei der Anmeldung Name und serverId (`hasJoined`). Das ist nötig, um den Kontobesitz zu prüfen.
- Bei `GET /v1/skins/*` fragt die API Mojang nach öffentlichen Profildaten (Name → UUID → Skin-URL). Dabei gehen keine Daten des Fragenden an Mojang.
