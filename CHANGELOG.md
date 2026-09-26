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

### Deutsch

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
