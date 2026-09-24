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

### Deutsch

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
