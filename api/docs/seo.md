# SEO of the website

What the website (trs-launcher.theredstonee.de) does for search engines and how to extend it.
All building blocks are plain functions in `shared/seo.ts` (used by pages and server routes, tested in
`tests/seo.test.ts`).

## Languages and URLs

| Language | URL | Notes |
|---|---|---|
| English | `/download` | also `x-default` |
| German | `/download?lang=de` | |
| Spanish | `/download?lang=es` | blog posts show the English changelog text with Spanish page chrome |

- `?lang=` is read during SSR before the `trs_lang` cookie and `Accept-Language`, so every language version
  can be crawled without cookies. Without `?lang=` the page adapts to cookie/header; responses carry
  `Vary: Accept-Language, Cookie` (set in `server/middleware/00.security.ts`).
- Internal links keep a `?lang=` that came from the URL (`useLocalePath()` in `app/composables/useSeo.ts`),
  so crawlers stay in one language. The language switch updates `?lang=` when it is present.
- Every page gets `<link rel="canonical">` for the language it rendered and `<link rel="alternate" hreflang>`
  for `en`, `de`, `es` and `x-default`.

## Per page head

Pages call `usePageSeo(() => ({ path, title, description, image?, type?, publishedTime?, jsonLd? }))`.
`buildPageHead()` turns that into: title, meta description, robots, canonical, hreflang, Open Graph
(`og:title/description/url/image/locale` + `og:locale:alternate`), Twitter card (`summary_large_image`) and
one JSON-LD `@graph` script. nuxt-security adds the CSP nonce to that script automatically; `<` is escaped
as `<` so text can never close the script tag.

Titles and descriptions live in `app/utils/messages.ts` under `seo` (per language, title ≤ ~60 chars,
description ≤ 160 chars – the test enforces ≤ 65/160). Write them for people: natural sentences with the
terms players search for, no keyword lists, no hidden text, no comparisons that name other launchers or
clients.

| Page | JSON-LD |
|---|---|
| `/` | Organization, WebSite, SoftwareApplication |
| `/features` | SoftwareApplication, BreadcrumbList |
| `/minecraft-launcher`, `/redstone-launcher`, `/modpacks`, `/fps-boost-pvp-client` | SoftwareApplication, FAQPage (the visible questions of that page), BreadcrumbList |
| `/download` | SoftwareApplication, BreadcrumbList |
| `/blog` | BreadcrumbList |
| `/blog/<version>` | BlogPosting (headline = update name, date, screenshots + banner motif), BreadcrumbList |
| `/cosmetics` (301 from `/capes`) | BreadcrumbList |
| `/faq` | FAQPage (exactly the visible questions), BreadcrumbList |
| `/privacy` | – |

SoftwareApplication takes the version and date from the latest GitHub release, screenshots from the newest
blog post (plus `public/shots/*`), `featureList` and `keywords` from `messages.<lang>.seo`. No ratings are
added – there are none to show.

Open Graph image: `/og.png` (1260×660) by default; blog posts use their first screenshot, `/cosmetics` and
`/features` use a screenshot from `public/shots`.

## Brand and name variants

The brand is "TRS Launcher by TheRedstonee – the redstone Minecraft launcher". People search for it under several
names, so they appear in two places:

- JSON-LD `alternateName` (constants in `shared/seo.ts`): SoftwareApplication = `LAUNCHER_ALT_NAMES` (TRS, TRS Minecraft
  Launcher, Redstone Launcher, The Redstone Launcher, TheRedstonee Launcher), WebSite = the same plus "TRS Client",
  Organization = TRS, Redstone Launcher. SoftwareApplication has `creator` and Organization `founder` = TheRedstonee.
- Visible text, written naturally: the home page h1 starts with "TRS Launcher" (kicker line inside the h1), the
  "What is TRS Launcher?" section (`home.about`) and the footer tagline.

## Topic pages (landing pages)

`/minecraft-launcher`, `/redstone-launcher`, `/modpacks` and `/fps-boost-pvp-client` target the main search topics.
Content lives in `shared/landing-{en,de,es}.ts` (types and helpers in `shared/landing.ts`), rendered by
`app/components/LandingView.vue`; the page files only name the id. Each page has one h1, 600–1000 words (English;
test-enforced), sections with screenshots from `public/news/<version>/` on GitHub (sizes in `SHOT_SIZES`), 4–6 FAQ with
FAQPage JSON-LD, breadcrumbs and a download call to action.

Rules: every statement must be backed by `CHANGELOG.md` (main) or `/features`; no made-up features; no names of other
launchers or clients (`COMPETITOR_PATTERN` in `shared/seo.ts`, checked by the tests). All three languages keep the same
section ids, screenshots, links and number of questions (test).

Internal links: `LandingLinks.vue` shows cards for the topic pages on the home page, `/features`, `/download`, `/faq` and
on every topic page (without itself); the menu and footer list them under "Topics"; `/features` sections link to the
matching topic page.

## llms.txt

`/llms.txt` (short overview with link lists, llmstxt.org convention) and `/llms-full.txt` (the whole English content:
key facts, latest versions, features, topic pages, download, FAQ, privacy summary, links) are built by
`server/lib/llms.ts` from the same texts as the pages (`messages.en`, `landingTexts.en`, FAQ, `WEBSITE_PRIVACY.en`) plus
the newest six versions from the changelog – they stay in sync without extra work. Served as
`text/plain; charset=utf-8`, cached for an hour; allowed explicitly in robots.txt. Changelog headlines that name other
clients are left out. Tests: `tests/llms.test.ts`.

## Documentation (https://trs-launcher.wiki)

