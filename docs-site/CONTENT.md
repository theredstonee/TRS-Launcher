# Inhalte der Doku schreiben

Regeln und exakte Syntax für alle, die Seiten unter `docs-site/content/` schreiben. Alles hier ist mit der
installierten Version geprüft (Docus 5.13, Nuxt UI 4.11, Nuxt Content 3.16) – bitte nichts anderes verwenden.
Aufbau, Build und Sicherheit: [README.md](README.md).

## Ordner und Adressen

```
content/
├─ en/                        Englisch  → https://trs-launcher.theredstonee.de/docs/en/…
│  ├─ index.md                Startseite der Sprache (Hero + Karten) – nicht ändern ohne Absprache
│  ├─ 1.getting-started/
│  │  ├─ .navigation.yml      Titel + Icon des Bereichs (schon angelegt)
│  │  ├─ index.md             Übersicht des Bereichs (Karten entstehen automatisch) – nicht ändern
│  │  ├─ 1.installation.md    → /docs/en/getting-started/installation
│  │  └─ 2.first-start.md     → /docs/en/getting-started/first-start
│  ├─ 2.launcher/
│  ├─ 3.client/
│  ├─ 4.help/
│  └─ 5.developers/
├─ de/                        Deutsch   → /docs/de/…   (gleiche Ordner und Dateinamen!)
└─ es/                        Spanisch  → /docs/es/…
```

- Seiten heißen `NN.slug.md` mit **zweistelliger** Nummer (`01.`, `02.` … `13.`): Nuxt Content sortiert die Pfade als Text, `10.` landet sonst vor `2.`. Die Nummer bestimmt nur die Reihenfolge und fällt in der Adresse weg. Slugs: klein,
  Englisch, Bindestriche (`crash-helper`, nicht `Crash_Helper`).
- **Gleicher Dateiname in allen Sprachen** (`de/4.help/3.crash-helper.md` ↔ `en/4.help/3.crash-helper.md`). Darüber
  finden sich die Sprachfassungen (hreflang, Sprachumschalter, Sitemap). Fehlt eine Sprache, ist das erlaubt – der
  Umschalter führt dann auf die Startseite der Sprache.
- Unterordner in einem Bereich sind möglich (`2.launcher/3.modpacks/1.import.md`), brauchen dann aber eine eigene
  `.navigation.yml` (`title:` + `icon:`). Kein `index.md` in Unterordnern anlegen.
- Nur `.md`-Dateien. Kein HTML, kein `<script>`, keine `<img>`/`<iframe>` – die CSP würde Inline-Code blockieren.

## Frontmatter

```yaml
---
title: "Install TRS Launcher"
description: "Download the installer for Windows or Linux, check the signature and start TRS Launcher for the first time."
navigation:
  icon: i-lucide-download
---
```

- `title` (Pflicht) = Überschrift der Seite und Titel im Browser/Google. Kurz, ≤ 60 Zeichen.
- `description` (Pflicht, **≤ 160 Zeichen**) = Untertitel, Meta-Beschreibung, Vorschaubild-Text, Suchtreffer.
- `navigation.icon` (optional) = Icon in der Seitenleiste.
- **Werte immer in `"…"` setzen.** Ein Doppelpunkt mit Leerzeichen (`mod: FPS`) macht YAML sonst zu einem Objekt und
  der Build bricht beim Vorschaubild ab.
- Im Text selbst **keine `# H1`** – die Überschrift kommt aus `title`. Gliedern mit `##` und `###` (die erscheinen
  rechts in „Auf dieser Seite“). `h1`/`h2` werden in der Pixel-Schrift gesetzt.
- Keine Namen anderer Launcher oder Clients in `title`/`description` (SEO-Regel der Website).

## Links

- Andere Doku-Seiten: `[Instanzen](/en/launcher/instances)` – **ohne** `/docs` davor (kommt automatisch dazu), mit
  Sprachkürzel, ohne `.md`, ohne Nummern. In der deutschen Fassung `/de/…`.
- Anker: `[Speicher](/en/help/performance#memory)` – Anker = Überschrift klein mit Bindestrichen.
- Website: volle Adresse, z. B. `[Download](https://trs-launcher.theredstonee.de/download)` (Deutsch mit `?lang=de`).
- Extern: volle `https://`-Adresse; öffnet automatisch in neuem Tab.

## Bilder / Screenshots

- Dateien unter `docs-site/public/shots/<bereich>/<name>.png` (Bereich = `getting-started`, `launcher`, `client`,
  `help`, `developers`). Kleine Dateinamen mit Bindestrichen. PNG oder WebP, max. ca. 1600 px breit, < 500 KB.
- Im Markdown **mit Alt-Text**: `![Die Bibliothek mit drei Instanzen](/shots/launcher/library.png)` – ohne `/docs`;
  der Basis-Pfad wird automatisch ergänzt. Bilder lassen sich per Klick vergrößern.
