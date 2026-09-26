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
| `/download` | SoftwareApplication, BreadcrumbList |
| `/blog` | BreadcrumbList |
| `/blog/<version>` | BlogPosting (headline = update name, date, screenshots + banner motif), BreadcrumbList |
| `/capes` | BreadcrumbList |
| `/faq` | FAQPage (exactly the visible questions), BreadcrumbList |
| `/privacy` | – |

SoftwareApplication takes the version and date from the latest GitHub release, screenshots from the newest
blog post (plus `public/shots/*`), `featureList` and `keywords` from `messages.<lang>.seo`. No ratings are
added – there are none to show.

Open Graph image: `/og.png` (1260×660) by default; blog posts use their first screenshot, `/capes` and
`/features` use a screenshot from `public/shots`.

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

- **New page:** add it to `SITE_PAGES` in `shared/seo.ts` (sitemap), add `seo.<page>` texts in all three
  languages in `messages.ts`, call `usePageSeo()` in the page, and use `lp('/path')` for links to it.
  `tests/seo.test.ts` checks the length/uniqueness of titles and descriptions.
- **New structured data type:** add a builder in `shared/seo.ts`, pass it in `jsonLd`, and add its required
  properties to `REQUIRED` in `tests/seo.test.ts`.
- **New language:** extend `SeoLang`, `SEO_LANGS` and `OG_LOCALE` in `shared/seo.ts` together with
  `messages.ts` and `useLang()`.
- After deploying, check pages with Google's Rich Results Test and the URL inspection in Search Console;
  the sitemap URL is `https://trs-launcher.theredstonee.de/sitemap.xml`.
