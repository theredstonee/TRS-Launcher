# TRS Launcher – Dokumentation

Die Doku unter **https://trs-launcher.theredstonee.de/docs** (Englisch, Deutsch, Spanisch). Gebaut mit
[Docus](https://docus.dev) 5 (Nuxt-4-Layer, Nuxt UI, Nuxt Content), **statisch erzeugt** und von der Website-App
(`../api`) mit ausgeliefert – es gibt keinen eigenen Server und kein eigenes nginx-Routing.

Inhalte schreiben: **[CONTENT.md](CONTENT.md)** (Ordner, Frontmatter, Komponenten-Syntax, Bilder).

## Befehle

```bash
cd docs-site
npm ci                 # bzw. npm i
npm run dev            # http://localhost:3000/docs/en (Basis-Pfad /docs/ wie live)
npm run generate       # statische Ausgabe → .output/public + .output/docs-meta (siehe unten)
npm run preview        # Ausgabe mit derselben CSP wie live: http://127.0.0.1:3480/docs/en
```

Node ≥ 24.11. `npm run dev`/`generate` legen vorher bei Bedarf leere Platzhalter in `../.nuxt/` an
(`scripts/ensure-parent-tsconfig.mjs`, siehe Fallen).

## Wie /docs ausgeliefert wird

1. `npm run generate` = `nuxt generate` (Nitro-Prerender aller Seiten, `app.baseURL: '/docs/'`) + `scripts/finalize.mjs`:
   - entfernt `200.html`, `404.html` und Docus' eigene `sitemap.xml` (falsche Adressen ohne `/docs`),
   - entfernt `onerror`-Attribute von Nuxt Image und hreflang-Links auf Sprachfassungen, die es nicht gibt,
   - bricht ab bei Symlinks/Hardlinks, fehlenden Seiten, Inline-Event-Handlern, fremden Hosts (Iconify-API, Google
     Fonts) in HTML/CSS/JSON oder fehlender `llms.txt`,
   - schreibt `.output/docs-meta/csp.json` (SHA-256 aller Inline-Skripte) und `.output/docs-meta/routes.json`
     (alle Seiten mit Sprache, Schlüssel und lastmod aus Git).
2. In `api/` bindet `modules/docs.ts` das beim `nuxt build` ein:
   - baut die Doku selbst, wenn sie fehlt oder eine Quelldatei neuer ist (`npm ci` falls nötig, dann `npm run
     generate`). `TRS_DOCS_BUILD=skip` überspringt das, `TRS_DOCS_BUILD=force` erzwingt es.
   - `.output/public` → Nitro-Public-Assets unter `/docs` (echte Dateien in `api/.output/public/docs`, keine Links),
   - CSP aus `csp.json` als Route-Regel für `/docs/**`,
   - `routes.json` als Server-Asset → Website-Sitemap (`/sitemap.xml`) mit hreflang je Seite.
3. `api/server/middleware/00.security.ts`: `/docs` und `/docs/` leiten per 302 auf `/docs/<sprache>` (Cookie `trs_lang`
   der Website, sonst `Accept-Language`, sonst Englisch). Fehlende Dateien unter `/docs/` zeigen die 404-Seite der Website.

Deploy: nichts extra – `npx nuxt build` in `api/` liefert die Doku mit (Voraussetzung: `docs-site/` liegt daneben und
`npm` ist verfügbar). Danach wie gewohnt `.output` packen (`cp -rL`, `--hard-dereference`).

## Sicherheit

- **CSP ohne `'unsafe-inline'` für Skripte:** statische Seiten können keine Nonce haben. Stattdessen stehen die Hashes der
  wenigen Inline-Skripte (Nuxt-Konfiguration, Farbschema, Import-Map, Site-Config je Sprache) im Header:
  `script-src 'self' 'wasm-unsafe-eval' 'sha256-…'`. `'wasm-unsafe-eval'` braucht die Suche (SQLite als WebAssembly im
  Browser). Sonst wie die Website: `default-src 'self'`, Bilder nur zusätzlich von raw.githubusercontent.com und
  textures.minecraft.net, `frame-ancestors 'none'`, `object-src 'none'`, `base-uri 'none'`.
- HSTS, `X-Frame-Options: DENY`, `nosniff`, `Referrer-Policy` kommen von nuxt-security (Route-Regel `/**` gilt auch für
  statische Dateien), dazu `Permissions-Policy`, CORP/COOP aus `modules/docs.ts`.
- Keine externen Skripte, Schriften oder Icons: Pixel-Schrift aus `@fontsource-variable/pixelify-sans`, Icons lokal
  gebündelt (`build/icons.ts` sammelt alle `i-lucide-…`/`i-simple-icons-…` aus Inhalten, App, Docus und Nuxt UI;
  `icon.provider: 'none'`), `ui.fonts: false`, keine Telemetrie.
- Aus: KI-Assistent (`docus.assistant.enabled: false`), MCP-Server (`mcp.enabled: false`), „Edit this page“/GitHub-Links
  (`github: false` in `app/app.config.ts`), Nuxt Studio, Links zu KI-Diensten im „Seite kopieren“-Menü.

## SEO

- Kanonische Adressen `https://trs-launcher.theredstonee.de/docs/<lang>/<pfad>`, hreflang für jede vorhandene Sprache +
  `x-default` (Englisch), `og:locale` wie auf der Website. `app/plugins/seo-base.ts` ergänzt den Basis-Pfad `/docs` in
  canonical, hreflang, `og:url` und JSON-LD (Docus baut die Adressen sonst ohne ihn).
- JSON-LD von Docus: `Article` + `BreadcrumbList` je Seite, `WebSite` auf der Startseite.
- Vorschaubilder (Open Graph, 1200×600) je Seite, beim Generieren als PNG gerendert (nuxt-og-image + Takumi) im TRS-Stil:
  `app/components/OgImage/Docs.takumi.vue` und `Landing.takumi.vue`.
- `/docs/llms.txt` und `/docs/llms-full.txt` (nuxt-llms, **nur Englisch**, je Bereich ein Abschnitt) + `/docs/raw/…md`
  (Markdown je Seite, `X-Robots-Tag: noindex`). Die Website-`/llms.txt` verlinkt beides im Abschnitt „Documentation“.
- Sitemap: die Website-Sitemap enthält alle Doku-Seiten (siehe oben); robots.txt sperrt nur die Such-Datenbank
  (`/docs/__nuxt_content/`) und die Sprachdateien (`/docs/_i18n/`).

## Aufbau

```
docs-site/
├─ nuxt.config.ts            Docus-Layer, i18n (en/de/es, Präfix), baseURL /docs/, Icons, llms, Prerender
├─ app/
│  ├─ app.config.ts          Farben (redstone/lamp/deepslate), SEO-Titel, github: false, KI aus
│  ├─ app.css                TRS-Look: Paletten, Pixel-Schrift für h1/h2, Redstone-Linie, Deepslate-Fußzeile
│  ├─ router.options.ts      entfernt Docus' überflüssiges `:lang?` (sonst trifft /en/help die Startseite)
│  ├─ components/app/…       Kopf (Logo, „Docs“, Website/Download/GitHub/Discord) und Fußzeile
│  ├─ components/content/SectionPages.vue   `:section-pages` – Karten aller Seiten eines Bereichs
│  ├─ components/docs/DocsPageHeaderLinks.vue  „Seite kopieren“ ohne KI-Dienste/MCP
│  ├─ components/LanguageSelect.vue          Sprachumschalter (EN/DE/ES), fällt auf die Startseite zurück
│  ├─ components/OgImage/…   Vorschaubilder
│  └─ plugins/               favicon (Logo statt /favicon.ico), seo-base (/docs in absoluten Adressen)
├─ modules/pages-list.ts     `#docs-pages` – Liste aller Seiten für den Sprachumschalter
├─ build/                    icons.ts (Icon-Liste), content-routes.mjs (Seiten aus content/)
├─ scripts/                  finalize.mjs, preview.mjs, ensure-parent-tsconfig.mjs
├─ content/<lang>/…          Inhalte (CONTENT.md)
└─ public/                   icon.png, shots/…
```

## Fallen

- **YAML-Doppelpunkt** in `description` ohne Anführungszeichen → Objekt statt Text → Build-Fehler
  `description.replace is not a function`. Werte immer quoten.
- **`mdast-util-to-markdown` 2.1.3** (erschienen 2026-09-27) verträgt sich nicht mit `remark-mdc`: jedes `**fett**` endet
  in „Maximum call stack size exceeded“ in `/llms-full.txt`. In `package.json` per `overrides` auf 2.1.2 gepinnt, dazu
  `@nuxtjs/mdc` auf eine Version (0.23.1) vereinheitlicht und `sharp` ≥ 0.35.5 (Sicherheitslücken in libvips).
  Bei Updates prüfen, ob die Pins noch nötig sind.
- **oxc/Vite 8 sucht die tsconfig in der Repo-Wurzel** (`../tsconfig.json` → `../.nuxt/tsconfig.app.json`). Fehlt dort
  `.nuxt` (frischer Checkout/Worktree), bricht der Build mit „Tsconfig not found“ ab – deshalb
  `scripts/ensure-parent-tsconfig.mjs` vor `dev`/`generate`.
- **Nitro-Static-Handler** sucht `<pfad>/index.html`, nicht `<pfad>.html` → `autoSubfolderIndex: true` (Docus stellt
  es sonst auf `false`).
- Statische Dateien laufen **vor** der Server-Middleware und an nuxt-security vorbei → Header für `/docs/**` nur über
  Route-Regeln (`api/modules/docs.ts`).
- Inline-Skripte ändern sich mit Nuxt-/Docus-Updates → neue Hashes; `csp.json` wird bei jedem Build neu erzeugt, es gibt
  nichts von Hand zu pflegen.
