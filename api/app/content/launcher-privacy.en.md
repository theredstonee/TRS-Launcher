TRS Launcher runs on your computer. It has **no telemetry, analytics, crash reporting or advertising**. It only sends
information to a server run by the TRS Launcher project if you turn on the optional [TRS services](#trs-services)
(capes, friends, online status). Without your consent, the launcher sends nothing there.

The launcher only connects to other services when that is needed for something you asked it to do:

| Service | When | What is sent |
|---|---|---|
| Microsoft / Xbox Live / Minecraft services | Signing in, starting the game | Standard OAuth sign-in; your Minecraft access token when the game starts |
| Mojang (`piston-meta`, `libraries`, `resources`) | Installing or starting a version | Download requests for game files |
| Mojang session server (`sessionserver.mojang.com`) | Signing in to the TRS services (only after you agreed) | The same "join" request a Minecraft server login uses: your access token, UUID and a one-time challenge |
| Mojang profile services (`api.mojang.com`, `sessionserver.mojang.com`, `textures.minecraft.net`) | Importing a skin by player name, showing player faces (friends, admin search) | The player name or UUID being looked up; a download of that skin image |
| The website of a link you enter | Only when you import a skin "by link" | A normal download request for that image (only HTTPS, no cookies or accounts) |
| TRS services (`trs-launcher.theredstonee.de`, formerly `api.theredstonee.de`) | Only after you agreed, see [below](#trs-services) | Your UUID, name, cape choice, friends and online status |
| Fabric, Quilt, Forge, NeoForge maven/meta servers | Installing a mod loader | Download requests |
| Modrinth (`api.modrinth.com`, `cdn.modrinth.com`) | Browsing, installing or updating content | Search queries, file hashes of installed mods (for update checks) |
| CurseForge (`api.curseforge.com`; files and images from `edge.forgecdn.net`, `mediafilez.forgecdn.net`, `media.forgecdn.net`) | Only when you pick CurseForge as the source, install a CurseForge modpack or have content from CurseForge installed | Search queries and filters, the project and file IDs of content installed from CurseForge (for details and update checks), download requests. Like every web request, this includes your IP address. You don't need a CurseForge account – the launcher identifies itself with its own API key, not with anything about you. |
| Minecraft servers in your server list | Showing live status | A standard server-list ping |
| mclo.gs | Only when you click "Log teilen" and confirm | The game log, with access tokens and your Windows user name removed |
| GitHub (`github.com`) | Checking for launcher updates | A request for the update manifest |
| Discord app on your computer (local only, no internet) | While the launcher is open and "Show Discord status" is on (default), see [below](#discord) | Your Discord status: "In the TRS Launcher", or the Minecraft version, mod loader and play time of the running game |

Account tokens are stored only on your computer, encrypted with Windows DPAPI. Uninstalling the launcher removes the
program; your data in `%APPDATA%\TRS-Launcher` can be deleted at any time.

## Discord

If the Discord app is running on your computer, the launcher shows a status on your Discord profile ("Playing TRS
Launcher"): "In the TRS Launcher" while only the launcher is open, and while you play the **Minecraft version, the mod
loader (e.g. Fabric) and how long you have been playing**. It never shows server addresses, instance names or player
names.

- The launcher only talks to the Discord app **on your own computer** (Discord's local interface, a named pipe or local
  socket). It sends nothing over the internet itself and doesn't need your Discord login.
- The Discord app then shows this status on your profile – **publicly visible to the people who can see your Discord
  profile** (friends, members of shared servers). What Discord does with it is covered by
  [Discord's privacy policy](https://discord.com/privacy).
- The status disappears when you close the launcher. If Discord isn't running, nothing happens.
- It is **on by default** and can be turned off at any time under *Settings → Privacy → Show Discord status* (or hide it
  in Discord under *User Settings → Activity Privacy*).

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
| Only with "Sync with TRS account" on: your own skins from "My skins" (the image, re-encoded without metadata, its name and model), your own mod presets (names and Modrinth project IDs, no files or folder paths) and your theme, accent colour and language, each with the time of the last change; deleted skins and presets are remembered for a short while | Keeping these the same on every PC where you use this Minecraft account |

**Sync:** "Sync with TRS account" (*Einstellungen → Datenschutz*, on by default while the TRS services are on) keeps
your own skins, your own presets and the look of the launcher (theme, accent colour, language) the same on all your
PCs. Java, memory and all other settings are **not** synced and never leave your PC. Turn the switch off to stop
syncing; what was already synced stays on the server until you delete it with "Alle TRS-Daten löschen". Only you can
read your synced data – there is no admin view of it.

The online status is kept **only in the server's memory**, is never written to disk, has no history and expires
**3 minutes** after the last update. It is visible only to your friends, and not at all if you set it to "nobody".

Admin actions (such as approving a cape or a ban) are recorded in an audit log together with the affected UUID.

### Purpose and legal basis

The data is processed only to provide the TRS services you asked for: capes, the friends list, the online status and
syncing your skins, presets and launcher look between your PCs.
The legal basis is the performance of the service you requested (Art. 6(1)(b) GDPR). Keeping the services free of abuse
(reviewing uploads, reports, bans and rate limits) is based on our legitimate interest in a safe service
(Art. 6(1)(f) GDPR). There is no advertising, no profiling and no sale of data.

### Retention and deletion

- Your data is kept as long as your TRS account exists.
- Session tokens expire after 30 days; signing out or removing an account from the launcher revokes the token.
- The online status disappears 3 minutes after the last update, or immediately when you close the launcher.
- Synced skins, presets and settings stay until you delete them in the launcher (a skin deleted on one PC is deleted on
  the server, too). Notes about deleted skins are kept for 30 days so your other PCs can delete them as well.
- **"Alle TRS-Daten löschen"** (*Einstellungen → Datenschutz*) deletes everything immediately (GDPR Art. 17): your
  account, sessions, friendships, requests and blocks, uploaded capes and their files, code redemptions, reports,
  your online status and all synced skins, presets and settings. Afterwards the TRS services are turned off in the
  launcher. The skins and presets on your PC are kept.
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