- Sprachabhängige Screenshots: `library.de.png` neben `library.png` und im deutschen Text darauf verweisen.
- Noch kein Bild da? `![Kommt bald](/shots/placeholder.png)`. Fehlende Bilder brechen den Build nicht, stehen aber als
  Warnung im Log (`[docs] Warnung: Bild fehlt …`).

## Komponenten (MDC) – exakte Syntax

Nur diese Komponenten verwenden. Blöcke beginnen mit `::name` und enden mit `::`; verschachtelte mit einem `:` mehr.

### Hinweise

```md
::note
Allgemeiner Hinweis mit **fett** und [Link](/en/launcher).
::

::tip
Tipp: Mit :kbd{value="F6"} öffnest du das Redstone-Overlay.
::

::warning
Vorsicht: Das löscht die Welt.
::

::caution
Gefahr: Nicht rückgängig zu machen.
::
```

### Schritte

```md
::steps{level="3"}
### Installer herunterladen
Lade die Datei von der [Download-Seite](https://trs-launcher.theredstonee.de/download) herunter.

### Installieren
Doppelklick auf die Datei.

### Starten
Öffne den TRS Launcher über das Startmenü.
::
```

Jeder Schritt ist eine `###`-Überschrift; `level="3"` immer mit angeben.

### Tabs

```md
::tabs
:::tabs-item{label="Windows" icon="i-simple-icons-windows"}
Text für Windows.
:::

:::tabs-item{label="Linux" icon="i-simple-icons-linux"}
Text für Linux.
:::
::
```

### Code und Code-Gruppe

````md
```bash
yay -S trs-launcher-bin
```

::code-group
```bash [AppImage]
chmod +x TRS-Launcher.AppImage
```

```bash [AUR]
yay -S trs-launcher-bin
```
::
````

Sprachen mit Farbe: `bash`, `shell`, `json`, `js`, `ts`, `yaml`, `md`, `diff`, `html`, `css`, `vue`. Der Name in
`[…]` ist der Tab-Titel.

### Karten

```md
::card-group
:::card{title="Instanzen" icon="i-lucide-boxes" to="/en/launcher/instances"}
Jede Instanz hat eigene Welten, Mods und Einstellungen.
:::

:::card{title="Discord" icon="i-simple-icons-discord" to="https://dc.theredstonee.de"}
Fragen an die Community.
:::
::
```

### Tasten und Abzeichen (im Fließtext)

```md
Drücke :kbd{value="ctrl"} + :kbd{value="K"} für die Suche, :kbd{value="F6"} für das Overlay.
Seit :badge{label="0.14.0" color="primary"} dabei, noch :badge{label="Beta" color="warning" variant="subtle"}.
```

- `:kbd{value="…"}`: einzelne Taste. Sonderwerte: `ctrl`, `meta` (⌘ auf Mac, Strg sonst), `shift`, `alt`, `enter`,
  `escape`; sonst der Text (`F6`, `K`, `G`).
- `:badge{label="…" color="…"}`: `color` = `primary` (Rot), `secondary` (Lampen-Gelb), `success`, `warning`, `error`,
  `neutral`; optional `variant="subtle"`.

### Tabellen

Normale Markdown-Tabellen:

```md
| Taste | Aktion |
|---|---|
| F6 | Redstone-Overlay |
```

## Icons

Nur **Lucide** (`i-lucide-<name>`, https://lucide.dev/icons) und **Simple Icons** für Marken (`i-simple-icons-github`,
`-discord`, `-windows`, `-linux`, `-modrinth`, `-curseforge`). Die Icons werden beim Build aus den Inhalten gesammelt und
**lokal gebündelt** – es gibt keinen Abruf von fremden Servern. Ein Tippfehler im Namen zeigt einfach kein Icon; der
Name muss auf lucide.dev existieren.

## Inhalt

- Sprache: Du-Form im Deutschen, locker und klar; Englisch einfach; Spanisch neutral (tú).
- Nur beschreiben, was es wirklich gibt (Quelle: `CHANGELOG.md` auf `main`, Website `/features`, Code). Keine erfundenen
  Funktionen, keine Versprechen.
- Name: „TRS Launcher“, „TRS Client“, Entwickler „TheRedstonee“. Hinweis „kein offizielles Minecraft-Produkt“ steht
  schon in der Fußzeile.
- Die englischen Seiten landen automatisch in `/docs/llms.txt` und `/docs/llms-full.txt` (für Sprachmodelle).

## Prüfen

```bash
cd docs-site
npm run dev        # http://127.0.0.1:3000/docs/en – Änderungen live
npm run generate   # muss grün sein; Warnungen zu fehlenden Bildern lesen
```
