
TRS Launcher runs on your computer. It has **no telemetry, analytics, crash reporting or advertising**. It only sends
information to a server run by the TRS Launcher project if you turn on the optional [TRS services](#trs-services)
(capes, friends, online status). Without your consent, the launcher sends nothing there.

The launcher only connects to other services when that is needed for something you asked it to do:

| Service | When | What is sent |
|---|---|---|
| Microsoft / Xbox Live / Minecraft services | Signing in, starting the game | Standard OAuth sign-in; your Minecraft access token when the game starts |
| Mojang (`piston-meta`, `libraries`, `resources`) | Installing or starting a version | Download requests for game files |
| Mojang session server (`sessionserver.mojang.com`) | Signing in to the TRS services (only after you agreed) | The same "join" request a Minecraft server login uses: your access token, UUID and a one-time challenge |
| TRS services (`trs-launcher.theredstonee.de`, formerly `api.theredstonee.de`) | Only after you agreed, see [below](#trs-services) | Your UUID, name, cape choice, friends and online status; with sync turned on also your own skins, your own presets and your theme, accent colour and language |
| Fabric, Quilt, Forge, NeoForge maven/meta servers | Installing a mod loader | Download requests |
| Modrinth (`api.modrinth.com`, `cdn.modrinth.com`) | Browsing, installing or updating content | Search queries, file hashes of installed mods (for update checks) |
| CurseForge (`api.curseforge.com`; files and images from `edge.forgecdn.net`, `mediafilez.forgecdn.net`, `media.forgecdn.net`) | Only when you pick CurseForge as the source, install a CurseForge modpack or have content from CurseForge installed | Search queries and filters, the project and file IDs of content installed from CurseForge (for details and update checks), download requests. Like every web request, this includes your IP address. You don't need a CurseForge account – the launcher identifies itself with its own API key, not with anything about you. |
| Minecraft servers in your server list | Showing live status | A standard server-list ping |
| mclo.gs | Only when you click "Log teilen" and confirm | The game log, with access tokens and your Windows user name removed |
| GitHub (`github.com`) | Checking for launcher updates | A request for the update manifest |

Account tokens are stored only on your computer, encrypted with Windows DPAPI. Uninstalling the launcher removes the
program; your data in `%APPDATA%\TRS-Launcher` can be deleted at any time.

## TRS services

The TRS services add TRS capes, a friends list and an online status to the launcher and to the TRS Client mod. They are
**off until you agree** in the launcher (a short note explains what is stored before the first sign-in). You can turn
them off again at any time under *Einstellungen → Datenschutz*.

### How signing in works

The launcher signs in with your Minecraft account the same way a Minecraft server checks a player: it asks the TRS
server for a one-time challenge, confirms it with Mojang's session server using your Minecraft access token, and the
TRS server asks Mojang whether that happened. **The TRS server never sees your password or your Minecraft access
token.** It then issues its own token, which the launcher stores encrypted with Windows DPAPI on your computer and never
hands to web content or to the game. The TRS Client mod signs in by itself through the game session.

### What is stored

| Data | Why |
|---|---|
| Minecraft UUID and player name | To identify your TRS account and show your name to friends |
| Account creation time and last sign-in time | Account management and abuse prevention |
| Session tokens (only as SHA-256 hashes, valid for 30 days, at most 10 per account) | Keeping you signed in |
| Your privacy settings (TRS badge, cape visible to others, online status visible to friends/nobody, share server) | So the services respect your choices |
| Your chosen cape, capes unlocked by codes or granted by the team | Showing your cape to other TRS players |
| Capes you upload (the image, re-encoded without metadata), their review status and an optional name | Cape uploads; every upload is reviewed by the team before others see it |
| Reports you file about other players' capes (reason, optional note) | Moderation |
| Friends, friend requests and blocks | The friends list |
| Online status: "online in the launcher" or "in game" with version and mod loader, and, only if you turned on "Server teilen", the server address | Showing friends what you play and letting them join you |
| Sync (only with your consent **and** the switch "Mit TRS-Konto synchronisieren" turned on): your own skins from "My skins" (the image, re-encoded without metadata, with name and model), your own mod presets (names and mod/pack IDs, no files or paths), the launcher settings theme, accent colour and language, each with the time of the change; for deleted skins only their ID and the deletion time, for 30 days | Keeping your skins, presets and look the same on all your devices. Java and memory settings are never sent |

The online status is kept **only in the server's memory**, is never written to disk, has no history and expires
**3 minutes** after the last update. It is visible only to your friends, and not at all if you set it to "nobody".

Sync data is visible only to your own account – not to other players and not to the team (there is no admin function
for it). The switch "Mit TRS-Konto synchronisieren" is under *Einstellungen → Datenschutz*; when it is off, the launcher
stops syncing. Delete sync data that is already stored with "Alle TRS-Daten löschen".

Admin actions (such as approving a cape or a ban) are recorded in an audit log together with the affected UUID.

### Purpose and legal basis

The data is processed only to provide the TRS services you asked for: capes, the friends list, the online status and,
if turned on, sync.
The legal basis is the performance of the service you requested (Art. 6(1)(b) GDPR). Keeping the services free of abuse
(reviewing uploads, reports, bans and rate limits) is based on our legitimate interest in a safe service
(Art. 6(1)(f) GDPR). There is no advertising, no profiling and no sale of data.

### Retention and deletion

- Your data is kept as long as your TRS account exists.
- Session tokens expire after 30 days; signing out or removing an account from the launcher revokes the token.
- The online status disappears 3 minutes after the last update, or immediately when you close the launcher.
- When you delete a synced skin, only its ID and the deletion time are kept for 30 days, so your other devices delete
  it too; after that they are removed automatically.
- **"Alle TRS-Daten löschen"** (*Einstellungen → Datenschutz*) deletes everything immediately (GDPR Art. 17): your
  account, sessions, friendships, requests and blocks, uploaded capes and their files, code redemptions, reports,
  your online status and all sync data (skins, presets, settings). Afterwards the TRS services are turned off in the launcher.
- Only an existing ban record (your UUID, the reason and the time) is kept after deletion, so a ban can't be escaped by
  signing in again.
- Server logs contain only technical data (method, path without query, status, duration, request id) – **no IP
  addresses and no tokens**. Rate limits count requests per IP address and per account **in memory only**; those
  counters are never written to disk.

### Hosting and processors

- The TRS server runs on a server in **Germany** (Pterodactyl-managed host). Data is stored there.
- **Cloudflare** (Cloudflare, Inc. / Cloudflare Germany GmbH) acts as a processor: the server is reachable only through
  a Cloudflare Tunnel, and Cloudflare terminates the HTTPS connection. Cloudflare therefore processes your IP address
  and the transmitted requests on our behalf under Cloudflare's data processing addendum. Transfers to the USA are
  covered by the EU-US Data Privacy Framework and standard contractual clauses.
- **Mojang/Microsoft** confirms the sign-in (see above): your computer sends the join request to Mojang directly, and
  the TRS server asks Mojang with your player name and the one-time challenge (`hasJoined`).

### Your rights

You have the right to access, rectification, erasure, restriction of processing, data portability and objection
(Art. 15–21 GDPR), and the right to lodge a complaint with a supervisory authority. Most of this you can do yourself in
the launcher (turn the services off, change the privacy settings, delete all data). For anything else, contact us.

### Contact

Theredstonee – open an issue at <https://github.com/theredstonee/TRS-Launcher/issues> or use the contact details on
<https://theredstonee.de>. Please don't post personal data in public issues; ask for a private contact instead.
