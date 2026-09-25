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
| CurseForge (`api.curseforge.com`; files and images from `edge.forgecdn.net`, `mediafilez.forgecdn.net`, `media.forgecdn.net`) | Only when you pick CurseForge as the source, install a CurseForge modpack, have content from CurseForge installed or import a CurseForge instance whose files are missing | Search queries and filters, the project and file IDs of content installed from CurseForge (for details and update checks), download requests. Like every web request, this includes your IP address. You don't need a CurseForge account – the launcher identifies itself with its own API key, not with anything about you. |
| Minecraft servers in your server list | Showing live status | A standard server-list ping |
| mclo.gs | Only when you click "Log teilen" and confirm | The game log, with access tokens and your Windows user name removed |
| GitHub (`github.com`) | Checking for launcher updates | A request for the update manifest |
| Discord app on your computer (local only, no internet) | While the launcher is open and "Show Discord status" is on (default), see [below](#discord) | Your Discord status: "In the TRS Launcher", or the Minecraft version, mod loader and play time of the running game |

Account tokens are stored only on your computer, encrypted with Windows DPAPI. Uninstalling the launcher removes the
program; your data in `%APPDATA%\TRS-Launcher` can be deleted at any time.

## Importing from other launchers

When you open "Import from another launcher", the launcher looks for other launchers **on your own computer**
(official Minecraft Launcher, CurseForge app, Modrinth App, Prism/MultiMC, Lunar Client, Badlion, Feather, OneClient,
ATLauncher, GDLauncher, TLauncher) and reads their instance lists. Nothing of this leaves your computer, and the other
launchers' files are only read, never changed – their databases are read from a temporary copy that is deleted right
afterwards. Sign-in data of other launchers (account and token files) is never read or copied. When you import an
instance, its worlds, mods, packs, settings and server list are copied into the new TRS instance; the project and file
IDs the other launcher saved are kept so the content can be updated. Only if files of a CurseForge instance are
missing does the launcher download them from CurseForge (see the table above).

## Switching accounts in the game (TRS Client)

- **Game started with the TRS Launcher:** the TRS Client can show your launcher accounts and switch between them
  without restarting. When you pick an account, the launcher hands a fresh Minecraft access token to the game over a
  local connection on your computer only (`127.0.0.1`), encrypted and only to the game process it started itself. The
  key for that connection is handed to the game in memory when it starts and is never written to disk. Nothing leaves
  your PC for this. "Add account" in the game opens the normal Microsoft sign-in of the launcher in your browser.
- **Game started without the TRS Launcher:** accounts you add in the game sign in directly with Microsoft, Xbox Live and
  the Minecraft services (with the TRS Launcher's own sign-in app). Only the refresh token is kept, encrypted (Windows
  DPAPI; elsewhere AES with a key file in your user folder), in `config/trsclient/accounts.json` of that game folder.
  Access tokens stay in memory. Remove an account in the game to delete it.
- For the small faces in the list, the game loads the skin from `textures.minecraft.net` and, if needed, the public
  profile from `sessionserver.mojang.com`.

## Wardrobe in the game (TRS Client)

The wardrobe of the TRS Client (skins, outfits, capes, emotes and the skin editor) only goes online for what you do
there:

- **Applying a skin or choosing a Minecraft cape** sends the skin image and its model (classic/slim), or the cape
  choice, together with your Minecraft access token directly to Mojang (`api.minecraftservices.com`), exactly like
  the official launcher. To show your capes, the game reads your Minecraft profile there and loads the images from
  `textures.minecraft.net`.
- **Adding a skin by player name** looks the name up at Mojang (`api.mojang.com`, `sessionserver.mojang.com`) or,
  with the TRS services on, through the TRS services, and downloads that skin from `textures.minecraft.net`.
- **Adding a skin by link** downloads that one image from the address you enter – only HTTPS, no cookies, never from
  addresses in your local network.
- **Adding a skin from a file** opens your system's file dialog; the file is only read on your PC.
- **With the TRS services on**, your skins in the wardrobe are the same "My skins" as in the launcher (synced with your
  TRS account, see below), and your favourites, outfits (name, skin, cape) and emote wheel slots are saved in a small
  "wardrobe" entry of your TRS account so they are the same on every PC. Choosing a TRS cape saves that choice in your
  TRS account; after applying a skin the game tells the TRS services, so other TRS players see the new skin sooner.
- **Without the TRS services** everything stays on your PC in `config/trsclient/wardrobe/` of the game folder.

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

### Friends in the game (TRS Client)

With the TRS services on, the TRS Client mod shows your friends list in the game. It asks the TRS server for your
friends, requests and blocked players only while the friends screen (or the server list / pause menu with friends
info) is open – about every 30 seconds, otherwise every 90 seconds or not at all. Friend requests, removing and
blocking are sent only when you click them. Friend faces are loaded from Mojang's public profile service
(`sessionserver.mojang.com`, `textures.minecraft.net`) and kept in memory only. The mod stores nothing of this on your
computer – except the servers you pin in the server list (`config/trsclient/server-pins.json`, only addresses, never
sent anywhere).

### TRS badge in the game (TRS Client)

The TRS Client shows a small TRS badge next to the names of players who are **playing with TRS right now**: while
they are in a world or on a server with the TRS Client, or while a game started by the TRS Launcher runs. A TRS user
who only has the launcher open, or who plays with another client, gets no badge. For this, the TRS Client reports
"in game" (version, mod loader and – only with "Server teilen" – the server address) about once a minute while you are
in a world or on a server, and the launcher does the same while a game it started runs; both are the online status
described below and stop when you leave the world or the game ends.

- **Who sees it:** whether someone is playing right now is only shown to players who are **in game themselves** –
  in practice other TRS players on the same server, next to names they see anyway. Everyone else gets "no badge". You
  always see your own badge. Players you blocked never see it.
- **Turning it off:** "Show TRS badge" (*Einstellungen → Datenschutz*) hides your badge from everyone. Setting your
  online status to "nobody" only hides you in friends lists – it does **not** hide the badge.

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
| Online status: "online in the launcher" or "in game" (from the launcher or the TRS Client) with version and mod loader, and, only if you turned on "Server teilen", the server address | Showing friends what you play and letting them join you; showing the TRS badge while you play (see above) |
| Only with "Sync with TRS account" on: your own skins from "My skins" (the image, re-encoded without metadata, its name and model), your own mod presets (names and Modrinth project IDs, no files or folder paths) and your theme, accent colour and language, each with the time of the last change; deleted skins and presets are remembered for a short while | Keeping these the same on every PC where you use this Minecraft account |
| Only with the TRS services on and "Sync with TRS account" on in the TRS Client (in game): your TRS Client settings – which modules are on and their settings, HUD layouts and profiles, the TRS keys of the modules, the config mode for performance mods, whether you finished the introduction (and the module pack you picked) and which "NEW" entries you have opened – each part with the time of its last change; no waypoints, no server addresses, no files, paths or tokens | Keeping the TRS Client the same on every PC and game folder where you use this Minecraft account, and showing the introduction only once |
| Only with the TRS services on: the wardrobe entry of the TRS Client – your favourite skins, outfits (name, skin, cape) and emote wheel slots, with the time of the last change | The same wardrobe on every PC |

**Sync:** "Sync with TRS account" (*Einstellungen → Datenschutz*, on by default while the TRS services are on) keeps
your own skins, your own presets and the look of the launcher (theme, accent colour, language) the same on all your
PCs. Java, memory and all other settings are **not** synced and never leave your PC. Turn the switch off to stop
syncing; what was already synced stays on the server until you delete it with "Alle TRS-Daten löschen". Only you can
read your synced data – there is no admin view of it.

**TRS Client sync:** the TRS Client mod signs in by itself (see above) and keeps its own settings in the same place,
as one document of at most 64 KB per account. It only does this while the TRS services are on in the launcher and
its switch "Sync with TRS account" (TRS menu → *TRS Online Features*, on by default) is on; the switch itself,
waypoints, the freelook server list and Minecraft's own options (options.txt) stay on your PC. The game also reads
your synced theme, accent colour and language and, when you change them in the introduction, writes them back so the
launcher follows. Deleting all TRS data deletes this document too.

The online status is kept **only in the server's memory**, is never written to disk, has no history and expires
**3 minutes** after the last update. It is visible only to your friends, and not at all if you set it to "nobody".
Only whether you are in game right now can also show up as your TRS badge (see above).

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
- The online status disappears 3 minutes after the last update, or immediately when you close the launcher and leave
  the world.
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