The docs are a separate Docus project in `../docs-site` (static, `nuxt generate`, baseURL `/`, build assets under
`/_docs/`), served by this same app on its own domain **trs-launcher.wiki** (at the root, no `/docs` prefix).
`modules/docs.ts` builds them during `nuxt build` when their sources changed (`TRS_DOCS_BUILD=skip` or `force`) and
copies them to `.output/docs-wiki` – deliberately not as public assets (Nitro would serve those on every host, and
both sites have `/icon.png`, `/shots/`, `/llms.txt`). Details: `docs-site/README.md`, writing rules:
`docs-site/CONTENT.md`.

- Host routing (`server/lib/wiki.ts`, installed as the FIRST h3 layer by `server/plugins/02.wiki.ts`, before Nitro's
  route rules, the website's static handler and all middleware). The host comes from the `Host` header only
  (`X-Forwarded-Host` is ignored – any client could set it and poison the Cloudflare cache). On the wiki host:
  - `/` → 302 to `/<lang>` (`trs_lang` cookie, then `Accept-Language`, else `en`; `Vary: Accept-Language, Cookie`).
  - existing files (`<path>/index.html` for pages) → 200 with the docs CSP; `/en|de|es/…` without a file → 404 with
    the docs' `404.html`; other docs folders (`/_docs/`, `/raw/`, …) without a file → plain 404.
  - `/robots.txt`, `/sitemap.xml` → generated for the wiki from the docs build's `routes.json`.
  - `/v1/…` → 404 JSON in the API error format (no redirect: API clients must not be moved silently and credentials
    never follow a cross-host redirect). Other methods than GET/HEAD → 405.
  - `/docs/…` → 301 to the same path without `/docs`; everything else → 301 to `SITE_URL` + same path and query.
- On the website: `/docs/<rest>` → 301 to `https://trs-launcher.wiki/<rest>` (query kept); `/docs` and `/docs/`
  → 302 to `https://trs-launcher.wiki/<lang>` chosen here (the `trs_lang` cookie only exists on this domain).
- The docs pages set their own canonical, hreflang (only languages that exist), `og:url` and Open Graph images on
  `https://trs-launcher.wiki`, plus JSON-LD (Article, BreadcrumbList, WebSite).
- The website's `sitemap.xml` no longer lists docs pages, its `robots.txt` has no docs rules. The wiki has its own
  `sitemap.xml` (each language with hreflang alternates, `lastmod` = last git commit of the Markdown file) and
  `robots.txt` (disallows only `/__nuxt_content/` and `/_i18n/`; `/raw/*.md` sends `X-Robots-Tag: noindex`).
  Add `trs-launcher.wiki` as its own Search Console property and submit that sitemap there.
- `https://trs-launcher.wiki/llms.txt` and `/llms-full.txt` (English only) are linked from this site's `/llms.txt`
  ("Documentation") and `/llms-full.txt` ("Links").
- Security headers on the wiki host (all set by `server/lib/wiki.ts`, nuxt-security does not run there): hash-based CSP
  (`buildDocsCsp()` in `shared/docs.ts`, no `'unsafe-inline'` for scripts, `frame-ancestors 'none'`), HSTS,
  `nosniff`, `X-Frame-Options: DENY`, `Referrer-Policy: strict-origin-when-cross-origin`, Permissions-Policy, COOP/CORP.
- Menu, footer, FAQ and download page link to `https://trs-launcher.wiki/<lang>` (`docsPageUrl()` in `shared/docs.ts`).

## Crawling

- `sitemap.xml` (`server/routes/sitemap.xml.get.ts`): every page from `SITE_PAGES` and every blog post, each
  language as its own `<url>` with all `xhtml:link` alternates. `lastmod`: pages = build time
  (`runtimeConfig.buildTime`, set when `nuxt build` runs), `/blog` = newer of build and newest post, posts =
  release date from the changelog.
- `robots.txt`: disallows `/v1/` and `/admin`, but allows `/v1/site/` (data the pages render with) and
  `/v1/capes/*.png` (cape images for image search).
- `/admin` sends `X-Robots-Tag: noindex, nofollow` (route rule) and a robots meta tag; the 404/error page is
  `noindex`.
- Static files: `/_nuxt/*` are immutable (hashed); `/shots`, `/og.png`, `/icon.png` are cached for a day,
  `/flags` for a week (route rules in `nuxt.config.ts`).
- Primary text is server-rendered; only the redstone scene and the 3D cape viewer are client-only.

## Extending

- **New topic page:** add the id to `LandingId`/`LANDING_IDS` in `shared/landing.ts`, write it in all three
  `landing-*.ts` files, add screenshot sizes to `SHOT_SIZES`, add `SITE_PAGES` and a page file with
  `<LandingView page="…" />` – menu, footer, cards, sitemap and llms.txt pick it up.
- **New page:** add it to `SITE_PAGES` in `shared/seo.ts` (sitemap), add `seo.<page>` texts in all three
  languages in `messages.ts`, call `usePageSeo()` in the page, and use `lp('/path')` for links to it.
  `tests/seo.test.ts` checks the length/uniqueness of titles and descriptions.
- **New structured data type:** add a builder in `shared/seo.ts`, pass it in `jsonLd`, and add its required
  properties to `REQUIRED` in `tests/seo.test.ts`.
- **New language:** extend `SeoLang`, `SEO_LANGS` and `OG_LOCALE` in `shared/seo.ts` together with
  `messages.ts` and `useLang()`.
- After deploying, check pages with Google's Rich Results Test and the URL inspection in Search Console;
  the sitemap URL is `https://trs-launcher.theredstonee.de/sitemap.xml`.
