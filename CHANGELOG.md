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
-->

## Unreleased

### English

- **The skin preview shows your TRS cape.** After a restart the 3D preview now shows the TRS cape you wear – the way
  other TRS players see you in game – instead of your Mojang cape. Click a Mojang cape to look at that one instead.
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
  switch them off in the TRS menu under Performance (takes effect at the next start through the TRS Launcher).

### Deutsch

- **Die Skin-Vorschau zeigt deinen TRS-Umhang.** Nach einem Neustart zeigt die 3D-Vorschau jetzt den TRS-Umhang, den du
  trägst – so, wie andere TRS-Spieler dich im Spiel sehen – statt deines Mojang-Umhangs. Klick einen Mojang-Umhang
  an, um den anzusehen.
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
  Launcher).

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

### English

- **TRS Client 0.4.0 – show yourself.** Hold **G** for the emote wheel and wave, dance or cheer – other TRS players
  see it. New comfort keys: smooth **zoom** (V), **freelook** (Left Alt) and toggle sprint/sneak.
- **Redstone tools.** See the signal strength over every piece of dust, a redstone overlay (F6) and a clock
  meter for your circuits – right in the game.

![The emote wheel in the TRS Client](/news/0.5.0/emote-wheel.png)

![Redstone tools: signal strength over every dust](/news/0.5.0/redstone-overlay.png)

![Cape physics with a live preview](/news/0.5.0/cape-physics.png)

![Smooth zoom](/news/0.5.0/zoom.png)

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

![Das Emote-Rad im TRS Client](/news/0.5.0/emote-wheel.png)

![Redstone-Werkzeuge: Signalstärke über jedem Staub](/news/0.5.0/redstone-overlay.png)

![Umhang-Physik mit Live-Vorschau](/news/0.5.0/cape-physics.png)

![Weicher Zoom](/news/0.5.0/zoom.png)

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

![The tasks panel in the title bar while a modpack installs](/news/0.4.3/tasks.png)

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

![Die Aufgabenleiste in der Titelleiste, während ein Modpack installiert wird](/news/0.4.3/tasks.png)

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

### English

- **A redstone title screen.** The TRS Client greets you with a title screen in the launcher’s redstone look –
  glowing dust, lamps and a torch – in every Minecraft version.
- **The TRS menu in the same style.** Buttons, panels and switches of the TRS menu match the launcher.
- **Smoother menus on 1.20 to 1.21.1.** TRS screens are drawn in one go and stay fluid.

![The TRS Client title screen](/news/0.4.1/title-screen.png)

### Deutsch

- **Ein Titelbildschirm aus Redstone.** Der TRS Client begrüßt dich mit einem Titelbildschirm im Redstone-Look des
  Launchers – glühender Staub, Lampen und eine Fackel – in jeder Minecraft-Version.
- **Das TRS-Menü im selben Stil.** Knöpfe, Flächen und Schalter des TRS-Menüs passen zum Launcher.
- **Flüssigere Menüs auf 1.20 bis 1.21.1.** TRS-Fenster werden in einem Rutsch gezeichnet und bleiben flüssig.

![Der Titelbildschirm des TRS Clients](/news/0.4.1/title-screen.png)

## 0.4.0 – 2026-09-23 – The Redstone Update | Das Redstone-Update

<!-- banner: accent=#ff5a4d motif=/news/0.4.0/banner.png -->

### English

- **A start page that lives.** A real redstone circuit runs across the top: clocks, pistons, lamps and flickering
  torches. The main line leads to the play button and charges up while your game starts – when it runs, the lamp
  glows.
- **News as a magazine.** One lead story with a big picture, the rest next to it.
- **Your account in the title bar.** Switch accounts right from the top of the window.
- **Skins without waiting.** Edit skins locally; the launcher sends them to Mojang in the background and bundles
  quick changes into one.
- **Calmer notifications.** Identical messages are merged and only a few are shown at once.

![The new start page with the redstone circuit](/news/0.4.0/start.png)

![While the game runs, the lamp glows](/news/0.4.0/running.png)

### Deutsch

- **Eine Startseite, die lebt.** Oben läuft eine echte Redstone-Schaltung: Takte, Kolben, Lampen und flackernde
  Fackeln. Die Hauptleitung führt zum Spielen-Knopf und lädt sich beim Spielstart auf – läuft das Spiel, leuchtet
  die Lampe.
- **Neuigkeiten als Magazin.** Eine Titelgeschichte mit großem Bild, der Rest daneben.
- **Dein Konto in der Titelleiste.** Wechsle das Konto direkt oben im Fenster.
- **Skins ohne Warten.** Bearbeite Skins lokal; der Launcher schickt sie im Hintergrund an Mojang und fasst schnelle
  Änderungen zusammen.
- **Ruhigere Meldungen.** Gleiche Meldungen werden zusammengefasst, und es sind nur wenige gleichzeitig zu sehen.

![Die neue Startseite mit der Redstone-Schaltung](/news/0.4.0/start.png)

![Während das Spiel läuft, leuchtet die Lampe](/news/0.4.0/running.png)

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

![The TRS menu in game](/news/0.3.0/trs-menu.png)

![Moving the HUD with the HUD editor](/news/0.3.0/hud-editor.png)

![Skins & capes with the 3D preview](/news/0.3.0/skins.png)

![The screenshot gallery](/news/0.3.0/gallery.png)

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

![Das TRS-Menü im Spiel](/news/0.3.0/trs-menu.png)

![Das HUD mit dem HUD-Editor verschieben](/news/0.3.0/hud-editor.png)

![Skins & Umhänge mit der 3D-Vorschau](/news/0.3.0/skins.png)

![Die Screenshot-Galerie](/news/0.3.0/gallery.png)

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

![The library with your own groups](/news/0.2.1/library.png)

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

![Die Bibliothek mit eigenen Gruppen](/news/0.2.1/library.png)

## 0.2.0 – 2026-09-22 – The Project Update | Das Projekt-Update

<!-- banner: accent=#f0c24b motif=/news/0.2.0/banner.png -->

### English

- **A page for every project.** Every mod and modpack gets its own page with description, gallery, versions and
  dependencies.
- **Images for your instances.** Give every instance its own picture.
- **History and version switching.** See what changed in an instance, switch mods to another version and spot
  downgrades at a glance.
- **More TRS Client.** Now also for NeoForge 1.21.1 and Forge 1.20.1.

![Managing the content of an instance](/news/0.2.0/content.png)

### Deutsch

- **Eine Seite für jedes Projekt.** Jede Mod und jedes Modpack hat eine eigene Seite mit Beschreibung, Galerie,
  Versionen und Abhängigkeiten.
- **Bilder für deine Instanzen.** Gib jeder Instanz ihr eigenes Bild.
- **Verlauf und Versionswechsel.** Sieh, was sich in einer Instanz geändert hat, wechsle Mods auf eine andere
  Version und erkenne Downgrades auf einen Blick.
- **Mehr TRS Client.** Jetzt auch für NeoForge 1.21.1 und Forge 1.20.1.

![Die Inhalte einer Instanz verwalten](/news/0.2.0/content.png)

## 0.1.0 – 2026-09-22 – The First Block | Der erste Block

<!-- banner: accent=#6fcf4a motif=/news/0.1.0/banner.png -->

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

![The very first start page](/news/0.1.0/start.png)

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

![Die allererste Startseite](/news/0.1.0/start.png)
