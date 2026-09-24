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
-->

## Unreleased

### English

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

### Deutsch

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

## 0.4.3 – 2026-09-24

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
