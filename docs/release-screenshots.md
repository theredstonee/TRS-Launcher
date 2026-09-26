# Release screenshots

Every launcher release shows screenshots of its new features: in the launcher (update card, news, "What's new" –
a dialog with banner, gallery and notes), on the website blog and in the GitHub release notes. From **0.6.5** on,
`node scripts/changelog.mjs check <version>` (and therefore the release build) fails without at least one screenshot.

## Where they go

1. Save the images as `public/news/<version>/<name>.png` (or `.webp`).
   - PNG or WebP, at most **8** per release, each at most **2 MB**, between **640×360** and **3840×2400** px.
   - Launcher pages: 1280×800. In-game: the autotest size (854×480) or larger.
   - Only made-up test data – no real account names, e-mail addresses, tokens or private paths.
2. List them in `CHANGELOG.md` right below the banner line of the version, one per line, captions English | German
   (both optional; without German the English caption is used for both):

   ```md
   ## 0.6.5 – 2026-10-01 – The Clip Update | Das Clip-Update
   <!-- banner: accent=#ff7ab8 motif=/news/0.6.5/banner.png -->
   <!-- shots:
   /news/0.6.5/clip-gallery.png | The clip gallery | Die Clip-Galerie
   /news/0.6.5/clip-trim.png | Trimming a clip | Einen Clip zuschneiden
   -->
   ```

   A single line works too, entries separated by `;`. Captions must not contain `[ ] < >` or `|`, max. 160 characters.
3. Check: `node scripts/changelog.mjs check <version>` and `npx vitest run --dir tests` (`tests/changelog.test.ts`
   validates the screenshots of every release).

## Launcher pages (nuxt dev + headless Chrome)

The launcher UI runs in a normal browser when the Tauri commands are mocked (`scripts/release-shots/tauri-mock.js`).
Always use a **separate** Chrome instance with its own profile – never the everyday browser.

```sh
npx nuxt prepare                       # once per checkout
npx nuxt dev --port 3947 --host 127.0.0.1
```

```powershell
& "C:\Program Files\Google\Chrome\Application\chrome.exe" --headless=new --remote-debugging-port=9471 `
  --user-data-dir="$env:TEMP\trs-release-shots" --no-first-run --mute-audio --window-size=1280,800 about:blank
```

```sh
# load the page with the mock (version / language / last seen version for "What's new")
node scripts/release-shots/cdp.mjs 9471 load http://127.0.0.1:3947/clips '{"version":"0.6.5","locale":"en"}'
# own test data for the page: a file that sets window.__MOCK_RESPONSES = { command: (args) => answer }
node scripts/release-shots/cdp.mjs 9471 load http://127.0.0.1:3947/clips '{"locale":"en"}' --extra my-clips-mock.js
# click, scroll, press keys, then take the picture
node scripts/release-shots/cdp.mjs 9471 run "document.querySelector('button.play')?.click()"
node scripts/release-shots/cdp.mjs 9471 key ArrowRight
node scripts/release-shots/cdp.mjs 9471 shot public/news/0.6.5/clip-gallery.png
```

Afterwards stop only the Chrome and nuxt processes you started (by PID). Unknown commands return `null`/`[]`;
anything that would start a game is blocked by the mock.

## In-game (TRS Client autotests)

The mod's autotests take screenshots on their own:

```sh
cd client-mod
./gradlew :fabric:1.21.1:runClient -PtrsAutotest                        # full run
./gradlew :fabric:1.21.1:runClient -PtrsAutotest -PtrsAutotestOnly=menus # only one part (menus, wardrobe, maps, redstone, clips, …)
```

- Pictures land in the run folder of that version project: `run/screenshots/trsclient-<minecraft>-*.png`.
- Runs must be **silent** (`ALSOFT_DRIVERS=null`, master volume 0 – the autotest sets it) and **one at a time**.
- New feature without a matching screenshot step? Add a `shot(mc, "trsclient-<name>")` step to the autotest.

Copy the best picture per feature to `public/news/<version>/`, list it in the `shots:` comment and run the check.
