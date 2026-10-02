TRS Launcher runs on your computer. It has **no telemetry, analytics, crash reporting or advertising**. It only sends
information to a server run by the TRS Launcher project if you turn on the optional [TRS services](#trs-services)
(capes, friends, online status, chat). Without your consent, the launcher sends nothing there.

The launcher only connects to other services when that is needed for something you asked it to do:

| Service | When | What is sent |
|---|---|---|
| Microsoft / Xbox Live / Minecraft services | Signing in, starting the game | Standard OAuth sign-in; your Minecraft access token when the game starts |
| Mojang (`piston-meta`, `libraries`, `resources`) | Installing or starting a version | Download requests for game files |
| Mojang session server (`sessionserver.mojang.com`) | Signing in to the TRS services (only after you agreed) | The same "join" request a Minecraft server login uses: your access token, UUID and a one-time challenge |
| Mojang profile services (`api.mojang.com`, `sessionserver.mojang.com`, `textures.minecraft.net`) | Importing a skin by player name, showing player faces (friends, admin search) | The player name or UUID being looked up; a download of that skin image |
| The website of a link you enter | Only when you import a skin "by link" | A normal download request for that image (only HTTPS, no cookies or accounts) |
| TRS services (`trs-launcher.theredstonee.de`, formerly `api.theredstonee.de`) | Only after you agreed, see [below](#trs-services) | Your UUID, name, cape choice, friends, online status, chat messages and pictures you send, reports |
| Fabric, Quilt, Forge, NeoForge maven/meta servers | Installing a mod loader | Download requests |
| Modrinth (`api.modrinth.com`, `cdn.modrinth.com`) | Browsing, installing or updating content | Search queries, file hashes of installed mods (for update checks) |
| CurseForge (`api.curseforge.com`; files and images from `edge.forgecdn.net`, `mediafilez.forgecdn.net`, `media.forgecdn.net`) | Only when you pick CurseForge as the source, install a CurseForge modpack, have content from CurseForge installed or import a CurseForge instance whose files are missing | Search queries and filters, the project and file IDs of content installed from CurseForge (for details and update checks), download requests. Like every web request, this includes your IP address. You don't need a CurseForge account – the launcher identifies itself with its own API key, not with anything about you. |
| Minecraft servers in your server list | Showing live status | A standard server-list ping |
| mclo.gs | Only when you click "Share log" and confirm | The log you picked (latest log, an older log or a crash report), with access tokens and your Windows/Linux user name removed |
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

### Sharing capes with friends

You can share a cape you uploaded with a friend once the team has approved it. Your friend gets an offer in the
launcher and in the game and decides whether to accept it. For this the TRS server stores which cape was offered to
whom, by whom and when, and whether it was accepted. After accepting, your friend can wear the cape like their own,
and other TRS players see it on them. A friend may pass the cape on to their own friends (at most 20 players per cape).

- **Who sees what:** the creator sees everyone who has the cape or an open offer for it (with their Minecraft names),
  also players a friend passed it on to. A holder sees who gave it to them, the creator's name and the players they
  passed it on to themselves.
- **Ending it:** the creator can take the cape back from anyone at any time; this also removes it from everyone that
  player passed it on to. Everyone can give a shared cape back. Removing a friend or blocking a player cancels open
  offers between you two; capes already accepted stay until someone takes them back. Deleting the cape, a rejection by
  the team or deleting a TRS account removes the affected shares immediately.

### Chat and social (TRS services)

With the TRS services on, you can write with your friends and in groups in the launcher and in the game.

- **What is stored:** your messages (text, replies, edits, server invites, waypoint cards), pictures you send,
  reactions, read positions, conversation mutes and group memberships. Messages are kept like in a normal chat **until they are
  deleted** – by you (for everyone), by a group owner, by the team or together with your account.
- **Encryption:** message texts, invites and group names are stored **encrypted** on the server (AES-256-GCM).
  Pictures are **re-encoded** by the server – this removes location data and all other metadata, and large pictures
  are made smaller – and stored encrypted as well. The key is kept separately from the data. The team only reads
  messages that were reported (see below); there is no general admin view of chats.
- **Who sees what:** a direct message only you and your friend; a group message the current members (players added
  later only see messages from the moment they joined). If you block someone, their messages in shared groups are
  hidden for you. After you stop being friends, a direct chat stays readable, but nobody can write in it any more.
- **Read receipts and "is typing"** are on by default and can be turned off in the privacy settings; then you don't see
  them from others either. "Is typing" is only kept in the server's memory for a few seconds.
- **Server invites:** when a chat shows a server address, the TRS server asks that Minecraft server for its icon,
  player count and description (the usual server list ping) and keeps the answer in memory for about a minute. Your IP
  address is not passed on – the Minecraft server only sees the TRS server. Addresses in local networks are never
  contacted.
- **Waypoints:** in the TRS Client you can send a waypoint (or your position) to a friend or a group. The card
  contains only the name you gave it, the coordinates, the dimension and the server address – for a singleplayer world
  only a short code calculated from the world folder, never the world name. Coordinates that appear in the normal
  Minecraft chat are recognised **only locally** in the TRS Client; saving them as a waypoint sends nothing anywhere.
- **Realtime:** while the launcher or the game is open, a connection to the TRS server delivers new messages and other
  updates (friend requests, online status, cape offers) right away. Missed updates are kept in the server's memory for
  up to 10 minutes so they arrive after a short disconnect.

### Chat data on this PC

- **Notification settings** (corner, duration, sound, Do not disturb, which kinds) are stored only in the launcher's
  settings on your PC. Windows notifications are only shown while the launcher is in the background, and only if that
  switch is on.
- **Pictures from your PC** stay where they are. So they appear under "Uploads", the launcher remembers the paths of the
  last 40 picture files you picked in `chat-uploads.json` in its data folder; screenshots you star are remembered in
  `screenshot-favorites.json`. Nothing of this leaves your PC unless you send a picture. Pictures pasted from the
  clipboard are only kept in memory until the launcher closes.
- **Pictures you receive** are loaded by the launcher itself and only kept in memory while it runs – nothing is written
  to disk, and your TRS token never reaches the launcher window.
- **"Last online"** in the friend list is what the launcher itself saw (stored locally per account), not information
  from the server.

### Shared screenshots

In the launcher's screenshot gallery and in the TRS Client ("Clips & pictures") you can share a screenshot as a link
(`https://trs-launcher.theredstonee.de/s/…`). Only the one picture you chose is sent, and only when you click
"Share as link"; your other screenshots stay on your PC.

- **What is stored:** the picture, **re-encoded** by the server (this removes location data and all other metadata;
  pictures larger than 4096 pixels are made smaller), its size and format, when it was shared and when it expires, and
  which TRS account shared it. The account is only used internally – for your list "My shared pictures", the limits and
  moderation. The public page and the picture show **no player name and no UUID**.
- **Who sees it:** **anyone who has the link** – there is no password. The link contains a long random code that can't
  be guessed, and the page tells search engines not to index it (`noindex`). If you post the link in Discord or a
  similar app, that app's servers load the picture to show a preview.
- **How long:** **30 days**, then the picture is deleted automatically. You can delete it earlier under "My shared
  pictures" in the launcher or the TRS Client – the link stops working right away (previews that other apps already
  made are outside our control). "Alle TRS-Daten löschen" deletes all your shared pictures.
- **Limits:** at most 10 MB per picture, 50 active links and 20 new links per day per account. An upload ban from
  moderation also blocks sharing.
- **Reports:** a shared picture can be reported, also directly on its page. A report from the page only sends the
  reason; the page stores no IP address (rate limits count in memory only). A report keeps a copy of the picture as
  evidence, like other reports (see below), and the team can delete shared pictures.

### Sharing modpacks

In the launcher you can share an instance as a modpack (*Instance → Share → Share modpack*). The launcher packs the
**mod list** (mods, resource packs and shaders that Modrinth, CurseForge or GitHub can serve are only listed with
their download address) and the **folders you pick** (e.g. settings/configs, resource packs, your own mod files) into
a `.mrpack` file and uploads it only when you click "Share". Worlds, screenshots, logs and account files are never
included.

- **What is stored:** the pack file, its name, description and version, Minecraft version and mod loader, how many
  files it contains, when it was shared, updated and when it expires, how often it was installed, the sharing TRS
  account, and to which friends it was sent.
- **Who sees it:** anyone with the **code** (`TRS-XXXX-XXXX`) or the **link** (`/p/…`) sees the name, description,
  contents summary and **your player name and head** – so people know whose pack it is. Downloading the pack needs a
  TRS account (the launcher). Friends you send it to see it in their list "Shared with me". The page is not indexed
  by search engines.
- **How long:** you choose per pack – **1, 7 or 30 days, or no expiry**. Expired packs are deleted automatically; you
  can delete a pack earlier in "My modpacks" – code and link stop working right away (copies others already installed
  stay on their PCs). A new version keeps the code; friends who installed it see "Update available".
- **Limits:** at most 1 GB per pack, 10 shared packs and 30 uploads per day per account. An upload ban from
  moderation also blocks sharing.
- **Reports:** a pack can be reported in the launcher or on its page (signed in). The report keeps the pack's name,
  code, description, contents summary and checksum as evidence (not the file); the team can delete packs.

### Circuit library (TRS Client)

The circuit library in the TRS Client (and on the website, /circuits) loads its circuits from the TRS server. It needs
no account: once per game start the TRS Client asks whether there are new or changed circuits and downloads only those;
they are cached in `config/trsclient/circuits/`. This only happens while the TRS online features are allowed. The
server only counts requests per IP address in memory (rate limit).

- **Submitting a circuit** (signed in, in the TRS Client or on the website): we store the circuit (only blocks and their
  states – no chest contents or other block data), the name, description, category and language you entered, your
  Minecraft UUID and name, the time, the status and the team's answer. Uploaded files are converted and discarded right
  away. Only team members whose role may manage the library see submissions.
- **Your name is shown:** if the team accepts your circuit, it appears in the library in the TRS Client and on the
  website **with your Minecraft name (and your UUID for the head picture) as the creator**. You confirm this before
  submitting.
- **How long:** decided submissions are deleted **90 days** after the decision, open ones stay until they are decided.
  A published circuit stays until the team removes it; "Alle TRS-Daten löschen" deletes your submissions and removes
  your name from your circuits. You can ask us to remove a circuit of yours at any time.
- **Limits and reports:** at most 5 submissions per day; an upload ban from moderation also blocks submissions. Circuits
  can be reported like other content (see below).

### Issues and bug reports

The public issue tracker on the website (/issues, /roadmap) is described in the website part of this policy. The
launcher and the TRS Client use it like this:

- **Notifications:** if you follow an issue (you do automatically for issues you opened or commented on), the launcher
  gets an event over its existing TRS connection when the status changes, the team answers, a fix ships ("fixed in")
  or the issue is merged, and shows a social notification with a "View" button that opens the issue in your browser.
  Nothing is stored for this on your PC; the social notification settings apply.
- **"Report a bug" in the TRS Client** sends a title, your description and – only the parts you tick, shown before
  sending – the TRS Client version, the Minecraft version and loader, your mod list, an excerpt of the game log and a
  screenshot, together with your TRS account. The client removes access tokens, session ids, e-mail addresses, IP
  addresses, UUIDs and your player name from the log first, the server does it again. The issue, versions, mod list and
  screenshot are public on the website with your Minecraft name; the log excerpt only you and the team can see.
- **How long:** issues and comments stay while the tracker exists; "Alle TRS-Daten löschen" deletes your votes,
  follows, log excerpts and uploaded pictures, your issues and comments stay without your name.

### Reporting bugs in the TRS Client

TRS Client menu → "Report bug" sends a bug report to the TRS issue tracker, signed in with your TRS account (TRS services
must be turned on in the launcher). Nothing is sent before you press "Send now" on the preview page, which shows exactly
what goes out.

- **Always:** title and description you typed, your Minecraft UUID and name (as the author of the report) and the time.
- **Ticked by default:** TRS Client version, Minecraft version and loader, list of mod file names in `mods/`.
- **Only if you tick it:** an excerpt of the game log (the last 300 lines of `logs/latest.log`, or the start of the newest
  crash report) and one screenshot (the newest one or one you pick).
- **The log is cleaned on your PC before it's sent:** access and session tokens, `--accessToken` and similar start
  arguments, UUIDs, your player name and account names, IP addresses, user names in paths (`C:\Users\<name>` →
  `C:\Users\<user>`), email addresses and the content of chat lines are replaced. The preview shows how many of each were
  removed. A screenshot is sent as it is – check what it shows.
- **Who sees it:** the report appears publicly in the issue tracker on trs-launcher.theredstonee.de (title, description,
  screenshot, versions, mod list and your name as author). The log excerpt is only visible to the TRS team and to you.
- **Limits:** a few reports per day; a sanction from moderation can block reporting.

### Reports and moderation

You can report messages, pictures, players and groups (with a reason and an optional note). The report stores an
encrypted copy of the reported content and of up to 10 messages before and after it, exactly as you could see them;
reported pictures are copied. Team members whose role allows it review reports on the website or in the launcher and can delete messages,
warn a player, mute them in chat for a while or ban them; every action is logged. The reporter only learns whether
something was done, not what. The reported player doesn't learn who reported them.

Automatic protection: messages sent too fast or repeated many times are blocked and can lead to a short automatic mute;
links and server invites in groups are only accepted from the owner or from players who are friends with everyone in
the group; the team can keep a list of blocked words. If three different players report the same player within a day,
that player is muted in chat until the team has looked at it. Players whose reports are often unfounded don't count for
this.

Reports are kept while they are open and for **90 days** after the decision (for objections); then the copies, notes
and pictures are deleted, and the report itself (without content) is deleted after **one year**. If you delete your
account, reports you filed stay without your name; reports against you and an active chat mute stay until these
periods end, so moderation can't be escaped by deleting the account.

### Sanctions and appeals

The team (members whose role allows it) can give sanctions for violations: a warning, a chat mute, a social ban (no friend
requests, groups or invites), an upload ban (no own capes or cosmetics), a world hosting ban or a ban of the whole TRS
account – for a limited time or permanently. We store your UUID, the kind, the reason (from a fixed list, plus an
optional text you can see), start and end, who gave the sanction, an internal team note and every later change
(shortened, extended, lifted – each with time, team member and reason). The team can also keep internal notes about
players and sees the names an account used to sign in to TRS.

In the launcher and in the game you see your active and past sanctions with kind, reason, start and end – not the
internal note and not who gave it. You can **appeal each active sanction once** (20 to 1000 characters); even with a
banned account this works through a short access valid only for that (1 hour). A team member who did not give the
sanction decides and writes you an answer.

**Retention:** sanctions with their changes and appeal are deleted **2 years after they ended** (expired or lifted);
permanent sanctions stay while they apply. Internal notes are deleted after **2 years**, former names **2 years** after
their last use, entries in the team's audit log after **2 years**. If you delete your account, warnings and ended
sanctions are deleted at once; **active** sanctions (and notes about them) stay until they end, so they can't be
escaped by deleting. The legal basis is our legitimate interest in a safe service (Art. 6(1)(f) GDPR).

### Website sign-in and team applications

**Website sign-in with Microsoft.** On trs-launcher.theredstonee.de you can sign in with the Microsoft account that owns
Minecraft: Java Edition. We only receive your Minecraft UUID and name; the Microsoft, Xbox and Minecraft tokens exist in
memory for a few seconds and are discarded – we store no tokens, e-mail or password, only a website session (8 hours).

**Website sign-in with the TRS Launcher.** Instead of Microsoft you can confirm a website sign-in in the launcher: the
website shows a short code and opens the launcher (or you type the code under Settings → Privacy → "Sign in on the
website"). The launcher then looks up the request and – **only after you click "Confirm"** – confirms it with the TRS
token of the account you picked; "Decline" rejects it. The launcher sends only the request reference and the code; it
shows the website, the code, a rough browser description (such as "Firefox · Windows") and the time of the request.
The server keeps the request for at most two minutes (hashes of the link and of a browser value, the code, the rough
browser description, after confirming your UUID) and then only the website session (8 hours).

**News in the launcher.** With the TRS services switched on, the launcher also loads the team's news posts from
trs-launcher.theredstonee.de (text and pictures; without your token – nothing about you is sent) and caches them locally.

**Team applications:** position, Minecraft name and UUID, Discord name, age group (never a birth date), your answers,
status and our answer. Only team members whose role may review applications see them. Rejected or withdrawn
applications are deleted 6 months after the decision, accepted ones 6 months after you leave the team; deleting your
TRS account deletes them at once.

### Hosting a world for friends (TRS Client)

In the TRS Client you can open your singleplayer world for friends ("Host world") without port forwarding. The TRS
server only manages **who may join** and helps the two games **find each other**; the game itself never runs through
the TRS server.

- **Stored on the TRS server while the world is open:** the world name, Minecraft version, mod loader and settings you
  chose (game mode, PvP, cheats, max. players, open/closed, visible to friends), the join code, who you invited, who asked
  to join and who you let in or banned (with times), and the player count your game reports. When you close the world,
  or 90 seconds after your game stops reporting, all of this is **deleted**. Friends see your open world only if you
  leave it visible to friends.
- **Kept longer:** only your own list of players you banned from all your worlds ("remember ban"), until you remove them
  or delete your TRS account.
- **Connecting:** the two games first try to connect **directly**. For this each game asks a STUN server (by default
  only the TRS relay server) for its public address and sends its connection candidates to the other player through the
  TRS server. **With a direct connection, you and the other player see each other's IP address** – like on any
  Minecraft server. These candidates are only passed through and kept at most 10 minutes in the server's memory for
  delivery.
- **TRS relay:** if a direct connection doesn't work, the game data runs through the TRS relay server (a separate server
  in Germany, run by the TRS Launcher project). It only lets players in with a short-lived access key from the TRS
  server, sees the IP addresses of the connected games and forwards the bytes. It **does not store or log any game data
  or IP addresses** (only counters such as the number of connections) and keeps nothing on disk.
- **Joining from the launcher:** under *Social → Worlds*, in chat (world cards) and in notifications the launcher shows
  your friends' open worlds and invites, and sends your "Join"/"Ask to join" to the TRS server with your TRS sign-in.
  When you are let in, it starts a matching instance and hands the game only the world's ID and join code, over the
  local connection on your computer (`127.0.0.1`) – no access keys. The game then connects by itself as described
  above. The launcher keeps nothing about hosted worlds on disk.
- **Public link (e4mc):** optionally you can create a public link that anyone can use to join. This uses **e4mc**, a
  service by other operators, not the TRS server. When you turn it on (only after a warning you have to confirm), your
  game connects to e4mc's relay and e4mc sees your IP address and the players' IP addresses and forwards the game data;
  e4mc's own privacy policy applies. It is off by default. The first time you turn it on, the game downloads the
  network library it needs (Netty with QUIC, open source) once from Maven Central (`repo1.maven.org`, checked against
  fixed checksums) and asks e4mc's broker (`broker.e4mc.link`) for the nearest relay – both see your IP address. The
  e4mc part of the TRS Client is based on the e4mc mod (MIT licence, © Skye); its licence text ships inside the game.
- **Only through the relay:** in the TRS Client (Social → "Direct connections") you can turn direct connections off.
  Then your game always uses the TRS relay and the other player never learns your IP address.
- **World backup:** before opening, the TRS Client can save a ZIP of your world in the game's `backups` folder on your
  computer. It never leaves your computer.
- **Protection in the game:** a guest can only log in with the name the TRS server confirmed for them (nobody can take
  the host's name), and when you remove or ban a player the game closes their connection at once.

#### Mods and resource pack when hosting a world

- **Off by default.** Only if you turn on "Share mods" or "Share resource pack" for a world does the TRS Client share
  anything; the choice is remembered in the game folder (`config/trsclient/hosting-share.json`).
- **What the TRS server stores (only while the world is open, deleted with it):** the list of shared mods – name,
  version, file name, size, required/optional, source (Modrinth, CurseForge, directly from the host or "get it
  yourself") with project and file IDs and the files' checksums (SHA-1, SHA-512/SHA-256) – and for a resource pack its
  name, size and checksums. Everybody who may see your world sees this list. **No files** are ever stored on or sent
  through the TRS server.
- **Recognizing your mods:** to find out which mods are on Modrinth or CurseForge, the TRS Launcher reads the mod files
  of your instance and sends their checksums (and CurseForge fingerprints) to Modrinth (`api.modrinth.com`) and
  CurseForge (`api.curseforge.com`); without the launcher the TRS Client asks Modrinth itself. They see your IP address.
- **Files directly between the players:** mods that are in no store (only if you turn on "Send mods directly from the
  host") and the resource pack go **directly from the host's game to the guest** – through the same direct connection or
  the TRS relay as the game, which only forwards the bytes and stores nothing. Only players you let into the world get
  them, and only exactly the files you chose. Guests download store mods themselves from Modrinth or CurseForge.
- **Guests:** the launcher shows the list on every join and checks every file (checksum and size) before using it. Mods
  directly from the host are only installed after you confirm "I trust this host". The resource pack is only used if you
  answer Minecraft's question with yes; your game serves it to Minecraft through a local address on your computer
  (`127.0.0.1`) that only works for this connection.

### Achievements

The launcher has achievements (points, rarities, a few rewards such as a cape or a cosmetic). They are part of the TRS
services and only work while those are on.

- **What the server counts itself:** time in game and day streaks (from the in-game status the launcher or the TRS
  Client sends anyway), friends, chat messages, issues and votes, circuits, shared packs, worlds, capes and cosmetics.
- **What the launcher reports:** a few facts only it knows – a game was started (with the local hour of the start,
  0–23, for time-of-day achievements), mods or a modpack were installed (only the number), a clip was saved (only the
  number), the crash helper fixed something, instances were imported from another launcher. No names, files, paths or
  instances are sent. Reports are collected for a few seconds and sent in the background; nothing is sent without the
  TRS services or while you are offline.
- **Stored on the TRS server:** which achievements you unlocked and when, counters and flags per account, your total
  time in game with the current and longest session and your day streak (no history of sessions), and the switch
  "Achievements visible to friends".
- **Who sees it:** you and your accepted friends (points and unlocked achievements) – nobody else. With "Achievements
  visible to friends" off (achievements page or *Einstellungen → Datenschutz*) your friends only see that your
  achievements are private.
- **How long:** as long as your TRS account exists; "Alle TRS-Daten löschen" deletes all of it.

### What is stored

| Data | Why |
|---|---|
| Minecraft UUID and player name | To identify your TRS account and show your name to friends |
| Account creation time and last sign-in time | Account management and abuse prevention |
| The address of your current Minecraft skin (a public link on Mojang's texture server), its model and when it was last seen | Showing your face in lists and your figure on the team page without asking Mojang every time |
| Session tokens (only as SHA-256 hashes, valid for 30 days, at most 10 per account) | Keeping you signed in |
| Your privacy settings (TRS badge, cape visible to others, online status visible to friends/nobody, share server) | So the services respect your choices |
| Your chosen cape, capes unlocked by codes or granted by the team | Showing your cape to other TRS players |
| Capes you upload (the image, re-encoded without metadata), their review status and an optional name | Cape uploads; every upload is reviewed by the team before others see it |
| Reports you file about other players' capes (reason, optional note) | Moderation |
| Cape shares: which capes you offered to whom (and who passed them on), open offers to you and the capes friends shared with you, each with the time and whether it was accepted | Sharing capes with friends (see above) |
| Friends, friend requests and blocks | The friends list |
| Online status: "online in the launcher" or "in game" (from the launcher or the TRS Client) with version and mod loader, and, only if you turned on "Server teilen", the server address | Showing friends what you play and letting them join you; showing the TRS badge while you play (see above) |
| Only with "Sync with TRS account" on: your own skins from "My skins" (the image, re-encoded without metadata, its name and model), your own mod presets (names and Modrinth project IDs, no files or folder paths) and your theme, accent colour and language, each with the time of the last change; deleted skins and presets are remembered for a short while | Keeping these the same on every PC where you use this Minecraft account |
| Only with the TRS services on and "Sync with TRS account" on in the TRS Client (in game): your TRS Client settings – which modules are on and their settings, HUD layouts and profiles, the TRS keys of the modules, the config mode for performance mods, whether you finished the introduction (and the module pack you picked) and which "NEW" entries you have opened – each part with the time of its last change; no waypoints, no server addresses, no files, paths or tokens | Keeping the TRS Client the same on every PC and game folder where you use this Minecraft account, and showing the introduction only once |
| Only with the TRS services on: the wardrobe entry of the TRS Client – your favourite skins, outfits (name, skin, cape) and emote wheel slots, with the time of the last change | The same wardrobe on every PC |
| Only with the TRS services on, "Sync with TRS account" on in the TRS Client and the notes switch "Sync with TRS account" on: your world notes from the TRS Client – title, text (including checklists and coordinates you typed), creation and change time, and the world they belong to (server address, or for a singleplayer world a code made from the world folder plus the folder name); deleted notes are remembered as an empty marker | The same notes on every PC |
| Chat: your messages (text, replies, edits, server invites), the pictures you send (re-encoded, encrypted), reactions, read positions, conversation mutes and group memberships, each with times | Chatting with friends and in groups (see above) |
| Chat settings: read receipts and "is typing" on or off | So the chat respects your choices |
| Shared screenshots (only the pictures you share): the re-encoded picture, size, format, share and expiry time, the sharing account (not shown publicly) | Sharing a screenshot as a link (see above) |
| Shared modpacks (only the packs you share): the pack file, name, description, version, Minecraft version, loader, file counts, share/update/expiry time, installs, the sharing account (name shown on the pack page), friends it was sent to | Sharing a modpack by code, link or with friends (see above) |
| Issues and comments you write, your votes and follows; with "Report a bug" in the TRS Client the parts you tick (versions, mod list, log excerpt – cleaned, only visible to you and the team – and a screenshot) | Reporting bugs, suggesting features, voting and notifications (see above) |
| Achievements: which ones you unlocked and when, counters and flags (e.g. number of game starts or installed mods, the local hour of a start), total time in game with current and longest session, day streak, and whether friends may see your achievements | Achievements and their rewards; visible to you and your friends only (see above) |
| Circuit submissions: the circuit (blocks and states only), name, description, category, language, time, status and the team's answer; for accepted circuits your name as the creator | Submitting a circuit to the library (see above) |
| Reports you file and reports about you, each with an encrypted copy of the reported content and its context; sanctions (warning, mute, bans) with their history and appeal, internal team notes, former names | Moderation (see above) |
| World hosting (only while your world is open): world name, version, mod loader and settings, join code, invited players, join requests, admitted and banned players with times, player count, shared mod list and resource pack info (names, sizes, sources, checksums – no files); your list of players banned from all your worlds | Hosting a world for friends (see above) |

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

**TRS Client notes:** notes per world are saved on your PC in `config/trsclient/notes` (one file per world or
server). With the TRS services on and both switches on (*TRS Online Features* → "Sync with TRS account" and on the
*World Notes* page "Sync with TRS account", both on by default) the TRS Client also keeps them in your TRS account,
note by note, so they are the same on every PC. Which note is pinned to the HUD stays on your PC. Only you can read your notes – there is no admin view.
A deleted note leaves an empty marker for 90 days so your other PCs delete it too. Turn off the notes switch to keep
all notes on this PC; "Alle TRS-Daten löschen" deletes the synced notes.

The online status is kept **only in the server's memory**, is never written to disk, has no history and expires
**3 minutes** after the last update. It is visible only to your friends, and not at all if you set it to "nobody".
Only whether you are in game right now can also show up as your TRS badge (see above).

Admin actions (such as approving a cape, a ban or a decision on a chat report) are recorded in an audit log together
with the affected UUID.

### Purpose and legal basis

The data is processed only to provide the TRS services you asked for: capes, the friends list, the online status, the
chat and syncing your skins, presets and launcher look between your PCs.
The legal basis is the performance of the service you requested (Art. 6(1)(b) GDPR). Keeping the services free of abuse
(reviewing uploads, reports and their evidence, spam protection, mutes, bans and rate limits) is based on our legitimate interest in a safe service
(Art. 6(1)(f) GDPR). There is no advertising, no profiling and no sale of data.

### Retention and deletion

- Your data is kept as long as your TRS account exists.
- Session tokens expire after 30 days; signing out or removing an account from the launcher revokes the token.
- The online status disappears 3 minutes after the last update, or immediately when you close the launcher and leave
  the world.
- Synced notes stay until you delete them in the TRS Client or delete all TRS data; markers of deleted notes are
  removed after 90 days.
- Synced skins, presets and settings stay until you delete them in the launcher (a skin deleted on one PC is deleted on
  the server, too). Notes about deleted skins are kept for 30 days so your other PCs can delete them as well.
- Chat messages and pictures stay until they are deleted (by you for everyone, by the group owner or by the team) or
  the group is deleted. Pictures that were uploaded but never sent are deleted after 1 hour.
- Shared screenshots are deleted automatically **30 days** after sharing, or earlier when you (or the team) delete them.
- Shared modpacks are deleted automatically when the duration you chose ends (1, 7 or 30 days; "no expiry" stays
  until you or the team delete it).
- Decided circuit submissions are deleted **90 days** after the decision; published circuits stay until the team
  removes them (without your name after "Alle TRS-Daten löschen").
- **"Alle TRS-Daten löschen"** (*Einstellungen → Datenschutz*) deletes everything immediately (GDPR Art. 17): your
  account, sessions, friendships, requests and blocks, uploaded capes and their files, cape shares (your capes with
  friends and the capes friends shared with you), code redemptions, reports,
  your online status, all synced skins, presets and settings, all your direct chats (for both sides) and your messages,
  reactions and pictures in groups (groups you own go to the longest member), all pictures you shared as a link, all modpacks you shared and your achievements with their counters. Afterwards the TRS services are turned off in the
  launcher. The skins and presets on your PC are kept.
- Only active sanctions (such as a ban or a running chat mute, with reason and period) and reports about you (until
  their retention ends, see above) are kept after deletion, so they can't be escaped by signing in again.
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
- **Minecraft servers in chat invites** are contacted by the TRS server (server list ping) to show their icon and
  player count; they only see the TRS server's address.
- **TRS relay server** (world hosting, see above): a separate server in **Germany** run by the TRS Launcher project; it only forwards game data and keeps nothing on disk.

### Your rights

You have the right to access, rectification, erasure, restriction of processing, data portability and objection
(Art. 15–21 GDPR), and the right to lodge a complaint with a supervisory authority. Most of this you can do yourself in
the launcher (turn the services off, change the privacy settings, delete all data). For anything else, contact us.

### Contact

Theredstonee – open an issue at <https://github.com/theredstonee/TRS-Launcher/issues> or use the contact details on
<https://theredstonee.de>. Please don't post personal data in public issues; ask for a private contact instead.
