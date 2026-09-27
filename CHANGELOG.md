# Changelog

Every version of the TRS Launcher is listed here – first in English, then in German. The release build copies
the section of its version into the GitHub release and the auto-update, and the launcher shows it once after
updating ("What's new").

<!--
How to write an entry:
- Collect changes under "## Unreleased" while working. For a release, rename it to "## <version> – <YYYY-MM-DD>".
- Every version needs both "### English" and "### Deutsch" with the same points. Write for players, not
  developers: what changed for them, in plain words, no file or function names.
- The release build fails when the section for its version is missing or one language is empty.
- Every release gets a theme name in the heading ("## 0.5.0 – 2026-09-30 – The Clip Update | Das Clip-Update")
  and an update banner right below it: an HTML comment with "banner: accent=#rrggbb motif=/news/0.5.0/banner.png".
  The banner keeps a fixed look (deepslate, redstone wires, pixel font); only the accent colour and the motif change.
  Make the motif in TRS Studio with the "Update-Banner" template (sketch → export as HD pixel art, motif target).
- Right below the banner comes a second HTML comment starting with "shots:" – one screenshot of the new features per
  line: "/news/<version>/<file>.png | English caption | Deutsche Bildunterschrift" (captions optional; without the
  German one the English caption is used for both). PNG or WebP in public/news/<version>/, at most 8, each at most
  2 MB and 640×360 to 3840×2400 px. From 0.6.5 on every release needs at least one screenshot; the release check
  (scripts/changelog.mjs check) fails otherwise. How to take them: docs/release-screenshots.md.
-->

## Unreleased

### English

- **Log filters you can combine.** In an instance's Logs tab, Errors, Warnings, Info and Debug are now switches: turn
  on as many as you like (for example only errors and warnings). New buttons "TRS" and "Chat" show only lines of the
  TRS Client or chat messages, and work together with the levels and the search. "All" shows everything again. Works
  for the live log and for older log files.
- **Search in Minecraft's key binds (TRS Client).** Controls → Key Binds now has a search box: type a name, or
  `key:R` / `key:2` for everything on a key, `mouse` or `key:mouse4` for mouse buttons, `mod:sodium` for one mod,
  `conflict` for keys used twice or `unbound` for free actions – combine them with spaces. The "Key…" button next to
  it shows everything on the next key or mouse button you press. Works from 1.8.9 to 26.3 and also finds the keys of
  other mods.
- **Sign in again without restarting (TRS Client).** When a server says "Invalid session" or you get disconnected,
  the error screen now has "Sign in again": the TRS Launcher gets a fresh session, the game uses it right away and
  connects to the server again – no need to restart the instance. The screen also offers "Connect again", "Switch
  account", "Copy error" and "Server status", and error screens now use the TRS menu style (can be turned off under
  Menu Style → Error screens). Signing in again needs the TRS Launcher to be open.
- **Emote wheel now on R by default.** From Minecraft 1.21.11 on, G opens the new "Quick Actions", so new players get
  the emote wheel on R. If you already use G, nothing changes – you can pick any key in the controls.

### Deutsch

- **Log-Filter, die sich kombinieren lassen.** Im Reiter „Logs“ einer Instanz sind Fehler, Warnungen, Info und Debug
  jetzt Schalter: Schalte beliebig viele an (zum Beispiel nur Fehler und Warnungen). Neue Knöpfe „TRS“ und „Chat“
  zeigen nur Zeilen des TRS Client bzw. Chat-Nachrichten und wirken zusammen mit den Stufen und der Suche. „Alle“
  zeigt wieder alles. Gilt für den Live-Log und für ältere Log-Dateien.
- **Suche in der Minecraft-Tastenbelegung (TRS Client).** Steuerung → Tastenbelegung hat jetzt ein Suchfeld: Tippe
  einen Namen, oder `key:R` / `key:2` für alles auf einer Taste, `maus` oder `key:maus4` für Maustasten, `mod:sodium`
  für einen Mod, `konflikt` für doppelt belegte Tasten oder `unbelegt` für freie Aktionen – mit Leerzeichen
  kombinierbar. Der Knopf „Taste…“ daneben zeigt alles, was auf der nächsten gedrückten Taste oder Maustaste liegt.
  Funktioniert von 1.8.9 bis 26.3 und findet auch die Tasten anderer Mods.
- **Neu anmelden ohne Neustart (TRS Client).** Meldet ein Server „Ungültige Sitzung“ oder wirst du getrennt, hat der
  Fehlerbildschirm jetzt „Neu anmelden“: Der TRS Launcher holt eine frische Sitzung, das Spiel übernimmt sie sofort und
  verbindet wieder mit dem Server – die Instanz muss nicht neu gestartet werden. Dazu gibt es „Erneut verbinden“,
  „Konto wechseln“, „Fehler kopieren“ und „Server-Status“, und Fehlerbildschirme erscheinen im TRS-Menü-Stil
  (abschaltbar unter Menü-Stil → Fehlerbildschirme). Zum Neu-Anmelden muss der TRS Launcher geöffnet sein.
- **Emote-Rad jetzt ab Werk auf R.** Ab Minecraft 1.21.11 öffnet G die neuen „Schnellaktionen“, deshalb bekommen neue
  Spieler das Emote-Rad auf R. Wer schon G nutzt, behält G – die Taste lässt sich in der Steuerung frei wählen.

## 0.10.0 – 2026-09-27 – Toolbox | Werkzeugkasten
<!-- banner: accent=#ff9f1c motif=/news/0.10.0/banner.png -->
<!-- shots:
/news/0.10.0/circuits.png | Circuit library in the TRS Client: ready-made redstone circuits with explanation and materials | Schaltungs-Bibliothek im TRS Client: fertige Redstone-Schaltungen mit Erklärung und Material
/news/0.10.0/circuit-ghost.png | Show a circuit as a template in your world and build it block by block | Schaltung als Vorlage in der Welt einblenden und Block für Block nachbauen
/news/0.10.0/circuits-web.png | All circuits also on the website – download for the structure block | Alle Schaltungen auch auf der Website – Download für den Konstruktionsblock
/news/0.10.0/crash-helper.png | Crash helper: the cause in plain words and a button to fix it | Absturz-Helfer: die Ursache in klaren Worten und ein Knopf zum Beheben
/news/0.10.0/modpack-choice.png | Modpacks: install with or without TRS Client | Modpacks: mit oder ohne TRS Client installieren
/news/0.10.0/tooltips.png | Better tooltips: shulker contents, maps, durability and food | Bessere Tooltips: Shulker-Inhalt, Karten, Haltbarkeit und Essen
/news/0.10.0/waypoint.png | Send waypoints to friends in the chat | Wegpunkte im Chat an Freunde schicken
/news/0.10.0/screenshot-link.png | Share a screenshot as a link, valid for 30 days | Screenshot als Link teilen, 30 Tage gültig
-->

### English

