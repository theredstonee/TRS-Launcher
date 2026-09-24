<div align="center">

[English](README.md) · [**Deutsch**](README.de.md) · [Español](README.es.md)

<img src="docs/logo.png" alt="TRS Launcher" width="96" height="96" />

# TRS Launcher

**Ein schneller, moderner Launcher für Minecraft: Java Edition unter Windows und Linux – mit eigenem In-Game-Client für mehr FPS, HUD und PvP-Funktionen.**

[![Latest release](https://img.shields.io/github/v/release/theredstonee/TRS-Launcher?include_prereleases&sort=semver&label=release&color=e0281e)](https://github.com/theredstonee/TRS-Launcher/releases)
[![Release build](https://img.shields.io/github/actions/workflow/status/theredstonee/TRS-Launcher/release.yml?label=build)](https://github.com/theredstonee/TRS-Launcher/actions/workflows/release.yml)
[![Downloads](https://img.shields.io/github/downloads/theredstonee/TRS-Launcher/total?color=ffb84d)](https://github.com/theredstonee/TRS-Launcher/releases)
[![License: GPL-3.0](https://img.shields.io/badge/license-GPL--3.0-blue)](LICENSE)
<br />
[![Platform: Windows](https://img.shields.io/badge/platform-Windows%2010%2F11-0078D6?logo=windows&logoColor=white)](#windows)
[![Platform: Linux](https://img.shields.io/badge/platform-Linux-FCC624?logo=linux&logoColor=black)](#linux)
[![Tauri 2](https://img.shields.io/badge/Tauri-2-24C8DB?logo=tauri&logoColor=white)](https://tauri.app)
[![Rust](https://img.shields.io/badge/Rust-2024-000000?logo=rust&logoColor=white)](https://www.rust-lang.org)
[![Nuxt 4](https://img.shields.io/badge/Nuxt-4-00DC82?logo=nuxt&logoColor=white)](https://nuxt.com)
[![Minecraft](https://img.shields.io/badge/Minecraft-1.5.2%E2%80%9326.x-62B47A)](#funktionen)

[Download](https://github.com/theredstonee/TRS-Launcher/releases) ·
[Wiki](https://github.com/theredstonee/TRS-Launcher/wiki/de-Home) ·
[Funktionen](#funktionen) ·
[Selbst bauen](#selbst-bauen) ·
[Aufbau](#aufbau)

<img src="docs/screenshot-home.png" alt="Startseite des TRS Launchers" width="860" />

</div>

> [!NOTE]
> Der TRS Launcher ist in früher Entwicklung. Versionen erscheinen als Pre-Releases und aktualisieren sich automatisch.

## Funktionen

**Spielen**
- Jede Minecraft-Version von 1.5.2 bis zur neuesten 26.x, auch Snapshots
- **Fabric, Quilt, Forge und NeoForge** – Forge/NeoForge werden mit unserem eigenen Installer und Processor-Runner installiert
- Java automatisch: Für jede Version wird die passende Mojang-Runtime geladen
- **TRS-Optimierung** – Vanilla-Instanzen starten mit Fabric, dem TRS Client und bewährten Performance-Mods (Sodium, Lithium, FerriteCore, ImmediatelyFast, ModernFix, …). Versionen ohne Fabric nutzen Forge mit dem TRS Client. Pro Instanz abschaltbar für echtes Vanilla
- Abgestimmte JVM-Standardwerte (G1/ZGC je nach Java-Version) und die starke Grafikkarte auf Laptops
- Spiele laufen weiter, wenn du den Launcher schließt, und werden beim nächsten Start wiedergefunden

**TRS Client (In-Game-Mod)**
- Mitgeliefert für **Fabric/Quilt 1.14.4–26.3, Forge 1.7.10–26.3 und NeoForge 1.20.2–26.3**, landet automatisch in jeder passenden Instanz
- In-Game-Menü (rechte Umschalttaste) mit Kacheln, Suche, Kategorien und Einstellungen je Modul, inklusive Farbwähler mit Deckkraft und Chroma
- HUD mit FPS, CPS, Tastenanzeige, Ping, Rüstung, Effekten, Koordinaten und mehr; der HUD-Editor rastet Module am Bildschirm und aneinander ein, mehrere Layouts lassen sich als Profile speichern und per Taste wechseln
- PvP-Anzeigen (Reichweite, Combo, Geschwindigkeit), eigenes Fadenkreuz, Treffer-Farbe, 1.7-Animationen, niedriges Feuer, Block-Umrandung, Hitboxen und kein Schadens-Wackeln
- **Minimap** und **Wegpunkte** je Welt oder Server, mit Entfernung, Lichtsäule und Todespunkt
- Chat-Verbesserungen (Zeitstempel, gleiche Nachrichten zusammenfassen, Kopieren per Strg+Klick), Zoom (V), Fullbright und Freelook
- Startbildschirm und Menü im Redstone-Stil, die Farbschema und Akzentfarbe des Launchers übernehmen
- Fair Play: keine Änderung von Reichweite oder Hitboxen, kein Auto-Klicken; die Minimap zeigt nur geladene Chunks, ohne Höhlenansicht
- Updates über einen eigenen, signierten Kanal – ohne neues Launcher-Release

**Inhalte**
- Mods, Modpacks, Resourcepacks, Datenpakete und Shader von **Modrinth** suchen und installieren, Abhängigkeiten inklusive – mit Projektseite für Beschreibung, Galerie, Versionen und Abhängigkeiten
- Beliebige Version wählen, nach Updates suchen, Changelogs lesen, Mods pro Instanz an- und ausschalten
- Instanzen aus dem **offiziellen Launcher, Modrinth App, CurseForge, Prism Launcher, MultiMC** oder einem beliebigen Ordner importieren
- Jede Instanz als **`.mrpack`** exportieren – Mods, die es auf Modrinth gibt, werden verlinkt, alles andere kommt als Overrides ins Paket – und Paketdateien wieder importieren

**TRS-Dienste (optional)**
- **TRS-Umhänge**: aus der TRS-Umhang-Sammlung wählen (einige davon animiert), Umhänge per Code freischalten oder eigene hochladen – das Team prüft sie, bevor andere sie sehen
- **Freunde**: Freundschaftsanfragen, Blockieren, wer online ist und was gespielt wird, und mit einem Klick auf den Server nachkommen
- Alles ist **aus, bis du zustimmst**; siehe [PRIVACY.de.md](PRIVACY.de.md#trs-dienste)

**Alles andere**
- **Redstone-Look**: eine lebendige Redstone-Schaltung auf der Startseite und hinter jeder Seite, eine Lampe als Spielen-Knopf, Farbschema Dunkel, OLED, Hell oder System und fünf Akzentfarben
- Mehrere Microsoft-Accounts, Wechsel über die Titelleiste (Tokens mit Windows DPAPI verschlüsselt, unter Linux mit einem Schlüssel aus dem Schlüsselbund)
- **Hintergrund-Aufgaben**: Installationen und Downloads laufen weiter, während du den Launcher benutzt – im Aufgaben-Panel pausieren, fortsetzen oder abbrechen, dazu ein Verlauf der fertigen Aufgaben
- Serverliste mit Live-Spielerzahl und Ping, Beitreten mit einem Klick
- **Skins & Umhänge** mit 3D-Vorschau: eigene Skin-Sammlung, Modell (klassisch/schmal) wechseln, jeden eigenen Mojang-Umhang wählen und alle Änderungen gesammelt anwenden
- **Screenshot-Galerie** über alle Instanzen mit Vollbildansicht, Kopieren in die Zwischenablage und Papierkorb
- **News** auf der Startseite: Minecraft-Patchnotes, Mojang-News, angesagte Modrinth-Projekte und Launcher-Versionen
- Live-Spiel-Log mit Filtern, Absturz-Diagnose, Dateireparatur und Log-Teilen über mclo.gs (Tokens geschwärzt)
- Welten, Spielzeit, Instanz-Banner und Duplizieren, eine Befehlspalette (Strg+K)
- **Stille Updates**: neue Versionen sind signiert, laden im Hintergrund und werden installiert, wenn du über die Titelleiste neu startest; laufende Spiele laufen weiter

## Installation

### Windows

1. Lade `TRS-Launcher_<version>_x64-setup.exe` von der [Release-Seite](https://github.com/theredstonee/TRS-Launcher/releases) herunter.
2. Starte die Datei. Der Launcher wird nur für deinen Windows-Benutzer installiert und braucht keine Administratorrechte.
3. Melde dich mit dem Microsoft-Konto an, das Minecraft besitzt.

> [!TIP]
> Der Installer ist noch nicht code-signiert, deshalb zeigt Windows SmartScreen eventuell „Der Computer wurde durch Windows geschützt“. Wähle **Weitere Informationen → Trotzdem ausführen**.

Deine Daten liegen in `%APPDATA%\TRS-Launcher`. Schritt-für-Schritt-Anleitungen findest du im [Wiki](https://github.com/theredstonee/TRS-Launcher/wiki/de-Home).

### Linux

Zu jedem Release gibt es ein **AppImage**, ein **.deb** und ein **.rpm** für x86_64 (gebaut unter Ubuntu 22.04 – jede Distribution mit glibc 2.35 oder neuer funktioniert). Java brauchst du nicht, der Launcher lädt die passende Mojang-Runtime selbst.

| Distribution | Installation |
|---|---|
| **Arch Linux**, Manjaro, EndeavourOS | `yay -S trs-launcher-bin` (oder `paru -S trs-launcher-bin`) aus dem AUR |
| **Debian, Ubuntu**, Linux Mint, Pop!_OS | `sudo apt install ./TRS-Launcher_<version>_amd64.deb` |
| **Fedora**, openSUSE | `sudo dnf install ./TRS-Launcher-<version>-1.x86_64.rpm` (openSUSE: `sudo zypper install …`) |
| **Jede Distribution** | `chmod +x TRS-Launcher_<version>_amd64.AppImage && ./TRS-Launcher_<version>_amd64.AppImage` |
| **Flatpak** | `flatpak install flathub dev.theredstonee.trslauncher` (sobald er auf Flathub veröffentlicht ist) |

- **Updates:** Das AppImage aktualisiert sich selbst wie die Windows-Version. .deb, .rpm, AUR und Flatpak aktualisiert deine Paketverwaltung; der Launcher sagt nur Bescheid, wenn eine neue Version da ist.
- Deine Daten liegen in `~/.local/share/TRS-Launcher` (Flatpak: `~/.var/app/dev.theredstonee.trslauncher/data/TRS-Launcher`).
- Der Anmeldeschlüssel liegt im Schlüsselbund des Systems (GNOME Keyring, KWallet, KeePassXC …). Ohne Schlüsselbund liegt er in einer Datei, die nur dein Benutzer lesen kann – die Einstellungen weisen dann darauf hin.
- Minecraft 1.12.2 und älter (LWJGL 2) braucht das Programm `xrandr`: `xorg-xrandr` (Arch), `x11-xserver-utils` (Debian/Ubuntu), `xrandr` (Fedora).
- Läuft unter Wayland und X11 (das Spiel selbst über XWayland). Mit dem proprietären NVIDIA-Treiber schaltet der Launcher den DMA-BUF-Renderer von WebKit ab, damit das Fenster nicht leer bleibt.
- Das AppImage braucht FUSE 2 (`libfuse2`/`fuse2`); ohne FUSE mit `--appimage-extract-and-run` starten.
- Unter Linux (noch) nicht vorhanden: die Windows-Firewall-Freigabe (nicht nötig) und Clip-Aufnahmen. Für ARM64 gibt es keine Mojang-Java-Runtime – dort in den Einstellungen ein eigenes Java angeben.

## Fair Play

- Die Anmeldung funktioniert **nur mit einem rechtmäßig erworbenen Microsoft-Konto** über den offiziellen OAuth-2.0-Weg von Microsoft (Authorization Code + PKCE, Device Code als Rückfall).
- Es gibt **keinen Offline- oder „Cracked“-Modus**, und Besitzprüfungen werden nie umgangen.
- Spieldateien werden **nicht weiterverteilt**. Sie kommen direkt von Mojangs offiziellen Servern auf deinen PC.
- Dein Passwort läuft nie durch den Launcher. Tokens bleiben verschlüsselt auf deinem PC und werden nie an fremde Server geschickt.

## Selbst bauen

**Voraussetzungen:** Node.js 22+ mit pnpm, Rust (stable, MSVC-Toolchain), die Visual Studio Build Tools („Desktopentwicklung mit C++“) und WebView2 (unter Windows 10/11 vorinstalliert).

**Unter Linux** brauchst du Node.js 22+ mit pnpm, Rust (stable) und die Entwicklungspakete von WebKitGTK/GTK:

```sh
# Debian/Ubuntu
sudo apt install build-essential curl file pkg-config libssl-dev libwebkit2gtk-4.1-dev libgtk-3-dev libayatana-appindicator3-dev librsvg2-dev libxdo-dev
# Arch
sudo pacman -S --needed base-devel webkit2gtk-4.1 gtk3 libayatana-appindicator librsvg openssl xdotool
# Fedora
sudo dnf install gcc-c++ openssl-devel webkit2gtk4.1-devel gtk3-devel libappindicator-gtk3-devel librsvg2-devel libxdo-devel
```

`pnpm tauri build --bundles appimage,deb,rpm` baut die Linux-Pakete. Rezepte für AUR und Flatpak liegen in [`packaging/`](packaging/).

```sh
pnpm install
pnpm app:dev      # Nuxt-Dev-Server + Tauri-Fenster mit Hot Reload
```

| Befehl | Zweck |
|---|---|
| `pnpm app:build` | Release-Build und NSIS-Installer (`src-tauri/target/release/bundle`) |
| `pnpm typecheck` | TypeScript/Vue prüfen |
| `pnpm test` | Frontend-Tests (Vitest) |
| `cargo test --workspace` (in `src-tauri`) | Rust-Tests |
| `cargo clippy --workspace --all-targets` (in `src-tauri`) | Rust-Lints |
| `./gradlew collectLauncherJars` (in `client-mod/…`) | TRS-Client-Jars für den Launcher bauen |

Mit `TRS_LAUNCHER_HOME` nutzt du ein anderes Datenverzeichnis – praktisch zum Testen. Die Start-Pipeline lässt sich auch ohne Oberfläche testen:

```sh
cargo run -p trs-core --example launch -- <daten-ordner> 1.21.1 [vanilla|fabric|quilt|forge|neoforge] [sekunden]
```

Entwicklungs-Builds ohne angemeldeten Account starten das Spiel im offiziellen **Demo-Modus**. Release-Builds verlangen immer einen Account, der das Spiel besitzt.

### Releases

Ein Tag wie `v0.2.0` startet [`release.yml`](.github/workflows/release.yml). Der Workflow baut und signiert den Windows-Installer sowie AppImage, .deb und .rpm für Linux, veröffentlicht ein Release (mit fertigem AUR-`PKGBUILD`) und aktualisiert – sobald beide Plattformen fertig sind – den Update-Kanal, den der eingebaute Updater abfragt.

### TRS-Client-Updates

Der In-Game-TRS-Client hat einen eigenen Update-Kanal und lässt sich deshalb ohne Launcher-Release aktualisieren. Das GitHub-Release `client-mod` enthält `client-mod.json` (Mod-Version plus jeden Build mit SHA-256 und Größe), dessen Minisign-Signatur `client-mod.json.sig` und alle Jars. Der Launcher prüft den Kanal beim Start und vor einem Spielstart höchstens alle 30 Minuten (kurzes Timeout; offline nutzt er, was er hat), akzeptiert nur Manifeste, die mit dem Updater-Schlüssel signiert und neuer als die mitgelieferte Version sind, und lädt nur den Jar, den die gestartete Instanz braucht, nach `<daten>/client-mod/<version>/` – geprüft gegen das Manifest. Bei jedem Fehler fällt er auf den mitgelieferten Jar zurück.

```sh
node scripts/publish-client-mod.mjs --bump patch   # mod_version erhöhen, danach die Jars neu bauen (collectLauncherJars)
node scripts/publish-client-mod.mjs --dry-run      # client-mod/dist zusammenführen, signieren, prüfen, src-tauri/resources/client-mod aktualisieren
node scripts/publish-client-mod.mjs                # dasselbe, danach ins Release client-mod hochladen (braucht gh)
```

Das Skript signiert mit `%USERPROFILE%\.tauri\trs-launcher.key` (Passwort aus `trs-launcher.key.password`) und prüft die Signatur vor dem Hochladen gegen `plugins.updater.pubkey`. Ein erzeugtes Manifest mit der Prüfung des Launchers selbst testen: `TRS_CLIENT_MOD_CHANNEL_DIR=client-mod/dist/channel TRS_CLIENT_MOD_DIST=client-mod/dist cargo test -p trs-core published_manifest -- --ignored`.

## Aufbau

```
app/                    Nuxt-4-Oberfläche (SPA, kein SSR)
src-tauri/
  src/                  Tauri-App: Commands, Aufgaben-Registry, Fehler-Mapping, Plugins
  crates/core/          trs-core: der oberflächenunabhängige Launcher-Kern
    meta/ prepare.rs    Versions-Metadaten, Bibliotheken, Natives, Assets
    forge.rs loaders.rs Forge/NeoForge-Installer, Fabric/Quilt-Profile
    launch.rs process.rs  Argumente, losgelöste Spielprozesse, Logs, Absturz-Diagnose
    auth/               Microsoft → Xbox Live → Minecraft, verschlüsselter Account-Speicher
    modrinth.rs modpack.rs content.rs  Modrinth, Modpacks, Inhalte je Instanz
    modpack_export.rs   .mrpack-Export (Modrinth-Abgleich per Hash, Overrides)
    skins.rs skin_sync.rs news.rs screenshots.rs  Minecraft-Profil/Skins, News-Cache, Screenshot-Galerie
    task.rs task_history.rs  Abbrechbare, pausierbare Hintergrund-Aufgaben und ihr Verlauf
    trs_api/            Client für die optionalen TRS-Dienste (Umhänge, Freunde, Online-Status)
    client_mod.rs client_mod_update.rs  Mitgelieferte TRS-Client-Builds und der signierte Update-Kanal
    import.rs servers.rs boost.rs hooks.rs sync.rs
  resources/client-mod/ Mitgelieferte TRS-Client-Builds + builds.json (Manifest mit Version + Prüfsummen)
client-mod/             TRS Client: gemeinsamer Kern + Fabric 1.14.4–26.3, Forge 1.7.10–26.3, NeoForge 1.20.2–26.3
```

Grundsätze:

- **Die Logik steckt in `trs-core`**, die Tauri-Schicht bleibt dünn. Der Kern prüft jede Eingabe selbst.
- **Das Webview bekommt keine Dateisystem-, Netzwerk- oder Shell-Rechte.** Alles läuft über eigene Commands, unter einer strengen CSP.
- **Zur Oberfläche gelangen nur eine feste Fehlerart und eine lesbare Meldung.** Details landen im Log.

## Code signing policy

Windows-Releases werden signiert, damit Windows prüfen kann, wer sie veröffentlicht hat.

- Free code signing provided by [SignPath.io](https://about.signpath.io/), certificate by [SignPath Foundation](https://signpath.org/).
- Die Builds entstehen aus diesem Repository über [GitHub Actions](.github/workflows/release.yml). Jedes Release muss vor dem Signieren von Hand freigegeben werden.

**Team-Rollen**

| Rolle | Mitglieder |
|---|---|
| Committer und Reviewer | [theredstonee](https://github.com/theredstonee) |
| Freigabe (Approver) | [theredstonee](https://github.com/theredstonee) |

**Datenschutz:** Dieses Programm überträgt keine Informationen an andere vernetzte Systeme, es sei denn, der Benutzer oder die Person, die es installiert oder betreibt, fordert dies ausdrücklich an. Die optionalen TRS-Dienste (Umhänge, Freunde, Online-Status) verbinden sich erst, nachdem du im Launcher zugestimmt hast. Welche Dienste der Launcher wann kontaktiert, steht in der [PRIVACY.de.md](PRIVACY.de.md) ([English](PRIVACY.md) · [Español](PRIVACY.es.md)).

## Danksagung

Teile der Prozessverwaltung sind aus [Polyfrost OneLauncher](https://github.com/Polyfrost/OneLauncher) (GPL-3.0-only) übernommen. Spiel-Metadaten stammen von Mojang, Mod-Daten von der [Modrinth API](https://docs.modrinth.com/).

## Lizenz

Der TRS Launcher steht unter der [GNU General Public License v3.0](LICENSE).

<sub>Der TRS Launcher ist kein offizielles Minecraft-Produkt. Er ist nicht von Mojang oder Microsoft genehmigt und steht in keiner Verbindung zu ihnen.</sub>