- **With or without TRS Client – your choice for modpacks.** When you install a modpack (Modrinth or CurseForge,
  from Discover or from a file) or import instances with mods from another launcher, the launcher now asks once:
  "With TRS Client" or "Without TRS Client". "With" is recommended – unless the pack already brings mods that
  overlap with it, such as another client (Essential), its own minimap (Xaero's, JourneyMap, VoxelMap, FTB Chunks)
  or its own HUD (MiniHUD). Then you see which ones, and "Without" is preselected. Packs with their own zoom only
  get a hint. If there is no TRS Client for the pack's Minecraft version, the option is greyed out with the reason.
  When importing several instances you choose once for all and can tick exceptions per instance. Your choice is
  saved in the instance and can be changed anytime in its settings. New instances without a pack work as before.
- **New setting "TRS Client for modpacks"** (Settings → Default settings): always ask (default), always with or
  always without – with "always …" the question no longer appears.
- **Crash helper.** When the game crashes – at startup or later – the launcher now reads the crash report and the log
  itself and explains in plain words what happened: which mods are involved (with their icons), and what helps. It
  recognizes mods that clash, missing or wrong dependencies, the same mod installed twice, mods for another Minecraft
  or loader version, too little memory, the wrong Java version, graphics driver crashes (AMD, Intel, NVIDIA, missing
  OpenGL), damaged files and known combinations such as Sodium with OptiFine, Iris without Sodium or two minimaps.
- **Fix it with one click.** Depending on the cause you get buttons like "Disable mod" (reversible), "Install
  dependency", "Remove duplicates", "Increase RAM", "Use Java 21", "Update TRS Client", "Share log" and "Launch again".
  Every change is confirmed first and shows up in the instance history.
- **Earlier crashes stay available.** Open them again from the History tab, or analyze any older log or crash report
  in the Logs tab.
- **Private by design.** The analysis runs only on your PC; nothing is sent unless you share the log. Paths with your
  user name and your player name are hidden in the lines shown.
- Includes a clear answer for the crash of TRS Client 0.9.0 together with Essential: update the TRS Client.
- **Share waypoints with friends.** In the TRS Client you can send a waypoint – or where you are standing – to a
  friend or a group in the social chat. It arrives as a card with name, coordinates, dimension and server. Your
  friends can **take it over** as their own waypoint (when they are on the same server or in the same world) or
  **show** it on the map. In the launcher chat the card has a button to copy the coordinates.
- **Clickable coordinates in the Minecraft chat.** When someone writes coordinates like "x: 100 y: 64 z: -20" or
  "100 64 -20", the TRS Client underlines them – one click saves them as a waypoint. This happens only on your PC,
  nothing is sent anywhere.
- **Share screenshots as a link.** In the launcher's screenshot gallery, in "Clips & pictures" and right after taking
  a screenshot in the game, "Share as link" uploads the picture and copies a link you can post anywhere – Discord
  shows a preview. Links stay online for 30 days and can be deleted earlier under "My shared pictures". Pictures are
  re-encoded without any hidden data, and the page never shows your name. Anyone can report a picture.
- **Better tooltips in the TRS Client.** Hovering over a shulker box shows its contents as a grid in the box's colour,
  filled maps show a small preview, tools and armour show their durability as a number and percent, food shows hunger
  and saturation as drumsticks, and many enchantments are packed into a few lines – hold Shift for the full list.
  Everything can be switched on and off one by one (TRS menu → "Better Tooltips"). Display only. Shulker contents from
  Minecraft 1.11; the better tooltips are not available on 1.7.10 and 1.13.2.
- **Server profiles.** Save a setup for a server – modules on or off, their settings and optionally a HUD profile – and
  the TRS Client switches to it by itself when you join (by address or pattern like "*.hypixel.net", or for
  singleplayer) and back to "Standard" when you leave. "Remember current setup for this server" in TRS menu →
  Profiles → Server profiles saves what you just changed on the server. Profiles stay on this PC.
- **Panorama screenshots.** One key (unbound by default, set it in Controls) or "Take panorama now" in the TRS menu
  records a 360° panorama: six cube images like the title screen panorama (ready for a resource pack) and one 360°
  picture you can view in any panorama viewer. Saved in screenshots/panorama; a message offers to open the folder, and "Share as link" on the
  module page uploads the 360° picture and copies the link.
  The HUD is hidden while recording. From Minecraft 1.8.9 (not on 1.7.10 and 1.13.2).
- **Website sign-in with Microsoft.** The website now only signs in with the Microsoft account that owns Minecraft.
  Confirming a website sign-in with a code from the launcher is gone; the launcher opens the website instead
  (Settings → Privacy, Ctrl+K, the team area).
- **Team roles with permissions.** The team area in the launcher now follows the fine-grained permissions of your
  team role: every team role sees it, but only the areas and buttons it may use – for example supporters see
  reports without their content ("Content only visible with permission") and can only give warnings. Sanctions given
  by someone with a higher rank can't be changed. Roles, applications and positions open on the website.
- **My applications.** Settings → Privacy and Ctrl+K show your team applications with status and the team's answer;
  open ones can be withdrawn. When the status changes you get a notification – in the game from the TRS Client, too.
- **Circuit library in the TRS Client.** A new page in the redstone category with ready-made redstone circuits:
  logic gates, repeater chain, torch tower, three clocks, memory (RS latch, T flip-flops, D latch), pulse circuits,
  a 2×2 piston door and a hidden staircase, farm basics (sugar cane, item filter, automatic furnace, item elevator)
  and displays. Each comes with an explanation, difficulty, size, material list (with what you already have in your
  inventory), the Minecraft version it needs and whether it runs reliably on servers – plus a rotatable 3D preview
  you can view layer by layer. Search, categories and "my version" help you find the right one.
- **Show a circuit as a template in the world.** The template follows your view until you confirm (R rotates,
  Enter confirms); ghost blocks then show what goes where – green when a block is right, red when it is wrong (with
  the reason) and grey while it is missing – with a "32/40 blocks" progress bar, layer by layer with ↑/↓ and H to
  hide it. The template stays in the world or on the server when you reconnect. Display only, like a Litematica
  preview: nothing is built for you and nothing is sent to the server. From Minecraft 1.8.9 (not on 1.7.10 and 1.13.2).
- **The circuits come from the TRS server** – new circuits appear without a mod update. The library checks for
  changes once per start and keeps a local copy for offline play; before the first download it tells you it will
  load with internet.
- **Submit your own circuits.** Mark a circuit in your world (two corners, up to 16×16×16), give it a name,
  category and short description and submit it while signed in with TRS. "My submissions" shows whether it is
  waiting, approved or rejected (with the reason).

### Deutsch

- **Mit oder ohne TRS Client – deine Wahl bei Modpacks.** Wenn du ein Modpack installierst (Modrinth oder
  CurseForge, aus „Entdecken“ oder als Datei) oder Instanzen mit Mods aus einem anderen Launcher importierst, fragt
  der Launcher jetzt einmal: „Mit TRS Client“ oder „Ohne TRS Client“. Empfohlen ist „Mit“ – außer das Pack bringt
  schon Mods mit, die sich damit überschneiden, etwa einen anderen Client (Essential), eine eigene Minimap
  (Xaero's, JourneyMap, VoxelMap, FTB Chunks) oder ein eigenes HUD (MiniHUD). Dann siehst du, welche, und „Ohne“
  ist vorausgewählt. Packs mit eigenem Zoom bekommen nur einen Hinweis. Gibt es für die Minecraft-Version des
  Packs keinen TRS Client, ist die Option mit Begründung ausgegraut. Beim Import mehrerer Instanzen wählst du einmal
  für alle und kannst je Instanz Ausnahmen anhaken. Die Wahl wird in der Instanz gespeichert und lässt sich
  jederzeit in ihren Einstellungen ändern. Neue Instanzen ohne Pack bleiben wie bisher.
- **Neue Einstellung „TRS Client bei Modpacks“** (Einstellungen → Standard-Einstellungen): immer fragen (Standard),
  immer mit oder immer ohne – bei „immer …“ kommt keine Frage mehr.
- **Absturz-Helfer.** Stürzt das Spiel ab – beim Start oder später –, liest der Launcher jetzt selbst Crash-Report und
  Log und erklärt verständlich, was passiert ist: welche Mods beteiligt sind (mit Symbol) und was hilft. Er erkennt
  Mods, die sich in die Quere kommen, fehlende oder falsche Abhängigkeiten, doppelt installierte Mods, Mods für eine
  andere Minecraft- oder Loader-Version, zu wenig Arbeitsspeicher, die falsche Java-Version, Abstürze im Grafiktreiber
  (AMD, Intel, NVIDIA, fehlendes OpenGL), beschädigte Dateien und bekannte Kombinationen wie Sodium mit OptiFine, Iris
  ohne Sodium oder zwei Minimaps.
- **Mit einem Klick beheben.** Je nach Ursache gibt es Knöpfe wie „Mod deaktivieren“ (rückgängig machbar),
  „Abhängigkeit installieren“, „Doppelte entfernen“, „RAM erhöhen“, „Java 21 verwenden“, „TRS Client aktualisieren“,
  „Log teilen“ und „Erneut starten“. Jede Änderung wird vorher bestätigt und landet im Verlauf der Instanz.
- **Frühere Abstürze bleiben abrufbar.** Im Reiter „Verlauf“ lassen sie sich wieder öffnen, im Reiter „Logs“ lässt
  sich jeder ältere Log oder Crash-Report analysieren.
- **Datenschutz inklusive.** Die Analyse läuft nur auf deinem PC; gesendet wird nichts, außer du teilst den Log. In den
  angezeigten Zeilen sind Pfade mit deinem Benutzernamen und dein Spielername ausgeblendet.
- Mit klarer Antwort auf den Absturz von TRS Client 0.9.0 zusammen mit Essential: TRS Client aktualisieren.
- **Wegpunkte mit Freunden teilen.** Im TRS Client schickst du einen Wegpunkt – oder deine aktuelle Position – an
  einen Freund oder eine Gruppe im Sozial-Chat. Er kommt als Karte mit Name, Koordinaten, Dimension und Server an.
  Deine Freunde können ihn als eigenen Wegpunkt **übernehmen** (wenn sie auf demselben Server bzw. in derselben
  Welt sind) oder auf der Karte **anzeigen**. Im Launcher-Chat kopiert ein Knopf die Koordinaten.
- **Anklickbare Koordinaten im Minecraft-Chat.** Schreibt jemand Koordinaten wie „x: 100 y: 64 z: -20“ oder
  „100 64 -20“, unterstreicht der TRS Client sie – ein Klick speichert sie als Wegpunkt. Das passiert nur auf deinem
  PC, es wird nichts gesendet.
- **Screenshots als Link teilen.** In der Screenshot-Galerie des Launchers, in „Clips & Bilder“ und direkt nach einem
  Screenshot im Spiel lädt „Als Link teilen“ das Bild hoch und kopiert einen Link, den du überall posten kannst –
  Discord zeigt eine Vorschau. Links bleiben 30 Tage online und lassen sich unter „Meine geteilten Bilder“ früher
  löschen. Bilder werden ohne versteckte Daten neu gespeichert, und die Seite zeigt nie deinen Namen. Jeder kann ein
  Bild melden.
- **Bessere Tooltips im TRS Client.** Beim Überfahren einer Shulker-Kiste siehst du ihren Inhalt als Raster in der
  Farbe der Kiste, gefüllte Karten zeigen eine kleine Vorschau, Werkzeuge und Rüstung ihre Haltbarkeit als Zahl und
  Prozent, Essen Hunger und Sättigung als Keulen, und viele Verzauberungen passen in wenige Zeilen – Umschalt halten
  zeigt die volle Liste. Alles lässt sich einzeln an- und ausschalten (TRS-Menü → „Bessere Tooltips“). Nur Anzeige.
  Shulker-Inhalt ab Minecraft 1.11; auf 1.7.10 und 1.13.2 gibt es die besseren Tooltips nicht.
- **Server-Profile.** Speichere ein Setup für einen Server – Module an oder aus, ihre Einstellungen und auf Wunsch ein
  HUD-Profil –, und der TRS Client schaltet beim Betreten selbst darauf um (per Adresse oder Muster wie
  „*.hypixel.net“ oder für Einzelspieler) und beim Verlassen zurück auf „Standard“. „Aktuelles Setup für diesen Server
  merken“ unter TRS-Menü → Profile → Server-Profile speichert, was du gerade auf dem Server geändert hast. Die Profile
  bleiben auf diesem PC.
- **Panorama-Screenshots.** Eine Taste (ab Werk unbelegt, in der Steuerung festlegen) oder „Jetzt aufnehmen“ im
  TRS-Menü nimmt ein 360°-Panorama auf: sechs Würfelbilder wie das Panorama des Titelbildschirms (fertig für ein
  Ressourcenpaket) und ein 360°-Bild für jeden Panorama-Betrachter. Gespeichert in screenshots/panorama; eine Meldung
  bietet an, den Ordner zu öffnen, und „Als Link teilen“ auf der Modulseite lädt das 360°-Bild hoch und kopiert den
  Link. Das HUD ist währenddessen ausgeblendet. Ab Minecraft 1.8.9 (nicht auf 1.7.10 und
  1.13.2).
- **Anmeldung auf der Website mit Microsoft.** Die Website meldet nur noch mit dem Microsoft-Konto an, dem Minecraft
  gehört. Die Bestätigung per Code aus dem Launcher entfällt; der Launcher öffnet stattdessen die Website
  (Einstellungen → Datenschutz, Strg+K, Team-Bereich).
- **Team-Rollen mit Rechten.** Der Team-Bereich im Launcher richtet sich jetzt nach den feinen Rechten deiner
  Team-Rolle: Jede Team-Rolle sieht ihn, aber nur die Bereiche und Knöpfe, die sie nutzen darf – Supporter sehen
  zum Beispiel Meldungen ohne Inhalt („Inhalt nur mit Recht sichtbar“) und dürfen nur verwarnen. Strafen von
  jemandem mit höherem Rang lassen sich nicht ändern. Rollen, Bewerbungen und Stellen öffnen sich auf der Website.
- **Meine Bewerbungen.** Einstellungen → Datenschutz und Strg+K zeigen deine Bewerbungen fürs Team mit Status und
  Antwort des Teams; offene kannst du zurückziehen. Ändert sich der Status, bekommst du einen Hinweis – im Spiel auch
  vom TRS Client.
- **Schaltungs-Bibliothek im TRS Client.** Eine neue Seite in der Redstone-Kategorie mit fertigen
  Redstone-Schaltungen: Logik-Gatter, Verstärker-Kette, Fackelturm, drei Takte, Speicher (RS-Latch, T-Flipflops,
  D-Latch), Impuls-Schaltungen, eine 2×2-Kolbentür und eine versteckte Treppe, Farm-Grundlagen (Zuckerrohr,
  Item-Filter, automatischer Ofen, Item-Aufzug) und Anzeigen. Zu jeder gibt es eine Erklärung, Schwierigkeit, Größe,
  Materialliste (mit dem, was du schon im Inventar hast), die nötige Minecraft-Version und ob sie auf Servern
  verlässlich läuft – dazu eine drehbare 3D-Vorschau, auch Schicht für Schicht. Suche, Kategorien und „Meine
  Version“ helfen beim Finden.
- **Schaltung als Vorlage in der Welt einblenden.** Die Vorlage folgt deinem Blick, bis du bestätigst (R dreht,
  Enter bestätigt); danach zeigen Geisterblöcke, was wohin gehört – grün, wenn ein Block stimmt, rot, wenn er falsch
  ist (mit Grund), und grau, solange er fehlt – mit Fortschritt „32/40 Blöcke“, Schicht für Schicht mit ↑/↓ und H zum
  Ausblenden. Die Vorlage bleibt in der Welt bzw. auf dem Server, auch nach dem Neuverbinden. Nur Anzeige wie eine
  Litematica-Vorschau: Nichts wird für dich gebaut und nichts an den Server geschickt. Ab Minecraft 1.8.9 (nicht auf
  1.7.10 und 1.13.2).
- **Die Schaltungen kommen vom TRS-Server** – neue Schaltungen gibt es ohne Mod-Update. Die Bibliothek prüft bei jedem
  Start kurz auf Änderungen und behält eine lokale Kopie für unterwegs ohne Internet; vor dem ersten Download sagt sie,
  dass sie beim nächsten Start mit Internet geladen wird.
- **Eigene Schaltungen einreichen.** Markiere eine Schaltung in deiner Welt (zwei Ecken, höchstens 16×16×16), gib
  Name, Kategorie und eine kurze Beschreibung an und reiche sie mit TRS-Anmeldung ein. „Meine Einreichungen“ zeigt,
  ob sie noch geprüft wird, angenommen oder abgelehnt wurde (mit Grund).

## 0.9.0 – 2026-09-27 – Pack Up | Einpacken & los
<!-- banner: accent=#ff4d5e motif=/news/0.9.0/banner.png -->
<!-- shots:
/news/0.9.0/share-mods.png | Hosting: choose which mods your friends take along | Hosting: auswählen, welche Mods deine Freunde mitnehmen
/news/0.9.0/join-with-mods.png | Joining a world with mods: new instance or a copy, with a clear warning for files from the host | Beitreten mit Mods: neue Instanz oder Kopie, mit klarer Warnung bei Dateien vom Host
/news/0.9.0/resource-pack.png | The host's resource pack comes straight over the hosting connection | Das Resource Pack des Hosts kommt direkt über die Hosting-Verbindung
/news/0.9.0/ping.png | Honest ping display with jitter, history and server TPS | Ehrliche Ping-Anzeige mit Jitter, Verlauf und Server-TPS
/news/0.9.0/ping-test.png | Ping test sorts your server list | Der Ping-Test sortiert deine Serverliste
/news/0.9.0/chat.png | Chat with timestamps, stacking and mentions | Chat mit Zeitstempeln, Stapeln und Erwähnungen
/news/0.9.0/pvp-hud.png | Counter HUD, hitmarker and warnings | Zähler-HUD, Hitmarker und Warnungen
/news/0.9.0/streamer.png | Streamer mode and Auto-GG | Streamer-Modus und Auto-GG
-->

### English

- **TRS Client 0.9.1: crash fix.** With some mod combinations (for example together with Essential) Minecraft crashed
  right at startup with TRS Client 0.9.0. The TRS Client now hooks into world hosting in a way that no longer clashes with other
  mods; hosting works exactly as before. The client updates itself on the next game start.
- **TRS Client: quieter public-link hint.** The red "Public link active" badge no longer sits on top of the game. While
  the public link is on, a small, subtle hint in the pause menu (and in the hosting window) reminds you of it.
- **TRS Client: an honest ping display.** The ping HUD now shows the real round-trip time to the server, the jitter
  (how much it varies) and a small history graph; optionally the server's TPS (an estimate from the server's time
  updates), timeouts and a warning on ping spikes (off by default). From Minecraft 1.20.2 the TRS Client measures the ping
  itself with one ping request every 2 seconds (adjustable, never more than one per second – the same request the F3
  network graph sends, just much less often). Older versions don't allow that without faking packets, so there the
  display shows the value the server reports in the player list and marks it "server". Nothing here makes your
  connection faster – it shows what it really is.
- **TRS Client: ping test in the server list.** A "Ping test" button in the multiplayer menu measures all your servers
  (like Minecraft's own server list ping, at most four at a time, once every 10 seconds) and sorts the list by ping;
  pinned servers stay on top. In 1.8.9–1.12.2 and 1.14.4–26.3 wherever the TRS menu style is on.
- **TRS Client: Network Optimization (Performance, on by default).** Incoming data is processed faster on your PC: the
  decryption of online-mode servers runs about 2 to 4.5 times faster (measured per packet, same result bit for bit), and
  compression no longer creates new arrays for every packet (up to about 87 KB less garbage per chunk packet – the time
  itself stays about the same). On 1.7.10 it also switches on TCP_NODELAY, which Minecraft 1.7.10 leaves off (from 1.8
  Minecraft does it itself). Honestly: this cannot lower your ping – the time packets travel through the internet stays
  the same, and nothing the server receives changes. No packets are held back, bundled or faked.
- **TRS Client: Low Input Latency (Performance, off by default).** Reads your mouse right before the frame and can
  limit how many frames wait in the graphics card queue. The biggest effect we measured is on 1.8.9–1.12.2 with an FPS
  limit: the mouse movement is used about 15 ms fresher at 60 FPS. On modern versions Minecraft already reads the mouse
  late, and in our tests the graphics card queue was empty – there it brings well under a millisecond, and "Maximum"
  costs a few percent FPS. The module page shows the measured values live, so you can check on your PC.
- **Launcher: ping test for servers.** The server page can sort by ping and measures at most four servers at a time;
  the Worlds tab of an instance has a "Ping test" for every server in its server list.
- **World hosting: ping per player.** The host's player list shows each player's ping next to "Direct" or "Via relay",
  and a guest's ping HUD shows whether the connection runs directly or through the relay.
- **World hosting with mods and a resource pack.** When you host a world in the TRS Client you can now share your mods
  and a resource pack with your guests – both are off by default and remembered per world. Pick which mods go along and
  whether each is required or optional; mods that add blocks or items are preselected as required, pure client mods are
  not selected. The TRS Launcher recognizes your mods on Modrinth and CurseForge (without the launcher, Modrinth only).
  Mods that are in no store can be sent directly from you if you turn that on – otherwise your guests only see "get it
  yourself". The resource pack goes straight to your guests (never through TRS) and they get Minecraft's usual question
  whether to use it (Minecraft 1.20.3 and newer).
- **Launcher: joining a world with mods.** Every time you join such a world, the launcher shows its mods – required or
  optional, from Modrinth, CurseForge or directly from the host, size, present or missing – and lets you create a new
  instance, add the mods to a copy of an existing one (your original stays untouched) or join without mods if nothing
  required is missing. Store mods are downloaded from their official source and checked. Mods directly from the host
  come with a clear warning on every join and are only installed after you tick "I trust this host". In the game,
  "Open in launcher" takes you straight to this dialog. Worlds with mods are marked in Social → Worlds and on world
  cards.
- **TRS Client: better chat.** Timestamps now also in 12-hour format, identical messages stack ("(x3)"), the chat
  keeps up to 1000 lines instead of 100 (adjustable), and you copy a line with Ctrl+click, right-click or both.
  **Mentions** highlight lines with your name or your own keywords, optionally with a sound (your own messages don't
  count), and the **Chat Filter** hides messages with words you choose – only on your screen.
- **TRS Client: Auto Reconnect.** When the connection drops or the server restarts, the disconnect screen reconnects
  after a countdown (adjustable, with a cancel button and a limited number of attempts). Never after a ban, a whitelist
  kick, a login from another place or a wrong game version.
- **TRS Client: Queue & Alerts.** Tells you when you are (almost) through a server queue – known queue plugins work out
  of the box, you can add your own patterns. While the game is in the background it also tells you when someone mentions
  you, when you die or get disconnected: a notice, an optional sound and a flashing taskbar (from Minecraft 1.13).
- **TRS Client: scoreboard, tab list, boss bar and titles.** Move and resize the scoreboard in the HUD editor, change its
  background and hide the red numbers; the tab list can show the ping in milliseconds (coloured if you like); the boss
  bar and the big title texts can be moved and resized too (titles on Minecraft before 1.20.5 only resized).
- **TRS Client: Warnings.** Short, subtle notices when armour or a tool is almost broken, when you are hungry or low on
  health and when your inventory is full – with an optional sound and a cooldown. After dying, an arrow and the distance
  show you the way back to your death point.
- **TRS Client: Item Counter.** Arrows, totems, healing and splash potions, golden apples, ender pearls and blocks in
  your inventory at a glance, with icons. It also counts how many totems each opponent popped (from Minecraft 1.11).
- **TRS Client: Hit Feedback.** A hit marker at the crosshair when your hit lands, more critical and sharpness
  particles and an optional hit sound. Display only – your attacks stay exactly the same.
- **TRS Client: Auto-GG reworked.** Still off by default. It now sends exactly one message per round end, 0.5 to
  2 seconds later and at most every 10 seconds, and it knows the end-of-round messages of Hypixel, Minemen and PvP.Land
  (your own patterns still work).
- **TRS Client: Streamer Mode.** Replaces your name – and if you like the names of other players – in chat, tab list,
  name tags and scoreboard with a name you choose, and hides server addresses (server list and server HUD). Toggle it
  with a key of your choice. Everything new is only display or comfort and allowed on servers like Hypixel; the only
  thing the client sends by itself is Auto-GG, which stays off until you switch it on.

### Deutsch

- **TRS Client 0.9.1: Absturz behoben.** Mit manchen Mod-Kombinationen (zum Beispiel zusammen mit Essential) stürzte
  Minecraft mit 0.9.0 direkt beim Start ab. Der TRS Client hängt sich jetzt so ins Welt-Hosting ein, dass es nicht mehr
  mit anderen Mods kollidiert; Hosting funktioniert genau wie vorher. Der Client aktualisiert sich beim nächsten
  Spielstart von selbst.
- **TRS Client: dezenterer Hinweis zum öffentlichen Link.** Das rote Abzeichen „Öffentlicher Link aktiv“ liegt nicht
  mehr über dem Spiel. Solange der öffentliche Link an ist, erinnert ein kleiner, unauffälliger Hinweis im Pausemenü
  (und im Hosting-Fenster) daran.
- **TRS Client: ehrliche Ping-Anzeige.** Das Ping-HUD zeigt jetzt die echte Hin- und Rücklaufzeit zum Server, den
  Jitter (wie stark er schwankt) und einen kleinen Verlauf; auf Wunsch auch die TPS des Servers (geschätzt aus seinen
  Zeit-Meldungen), Zeitüberschreitungen und eine Warnung bei Ping-Spitzen (standardmäßig aus). Ab Minecraft 1.20.2 misst
  der TRS Client selbst – mit einer Ping-Anfrage alle 2 Sekunden (einstellbar, nie öfter als einmal pro Sekunde; dieselbe
  Anfrage, die die F3-Netzwerkgrafik schickt, nur viel seltener). Ältere Versionen erlauben das nicht, ohne Pakete zu
  fälschen – dort zeigt die Anzeige den Wert, den der Server in der Spielerliste meldet, und kennzeichnet ihn mit
  „Server“. Schneller wird deine Verbindung dadurch nicht – du siehst, wie sie wirklich ist.
- **TRS Client: Ping-Test in der Serverliste.** Ein Knopf „Ping-Test“ im Mehrspieler-Menü misst alle deine Server (wie
  Minecrafts eigener Serverlisten-Ping, höchstens vier gleichzeitig, einmal alle 10 Sekunden) und sortiert die Liste
  nach Ping; angeheftete Server bleiben oben. In 1.8.9–1.12.2 und 1.14.4–26.3, überall dort, wo der TRS-Menüstil an ist.
- **TRS Client: Netzwerk-Optimierung (Leistung, standardmäßig an).** Ankommende Daten werden auf deinem PC schneller
  verarbeitet: Die Entschlüsselung bei Servern im Online-Modus läuft etwa 2- bis 4,5-mal so schnell (je Paket gemessen,
  Bit für Bit dasselbe Ergebnis), und die Kompression legt nicht mehr für jedes Paket neue Felder an (bis zu etwa 87 KB
  weniger Speichermüll je Chunk-Paket – die Zeit selbst bleibt etwa gleich). Unter 1.7.10 schaltet sie außerdem
  TCP_NODELAY ein, das Minecraft 1.7.10 auslässt (ab 1.8 macht Minecraft das selbst). Ehrlich gesagt: Deinen Ping senkt
  das nicht – die Laufzeit der Pakete durchs Internet bleibt gleich, und am Server kommt genau dasselbe an. Es werden
  keine Pakete zurückgehalten, gebündelt oder gefälscht.
- **TRS Client: Niedrige Eingabe-Verzögerung (Leistung, standardmäßig aus).** Liest deine Maus direkt vor dem Bild und
  kann begrenzen, wie viele Bilder in der Warteschlange der Grafikkarte warten. Den größten Effekt haben wir unter
  1.8.9–1.12.2 mit FPS-Grenze gemessen: Bei 60 FPS wird die Mausbewegung etwa 15 ms frischer verwendet. In neuen Versionen
  liest Minecraft die Maus schon spät, und in unseren Tests war die Warteschlange der Grafikkarte leer – dort bringt es
  deutlich unter einer Millisekunde, und „Maximal“ kostet ein paar Prozent FPS. Die Modulseite zeigt die Messwerte live,
  so kannst du es auf deinem PC nachprüfen.
- **Launcher: Ping-Test für Server.** Die Server-Seite kann nach Ping sortieren und misst höchstens vier Server
  gleichzeitig; im Welten-Reiter einer Instanz gibt es einen „Ping-Test“ für jeden Server ihrer Serverliste.
- **Welt-Hosting: Ping je Spieler.** Die Spielerliste des Hosts zeigt neben „Direkt“ bzw. „Über Relay“ den Ping jedes
  Spielers, und das Ping-HUD eines Gastes zeigt, ob die Verbindung direkt oder über das Relay läuft.
- **Welt-Hosting mit Mods und Resource Pack.** Wenn du im TRS Client eine Welt hostest, kannst du jetzt deine Mods und
  ein Resource Pack mit deinen Gästen teilen – beides ist ab Werk aus und wird je Welt gemerkt. Du wählst, welche Mods
  mitgehen und ob sie Pflicht oder optional sind; Mods mit neuen Blöcken oder Items sind als Pflicht vorausgewählt, reine
  Client-Mods nicht. Der TRS Launcher erkennt deine Mods bei Modrinth und CurseForge (ohne Launcher nur Modrinth). Mods,
  die es in keinem Store gibt, kannst du auf Wunsch direkt übertragen – sonst sehen deine Gäste nur „selbst besorgen“.
  Das Resource Pack geht direkt an deine Gäste (nie über TRS), und sie bekommen die normale Minecraft-Frage, ob sie es
  verwenden wollen (ab Minecraft 1.20.3).
- **Launcher: Welten mit Mods beitreten.** Bei jedem Beitritt zu so einer Welt zeigt der Launcher ihre Mods – Pflicht
  oder optional, von Modrinth, CurseForge oder direkt vom Host, Größe, vorhanden oder fehlend – und du legst eine neue
  Instanz an, ergänzt eine Kopie einer vorhandenen (das Original bleibt unverändert) oder trittst ohne Mods bei, wenn
  nichts Pflicht fehlt. Store-Mods lädt er aus der offiziellen Quelle und prüft sie. Mods direkt vom Host kommen bei
  jedem Beitritt mit einer deutlichen Warnung und werden erst übernommen, wenn du „Ich vertraue diesem Host“ ankreuzt.
  Im Spiel führt „Im Launcher öffnen“ direkt zu diesem Dialog. Welten mit Mods sind unter Sozial → Welten und auf
  Weltkarten markiert.
- **TRS Client: besserer Chat.** Zeitstempel jetzt auch im 12-Stunden-Format, gleiche Nachrichten werden gestapelt
  („(x3)“), der Chat behält bis zu 1000 statt 100 Zeilen (einstellbar), und eine Zeile kopierst du mit Strg+Klick,
  Rechtsklick oder beidem. **Erwähnungen** heben Zeilen mit deinem Namen oder eigenen Stichwörtern hervor, auf Wunsch
  mit Ton (deine eigenen Nachrichten zählen nicht), und der **Chat-Filter** blendet Nachrichten mit Wörtern deiner Wahl
  aus – nur auf deinem Bildschirm.
- **TRS Client: Auto-Reconnect.** Bricht die Verbindung ab oder startet der Server neu, verbindet der „Verbindung
  getrennt“-Bildschirm nach einem Countdown neu (einstellbar, mit „Abbrechen“ und begrenzten Versuchen). Nie nach einem
  Bann, einem Whitelist-Kick, einer Anmeldung von woanders oder einer falschen Spielversion.
- **TRS Client: Warteschlange & Hinweise.** Sagt dir, wenn du in einer Server-Warteschlange (fast) dran bist – bekannte
  Warteschlangen-Plugins klappen sofort, eigene Muster kannst du ergänzen. Ist das Spiel im Hintergrund, meldet es auch,
  wenn dich jemand erwähnt, du stirbst oder die Verbindung getrennt wird: Hinweis, auf Wunsch Ton und blinkende
  Taskleiste (ab Minecraft 1.13).
- **TRS Client: Scoreboard, Tabliste, Bossleiste und Titel.** Scoreboard im HUD-Editor verschieben und skalieren,
  Hintergrund einstellen und die roten Zahlen ausblenden; die Tabliste zeigt den Ping auf Wunsch in Millisekunden (auch
  farbig); Bossleiste und die großen Titeltexte lassen sich ebenfalls verschieben und skalieren (Titel vor Minecraft
  1.20.5 nur in der Größe).
- **TRS Client: Warnungen.** Kurze, dezente Hinweise, wenn Rüstung oder Werkzeug fast kaputt sind, du Hunger oder wenig
  Leben hast und das Inventar voll ist – auf Wunsch mit Ton und Abklingzeit. Nach dem Tod zeigen ein Pfeil und die
  Entfernung den Weg zurück zum Todespunkt.
- **TRS Client: Zähler.** Pfeile, Totems, Heil- und Wurftränke, Goldäpfel, Enderperlen und Blöcke im Inventar auf einen
  Blick, mit Symbolen. Dazu zählt es, wie viele Totems jeder Gegner verbraucht hat (ab Minecraft 1.11).
- **TRS Client: Treffer-Feedback.** Ein Hitmarker am Fadenkreuz, wenn dein Schlag trifft, mehr Kritisch- und
  Schärfe-Partikel und auf Wunsch ein Treffer-Ton. Nur Anzeige – deine Angriffe bleiben genau gleich.
- **TRS Client: Auto-GG überarbeitet.** Weiterhin ab Werk aus. Es sendet jetzt genau eine Nachricht je Rundenende,
  0,5 bis 2 Sekunden danach und höchstens alle 10 Sekunden, und kennt die Rundenende-Meldungen von Hypixel, Minemen und
  PvP.Land (eigene Muster gehen weiterhin).
- **TRS Client: Streamer-Modus.** Ersetzt deinen Namen – und auf Wunsch die Namen anderer Spieler – in Chat, Tabliste,
  Namensschildern und Scoreboard durch einen Namen deiner Wahl und verbirgt Server-Adressen (Serverliste und
  Server-HUD). Umschalten per Taste deiner Wahl. Alles Neue ist nur Anzeige oder Komfort und auf Servern wie Hypixel
  erlaubt; das Einzige, was der Client selbst sendet, ist Auto-GG – und das bleibt aus, bis du es einschaltest.

## 0.8.0 – 2026-09-26 – Open House | Tag der offenen Tür
<!-- banner: accent=#3ecfcf motif=/news/0.8.0/banner.png -->
<!-- shots:
/news/0.8.0/worlds.png | Social → Worlds: join your friends' worlds, ask to join or enter a code | Sozial → Welten: Welten von Freunden beitreten, anfragen oder per Code
/news/0.8.0/host-world.png | Host your singleplayer world for friends – no port forwarding | Einzelspielerwelt für Freunde hosten – ohne Portfreigabe
/news/0.8.0/host-manage.png | Manage players, rights and join requests while you host | Spieler, Rechte und Beitrittsanfragen beim Hosten verwalten
/news/0.8.0/join-code.png | Join with a code in the TRS Client | Mit Code beitreten im TRS Client
/news/0.8.0/minimap-indoors.png | The minimap looks inside buildings, barriers are ignored | Die Minimap schaut in Gebäude, Barrieren werden ignoriert
/news/0.8.0/shield.png | Shield Position with live preview | Schild-Position mit Live-Vorschau
/news/0.8.0/sanctions.png | See your sanctions and appeal once | Eigene Strafen sehen und einmal Einspruch einlegen
-->

### English

- **TRS Client: the rubber duck.** A secret head cosmetic: a yellow rubber duck that sits on your head in the TRS
  Client – and everyone else with the TRS Client sees it too. It waddles when you walk, bobs when you stand, looks
  around and blinks, lags a little behind fast head turns, flaps its wings when you jump or fall, squashes on landing and
  quacks when you start sneaking or play an emote. You only get it with a code; without one it doesn't show up anywhere.
  Redeem the code in the launcher (Skins → TRS capes → Redeem code) and put the duck on straight away, or later under
  "Head cosmetics". Works in Minecraft 1.8.9–1.12.2 (Forge) and 1.14.4–26.3 (Fabric, Forge, NeoForge); a new switch
  under TRS Online hides TRS head cosmetics if you prefer.
- **TRS Client: Shield Position.** A new module in the PvP category holds your shield further to the side and lower in
  first person, so you see more of the fight. While blocking it switches to its own flatter pose – smoothly, without
  the old jump. Pick a preset ("Side", "Low", "Vanilla") or set position, rotation and size yourself for normal and for
  blocking; a live preview on the settings page shows how much of the screen the shield covers. If you like, the shield
  turns see-through while you block (opacity adjustable). Off by default; works with shields from other mods too, and
  third person stays unchanged. Available from Minecraft 1.10.2 (Forge) and 1.14.4 (Fabric) up to 26.3.
- **Join your friends' worlds from the launcher.** When a friend opens their singleplayer world in the TRS Client, you
  see it live under Social → Worlds with version, game mode and player count. Invited? Press "Join". Not invited? Press
  "Ask to join" (or enter a code like K7Q-M2X) – the launcher says "Request sent" and, as soon as the host lets you in,
  starts the game by itself. It picks the instance with the same Minecraft version and mod loader (you choose if there
  are several) and, if none fits, creates one with the TRS Client in one click. The game then joins the world on its
  own – no address to type, no port forwarding. If the game is already running, it joins right away.
- **World cards and notifications.** A world shared in chat shows up as a card ("Bob's world – 1.21.11 Fabric – Join")
  with its live state, and invites pop up as a notification with "Join" and "Decline". You also get a short note when a
  host lets you in, declines, removes you or closes the world.
- **Your own world at a glance.** While you host a world in the game, Social → Worlds shows its join code, how many
  players are in it and who is waiting – manage it in the game (pause menu → Host world).
- **TRS Client: host your singleplayer world for friends.** In the pause menu, "Host world" opens your world for up to
  10 players – no port forwarding. Pick a name, game mode, cheats, PvP, the maximum number of players and who can see it;
  the world is backed up first. You get a join code to copy, can invite friends (online friends first), accept or decline
  requests (also from a notification) and see every player with their connection ("Direct" or "Via relay"). Give players
  OP, make them spectators or take away building, remove or ban them (also for all your worlds), and stop hosting with one
  click. Friends connect directly when possible and through the TRS relay otherwise.
- **TRS Client: join friends' worlds.** Invites pop up with "Join" (quick-reply key), world cards in chat have a "Join"
  button, Social → Worlds lists your friends' open worlds with "Join" or "Ask to join", and the multiplayer screen has
  "Join with code". A different Minecraft version is shown clearly before you try.
- **TRS Client: public link (optional).** For friends without the TRS Client you can turn on a public link via the e4mc
  service. It is off by default, needs a confirmed warning every single time and shows a red "Public link active" badge
  in the game and the pause menu with a one-click "Deactivate".
- **TRS Client: the maps look inside buildings.** Under a roof or ceiling the minimap now shows the inside instead of
  the roof: floors, rooms, halls and lobbies – only blocks up to just below the roof (at most about 10 blocks above
  your head) count. Outdoors and under trees nothing changes, in caves the cave view stays in charge. The world map
  follows the minimap's level (the button at the top switches back to the surface). New setting "Hide roofs" in the
  Minimap module (on by default); like the cave view it is off on servers that ask for Fair Play.
- **TRS Client: barriers no longer get in the way of the maps.** Barrier blocks, light blocks and structure voids are
  treated like air – a lobby with an invisible barrier ceiling or barrier floors in empty worlds no longer leave dark
  gaps, the map shows what is below.
- **No double notifications while you play.** While a game with the TRS Client is running, the launcher stays quiet
  about messages, friend requests, friends coming online, invites and cape offers – no pop-up, no sound, no Windows
  notification – because the TRS Client already shows them in the game. They still count as unread. As soon as the
  game closes, the launcher notifies you again. Games without the TRS Client, update notes and errors are unchanged.
- **Moderation:** the team now has moderators, clearer sanctions (chat mute, social, upload and world-hosting bans,
  temporary account bans) and you can see your own sanctions and appeal each one once – even while banned. Settings →
  Privacy → "My sanctions" (and a notice at the top while one is active) shows the kind, what it blocks, when it ends
  and why; blocked actions now explain themselves instead of showing an error, and new sanctions or answers to your
  appeal arrive as a notification right away. The same works in the TRS Client under Social.
- **New team area for admins and moderators:** an overview of what needs attention, reports with filters and priority,
  appeals, a player file with the full sanction history (lift, shorten or extend with a reason), new sanctions with
  kind, duration and reason templates plus a confirmation step, capes and cosmetics, open worlds, codes, word filter,
  roles and the audit log – with global search, multi-select for bulk actions and keyboard shortcuts (/, j/k, a/r, ?).

### Deutsch

- **TRS Client: die Quietscheente.** Eine geheime Kopf-Kosmetik: eine gelbe Quietscheente, die im TRS Client auf deinem
  Kopf sitzt – und alle anderen mit TRS Client sehen sie auch. Sie watschelt beim Laufen, wippt im Stand, schaut sich um
  und blinzelt, hängt bei schnellen Kopfdrehungen etwas hinterher, schlägt beim Springen und Fallen mit den Flügeln,
  staucht sich bei der Landung und quakt, wenn du zu schleichen beginnst oder ein Emote spielst. Es gibt sie nur per
  Code; ohne Code taucht sie nirgends auf. Löse den Code im Launcher ein (Skins → TRS-Umhänge → Code einlösen) und setz
  die Ente gleich auf – oder später unter „Kopf-Kosmetik“. Läuft in Minecraft 1.8.9–1.12.2 (Forge) und 1.14.4–26.3
  (Fabric, Forge, NeoForge); ein neuer Schalter unter TRS Online blendet TRS-Kopf-Kosmetik aus, wenn du willst.
- **TRS Client: Schild-Position.** Ein neues Modul in der Kategorie PvP hält dein Schild in der 1. Person weiter
  seitlich und tiefer, damit du vom Kampf mehr siehst. Beim Blocken wechselt es in eine eigene, flachere Haltung – weich,
  ohne den alten Sprung. Wähle eine Vorlage („Seitlich“, „Tief“, „Vanilla“) oder stelle Position, Drehung und Größe für
  normal und fürs Blocken selbst ein; eine Live-Vorschau auf der Einstellungsseite zeigt, wie viel vom Bild das Schild
  verdeckt. Auf Wunsch wird das Schild beim Blocken durchsichtig (Deckkraft einstellbar). Ab Werk aus; klappt auch mit
  Schilden anderer Mods, die 3. Person bleibt unverändert. Verfügbar ab Minecraft 1.10.2 (Forge) bzw. 1.14.4 (Fabric)
  bis 26.3.
- **Den Welten deiner Freunde aus dem Launcher beitreten.** Öffnet ein Freund seine Einzelspielerwelt im TRS Client,
  siehst du sie live unter Sozial → Welten mit Version, Spielmodus und Spielerzahl. Eingeladen? „Beitreten“ drücken.
  Nicht eingeladen? „Anfragen“ (oder einen Code wie K7Q-M2X eingeben) – der Launcher meldet „Anfrage gesendet“ und
  startet das Spiel von selbst, sobald der Host dich hereinlässt. Er nimmt die Instanz mit gleicher Minecraft-Version
  und gleichem Mod-Loader (bei mehreren wählst du) und legt, wenn keine passt, mit einem Klick eine mit TRS Client an.
  Das Spiel tritt der Welt dann selbst bei – ohne Adresse eintippen, ohne Portfreigabe. Läuft das Spiel schon, tritt es
  sofort bei.
- **Weltkarten und Benachrichtigungen.** Eine im Chat geteilte Welt erscheint als Karte („Welt von Bob – 1.21.11
  Fabric – Beitreten“) mit aktuellem Stand, und Einladungen kommen als Benachrichtigung mit „Beitreten“ und „Ablehnen“.
  Außerdem bekommst du einen kurzen Hinweis, wenn der Host dich hereinlässt, ablehnt, entfernt oder die Welt schließt.
- **Deine eigene Welt im Blick.** Während du im Spiel eine Welt hostest, zeigt Sozial → Welten ihren Beitrittscode,
  wie viele Spieler drin sind und wer wartet – verwaltet wird sie im Spiel (Pausemenü → Welt hosten).
- **TRS Client: Einzelspielerwelt für Freunde hosten.** „Welt hosten“ im Pausemenü öffnet deine Welt für bis zu
  10 Spieler – ohne Portfreigabe. Name, Spielmodus, Cheats, PvP, maximale Spielerzahl und Sichtbarkeit wählst du selbst,
  vorher wird die Welt gesichert. Du bekommst einen Beitrittscode zum Kopieren, lädst Freunde ein (Online-Freunde zuerst),
  nimmst Anfragen an oder lehnst sie ab (auch über eine Benachrichtigung) und siehst jeden Spieler mit seiner Verbindung
  („Direkt“ oder „Über Relay“). Gib Spielern OP, mach sie zu Zuschauern oder nimm ihnen das Bauen, entferne oder sperre
  sie (auch für alle deine Welten) und beende das Hosting mit einem Klick. Freunde verbinden sich direkt, wenn es geht,
  sonst über das TRS-Relay.
- **TRS Client: Welten von Freunden beitreten.** Einladungen erscheinen mit „Beitreten“ (Schnellantwort-Taste),
  Weltkarten im Chat haben einen „Beitreten“-Knopf, Sozial → Welten zeigt die offenen Welten deiner Freunde mit
  „Beitreten“ oder „Anfragen“, und im Mehrspieler-Menü gibt es „Mit Code beitreten“. Eine andere Minecraft-Version wird
  vorher klar angezeigt.
- **TRS Client: öffentlicher Link (optional).** Für Freunde ohne TRS Client kannst du über den Dienst e4mc einen
  öffentlichen Link einschalten. Ab Werk ist er aus, braucht jedes Mal eine bestätigte Warnung und zeigt im Spiel und im
  Pausemenü ein rotes Abzeichen „Öffentlicher Link aktiv“ mit „Deaktivieren“ per Klick.
- **TRS Client: Die Karten schauen in Gebäude hinein.** Unter einem Dach oder einer Decke zeigt die Minimap jetzt das
  Innere statt des Dachs: Böden, Räume, Hallen und Lobbys – es zählen nur Blöcke bis knapp unter dem Dach (höchstens
  etwa 10 Blöcke über deinem Kopf). Im Freien und unter Bäumen bleibt alles wie bisher, in Höhlen bleibt die
  Höhlenansicht zuständig. Die Weltkarte folgt der Ebene der Minimap (der Knopf oben schaltet zurück zur Oberfläche).
  Neue Einstellung „Dach ausblenden“ im Minimap-Modul (standardmäßig an); wie die Höhlenansicht ist sie auf Servern
  aus, die Fair Play verlangen.
- **TRS Client: Barrieren stören die Karten nicht mehr.** Barriere-Blöcke, Licht-Blöcke und Strukturleeren zählen wie
  Luft – eine Lobby mit unsichtbarer Barriere-Decke oder Barriere-Böden in leeren Welten hinterlassen keine dunklen
  Lücken mehr, die Karte zeigt, was darunter liegt.

- **Keine doppelten Benachrichtigungen beim Spielen.** Läuft ein Spiel mit TRS Client, schweigt der Launcher zu
  Nachrichten, Freundesanfragen, Freunden, die online kommen, Einladungen und Umhang-Angeboten – kein Hinweis, kein
  Ton, keine Windows-Benachrichtigung –, denn der TRS Client zeigt sie schon im Spiel. Sie zählen weiter als ungelesen.
  Sobald das Spiel zu ist, meldet sich der Launcher wieder. Spiele ohne TRS Client, Update-Hinweise und Fehler bleiben
  wie bisher.
- **Moderation:** Das Team hat jetzt Moderatoren und klarere Strafen (Chat-Stumm, Sozial-, Upload- und
  Welt-Hosting-Sperre, befristeter Konto-Bann). Du siehst deine eigenen Strafen und kannst gegen jede einmal Einspruch
  einlegen – auch wenn dein Konto gesperrt ist. Einstellungen → Datenschutz → „Meine Strafen“ (und ein Hinweis oben,
  solange eine aktiv ist) zeigt Art, was gesperrt ist, wann sie endet und warum; gesperrte Aktionen erklären sich jetzt
  selbst statt einen Fehler zu zeigen, und neue Strafen oder Antworten auf deinen Einspruch kommen sofort als
  Benachrichtigung. Im TRS Client geht das genauso unter Sozial.
- **Neuer Team-Bereich für Admins und Moderatoren:** Übersicht über alles, was Aufmerksamkeit braucht, Meldungen mit
  Filtern und Priorität, Einsprüche, Spieler-Akte mit dem ganzen Strafverlauf (aufheben, verkürzen oder verlängern mit
  Begründung), neue Strafen mit Art, Dauer, Grund-Vorlagen und Bestätigung, Umhänge und Kosmetik, offene Welten, Codes,
  Wortfilter, Rollen und Audit-Log – mit globaler Suche, Mehrfachauswahl für Sammelaktionen und Tastenkürzeln
  (/, j/k, a/r, ?).

## 0.7.0 – 2026-09-26 – The Together Update | Das Zusammen-Update

<!-- banner: accent=#ff9f3d motif=/news/0.7.0/banner.png -->
<!-- shots:
/news/0.7.0/chat.png | Social: chat with friends, groups, pictures and server invites | Sozial: Chat mit Freunden, Gruppen, Bildern und Server-Einladungen
/news/0.7.0/friends.png | Friends, requests and blocked players side by side | Freunde, Anfragen und Blockierte nebeneinander
/news/0.7.0/notifications.png | Notifications for messages, requests and friends coming online | Benachrichtigungen für Nachrichten, Anfragen und Freunde, die online kommen
/news/0.7.0/ingame-chat.png | Chat right inside the game with the TRS Client | Chat direkt im Spiel mit dem TRS Client
/news/0.7.0/ingame-quick-reply.png | Quick reply without leaving the game | Schnellantwort, ohne das Spiel zu verlassen
/news/0.7.0/clip-trim.png | New clip player: play, trim and share clips | Neuer Clip-Player: Clips abspielen, zuschneiden und teilen
/news/0.7.0/news-dialog.png | Update news with banner and screenshots | Update-News mit Banner und Screenshots
-->

### English

- **TRS Client: chat with your friends in the game.** The Friends screen is now "Social" with the tabs Chat and
  Friends (title screen, pause menu, TRS menu, or its own key). Write to friends and groups, reply to a message, copy,
  edit or delete your own, mark as unread, react, and see when someone is typing or has read your message – all live,
  without reloading. Send up to 10 screenshots of this instance at once ("Select pictures"), open pictures large, and
  invite friends to the server you're on: they see an invite card with the server icon and player count and join with
  one click (you're asked before leaving your current world). Create and manage groups, and the Friends tab shows your
  friends, requests (including cape offers) and blocked players side by side.
- **TRS Client: notifications in the game.** New messages, server invites, friend requests, cape offers and friends
  coming online appear as small notifications in the top right for 5 seconds – above every menu, and they slide down
  out of the way of Minecraft's own pop-ups (recipes, tips, advancements). Press the quick-reply key (Y) to answer
  right away or join an invite without leaving the game. In the TRS menu under Social you choose the corner, duration
  (3–10 s), sound, "Do not disturb" (also automatically in fullscreen) and which kinds you want.
- **TRS Client: report and stay safe.** Report messages, pictures, players and groups with a reason and an optional
  note; you get feedback when the team has looked at it. Text from others is always shown without colour or format
  codes, links only open after you confirm them, and a chat mute from the team is shown clearly.
- **TRS Client wardrobe: your own skin is back.** Under Skins, the library now starts with a "Current" card showing the
  skin your account is wearing right now (slim or classic arms; the default skin for offline accounts). Only this card
  has the "worn" lamp; if the same skin is also in your library, that entry shows a small "= current" tag instead of a
  second lamp. From the card you can save the skin to your synced library (named after your account, without creating
  a duplicate) or open it in the editor as a template. It updates right after you apply another skin or switch
  accounts in the game, and the highlighted card now only means "shown in the preview", not "worn".
- **Clip gallery.** All your clips as tiles with a preview picture, length and date – search by name, filter by
  instance and sort by date, length, size or name. Hover over a tile for a moving preview. Every instance also has
  its own "Clips" tab.
- **New clip player.** Play/pause, a timeline that shows a small preview picture while you hover over it, volume and
  mute, playback speed and full screen – with keyboard shortcuts (Space or K, ←/→ 5 seconds, J/L 10 seconds, F, M,
  ,/. single frames, Alt+←/→ for the previous/next clip). If a clip can't be played in the launcher, it offers to open
  it in your system's video player.
- **Trim clips.** Set start and end on the timeline (or with I and O), preview the selection and save it as a new
  clip – the original always stays. When the start is on a keyframe, the clip is copied without re-encoding in an
  instant; otherwise the launcher explains why it has to re-encode (exact, takes a moment) and lets you cut fast at
  the keyframe instead.
- **Share clips.** "Save as …", copy the clip as a file to paste it into Discord or a folder, show it in the folder,
  rename and delete (to the recycle bin).
- **TRS Client: clip preview in game.** In "Clips & Images" a clip now opens a small animated preview with play/pause
  and a timeline, plus "Open in launcher": the launcher comes to the front and plays the clip. Works when the game was
  started from the TRS Launcher.
- **Update news with screenshots.** Clicking an update – on the start page, in the news or in "What's new" after an
  update – opens the post right in the launcher: the update banner, a gallery of screenshots of the new features
  (click to enlarge, browse with the arrows or the arrow keys), the release notes and "View on website" for the blog
  post. Older updates got their screenshots too.
- **New: Social – chat with your friends.** "Friends" is now "Social" with two tabs. **Chat:** direct messages and
  groups (create a group from your friends, rename it, add or remove members, hand over ownership, leave). Messages
  show up instantly with day separators, replies, "(edited)", reactions (👍 ❤️ 😂 😮 😢 😡 🎉 🔥 👀 ✅), "is typing …"
  and read receipts. Send up to 10 pictures per message – screenshots of all your instances, favourites or files from
  your PC (also by drag & drop or Ctrl+V) – and invite friends to a server: the invite card shows the icon and player
  count, and "Join" starts a matching instance and connects right away. Right-click a message to reply, copy, edit or
  delete your own, mark it as unread or report it. Mute conversations for an hour, a day or until you unmute.
  **Friends:** friend list with online status and "last online", requests (including cape offers) and blocked players
  side by side.
- **Everything updates live.** Friends, requests, online status, cape offers, messages and report feedback now arrive
  within a moment – no more reloading or waiting. If the connection drops, the launcher reconnects on its own and
  catches up on everything it missed.
- **Notifications.** Small pop-ups for new messages (with quick reply), server invites (Join), friend requests and cape
  offers (Accept/Decline) and friends coming online. Choose the corner, how long they stay (3–10 s), sound on/off, Do
  not disturb (also automatically while a game runs in fullscreen) and which kinds you want. When the launcher is in
  the background, Windows shows them too.
- **Report and stay safe.** Report messages, pictures, players or groups with a reason; "My reports" shows what
  happened. New privacy switches for read receipts and the typing indicator. Links in messages only open after you
  confirm them.
- **For admins:** a new "Reports" tab with the context of each report, reported pictures, notes, history, word filter
  and actions (delete message, warn, mute, ban, dismiss).

### Deutsch

- **TRS Client: Chatten mit Freunden im Spiel.** Aus dem Freunde-Bildschirm wird „Sozial“ mit den Reitern Chat und
  Freunde (Titelbildschirm, Pausenmenü, TRS-Menü oder eigene Taste). Schreib Freunden und Gruppen, antworte auf eine
  Nachricht, kopiere, bearbeite oder lösche eigene, markiere als ungelesen, reagiere und sieh, wer gerade tippt oder
  deine Nachricht gelesen hat – alles live, ohne neu zu laden. Schick bis zu 10 Bildschirmfotos dieser Instanz auf
  einmal („Bilder auswählen“), öffne Bilder groß und lade Freunde auf deinen Server ein: Sie sehen eine Einladungskarte
  mit Server-Symbol und Spielerzahl und treten mit einem Klick bei (vorher kommt eine Rückfrage, wenn du gerade in einer
  Welt bist). Erstelle und verwalte Gruppen; der Reiter Freunde zeigt Freunde, Anfragen (auch Umhang-Angebote) und
  Blockierte nebeneinander.
- **TRS Client: Benachrichtigungen im Spiel.** Neue Nachrichten, Server-Einladungen, Freundschaftsanfragen,
  Umhang-Angebote und Freunde, die online kommen, erscheinen 5 Sekunden lang oben rechts – über jedem Menü, und sie
  rutschen unter Minecrafts eigene Hinweise (Rezepte, Tipps, Fortschritte), statt sie zu verdecken. Mit der Schnellantwort-Taste
  (Y) antwortest du sofort oder trittst einer Einladung bei, ohne das Spiel zu verlassen. Im TRS-Menü unter Sozial
  stellst du Ecke, Dauer (3–10 s), Ton, „Nicht stören“ (auch automatisch im Vollbild) und die Arten ein.
- **TRS Client: Melden und Sicherheit.** Melde Nachrichten, Bilder, Spieler und Gruppen mit Grund und optionalem Text;
  du bekommst eine Rückmeldung, wenn das Team es geprüft hat. Text von anderen wird immer ohne Farb- und
  Formatierungscodes gezeigt, Links öffnen sich erst nach deiner Bestätigung, und eine Chat-Stummschaltung durch das
  Team wird deutlich angezeigt.
- **TRS-Client-Garderobe: dein eigener Skin ist wieder da.** Unter „Skins“ beginnt die Bibliothek jetzt mit der Karte
  „Aktuell“ – dem Skin, den dein Konto gerade trägt (Slim- oder Classic-Arme; bei Offline-Konten der Standard-Skin).
  Nur diese Karte hat die Lampe „getragen“; liegt derselbe Skin auch in deiner Bibliothek, zeigt dieser Eintrag statt
  einer zweiten Lampe das kleine Schild „= aktuell“. Über die Karte speicherst du den Skin in deiner synchronisierten
  Bibliothek (mit deinem Kontonamen, ohne Doppelung) oder öffnest ihn als Vorlage im Editor. Sie aktualisiert sich
  sofort, wenn du einen anderen Skin anwendest oder im Spiel das Konto wechselst, und die hervorgehobene Karte heißt
  jetzt nur noch „in der Vorschau“, nicht „getragen“.
- **Clip-Galerie.** Alle Clips als Kacheln mit Vorschaubild, Länge und Datum – nach Namen suchen, nach Instanz filtern
  und nach Datum, Länge, Größe oder Name sortieren. Beim Überfahren einer Kachel läuft eine kleine Vorschau. Jede
  Instanz hat außerdem einen eigenen Reiter „Clips“.
- **Neuer Clip-Player.** Abspielen/Pause, eine Zeitleiste, die beim Überfahren ein kleines Vorschaubild zeigt,
  Lautstärke und Stummschalten, Wiedergabetempo und Vollbild – mit Tastenkürzeln (Leertaste oder K, ←/→ 5 Sekunden, J/L
  10 Sekunden, F, M, ,/. Einzelbilder, Alt+←/→ für den vorherigen/nächsten Clip). Lässt sich ein Clip im Launcher nicht
  abspielen, bietet er an, ihn im Videoplayer des Systems zu öffnen.
- **Clips zuschneiden.** Start und Ende auf der Zeitleiste setzen (oder mit I und O), den Ausschnitt ansehen und als
  neuen Clip speichern – das Original bleibt immer erhalten. Liegt der Start auf einem Keyframe, wird der Clip ohne
  Neukodierung sofort kopiert; sonst erklärt der Launcher, warum er neu kodieren muss (genau, dauert einen Moment), und
  bietet an, stattdessen schnell am Keyframe zu schneiden.
- **Clips teilen.** „Speichern unter …“, den Clip als Datei kopieren und in Discord oder einen Ordner einfügen, im Ordner
  zeigen, umbenennen und löschen (in den Papierkorb).
- **TRS Client: Clip-Vorschau im Spiel.** In „Clips & Bilder“ öffnet ein Clip jetzt eine kleine animierte Vorschau mit
  Abspielen/Pause und Zeitleiste, dazu „Im Launcher öffnen“: Der Launcher kommt nach vorn und spielt den Clip ab.
  Klappt, wenn das Spiel über den TRS Launcher gestartet wurde.
- **Update-News mit Screenshots.** Ein Klick auf ein Update – auf der Startseite, in den Neuigkeiten oder in „Was ist
  neu“ nach einem Update – öffnet den Beitrag direkt im Launcher: das Update-Banner, eine Galerie mit Screenshots der
  Neuerungen (zum Vergrößern anklicken, mit den Pfeilen oder Pfeiltasten blättern), die Versionshinweise und „Auf
  Website ansehen“ für den Blog-Beitrag. Ältere Updates haben ihre Screenshots ebenfalls bekommen.
- **Neu: Sozial – chatte mit deinen Freunden.** Aus „Freunde“ wird „Sozial“ mit zwei Reitern. **Chat:**
  Direktnachrichten und Gruppen (Gruppe aus deinen Freunden erstellen, umbenennen, Mitglieder hinzufügen oder entfernen,
  Besitz übergeben, verlassen). Nachrichten erscheinen sofort – mit Tagestrennern, Antworten, „(bearbeitet)“,
  Reaktionen (👍 ❤️ 😂 😮 😢 😡 🎉 🔥 👀 ✅), „schreibt …“ und Lesebestätigungen. Bis zu 10 Bilder pro Nachricht –
  Screenshots aller Instanzen, Favoriten oder Dateien vom PC (auch per Drag & Drop oder Strg+V) – und Einladungen auf
  einen Server: Die Karte zeigt Icon und Spielerzahl, „Beitreten“ startet eine passende Instanz und verbindet direkt.
  Rechtsklick auf eine Nachricht: antworten, kopieren, eigene bearbeiten oder löschen, als ungelesen markieren, melden.
  Unterhaltungen lassen sich für eine Stunde, einen Tag oder bis auf Weiteres stummschalten. **Freunde:** Freundesliste
  mit Online-Status und „zuletzt online“, Anfragen (auch Umhang-Angebote) und blockierte Spieler nebeneinander.
- **Alles aktualisiert sich live.** Freunde, Anfragen, Online-Status, Umhang-Angebote, Nachrichten und Rückmeldungen zu
  Meldungen kommen jetzt sofort an – kein Neuladen, kein Warten mehr. Bricht die Verbindung ab, verbindet sich der
  Launcher selbst neu und holt alles Verpasste nach.
- **Benachrichtigungen.** Kleine Hinweise bei neuen Nachrichten (mit Schnellantwort), Server-Einladungen (Beitreten),
  Freundschaftsanfragen und Umhang-Angeboten (Annehmen/Ablehnen) und wenn Freunde online kommen. Einstellbar: Ecke,
  Anzeigedauer (3–10 s), Ton an/aus, Nicht stören (auch automatisch, solange ein Spiel im Vollbild läuft) und welche
  Arten du willst. Ist der Launcher im Hintergrund, zeigt Windows sie zusätzlich an.
- **Melden und sicher bleiben.** Nachrichten, Bilder, Spieler oder Gruppen mit Grund melden; „Meine Meldungen“ zeigt,
  was daraus wurde. Neue Datenschutz-Schalter für Lesebestätigungen und „schreibt …“. Links in Nachrichten öffnen sich
  erst nach einer Bestätigung.
- **Für Admins:** neuer Reiter „Meldungen“ mit Kontext jeder Meldung, gemeldeten Bildern, Notizen, Verlauf, Wortfilter
  und Entscheidungen (Nachricht löschen, verwarnen, stummschalten, sperren, abweisen).
- **TRS Client: the resource loading screen stays in the TRS style from start to finish.** Fading in, loading and
  fading out over the next screen no longer let the red Mojang screen, its logo or the white loading bar shine
  through – at game start, after F3+T or changing resource packs, and when a server sends its resource pack while you
  join. On the "Connecting to the server" screen the TRS logo, the status and the lamp row now always sit above the
  "Cancel" button instead of being covered by it, at every window size and GUI scale.

### Deutsch

- **TRS Client: Der Ladebildschirm für Ressourcen bleibt von Anfang bis Ende im TRS-Stil.** Beim Einblenden, Laden und
  Ausblenden über dem nächsten Bildschirm scheinen der rote Mojang-Bildschirm, sein Logo und der weiße Ladebalken nicht
  mehr durch – beim Spielstart, nach F3+T oder dem Wechsel von Ressourcenpaketen und wenn ein Server beim Beitreten
  sein Ressourcenpaket schickt. Auf dem Bildschirm „Verbinde mit dem Server“ stehen TRS-Logo, Status und Lampenreihe
  jetzt immer über dem Knopf „Abbrechen“, statt von ihm verdeckt zu werden – bei jeder Fenstergröße und GUI-Skalierung.

## 0.6.4 – 2026-09-26 – The Workshop Update | Das Werkstatt-Update
<!-- banner: accent=#d99a5b motif=/news/0.6.4/banner.png -->

### English

- **New instance page with tabs.** Content, Files, Worlds, Screenshots, History, Logs and Share now sit in one tab bar
  below the instance header (icon, name, version, play time, last played, Play/Stop, settings). The page remembers the
  last tab for every instance, and the tabs work with the arrow keys.
- **Much better logs.** Search with highlighted matches, filters for errors, warnings and info with counts, coloured
  lines and collapsible stack traces. Besides the live log you can open older logs (also packed `.log.gz` files), crash
  reports and the launcher's own output. The view follows new lines while you are at the bottom and offers "Jump to
  bottom" when you scroll up; it stays smooth even with more than 100,000 lines. Clear the view, copy the visible
  lines, switch to full screen, or share: after a confirmation the log is uploaded to mclo.gs without login tokens and
  user names, and you get the link with a QR code, a copy button and "Open in browser".
- **Files tab.** Browse the instance folder with breadcrumbs, icons for mods, configs, worlds, resource packs,
  shaders, screenshots and logs, size and dates, sorting and a filter. Create folders and files, rename, move to the
  trash, show in the file manager, select several entries, and upload with the file picker or by dragging files and
  folders into the window. Everything stays inside the instance folder.
- **Worlds tab.** Every world shows its real name, game mode, hardcore/cheats, the version it was last played in, size
  and last played date. Open the folder, back a world up as a ZIP (into the "backups" folder) or move it to the trash.
  Below you find the instance's servers: add, edit and remove them, and "Join" starts the game and connects straight
  away. Servers from the launcher's server list are marked.
- **Share tab.** Export the instance as a modpack, share the log, back up a world or pick single files – all in one
  place.

### Deutsch

- **Neue Instanzseite mit Tabs.** Inhalte, Dateien, Welten, Screenshots, Verlauf, Logs und Teilen stehen jetzt in einer
  Tab-Leiste unter dem Instanz-Kopf (Icon, Name, Version, Spielzeit, zuletzt gespielt, Spielen/Stoppen, Einstellungen).
  Die Seite merkt sich den zuletzt offenen Tab je Instanz, und die Tabs lassen sich mit den Pfeiltasten bedienen.
- **Viel bessere Logs.** Suche mit hervorgehobenen Treffern, Filter für Fehler, Warnungen und Info mit Anzahl, farbige
  Zeilen und aufklappbare Stacktraces. Neben dem Live-Log lassen sich ältere Logs (auch gepackte `.log.gz`),
  Absturzberichte und die Ausgabe des Launchers öffnen. Die Ansicht läuft mit, solange du unten bist, und bietet „Nach
  unten“, wenn du hochscrollst; auch mit mehr als 100 000 Zeilen bleibt sie flüssig. Ansicht leeren, sichtbare Zeilen
  kopieren, Vollbild oder teilen: Nach einer Bestätigung landet der Log ohne Anmelde-Tokens und Benutzernamen auf
  mclo.gs, und du bekommst den Link mit QR-Code, Kopieren-Knopf und „Im Browser öffnen“.
- **Tab „Dateien“.** Durchsuche den Instanzordner mit Brotkrumen-Pfad, Symbolen für Mods, Configs, Welten,
  Ressourcenpakete, Shader, Screenshots und Logs, Größe und Datum, Sortierung und Filter. Ordner und Dateien anlegen,
  umbenennen, in den Papierkorb legen, im Dateimanager zeigen, mehrere Einträge auswählen und per Dateiauswahl oder
  Drag & Drop von Dateien und Ordnern hochladen. Alles bleibt im Instanzordner.
- **Tab „Welten“.** Jede Welt zeigt ihren echten Namen, Spielmodus, Hardcore/Cheats, die zuletzt gespielte Version,
  Größe und das Datum. Ordner öffnen, Welt als ZIP sichern (in den Ordner „backups“) oder in den Papierkorb legen.
  Darunter stehen die Server der Instanz: hinzufügen, bearbeiten, entfernen – „Beitreten“ startet das Spiel und
  verbindet sofort. Server aus der Serverliste des Launchers sind markiert.
- **Tab „Teilen“.** Instanz als Modpack exportieren, Log teilen, Welt sichern oder einzelne Dateien heraussuchen – alles
  an einem Ort.

## 0.6.3 – 2026-09-26 – The Safety Net Update | Das Sicherheitsnetz-Update
<!-- banner: accent=#e8d44d motif=/news/0.6.3/banner.png -->

### English

- **No more "Incompatible mods found!" because of a missing mod.** Some mods need another mod to run (More Culling
  from the Max FPS package needs Cloth Config, for example). If a download was cancelled or failed on the first start,
  the mod could end up without its partner and the game crashed on every start. The launcher now always downloads the
  needed mods first, retries the performance package on the next start if something was missing, and checks before
  every start whether a mod lacks a mod it needs – if so, it installs or switches it back on and tells you. Updating
  or switching a mod version and installing from Discover also bring new required mods along now.
- **One click to install what's missing.** If the game still stops because a mod is missing, the crash message names
  it and offers "Install …".
- The first start of a new instance shows a "Mods" step while the performance mods are downloaded, instead of looking
  stuck.

### Deutsch

- **Kein „Incompatible mods found!“ mehr wegen einer fehlenden Mod.** Manche Mods brauchen eine andere Mod (More
  Culling aus dem Max-FPS-Paket etwa Cloth Config). Wurde beim ersten Start ein Download abgebrochen oder schlug fehl,
  konnte die Mod ohne ihren Partner dastehen – und das Spiel stürzte bei jedem Start ab. Der Launcher lädt die
  benötigten Mods jetzt immer zuerst, ergänzt das Leistungspaket beim nächsten Start, wenn etwas fehlte, und prüft vor
  jedem Start, ob einer Mod eine benötigte Mod fehlt – dann installiert er sie oder schaltet sie wieder ein und sagt es
  dir. Auch Mod-Updates, Versionswechsel und Installationen aus „Entdecken“ bringen neue Pflicht-Mods jetzt mit.
- **Fehlendes mit einem Klick installieren.** Bricht das Spiel trotzdem wegen einer fehlenden Mod ab, nennt die
  Absturzmeldung sie und bietet „… installieren“ an.
- Der erste Start einer neuen Instanz zeigt den Schritt „Mods“, während die Leistungs-Mods geladen werden – statt
  hängenzubleiben.

## 0.6.2 – 2026-09-26 – The Sharing Update | Das Teilen-Update
<!-- banner: accent=#ff5fa2 motif=/news/0.6.2/banner.png -->

### English

- **Share your own capes with friends.** Once the team has approved a cape you uploaded, "Share with a friend" (skins
  page in the launcher, or Wardrobe → Capes in the game) offers it to a friend. Your friend sees the offer with a
  preview in the launcher (Friends → Requests, and above your capes) and in the game (wardrobe and friends screen) and
  can accept or decline it. Accepted capes show up in their collection, can be worn like any unlocked cape and are
  visible to other players. Friends may pass a shared cape on to their own friends – up to 20 players per cape. You see
  who has your cape and can take it back at any time; everyone they passed it on to loses it too. Friends can give a
  shared cape back. Removing or blocking a friend cancels open offers between you. New offers show up with a note and a
  "NEW" mark.

### Deutsch

- **Eigene Umhänge mit Freunden teilen.** Sobald das Team einen hochgeladenen Umhang freigegeben hat, bietet „Mit
  Freund teilen“ (Skins-Seite im Launcher oder Garderobe → Umhänge im Spiel) ihn einem Freund an. Dein Freund sieht das
  Angebot mit Vorschau im Launcher (Freunde → Anfragen und über den Umhängen) und im Spiel (Garderobe und Freunde) und
  kann es annehmen oder ablehnen. Angenommene Umhänge stehen danach in seiner Sammlung, lassen sich wie freigeschaltete
  tragen und sind für andere sichtbar. Freunde dürfen einen geteilten Umhang an eigene Freunde weitergeben – höchstens
  20 Spieler je Umhang. Du siehst, wer deinen Umhang hat, und kannst ihn jederzeit zurücknehmen; dann verlieren ihn auch
  alle, an die er weitergegeben wurde. Freunde können einen geteilten Umhang zurückgeben. Entfernst oder blockierst du
  einen Freund, fallen offene Angebote zwischen euch weg. Neue Angebote erscheinen mit Hinweis und „NEU“-Markierung.

## 0.6.1 – 2026-09-26 – The Polish Update | Das Feinschliff-Update
<!-- banner: accent=#ff7a3d motif=/news/0.6.1/banner.png -->

### English

- **The redstone background moves again on every PC.** On some PCs it stood still – Windows reports "reduce motion"
  as soon as its animation effects are off (performance options, remote desktop, tuning tools). New setting under
  Settings → Appearance → "Animations": "Always on" (default), "Follow Windows/system" or "Reduced". It applies to all
  animations in the launcher. The background also picks up again reliably after the window was minimised or
  restored, and a hiccup in the circuit no longer freezes it.
- **Clips always tell you what's going on.** Pressing F9 or F10 in the game now always shows a clear message: clips
  are off, the game wasn't started from the TRS Launcher, the recorder is still downloading (with progress), no video
  encoder works, or the clip was saved. The buttons in "Clips & Images" answer the same way.
- **Turn clips on right from the game.** If clips are off, press F9 (or F10) a second time – or "Turn on now" in
  "Clips & Images" – and the launcher switches clips on and starts recording straight away. The message says what
  gets recorded (game window and system sound; the microphone only if you turned it on in the launcher). The switch in
  the launcher settings follows along. With older launchers the game points you to Settings → Clips instead.
- **Fewer download retries.** If the recorder (FFmpeg) can't be downloaded, the launcher waits a minute before trying
  again instead of retrying every second.
- **A much better minimap.** The TRS Client minimap now glides: movement, turning and zooming are smooth instead of
  jumping from block to block, and the map is sharp at every zoom level (one pixel per block, drawn crisp). It shows
  real block colours with biome-tinted grass and leaves, soft relief shading, clear water that gets darker with depth,
  round or square, in a redstone frame with compass letters. Below it: coordinates, biome and in-game time if you
  like. Underground and in the Nether it switches to a cave view on its own. Players show their faces (TRS friends get
  a golden ring), and you can show hostile mobs and animals as dots. Waypoints and your last death point appear on the
  edge when they are far away.
- **New: fullscreen world map (M).** Everything you have explored stays on the map – saved per world, server and
  dimension on your PC (size-limited). Drag to move, scroll to zoom around the mouse, see the coordinates under the
  cursor and jump back to yourself with one click. Right-click to create, edit, hide or delete waypoints right on the
  map. If another map mod already uses M, the TRS key starts unbound instead of clashing.
- **Fair Play with one switch.** One switch turns off everything a map could use to cheat: no cave view, and players
  and mobs only show up when you could actually see them. Servers that ask map mods for fair play (the common Xaero
  codes) are respected automatically.
- **Everything adjustable.** Shape, size, zoom, opacity, rotation, every marker and every line below the map can be set
  in the TRS menu; position and size in the HUD editor.

### Deutsch

- **Der Redstone-Hintergrund bewegt sich wieder auf jedem PC.** Auf manchen PCs stand er still – Windows meldet
  „Bewegung reduzieren“, sobald die Animationseffekte aus sind (Leistungsoptionen, Remotedesktop, Tuning-Tools). Neue
  Einstellung unter Einstellungen → Aussehen → „Animationen“: „Immer an“ (Standard), „Wie Windows/System“ oder
  „Reduziert“. Sie gilt für alle Animationen im Launcher. Außerdem läuft der Hintergrund nach dem Minimieren oder
  Wiederherstellen des Fensters zuverlässig weiter, und ein Aussetzer in der Schaltung lässt ihn nicht mehr einfrieren.
- **Clips sagen dir immer, was los ist.** F9 oder F10 im Spiel zeigt jetzt immer eine klare Meldung: Clips sind aus,
  das Spiel wurde nicht über den TRS Launcher gestartet, die Aufnahme-Komponente lädt noch (mit Fortschritt), kein
  Video-Encoder läuft oder der Clip ist gespeichert. Die Knöpfe unter „Clips & Bilder“ antworten genauso.
- **Clips direkt im Spiel einschalten.** Sind Clips aus, drück F9 (oder F10) ein zweites Mal – oder „Jetzt
  einschalten“ unter „Clips & Bilder“ – und der Launcher schaltet Clips ein und nimmt sofort auf. Die Meldung sagt, was
  aufgenommen wird (Spielfenster und Systemton; das Mikrofon nur, wenn du es im Launcher eingeschaltet hast). Der
  Schalter in den Launcher-Einstellungen zieht mit. Mit älteren Launchern verweist das Spiel stattdessen auf
  Einstellungen → Clips.
- **Weniger Download-Versuche.** Lässt sich die Aufnahme-Komponente (FFmpeg) nicht laden, wartet der Launcher eine
  Minute, statt es jede Sekunde neu zu versuchen.
- **Eine viel bessere Minimap.** Die Minimap des TRS Client gleitet jetzt: Bewegen, Drehen und Zoomen laufen flüssig
  statt von Block zu Block zu springen, und die Karte ist in jeder Zoomstufe scharf (ein Pixel je Block, klar
  gezeichnet). Sie zeigt echte Blockfarben mit Gras und Laub in Biomfarbe, sanfte Relief-Schattierung, klares Wasser,
  das mit der Tiefe dunkler wird, rund oder eckig, im Redstone-Rahmen mit Himmelsrichtungen. Darunter auf Wunsch
  Koordinaten, Biom und Spielzeit. Unter Tage und im Nether wechselt sie von selbst in die Höhlenansicht. Spieler
  erscheinen mit Gesicht (TRS-Freunde mit goldenem Ring), feindliche Kreaturen und Tiere auf Wunsch als Punkte.
  Wegpunkte und dein letzter Todespunkt stehen am Rand, wenn sie weit weg sind.
- **Neu: Weltkarte im Vollbild (M).** Alles, was du erkundet hast, bleibt auf der Karte – gespeichert je Welt, Server
  und Dimension auf deinem PC (in der Größe begrenzt). Ziehen zum Verschieben, Mausrad zum Zoomen um den Mauszeiger,
  Koordinaten unter dem Zeiger und mit einem Klick zurück zu dir. Per Rechtsklick legst du Wegpunkte direkt auf der
  Karte an, bearbeitest, versteckst oder löschst sie. Nutzt eine andere Karten-Mod schon M, startet die TRS-Taste
  unbelegt statt doppelt belegt.
- **Fair Play mit einem Schalter.** Ein Schalter schaltet alles ab, womit eine Karte schummeln könnte: keine
  Höhlenansicht, Spieler und Kreaturen nur, wenn du sie wirklich sehen könntest. Server, die von Karten-Mods Fair Play
  verlangen (die verbreiteten Xaero-Codes), werden automatisch beachtet.
- **Alles einstellbar.** Form, Größe, Zoom, Deckkraft, Drehung, jede Markierung und jede Zeile unter der Karte stellst
  du im TRS-Menü ein; Lage und Größe im HUD-Editor.

## 0.6.0 – 2026-09-25 – The Sync Update | Das Sync-Update
<!-- banner: accent=#4be38a motif=/news/0.6.0/banner.png -->

### English

- **Wardrobe with a full skin editor in the game.** The TRS Client now has a wardrobe (button under your figure on the title screen, TRS menu or
  your own key): your skins with favourites and a library, outfits (skin + cape) to flip through, your Minecraft and
  TRS capes, and your emote wheel – all with a big 3D preview. "Add skin" takes a file, a link or a player name. Apply
  a skin and it becomes your Minecraft skin right away. The editor lets you paint straight onto the 3D figure or the
  flat skin: brush, eraser, eyedropper, fill, left/right mirror, undo/redo, colour palette with recent colours and hex
  input, inner and outer layer, classic or slim arms and blank templates. With the TRS services on, your skins are the
  same "My skins" as in the launcher, and favourites, outfits and emote wheel follow you to every PC.
- **Import from many more launchers.** "Import from another launcher" now also finds Lunar Client, Badlion, OneClient,
  ATLauncher, GDLauncher (old and new) and TLauncher, next to the official launcher, CurseForge, the Modrinth App, Prism
  and MultiMC. It shows which launchers are on your PC and, per instance, exactly what comes along: worlds, mods,
  resource and shader packs, settings and your server list. The version and mod loader are detected for you. Lunar
  and Badlion bring your own worlds, packs, settings and mods – their built-in client mods aren't free and stay behind,
  TRS Client takes their place. Feather is recognised but keeps no readable list, so pick its folder by hand.
- **Imported mods stay updatable.** Content from CurseForge, ATLauncher and GDLauncher keeps its origin, so you can
  update it right away. Files missing from a CurseForge instance are downloaded for you.
- **Skins from ATLauncher.** "Add skin" → "From other launchers" now also brings over the skins of your ATLauncher
  accounts.
- **Safer imports.** Sign-in data of other launchers is never read or copied, other launchers' files are only read,
  and links that point outside the launcher's own folders are skipped.
- **New TRS Client title screen: you on a redstone turntable.** Your own skin (and your TRS or Mojang cape) now
  stands on a slowly turning redstone turntable on the left of the title screen – drag it to spin it, and your head
  follows the mouse. Below it are your name and a "Wardrobe" button. A new bar on the right leads to Accounts,
  Friends, Clips & Images and the TRS settings; the parts that aren't ready yet say "Coming soon". In small
  windows the bar shrinks to icons and the figure steps aside. Works in every supported Minecraft version, from 1.8.9
  to 26.3.
- **Switch accounts in the game.** With the TRS Client you can now switch Minecraft accounts right in the game – no
  restart. Started from the launcher, all your launcher accounts are there, and "Add account" signs in through the
  launcher and saves the new account there too. Started from another launcher, you can sign in with Microsoft in the
  game and keep accounts per game folder. Find it in the TRS menu under "Accounts".
- **Menus in the redstone look.** The pause menu, server list, loading screens, options and world list now wear the
  TRS redstone style: redstone background, stone buttons that light up, server cards with redstone ping bars. Every
  button keeps working – also the ones other mods add. Pin your favourite servers to keep them on top, and see which
  TRS friends are playing on a server right in the list. The pause menu gets buttons for Wardrobe, Accounts, Clips,
  Friends and a new "Server info" page. Loading screens and the start-up screen show a row of redstone lamps. Each menu
  can go back to the classic look in the TRS menu under "Menu Style".
- **Friends in the game.** See your TRS friends with their Minecraft faces, who is online and what they are playing,
  answer friend requests, add friends by name, remove or block players – right in the game from the title screen, the
  TRS menu or the pause menu.
- **Clips & Images in the game.** All your clips and screenshots as tiles with previews: look at screenshots in full
  size, start or stop a recording, save a clip, open the folder or delete files (with a safety question).
- **Safer connection between launcher and game.** Clips and account switching now use a key the game only gets in
  memory – no more secret in a file in your game folder.
- **The skin preview shows your TRS cape.** After a restart the 3D preview now shows the TRS cape you wear – the way
  other TRS players see you in game – instead of your Mojang cape. Click a Mojang cape to look at that one instead.
- **Your skins, presets and look follow you to every PC.** With the TRS services on, "My skins", your own presets and
  your theme, accent colour and language are synced with your Minecraft account – add a skin on one PC and it's there
  on the next one, delete it and it's gone everywhere. Your Java and memory settings stay on each PC. You can rename
  skins in your collection now, too. Don't want it? Turn off "Sync with TRS account" under Settings → Privacy.
- **Adding skins is much easier.** "Add skin" now opens a menu: pick several PNG files at once, drag them straight into
  the window, paste a link, type a player's name to copy their skin, or bring over the skins you saved in the official
  Minecraft Launcher, Prism Launcher or the Modrinth App – with a checklist and previews. The launcher recognises slim
  (Alex) arms by itself; for a single skin you can still change the name and model before it's added.
- **Upload capes the way you want them.** The new cape dialog works like cropping a profile picture: pick any image
  (PNG, JPEG, WebP), drag and zoom to choose the part that goes on the cape and watch it live on the player in 3D.
  Animated GIFs, several images at once, sprite sheets and TRS Studio exports become animated capes with up to 16
  frames – you pick the speed. Custom capes can now be up to 512×256 pixels per frame and 5 MB.
- **TRS capes in HD in the game.** The TRS Client now also loads large HD capes up to 8 MB.
- **For the team: reviewing capes is faster.** Click a waiting cape to see it on the player in 3D, the texture pixel
  by pixel with zoom and every frame, and who uploaded it. Approve with A, reject with D (with a reason to pick) and
  browse with the arrow keys.
- **Mods that don't get along are caught before the game starts.** Some mods only work with certain versions of
  other mods (Sodium 0.8.14, for example, refuses Iris 1.10.7) – Modrinth doesn't say so, but the mod files do. The
  launcher now reads those rules: FPS-Boost and other presets pick versions that fit together (and tell you when a
  version was chosen for that reason), updates that would break another mod are held back, and an instance that
  already has such a pair gets a "Fix" button in the content list. If the game still stops with "Incompatible mods
  found", the crash notice offers to switch the mod to a compatible version with one click.
- **Your TRS Client settings follow you to every PC.** With the TRS services on, your modules and their settings,
  HUD layouts and profiles and your TRS keys are saved with your Minecraft account – set up the client once and it
  looks the same on your next PC or game folder. The theme, accent colour and language from the launcher now also
  switch in the game right away. Waypoints, server lists and Minecraft's own options stay on each PC. Don't want it?
  Turn off "Sync with TRS account" on the "TRS Online Features" page in the TRS menu.
- **A short introduction when you start the TRS Client for the first time.** Four quick steps in the redstone look:
  language and theme, performance (pretty or maximum FPS, frame limit, VSync, Dynamic FPS), your TRS keys – keys that
  clash with Minecraft or other mods are highlighted and can be changed right there – and a module pack with a HUD
  layout. Skip it any time. Everyone sees it once – also if you're updating from an earlier TRS Client – and only once
  per TRS account: finish or skip it on one PC and it won't come back on the others. You can start it again in the
  TRS menu.
- **Module packs.** One click sets up the client for your play style – PvP, Redstone, Comfort or Minimal – with a
  matching HUD layout. You see beforehand what gets turned on, off and moved, and you can undo it. Find them in the
  TRS menu under "Presets".
- **"NEW" in the TRS menu.** Modules and settings that arrive with a client update carry a "NEW" sign until you've
  opened them once – on every PC with your account. Updating from an earlier TRS Client, you'll find it on everything
  new in this update: the wardrobe (and its key), menu style, friends, clips & images, accounts, module packs, the
  built-in optimizations, the graphics mode and account sync – in the TRS menu and on the title screen.
- **Much higher FPS in new instances.** Minecraft starts with VSync on and a frame limit of 120 FPS, so even a
  strong PC got stuck at around 100 FPS. New instances now start with an unlimited frame rate and VSync off – your
  own settings in existing instances stay as they are.
- **TRS Client: performance check finds the frame limit.** The check in the TRS menu now flags a frame limit below
  "Unlimited" and lifts it with one click, and every FPS Boost level lifts it too. Undo restores it.
- **TRS Client: no more FPS cap while playing.** If Minecraft missed that its window got the focus back, the
  background limiter of the TRS Client could hold the running game at about 100 FPS. It now asks the system
  directly and never slows down the game you are playing.
- **TRS Client: built-in optimizations (Fabric).** The TRS Client now brings free performance mods along – Lithium,
  FerriteCore, ImmediatelyFast, ModernFix and BadOptimizations, wherever they exist for your Minecraft version – so
  it is faster even without extra mods. If you install one of them yourself, the newer version is used. You can
  switch them off in the TRS menu under Performance (takes effect at the next start through the TRS Launcher). The
  FPS Boost preset of the launcher no longer installs these mods a second time – unless you switched the built-in
  optimizations off or the TRS Client is off for that instance.
- **TRS Client: graphics mode "Pretty" or "Max FPS".** Pick it in the TRS menu under Performance or in the
  instance settings of the launcher. Pretty keeps the look; Max FPS turns off clouds and smooth lighting, lowers
  particles and uses fast graphics and fast leaves. Settings you changed yourself stay as they are, and switching
  back restores the rest.
- **TRS Client: lighter HUD on Minecraft 1.21.6 and newer.** HUD texts no longer create lots of short-lived data
  every frame, which means fewer small stutters.
- **The TRS badge is now live.** The badge next to a player's name now means "playing with TRS right now": it shows
  while someone is in a world or on a server with the TRS Client, or plays a game started by the TRS Launcher – not
  when a TRS user only has the launcher open or plays with another client. It appears and disappears within seconds.
  For privacy, you only see other players' badges while you are in game yourself; your own you always see. "Show TRS
  badge" in the privacy settings still turns yours off.

### Deutsch

- **Garderobe mit vollem Skin-Editor im Spiel.** Der TRS Client hat jetzt eine Garderobe (Knopf unter deiner Figur im Titelbildschirm,
  TRS-Menü oder eigene Taste): deine Skins mit Favoriten und Bibliothek, Outfits (Skin + Umhang) zum Durchblättern,
  deine Minecraft- und TRS-Umhänge und dein Emote-Rad – alles mit großer 3D-Vorschau. „Skin hinzufügen“ nimmt eine
  Datei, einen Link oder einen Spielernamen. Ein Klick auf „Anwenden“ macht den Skin sofort zu deinem Minecraft-Skin.
  Im Editor malst du direkt auf der 3D-Figur oder auf dem flachen Skin: Pinsel, Radierer, Pipette, Füllen,
  Links/Rechts-Spiegeln, Rückgängig/Wiederholen, Farbpalette mit zuletzt benutzten Farben und Hex-Eingabe, innere und
  äußere Ebene, Classic- oder Slim-Arme und leere Vorlagen. Mit eingeschalteten TRS-Diensten sind deine Skins dieselben
  „Meine Skins“ wie im Launcher, und Favoriten, Outfits und Emote-Rad folgen dir auf jeden PC.
- **Import aus viel mehr Launchern.** „Aus anderem Launcher importieren“ findet jetzt auch Lunar Client, Badlion,
  OneClient, ATLauncher, GDLauncher (alt und neu) und TLauncher – neben dem offiziellen Launcher, CurseForge, der
  Modrinth App, Prism und MultiMC. Du siehst, welche Launcher auf deinem PC liegen, und je Instanz genau, was mitkommt:
  Welten, Mods, Ressourcen- und Shaderpakete, Einstellungen und deine Serverliste. Version und Modloader werden
  erkannt. Bei Lunar und Badlion kommen deine eigenen Welten, Pakete, Einstellungen und Mods mit – die eingebauten
  Client-Mods sind nicht frei und bleiben zurück, der TRS Client übernimmt ihren Platz. Feather wird erkannt,
  speichert aber keine lesbare Liste – wähle seinen Ordner dann von Hand.
- **Importierte Mods bleiben aktualisierbar.** Inhalte aus CurseForge, ATLauncher und GDLauncher behalten ihre
  Herkunft, du kannst sie also sofort aktualisieren. Fehlen in einer CurseForge-Instanz Dateien, werden sie geladen.
- **Skins aus dem ATLauncher.** „Skin hinzufügen“ → „Aus anderen Launchern“ holt jetzt auch die Skins deiner
  ATLauncher-Konten.
- **Sicherer importieren.** Anmeldedaten anderer Launcher werden nie gelesen oder kopiert, fremde Dateien nur
  gelesen, und Verknüpfungen, die aus den Launcher-Ordnern herausführen, werden übersprungen.
- **Neuer Titelbildschirm im TRS Client: du auf einer Redstone-Drehscheibe.** Dein eigener Skin (mit deinem TRS-
  oder Mojang-Umhang) steht jetzt links auf einer langsam drehenden Redstone-Drehscheibe – zieh daran, um sie zu
  drehen, und dein Kopf folgt der Maus. Darunter stehen dein Name und ein „Garderobe“-Knopf. Eine neue Leiste rechts führt
  zu Konten, Freunde, Clips & Bilder und den TRS-Einstellungen; was noch nicht fertig ist, meldet „Kommt
  bald“. In kleinen Fenstern wird die Leiste zu Symbolen und die Figur macht Platz. Klappt in jeder unterstützten
  Minecraft-Version von 1.8.9 bis 26.3.
- **Konten im Spiel wechseln.** Mit dem TRS Client wechselst du dein Minecraft-Konto jetzt direkt im Spiel – ohne
  Neustart. Über den Launcher gestartet sind alle Launcher-Konten da, und „Konto hinzufügen“ meldet über den Launcher
  an und speichert das neue Konto auch dort. Über einen anderen Launcher gestartet, meldest du dich im Spiel bei
  Microsoft an und behältst Konten je Spielordner. Zu finden im TRS-Menü unter „Konten“.
- **Menüs im Redstone-Look.** Pausenmenü, Serverliste, Ladebildschirme, Einstellungen und Weltenliste tragen jetzt den
  TRS-Redstone-Stil: Redstone-Hintergrund, Steinknöpfe, die aufleuchten, Serverkarten mit Redstone-Ping-Balken. Jeder
  Knopf funktioniert weiter – auch die von anderen Mods. Hefte Lieblingsserver an, damit sie oben bleiben, und sieh
  direkt in der Liste, welche TRS-Freunde gerade auf einem Server spielen. Das Pausenmenü bekommt Knöpfe für
  Garderobe, Konten, Clips, Freunde und eine neue Seite „Server-Info“. Ladebildschirme und der Startbildschirm zeigen
  eine Reihe Redstone-Lampen. Jedes Menü lässt sich im TRS-Menü unter „Menü-Stil“ wieder klassisch stellen.
- **Freunde im Spiel.** Sieh deine TRS-Freunde mit ihrem Minecraft-Gesicht, wer online ist und was gespielt wird,
  beantworte Freundschaftsanfragen, füge Freunde per Name hinzu, entferne oder blockiere Spieler – direkt im Spiel
  über Titelbildschirm, TRS-Menü oder Pausenmenü.
- **Clips & Bilder im Spiel.** Alle Clips und Bildschirmfotos als Kacheln mit Vorschau: Bilder groß ansehen, Aufnahme
  starten oder stoppen, Clip speichern, Ordner öffnen oder Dateien löschen (mit Sicherheitsfrage).
- **Sicherere Verbindung zwischen Launcher und Spiel.** Clips und Kontowechsel nutzen jetzt einen Schlüssel, den das
  Spiel nur im Arbeitsspeicher bekommt – kein Geheimnis mehr in einer Datei im Spielordner.
- **Die Skin-Vorschau zeigt deinen TRS-Umhang.** Nach einem Neustart zeigt die 3D-Vorschau jetzt den TRS-Umhang, den du
  trägst – so, wie andere TRS-Spieler dich im Spiel sehen – statt deines Mojang-Umhangs. Klick einen Mojang-Umhang
  an, um den anzusehen.
- **Deine Skins, Presets und dein Look kommen mit auf jeden PC.** Mit eingeschalteten TRS-Diensten werden „Meine
  Skins“, deine eigenen Presets sowie Theme, Akzentfarbe und Sprache mit deinem Minecraft-Account synchronisiert – auf
  einem PC einen Skin hinzufügen, und er ist auf dem nächsten da; löschen, und er ist überall weg. Java- und
  Speicher-Einstellungen bleiben auf jedem PC. Skins in deiner Sammlung lassen sich jetzt auch umbenennen. Nicht
  gewollt? Unter Einstellungen → Datenschutz „Mit TRS-Konto synchronisieren“ ausschalten.
- **Skins hinzufügen geht viel leichter.** „Skin hinzufügen“ öffnet jetzt ein Menü: mehrere PNG-Dateien auf einmal
  wählen, sie direkt ins Fenster ziehen, einen Link einfügen, per Spielername den Skin eines Spielers übernehmen oder
  die Skins aus dem offiziellen Minecraft Launcher, dem Prism Launcher oder der Modrinth App holen – mit Auswahlliste
  und Vorschau. Schlanke (Alex-)Arme erkennt der Launcher selbst; bei einem einzelnen Skin kannst du Name und Modell
  vor dem Hinzufügen noch ändern.
- **Umhänge hochladen, wie du sie willst.** Der neue Umhang-Dialog funktioniert wie das Zuschneiden eines Profilbilds:
  beliebiges Bild wählen (PNG, JPEG, WebP), den Ausschnitt verschieben und zoomen und ihn live in 3D am Spieler
  sehen. Animierte GIFs, mehrere Bilder auf einmal, Sprite-Sheets und TRS-Studio-Exporte werden zu animierten
  Umhängen mit bis zu 16 Frames – das Tempo bestimmst du. Eigene Umhänge dürfen jetzt bis 512×256 Pixel je Frame und
  5 MB groß sein.
- **TRS-Umhänge in HD im Spiel.** Der TRS Client lädt jetzt auch große HD-Umhänge bis 8 MB.
- **Fürs Team: Umhänge schneller prüfen.** Ein Klick auf einen wartenden Umhang zeigt ihn in 3D am Spieler, die
  Textur Pixel für Pixel mit Zoom und allen Frames und wer ihn hochgeladen hat. Freigeben mit A, ablehnen mit D (mit
  auswählbarem Grund), blättern mit den Pfeiltasten.
- **Mods, die sich nicht vertragen, fallen vor dem Spielstart auf.** Manche Mods laufen nur mit bestimmten Versionen
  anderer Mods (Sodium 0.8.14 etwa verweigert Iris 1.10.7) – bei Modrinth steht das nicht, in den Mod-Dateien schon.
  Der Launcher liest diese Regeln jetzt: FPS-Boost und andere Presets wählen Versionen, die zusammenpassen (und sagen
  dir, wenn eine Version deshalb gewählt wurde), Updates, die eine andere Mod kaputt machen würden, werden
  zurückgehalten, und eine Instanz, die schon so ein Paar hat, bekommt in der Inhaltsliste einen „Beheben“-Knopf.
  Stoppt das Spiel trotzdem mit „Incompatible mods found“, bietet der Absturz-Hinweis an, die Mod mit einem Klick
  gegen eine passende Version zu tauschen.
- **Deine TRS-Client-Einstellungen folgen dir auf jeden PC.** Mit eingeschalteten TRS-Diensten werden deine Module
  und ihre Einstellungen, HUD-Layouts und -Profile und deine TRS-Tasten mit deinem Minecraft-Konto gespeichert – den
  Client einmal einrichten, und er sieht auf dem nächsten PC oder Spielordner genauso aus. Thema, Akzentfarbe und
  Sprache aus dem Launcher wechseln jetzt auch im Spiel sofort mit. Wegpunkte, Serverlisten und Minecrafts eigene
  Optionen bleiben auf jedem PC. Nicht gewünscht? Schalte „Mit TRS-Konto synchronisieren“ auf der Seite
  „TRS-Online-Funktionen“ im TRS-Menü aus.
- **Eine kurze Einführung beim ersten Start des TRS Clients.** Vier schnelle Schritte im Redstone-Look: Sprache und
  Thema, Leistung (schön oder maximale FPS, Bildraten-Grenze, VSync, Dynamische FPS), deine TRS-Tasten – Tasten, die
  mit Minecraft oder anderen Mods kollidieren, leuchten und lassen sich direkt ändern – und ein Modul-Paket mit
  HUD-Vorlage. Jederzeit überspringbar. Alle sehen sie einmal – auch wenn du von einem älteren TRS Client
  aktualisierst – und nur einmal pro TRS-Konto: Auf einem PC abgeschlossen oder übersprungen, kommt sie auf den
  anderen nicht wieder. Im TRS-Menü kannst du sie erneut starten.
- **Modul-Pakete.** Ein Klick richtet den Client für deinen Spielstil ein – PvP, Redstone, Komfort oder Minimal – mit
  passender HUD-Vorlage. Vorher siehst du, was ein-, aus- und umgestellt wird, und du kannst es rückgängig machen. Zu
  finden im TRS-Menü unter „Pakete“.
- **„NEU“ im TRS-Menü.** Module und Einstellungen, die mit einem Client-Update kommen, tragen ein „NEU“-Schild, bis du
  sie einmal geöffnet hast – auf jedem PC mit deinem Konto. Aktualisierst du von einem älteren TRS Client, steht es an
  allem, was dieses Update bringt: Garderobe (samt Taste), Menü-Stil, Freunde, Clips & Bilder, Konten, Modul-Pakete,
  eingebaute Optimierungen, Grafik-Modus und Konto-Sync – im TRS-Menü und auf dem Startbildschirm.
- **Deutlich mehr FPS in neuen Instanzen.** Minecraft startet mit VSync und einer Bildraten-Grenze von 120 FPS –
  selbst ein starker PC blieb so bei rund 100 FPS hängen. Neue Instanzen starten jetzt mit unbegrenzter Bildrate
  und ohne VSync; deine eigenen Einstellungen in bestehenden Instanzen bleiben, wie sie sind.
- **TRS Client: Der Leistungs-Check findet die Bildraten-Grenze.** Der Check im TRS-Menü meldet jetzt eine Grenze
  unter „Unbegrenzt“ und hebt sie mit einem Klick auf; auch jede FPS-Boost-Stufe hebt sie auf. „Rückgängig“ stellt
  sie wieder her.
- **TRS Client: Keine FPS-Bremse mehr beim Spielen.** Hat Minecraft verpasst, dass sein Fenster wieder im
  Vordergrund ist, konnte die Hintergrund-Bremse des TRS Clients das laufende Spiel bei etwa 100 FPS halten. Sie
  fragt jetzt direkt beim System nach und bremst nie das Spiel, das du gerade spielst.
- **TRS Client: Eingebaute Optimierungen (Fabric).** Der TRS Client bringt jetzt freie Leistungs-Mods gleich mit –
  Lithium, FerriteCore, ImmediatelyFast, ModernFix und BadOptimizations, soweit es sie für deine Minecraft-Version
  gibt – und ist damit auch ohne zusätzliche Mods schneller. Installierst du eine davon selbst, wird die neuere
  Fassung benutzt. Abschalten kannst du sie im TRS-Menü unter Leistung (wirkt beim nächsten Start über den TRS
  Launcher). Das FPS-Boost-Preset des Launchers installiert diese Mods nicht mehr doppelt – außer du hast die
  eingebauten Optimierungen ausgeschaltet oder der TRS Client ist für die Instanz aus.
- **TRS Client: Grafik-Modus „Schön“ oder „Max FPS“.** Wählbar im TRS-Menü unter Leistung oder in den
  Instanz-Einstellungen des Launchers. „Schön“ behält die Optik; „Max FPS“ schaltet Wolken und weiche Beleuchtung
  aus, senkt die Partikel und nutzt schnelle Grafik und schnelles Laub. Was du selbst geändert hast, bleibt, und
  beim Zurückschalten kommt der Rest wieder.
- **TRS Client: Leichteres HUD ab Minecraft 1.21.6.** HUD-Texte erzeugen nicht mehr in jedem Bild viele kurzlebige
  Daten – das heißt weniger kleine Ruckler.
- **Das TRS-Symbol ist jetzt live.** Das Symbol neben einem Spielernamen heißt jetzt „spielt gerade mit TRS“: Es
  erscheint, solange jemand mit dem TRS Client in einer Welt oder auf einem Server ist oder ein vom TRS Launcher
  gestartetes Spiel spielt – nicht, wenn ein TRS-Nutzer nur den Launcher offen hat oder mit einem anderen Client
  spielt. Es kommt und geht innerhalb von Sekunden. Zum Schutz deiner Privatsphäre siehst du die Symbole anderer nur,
  während du selbst im Spiel bist; dein eigenes siehst du immer. „TRS-Symbol zeigen“ in den Datenschutz-Einstellungen
  schaltet deins weiterhin ab.

## 0.5.1 – 2026-09-24 – The Turbo Update | Das Turbo-Update

<!-- banner: accent=#3dd6ff motif=/news/0.5.1/banner.png -->

### English

- **No more freezes when players join or leave.** With the TRS Client, the game could freeze for up to a minute
  as soon as another player came online or left – for both players at the same moment. Fixed in TRS Client
  0.4.1, which installs itself automatically.
- **Smoother frames, fewer stutters.** The FPS boost at launch now uses a garbage collector setup made for the game
  client (G1 with short pauses); ZGC is only used from 12 GB of memory, where it no longer causes hitches. The
  settings follow the Java version the game really starts with – also your own Java.
- **Your own JVM arguments no longer switch the boost off.** Only the settings you set yourself replace the
  launcher's (your own memory size, garbage collector or option wins); everything else stays tuned.
- **Memory that fits your PC.** New installations start with 6 GB on PCs with 16 GB or more, 4 GB from 8 GB and
  half of the memory below that. If more is set than the PC has, the game gets at most your memory minus 2 GB so
  Windows doesn't have to swap. Existing settings are kept.
- **FPS boost with shaders – pick your level.** When creating an instance or applying presets, the FPS boost now
  comes in three levels: **Max FPS** (optimisations only, for weaker PCs), **Light shaders** (plus Iris and the
  very light MakeUp – Ultra Fast shader) and **Pretty shaders** (plus Iris and Complementary Reimagined). The shader
  is switched on right away; press **K** in game to turn shaders on or off. Shaders are available with Fabric,
  Quilt and NeoForge; elsewhere they are skipped and named in the summary. Nvidium can't be combined with shaders
  and is left out. New instances still start with Max FPS.
- **Real faces for your friends.** The friends list and the admin player search show each player's Minecraft face
  instead of a placeholder.
- **FPS boost for existing instances.** Instances with a mod loader but without Sodium, Embeddium or OptiFine now
  show a small hint with the level choice – one click installs the boost. Hide it once and it stays hidden for that
  instance; modpacks never show it.
- **Show off on Discord.** With Discord open, your profile now shows "Playing TRS Launcher" – while you play also the
  Minecraft version, the mod loader and your play time, plus a button for friends to get the launcher. Server
  addresses and names are never shown. Turn it off under Settings → Privacy.
- **TRS Client runs smoother.** The HUD is now drawn in one go instead of piece by piece (far fewer draw calls,
  especially on Minecraft 1.20–1.21.1), “Hide entities behind walls” works in the background and no longer causes
  stutters, capes, badges and the online features do less work per frame, and saving settings never makes the game
  wait for the disk. The Colors module needs less work per frame, too.
- **Dynamic FPS: no AFK limit by default.** Standing still no longer lowers your FPS – that also keeps FPS
  measurements and recordings honest. The limits in the background and when minimized stay; the AFK limit can
  still be switched on.
- **Shader packs:** while a shader pack is active (Iris, Oculus or OptiFine), Colors pauses – the pack does its own
  color work – and hiding entities behind walls rests, so every shadow stays in place.
- **Armor display across.** New setting “Orientation”: vertical as before or horizontal – the items in one row with
  the durability below. The HUD editor sizes and anchors it correctly.
- **New TRS badge:** a small glowing redstone dust pile in front of TRS players’ names in the tab list and above
  their heads.
- The clip keys (F9/F10) never make the game wait for the launcher connection.

### Deutsch

- **Kein Einfrieren mehr, wenn Spieler kommen oder gehen.** Mit dem TRS Client konnte das Spiel bis zu einer Minute
  einfrieren, sobald ein anderer Spieler online kam oder ging – bei beiden gleichzeitig. Behoben im TRS Client
  0.4.1, der sich automatisch aktualisiert.
- **Flüssigere Bilder, weniger Ruckler.** Der FPS-Boost beim Start nutzt jetzt eine Speicherbereinigung, die für
  das Spiel gemacht ist (G1 mit kurzen Pausen); ZGC kommt erst ab 12 GB Arbeitsspeicher zum Einsatz, wo es nicht
  mehr hängt. Die Einstellungen richten sich nach der Java, mit der das Spiel wirklich startet – auch nach deiner
  eigenen.
- **Eigene JVM-Argumente schalten den Boost nicht mehr ab.** Nur was du selbst angibst, ersetzt die Werte des
  Launchers (eigene Speichergröße, eigene Speicherbereinigung oder gleiche Option gewinnt); der Rest bleibt
  abgestimmt.
- **Arbeitsspeicher passend zu deinem PC.** Neue Installationen starten mit 6 GB auf PCs ab 16 GB, mit 4 GB ab
  8 GB und darunter mit der Hälfte. Ist mehr eingestellt, als der PC hat, bekommt das Spiel höchstens deinen
  Speicher minus 2 GB, damit Windows nicht auslagern muss. Bestehende Einstellungen bleiben.
- **FPS-Boost mit Shadern – Stufe wählbar.** Beim Anlegen einer Instanz und bei „Preset anwenden“ gibt es den
  FPS-Boost jetzt in drei Stufen: **Max FPS** (nur Optimierungen, für schwache PCs), **Shader leicht** (dazu Iris
  und der sehr sparsame Shader MakeUp – Ultra Fast) und **Shader schön** (dazu Iris und Complementary Reimagined).
  Der Shader ist gleich eingeschaltet; mit der Taste **K** schaltest du Shader im Spiel an und aus. Shader gibt es
  mit Fabric, Quilt und NeoForge; sonst werden sie übersprungen und in der Zusammenfassung genannt. Nvidium lässt
  sich nicht mit Shadern kombinieren und bleibt dann weg. Neue Instanzen starten weiterhin mit Max FPS.
- **Echte Gesichter bei Freunden.** Die Freundesliste und die Admin-Spielersuche zeigen das Minecraft-Gesicht jedes
  Spielers statt eines Platzhalters.
- **FPS-Boost für bestehende Instanzen.** Instanzen mit Modloader, aber ohne Sodium, Embeddium oder OptiFine zeigen
  jetzt einen kleinen Hinweis mit Stufenwahl – ein Klick installiert den Boost. Einmal ausgeblendet, bleibt er für
  diese Instanz weg; bei Modpacks erscheint er nie.
- **Zeig auf Discord, was du spielst.** Ist Discord offen, steht auf deinem Profil jetzt „Spielt TRS Launcher“ – beim
  Spielen auch Minecraft-Version, Modloader und Spielzeit, dazu ein Knopf, mit dem sich Freunde den Launcher holen
  können. Server-Adressen und Namen werden nie gezeigt. Abschaltbar unter Einstellungen → Datenschutz.
- **TRS Client läuft flüssiger.** Das HUD wird jetzt in einem Rutsch gezeichnet statt Stück für Stück (viel
  weniger Zeichenaufrufe, vor allem in Minecraft 1.20–1.21.1), „Hinter Wänden ausblenden“ rechnet im Hintergrund und
  sorgt nicht mehr für Ruckler, Umhänge, Abzeichen und die Online-Funktionen machen weniger Arbeit je Bild, und
  beim Speichern der Einstellungen wartet das Spiel nie auf die Festplatte. Auch das Modul „Farben“ braucht je Bild
  weniger Arbeit.
- **Dynamische FPS: keine AFK-Bremse mehr als Standard.** Wer stillsteht, verliert keine FPS mehr – so bleiben auch
  FPS-Messungen und Aufnahmen ehrlich. Die Grenzen im Hintergrund und minimiert bleiben; die AFK-Grenze lässt sich
  weiterhin einschalten.
- **Shaderpacks:** Solange ein Shaderpack aktiv ist (Iris, Oculus oder OptiFine), pausiert „Farben“ – das Pack
  färbt selbst – und das Ausblenden hinter Wänden ruht, damit jeder Schatten bleibt.
- **Rüstungsanzeige quer.** Neue Einstellung „Ausrichtung“: senkrecht wie bisher oder waagerecht – die Gegenstände
  in einer Zeile, die Haltbarkeit darunter. Der HUD-Editor passt Größe und Ankerung richtig an.
- **Neues TRS-Abzeichen:** ein kleines, leuchtendes Häufchen Redstone-Staub vor den Namen von TRS-Spielern in der
  Tabliste und über ihren Köpfen.
- Die Clip-Tasten (F9/F10) lassen das Spiel nie auf die Verbindung zum Launcher warten.

## 0.5.0 – 2026-09-24 – The Showtime Update | Das Showtime-Update

<!-- banner: accent=#ffc24b motif=/news/0.5.0/banner.png -->
<!-- shots:
/news/0.5.0/emote-wheel.png | The emote wheel in the TRS Client | Das Emote-Rad im TRS Client
/news/0.5.0/redstone-overlay.png | Redstone tools: signal strength over every dust | Redstone-Werkzeuge: Signalstärke über jedem Staub
/news/0.5.0/cape-physics.png | Cape physics with a live preview | Umhang-Physik mit Live-Vorschau
/news/0.5.0/zoom.png | Smooth zoom | Weicher Zoom
/news/0.5.0/colors.png | Toggle sprint and sneak, key strokes and colourful HUD modules | Sprinten und Schleichen umschalten, Tastenanzeige und farbige HUD-Module
-->

### English

- **TRS Client 0.4.0 – show yourself.** Hold **G** for the emote wheel and wave, dance or cheer – other TRS players
  see it. New comfort keys: smooth **zoom** (V), **freelook** (Left Alt) and toggle sprint/sneak.
- **Redstone tools.** See the signal strength over every piece of dust, a redstone overlay (F6) and a clock
  meter for your circuits – right in the game.

- **Mod presets.** Create your own presets (e.g. “My basics”) with mods, resource packs and shaders from
  Modrinth, tick them when creating an instance or apply them later – mark a preset as “always automatic” and it’s
  preselected every time. Each mod is only installed if there’s a version for your Minecraft version and loader
  (with its required dependencies); anything else is skipped and named in a short summary. Share presets with
  friends as a small file.
- **Ready-made TRS presets:** FPS boost (the old performance pack, now also with fixes for Forge 1.12.2/1.8.9),
  Voice chat (Simple Voice Chat), Replay (Flashback, otherwise ReplayMod) and Nvidium for NVIDIA cards from GTX 16xx.
- **More FPS at launch.** Tuned Java settings per Java version and memory (ZGC or G1, memory reserved up front),
  optional higher process priority, and the dedicated graphics card is now only set for the launcher’s own Java –
  switching it off undoes it.
- **Clips & recording (like ShadowPlay/Medal).** Turn it on under Settings → Clips: while a game from the launcher
  runs, **F9** saves the last 15–120 seconds as an MP4 and **F10** starts and stops a normal recording. Only the
  game window is recorded, with system sound (microphone optional, off by default), using your graphics card
  (NVIDIA, AMD, Intel) or software as a fallback. The recorder (FFmpeg) is downloaded once when you switch it on.
  The new **Clips** page plays, renames, deletes and shows your clips, with running recordings and storage use;
  a storage limit moves the oldest clips to the recycle bin. Everything stays on your PC. Off by default.
- **TRS Client: clip keys and recording display.** F9/F10 (changeable in the Minecraft controls) with a small HUD
  element – red dot and time while recording, "Clip saved (30 s)" – movable in the HUD editor. Without the TRS
  Launcher the keys only show a hint.
- **TRS capes load again.** The cape page failed as soon as one of the new HD capes (up to 512×256) was in the
  list.
- **Modpack downloads no longer give up so quickly.** Better MC and other packs sometimes list a file size that is
  off by one byte – the launcher now trusts the file's checksum instead and installs them. Dropped connections,
  timeouts and busy servers are retried patiently for about a minute; only files that really don't exist fail
  right away.
- **Changelog.** Every update now comes with notes in English and German, shown once after updating.
- **Publisher.** The launcher now shows "Theredstonee" as publisher in Windows (apps list and file properties).
- **CurseForge is here.** Discover now has a Modrinth ⇄ CurseForge switch: search mods, resource packs, shaders and
  data packs on CurseForge with the same filters, open project pages, pick versions, and install – dependencies come
  along, and updates are found just like for Modrinth content. CurseForge modpacks (also as a downloaded .zip) become a
  new instance. If an author only allows downloads on CurseForge itself, the launcher doesn't sneak around that: it
  shows the files with a button to their CurseForge page and picks them up from your downloads folder automatically.
- **TRS Client: cape settings and colors.** Cape Physics now has its own settings like WaveyCapes – style (smooth or
  blocky), wind (off, waves, gusts), movement (vanilla, swinging, calm "Dungeons"), gravity, lift when running,
  stiffness and detail – with a live, turning preview of your own player and a reset button; saved in your
  profiles. The new "Colors" module adjusts saturation (0–200 %), contrast, brightness, vibrance and color
  temperature of the game image right away, while the HUD and menus keep their colors.
- **TRS Client: new "Performance" category for more FPS.** *FPS Boost* sets everything to Low, Medium or High with
  one click and shows the FPS before and after; a performance check finds FPS killers in your video settings
  (VSync, simulation distance, render distance, Fabulous graphics, onboard graphics although a graphics card is
  installed …) and fixes them with one click – every change can be undone, even after a restart. *Dynamic FPS*
  limits the frame rate in the background, minimized and when you are AFK and makes the game quieter; full FPS the
  moment you come back. *Entity Culling* skips mobs behind walls and far away mobs, chests, signs, dropped items,
  item frames and name tags; *Particles* sets a limit and switches off explosions, rain splashes or smoke; *World
  Details* hides sky, stars, fog, rain/snow and texture animations. Mods like Sodium, OptiFine, EntityCulling or
  Dynamic FPS are detected – their part is left to them ("taken over by …"). Everything is saved in your profiles.
- **New address for the TRS services.** Launcher and TRS Client now talk to trs-launcher.theredstonee.de, where the
  TRS website will live too. The old address keeps working, so capes, friends and your online status carry on
  without you doing anything.
- **Website sign-in.** TRS admins can sign in to the website with the launcher: the website shows a code, enter it
  under Admin, Settings → Privacy or via Ctrl+K ("Confirm website sign-in") and confirm. The launcher clearly asks
  first – only confirm if you are on the website yourself right now, and never give the code to anyone.
- **Linux support.** The launcher now runs on Linux – Arch, Ubuntu/Debian, Fedora and others – as AppImage (updates
  itself), .deb, .rpm, in the AUR (`trs-launcher-bin`) and ready for Flatpak. Java, all mod loaders and old versions
  like 1.8.9 work just like on Windows; sign-in keys go into your system keyring. Instances from Prism Launcher,
  MultiMC, the Modrinth App (also as Flatpak) and `~/.minecraft` can be imported. Windows-only features (firewall
  helper, clips) are hidden there for now.
- **Dedicated GPU on Linux too.** On laptops with two graphics chips, the game runs on the stronger one
  (PRIME offload).
- **Every update has its own banner.** The update card, the news and the update post show a banner with the
  update's name, its own colour and a pixel-art picture – the same redstone look every time. Older updates got
  their names and pictures too.

### Deutsch

- **TRS Client 0.4.0 – zeig dich.** Halte **G** für das Emote-Rad und winke, tanze oder jubel – andere TRS-Spieler
  sehen es. Neue Komfort-Tasten: weicher **Zoom** (V), **Freelook** (linke Alt-Taste) und Sprinten/Schleichen zum
  Umschalten.
- **Redstone-Werkzeuge.** Sieh die Signalstärke über jedem Staub, ein Redstone-Overlay (F6) und einen Takt-Messer
  für deine Schaltungen – direkt im Spiel.

- **Mod-Presets.** Eigene Presets anlegen (z. B. „Meine Basics“) mit Mods, Ressourcenpaketen und Shadern von
  Modrinth, beim Anlegen einer Instanz ankreuzen oder später anwenden – als „immer automatisch“ markiert, sind sie
  jedes Mal vorausgewählt. Jede Mod wird nur installiert, wenn es eine Version für deine Minecraft-Version und
  deinen Loader gibt (samt Pflicht-Abhängigkeiten); alles andere wird übersprungen und in einer kurzen
  Zusammenfassung genannt. Presets lassen sich als kleine Datei mit Freunden teilen.
- **Fertige TRS-Presets:** FPS-Boost (das bisherige Performance-Paket, jetzt auch mit Fixes für Forge 1.12.2/1.8.9),
  Voice Chat (Simple Voice Chat), Replay (Flashback, sonst ReplayMod) und Nvidium für NVIDIA-Karten ab GTX 16xx.
- **Mehr FPS beim Start.** Abgestimmte Java-Einstellungen je Java-Version und Speicher (ZGC oder G1, Speicher gleich
  reserviert), auf Wunsch höhere Prozesspriorität, und die leistungsstarke Grafikkarte wird nur noch für das Java
  des Launchers eingetragen – Abschalten nimmt es wieder zurück.
- **Clips & Aufnahme (wie ShadowPlay/Medal).** Unter Einstellungen → Clips einschalten: Solange ein Spiel aus dem
  Launcher läuft, speichert **F9** die letzten 15–120 Sekunden als MP4, **F10** startet und stoppt eine normale
  Aufnahme. Aufgenommen wird nur das Spielfenster, mit Systemton (Mikrofon wählbar, standardmäßig aus), über die
  Grafikkarte (NVIDIA, AMD, Intel) oder notfalls per Software. Die Aufnahme-Komponente (FFmpeg) wird beim
  Einschalten einmal geladen. Die neue Seite **Clips** spielt Clips ab, benennt sie um, löscht sie und zeigt sie
  im Ordner – mit laufenden Aufnahmen und Speicherplatz; ein Speicher-Limit schiebt die ältesten Clips in den
  Papierkorb. Alles bleibt auf deinem PC. Standardmäßig aus.
- **TRS Client: Clip-Tasten und Aufnahme-Anzeige.** F9/F10 (in der Minecraft-Steuerung änderbar) mit kleinem
  HUD-Element – roter Punkt und Zeit während der Aufnahme, „Clip gespeichert (30 s)“ – im HUD-Editor verschiebbar.
  Ohne TRS Launcher zeigen die Tasten nur einen Hinweis.
- **TRS-Umhänge laden wieder.** Die Umhang-Seite schlug fehl, sobald einer der neuen HD-Umhänge (bis 512×256) in
  der Liste war.
- **Modpack-Downloads geben nicht mehr so schnell auf.** Better MC und andere Packs geben manchmal eine um ein Byte
  falsche Dateigröße an – der Launcher vertraut jetzt der Prüfsumme der Datei und installiert sie. Abgebrochene
  Verbindungen, Zeitüberschreitungen und ausgelastete Server werden rund eine Minute lang geduldig wiederholt; nur
  Dateien, die es wirklich nicht gibt, schlagen sofort fehl.
- **Changelog.** Jedes Update bringt jetzt Hinweise auf Englisch und Deutsch mit, die einmal nach dem Update
  erscheinen.
- **Herausgeber.** Der Launcher nennt in Windows jetzt „Theredstonee“ als Herausgeber (App-Liste und
  Dateieigenschaften).
- **CurseForge ist da.** „Entdecken“ hat jetzt einen Umschalter Modrinth ⇄ CurseForge: Mods, Ressourcenpakete, Shader
  und Datenpakete auf CurseForge mit denselben Filtern suchen, Projektseiten ansehen, Versionen wählen und installieren
  – Abhängigkeiten kommen mit, und Updates werden genauso gefunden wie bei Modrinth-Inhalten. CurseForge-Modpacks
  (auch als heruntergeladene .zip) werden zur neuen Instanz. Erlaubt ein Autor Downloads nur direkt auf CurseForge,
  umgeht der Launcher das nicht: Er zeigt die Dateien mit einem Knopf zur CurseForge-Seite und übernimmt sie
  automatisch aus deinem Download-Ordner.
- **TRS Client: Umhang-Einstellungen und Farben.** Die Umhang-Physik hat jetzt eigene Einstellungen wie
  WaveyCapes – Stil (glatt oder blockig), Wind (aus, Wellen, Böen), Bewegung (Vanilla, schwingend, ruhig wie in
  „Dungeons“), Schwerkraft, Anhebung beim Laufen, Steifheit und Detailstufe – mit einer drehenden Live-Vorschau
  deines Spielers und einem Knopf zum Zurücksetzen; gespeichert in deinen Profilen. Das neue Modul „Farben“
  ändert Sättigung (0–200 %), Kontrast, Helligkeit, Dynamik und Farbtemperatur des Spielbilds sofort, HUD und
  Menüs behalten ihre Farben.
- **TRS Client: neue Kategorie „Leistung“ für mehr FPS.** *FPS-Boost* stellt mit einem Klick alles auf Niedrig,
  Mittel oder Hoch und zeigt die FPS vorher und nachher; ein Leistungs-Check findet FPS-Bremsen in deinen
  Grafikeinstellungen (VSync, Simulationsdistanz, Sichtweite, Grafik „Fabelhaft“, Onboard-Grafik trotz Grafikkarte …)
  und behebt sie per Klick – jede Änderung lässt sich rückgängig machen, auch nach einem Neustart. *Dynamische FPS*
  begrenzt die Bildrate im Hintergrund, minimiert und bei AFK und macht das Spiel leiser; sobald du zurück bist,
  gibt es sofort volle FPS. *Entity-Culling* lässt Mobs hinter Wänden sowie weit entfernte Mobs, Truhen, Schilder,
  Items am Boden, Item-Rahmen und Namensschilder weg; *Partikel* setzt eine Obergrenze und schaltet Explosionen,
  Regen-Spritzer oder Rauch ab; *Welt-Details* blendet Himmel, Sterne, Nebel, Regen/Schnee und Textur-Animationen aus.
  Mods wie Sodium, OptiFine, EntityCulling oder Dynamic FPS werden erkannt – ihren Teil übernehmen sie („übernimmt …“).
  Alles wird in deinen Profilen gespeichert.
- **Neue Adresse für die TRS-Dienste.** Launcher und TRS Client sprechen jetzt mit trs-launcher.theredstonee.de,
  wo künftig auch die TRS-Website liegt. Die alte Adresse funktioniert weiter – Umhänge, Freunde und dein
  Online-Status laufen ohne dein Zutun weiter.
- **Website-Anmeldung.** TRS-Admins können sich mit dem Launcher auf der Website anmelden: Die Website zeigt einen
  Code, den gibst du unter Admin, Einstellungen → Datenschutz oder über Strg+K („Website-Anmeldung bestätigen“)
  ein und bestätigst. Der Launcher fragt vorher deutlich nach – nur bestätigen, wenn du gerade selbst auf der
  Website bist, und den Code niemals weitergeben.
- **Linux-Unterstützung.** Der Launcher läuft jetzt unter Linux – Arch, Ubuntu/Debian, Fedora und andere – als
  AppImage (aktualisiert sich selbst), .deb, .rpm, im AUR (`trs-launcher-bin`) und bereit für Flatpak. Java, alle
  Modloader und alte Versionen wie 1.8.9 funktionieren wie unter Windows; Anmeldeschlüssel liegen im Schlüsselbund
  des Systems. Instanzen aus Prism Launcher, MultiMC, der Modrinth App (auch als Flatpak) und `~/.minecraft` lassen
  sich importieren. Reine Windows-Funktionen (Firewall-Freigabe, Clips) sind dort vorerst ausgeblendet.
- **Starke Grafikkarte auch unter Linux.** Auf Laptops mit zwei Grafikchips läuft das Spiel auf dem stärkeren
  (PRIME-Offload).
- **Jedes Update hat sein eigenes Banner.** Update-Karte, Neuigkeiten und der Beitrag zeigen ein Banner mit dem
  Namen des Updates, einer eigenen Farbe und einem Pixel-Art-Bild – immer im gleichen Redstone-Look. Auch die
  älteren Updates haben ihre Namen und Bilder bekommen.

## 0.4.3 – 2026-09-24 – The Friends Update | Das Freunde-Update

<!-- banner: accent=#ff7ab8 motif=/news/0.4.3/banner.png -->
<!-- shots:
/news/0.4.3/tasks.png | The tasks panel in the title bar while a modpack installs | Die Aufgabenleiste in der Titelleiste, während ein Modpack installiert wird
-->

### English

- **8 languages.** The launcher speaks English (default), German and Spanish, plus French, Polish, Portuguese
  (Brazil), Turkish and Dutch as beta. On first start you pick your language, with a suggestion based on your
  system.
- **TRS capes, badge and friends.** Wear TRS capes (also animated and HD up to 512×256), redeem codes, upload your
  own cape, see the TRS badge, add friends and join their server with one click. Everything only after you agree,
  and you can delete all TRS data at any time.
- **TRS Client 0.3.0.** Translated into the same 8 languages, shows TRS capes and badges in game and lets every cape
  move like cloth.
- **Background installs.** Installs keep running when you leave the page; the tasks panel in the title bar shows
  everything that is installing or running, with pause and cancel.

- **Calmer redstone.** The animated redstone background is slower and more varied, with long cables and the classic
  circuits.
- **Smaller fixes.** Deleting an instance needs just one confirmation, and the big play button no longer shows
  "Play" and "Running" at the same time.

### Deutsch

- **8 Sprachen.** Der Launcher spricht Englisch (Standard), Deutsch und Spanisch, dazu Französisch, Polnisch,
  Portugiesisch (Brasilien), Türkisch und Niederländisch als Beta. Beim ersten Start wählst du deine Sprache, mit
  einem Vorschlag passend zu deinem System.
- **TRS-Umhänge, Abzeichen und Freunde.** Trage TRS-Umhänge (auch animiert und in HD bis 512×256), löse Codes ein,
  lade einen eigenen Umhang hoch, zeig das TRS-Abzeichen, füge Freunde hinzu und tritt ihrem Server mit einem Klick
  bei. Alles erst nach deiner Zustimmung, und du kannst alle TRS-Daten jederzeit löschen.
- **TRS Client 0.3.0.** In dieselben 8 Sprachen übersetzt, zeigt TRS-Umhänge und -Abzeichen im Spiel und lässt jeden
  Umhang wie Stoff schwingen.
- **Installationen im Hintergrund.** Installationen laufen weiter, wenn du die Seite verlässt; die Aufgabenleiste in
  der Titelleiste zeigt alles, was installiert wird oder läuft, mit Pause und Abbrechen.

- **Ruhigerer Redstone.** Der animierte Redstone-Hintergrund ist langsamer und abwechslungsreicher, mit langen Kabeln
  und den klassischen Schaltungen.
- **Kleinere Korrekturen.** Eine Instanz löschst du mit einer einzigen Bestätigung, und der große Spielen-Knopf zeigt
  nicht mehr gleichzeitig „Spielen“ und „Läuft“.

## 0.4.2 – 2026-09-23 – Quiet Updates | Leise Updates

<!-- banner: accent=#a67bff motif=/news/0.4.2/banner.png -->

### English

- **Updates without waiting.** New launcher versions download in the background while you play. When
  one is ready, a button in the title bar restarts into it – while it installs you see a loading screen instead of
  a frozen window.
- **TRS Client updates on its own.** The in-game client now has its own signed update channel, so it can get fixes
  without a new launcher.
- **Redstone everywhere.** The live redstone circuit now runs quietly behind every page, not just the start page.

### Deutsch

- **Updates ohne Warten.** Neue Launcher-Versionen laden im Hintergrund, während du spielst. Ist eine fertig,
  startest du sie mit einem Knopf in der Titelleiste neu – während der Installation siehst du einen Ladebildschirm
  statt eines eingefrorenen Fensters.
- **Der TRS Client aktualisiert sich selbst.** Der Client im Spiel hat jetzt einen eigenen, signierten Update-Kanal
  und bekommt Korrekturen auch ohne neuen Launcher.
- **Redstone überall.** Die lebendige Redstone-Schaltung läuft jetzt ruhig hinter jeder Seite, nicht nur auf der
  Startseite.

## 0.4.1 – 2026-09-23 – Redstone Title | Redstone-Titelbild

<!-- banner: accent=#ff8a3d motif=/news/0.4.1/banner.png -->
<!-- shots:
/news/0.4.1/title-screen.png | The TRS Client title screen | Der Titelbildschirm des TRS Clients
-->

### English

- **A redstone title screen.** The TRS Client greets you with a title screen in the launcher’s redstone look –
  glowing dust, lamps and a torch – in every Minecraft version.
- **The TRS menu in the same style.** Buttons, panels and switches of the TRS menu match the launcher.
- **Smoother menus on 1.20 to 1.21.1.** TRS screens are drawn in one go and stay fluid.

### Deutsch

- **Ein Titelbildschirm aus Redstone.** Der TRS Client begrüßt dich mit einem Titelbildschirm im Redstone-Look des
  Launchers – glühender Staub, Lampen und eine Fackel – in jeder Minecraft-Version.
- **Das TRS-Menü im selben Stil.** Knöpfe, Flächen und Schalter des TRS-Menüs passen zum Launcher.
- **Flüssigere Menüs auf 1.20 bis 1.21.1.** TRS-Fenster werden in einem Rutsch gezeichnet und bleiben flüssig.

## 0.4.0 – 2026-09-23 – The Redstone Update | Das Redstone-Update

<!-- banner: accent=#ff5a4d motif=/news/0.4.0/banner.png -->
<!-- shots:
/news/0.4.0/start.png | The new start page with the redstone circuit | Die neue Startseite mit der Redstone-Schaltung
/news/0.4.0/running.png | While the game runs, the lamp glows | Während das Spiel läuft, leuchtet die Lampe
-->

### English

- **A start page that lives.** A real redstone circuit runs across the top: clocks, pistons, lamps and flickering
  torches. The main line leads to the play button and charges up while your game starts – when it runs, the lamp
  glows.
- **News as a magazine.** One lead story with a big picture, the rest next to it.
- **Your account in the title bar.** Switch accounts right from the top of the window.
- **Skins without waiting.** Edit skins locally; the launcher sends them to Mojang in the background and bundles
  quick changes into one.
- **Calmer notifications.** Identical messages are merged and only a few are shown at once.

### Deutsch

- **Eine Startseite, die lebt.** Oben läuft eine echte Redstone-Schaltung: Takte, Kolben, Lampen und flackernde
  Fackeln. Die Hauptleitung führt zum Spielen-Knopf und lädt sich beim Spielstart auf – läuft das Spiel, leuchtet
  die Lampe.
- **Neuigkeiten als Magazin.** Eine Titelgeschichte mit großem Bild, der Rest daneben.
- **Dein Konto in der Titelleiste.** Wechsle das Konto direkt oben im Fenster.
- **Skins ohne Warten.** Bearbeite Skins lokal; der Launcher schickt sie im Hintergrund an Mojang und fasst schnelle
  Änderungen zusammen.
- **Ruhigere Meldungen.** Gleiche Meldungen werden zusammengefasst, und es sind nur wenige gleichzeitig zu sehen.

## 0.3.1 – 2026-09-23 – Fabric Fix | Fabric-Fix

<!-- banner: accent=#b8c0d0 motif=/news/0.3.1/banner.png -->

### English

- **Fabric starts again.** Fixes a crash when starting Fabric instances from 1.15 to 1.21.8.
- **Off means off.** A TRS Client you switched off for an instance stays off.

### Deutsch

- **Fabric startet wieder.** Behebt einen Absturz beim Start von Fabric-Instanzen von 1.15 bis 1.21.8.
- **Aus heißt aus.** Ein TRS Client, den du für eine Instanz ausgeschaltet hast, bleibt aus.

## 0.3.0 – 2026-09-22 – The HUD Update | Das HUD-Update

<!-- banner: accent=#4fd1e0 motif=/news/0.3.0/banner.png -->
<!-- shots:
/news/0.3.0/trs-menu.png | The TRS menu in game | Das TRS-Menü im Spiel
/news/0.3.0/hud-editor.png | Moving the HUD with the HUD editor | Das HUD mit dem HUD-Editor verschieben
/news/0.3.0/skins.png | Skins & capes with the 3D preview | Skins & Umhänge mit der 3D-Vorschau
/news/0.3.0/gallery.png | The screenshot gallery | Die Screenshot-Galerie
-->

### English

- **A new TRS menu.** Right Shift opens the TRS menu: modules in categories (HUD, PvP, chat, world), search,
  settings for every module and HUD profiles you can switch between.
- **HUD editor.** Drag every display where you want it, scroll to resize, right-click to reset.
- **More for PvP and exploring.** Custom crosshair, hit colour, CPS and keystrokes, waypoints with beams, a minimap
  and chat tools that copy lines without colour codes.
- **Skins & capes page.** Collect skins, try them on in a 3D preview and put them on your account.
- **Screenshot gallery.** All screenshots from all instances in one place.
- **Share instances.** Export an instance as a .mrpack and import pack files.
- **Ctrl+K.** A command palette that finds instances, pages and actions.
- **Fresh look.** A slim icon sidebar, banners for your instances and a start page with a quick start.

### Deutsch

- **Ein neues TRS-Menü.** Die rechte Umschalttaste öffnet das TRS-Menü: Module in Kategorien (HUD, PvP, Chat, Welt),
  Suche, Einstellungen für jedes Modul und HUD-Profile zum Umschalten.
- **HUD-Editor.** Zieh jede Anzeige dorthin, wo du sie willst, Mausrad für die Größe, Rechtsklick setzt zurück.
- **Mehr für PvP und Erkundung.** Eigenes Fadenkreuz, Trefferfarbe, CPS und Tastenanzeige, Wegpunkte mit Strahl,
  eine Minikarte und Chat-Werkzeuge, die Zeilen ohne Farbcodes kopieren.
- **Seite für Skins & Umhänge.** Sammle Skins, probiere sie in einer 3D-Vorschau an und setze sie auf dein Konto.
- **Screenshot-Galerie.** Alle Screenshots aller Instanzen an einem Ort.
- **Instanzen teilen.** Exportiere eine Instanz als .mrpack und importiere Pack-Dateien.
- **Strg+K.** Eine Befehlspalette, die Instanzen, Seiten und Aktionen findet.
- **Frischer Look.** Eine schmale Symbolleiste, Banner für deine Instanzen und eine Startseite mit Schnellstart.

## 0.2.2 – 2026-09-22 – Quiet Firewall | Leise Firewall

<!-- banner: accent=#ffb13d motif=/news/0.2.2/banner.png -->

### English

- **Firewall without a blue window.** The launcher adds its firewall rules directly through Windows instead of a
  PowerShell window.

### Deutsch

- **Firewall ohne blaues Fenster.** Der Launcher legt seine Firewall-Regeln direkt über Windows an statt über ein
  PowerShell-Fenster.

## 0.2.1 – 2026-09-22 – The Library Update | Das Bibliotheks-Update

<!-- banner: accent=#c9853f motif=/news/0.2.1/banner.png -->
<!-- shots:
/news/0.2.1/library.png | The library with your own groups | Die Bibliothek mit eigenen Gruppen
-->

### English

- **A new library.** Square cards, sorting, filters and your own groups.
- **One content list.** Mods, resource packs, shaders and data packs in one table with filter chips, selection and
  bulk actions.
- **A new discover page.** Browse mods, modpacks, resource packs and shaders with big pictures and filters.
- **Settings as a window.** Global and per-instance settings in clear sections that save by themselves, with Java
  per Minecraft version, storage management and start hooks.
- **The TRS Client for (almost) every version.** Fabric from 1.14.4, Forge from 1.7.10 and NeoForge from 1.20.2 up
  to 26.3 – with a new title screen, more HUD modules and PvP features.
- **Clearer update errors.** If a launcher update fails, you see why and can try again.

### Deutsch

- **Eine neue Bibliothek.** Quadratische Karten, Sortieren, Filter und eigene Gruppen.
- **Eine Liste für alle Inhalte.** Mods, Ressourcenpakete, Shader und Datenpakete in einer Tabelle mit Filtern,
  Auswahl und Sammelaktionen.
- **Eine neue Entdecken-Seite.** Stöbere durch Mods, Modpacks, Ressourcen- und Shaderpacks mit großen Bildern und Filtern.
- **Einstellungen als Fenster.** Globale und Instanz-Einstellungen in klaren Bereichen, die sich selbst speichern,
  mit Java pro Minecraft-Version, Speicherverwaltung und Start-Hooks.
- **Der TRS Client für (fast) jede Version.** Fabric ab 1.14.4, Forge ab 1.7.10 und NeoForge ab 1.20.2 bis 26.3 –
  mit neuem Titelbildschirm, mehr HUD-Modulen und PvP-Funktionen.
- **Klarere Update-Fehler.** Schlägt ein Launcher-Update fehl, siehst du warum und kannst es erneut versuchen.

## 0.2.0 – 2026-09-22 – The Project Update | Das Projekt-Update

<!-- banner: accent=#f0c24b motif=/news/0.2.0/banner.png -->
<!-- shots:
/news/0.2.0/content.png | Managing the content of an instance | Die Inhalte einer Instanz verwalten
-->

### English

- **A page for every project.** Every mod and modpack gets its own page with description, gallery, versions and
  dependencies.
- **Images for your instances.** Give every instance its own picture.
- **History and version switching.** See what changed in an instance, switch mods to another version and spot
  downgrades at a glance.
- **More TRS Client.** Now also for NeoForge 1.21.1 and Forge 1.20.1.

### Deutsch

- **Eine Seite für jedes Projekt.** Jede Mod und jedes Modpack hat eine eigene Seite mit Beschreibung, Galerie,
  Versionen und Abhängigkeiten.
- **Bilder für deine Instanzen.** Gib jeder Instanz ihr eigenes Bild.
- **Verlauf und Versionswechsel.** Sieh, was sich in einer Instanz geändert hat, wechsle Mods auf eine andere
  Version und erkenne Downgrades auf einen Blick.
- **Mehr TRS Client.** Jetzt auch für NeoForge 1.21.1 und Forge 1.20.1.

## 0.1.0 – 2026-09-22 – The First Block | Der erste Block

<!-- banner: accent=#6fcf4a motif=/news/0.1.0/banner.png -->
<!-- shots:
/news/0.1.0/start.png | The very first start page | Die allererste Startseite
-->

### English

- **The first TRS Launcher.** Install and start every Minecraft version – Vanilla, Fabric, Quilt, Forge and
  NeoForge – with several Microsoft accounts.
- **Mods and modpacks.** Search, install and update content per instance.
- **Bring what you have.** Import instances from the official launcher, Prism, MultiMC and the Modrinth App, or
  from any folder.
- **Servers, screenshots and updates.** A server list with live status, screenshots per instance and a launcher
  that updates itself.
- **Help when it crashes.** Games run on their own with log files; after a crash the launcher explains what went
  wrong.
- **The TRS Client.** Our own client mod ships with the launcher and is added automatically.

### Deutsch

- **Der erste TRS Launcher.** Installiere und starte jede Minecraft-Version – Vanilla, Fabric, Quilt, Forge und
  NeoForge – mit mehreren Microsoft-Konten.
- **Mods und Modpacks.** Inhalte pro Instanz suchen, installieren und aktualisieren.
- **Nimm mit, was du hast.** Importiere Instanzen aus dem offiziellen Launcher, Prism, MultiMC und der Modrinth App
  oder aus einem beliebigen Ordner.
- **Server, Screenshots und Updates.** Eine Serverliste mit Live-Status, Screenshots pro Instanz und ein Launcher,
  der sich selbst aktualisiert.
- **Hilfe bei Abstürzen.** Spiele laufen eigenständig mit Log-Dateien; nach einem Absturz erklärt der Launcher, was
  schiefging.
- **Der TRS Client.** Unsere eigene Client-Mod kommt mit dem Launcher und wird automatisch hinzugefügt.
