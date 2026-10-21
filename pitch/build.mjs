// Builds the TRS Launcher showcase into a self-contained static folder:
//   <out>/index.html + <out>/assets/* (relative paths; works from file://, any http sub-path and a Tauri webview).
// Strict-CSP friendly: no inline scripts, no eval. Fonts/logo are data: URLs, the QR code is inline SVG.
// Usage: node pitch/build.mjs <outDir>
import { copyFileSync, existsSync, mkdirSync, readdirSync, readFileSync, rmSync, statSync, writeFileSync } from 'node:fs'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath, pathToFileURL } from 'node:url'

const here = dirname(fileURLToPath(import.meta.url))
const root = resolve(here, '..')
const outArg = process.argv[2]
if (!outArg) {
  console.error('usage: node pitch/build.mjs <outDir>')
  process.exit(1)
}
const out = resolve(outArg)
const assets = join(out, 'assets')

const esbuildPath = [join(root, 'node_modules/esbuild/lib/main.js'), join(root, 'node_modules/.pnpm/node_modules/esbuild/lib/main.js')].find(existsSync)
if (!esbuildPath) throw new Error('esbuild not found – run pnpm install first')
const esbuild = await import(pathToFileURL(esbuildPath).href)

const b64 = (p) => readFileSync(p).toString('base64')
const media = join(here, 'media')
const qr = readFileSync(join(media, 'qr-download.svg'), 'utf8').trim()
const logo = `data:image/png;base64,${b64(join(media, 'icon.png'))}`

rmSync(assets, { recursive: true, force: true })
mkdirSync(assets, { recursive: true })

// JS
const res = await esbuild.build({
  entryPoints: [join(here, 'src/main.ts')],
  bundle: true,
  format: 'iife',
  target: 'es2020',
  minify: true,
  legalComments: 'none',
  write: false,
  define: {
    'import.meta.env': '{"DEV":false}',
    __QR__: JSON.stringify(qr),
    __LOGO__: JSON.stringify(logo),
  },
})
writeFileSync(join(assets, 'pitch.js'), res.outputFiles[0].text)

// CSS (fonts inlined: file:// pages can't always load font files)
const fontDir = join(root, 'node_modules/@fontsource-variable/pixelify-sans/files')
let css = readFileSync(join(here, 'src/style.css'), 'utf8')
css = css
  .replace('url("pixelify-latin.woff2")', `url(data:font/woff2;base64,${b64(join(fontDir, 'pixelify-sans-latin-wght-normal.woff2'))})`)
  .replace('url("pixelify-latin-ext.woff2")', `url(data:font/woff2;base64,${b64(join(fontDir, 'pixelify-sans-latin-ext-wght-normal.woff2'))})`)
const cssMin = await esbuild.transform(css, { loader: 'css', minify: true })
writeFileSync(join(assets, 'pitch.css'), cssMin.code)

// media
for (const f of readdirSync(media)) {
  if (/\.(webp|png)$/.test(f)) copyFileSync(join(media, f), join(assets, f))
}

// jingle of the start animation (only loaded when sound is turned on); a script so it also works from file://
const jingle = join(root, 'app/assets/intro/redstone.mp3')
writeFileSync(join(assets, 'jingle-redstone.js'), `window.__TRS_PITCH_JINGLE=${JSON.stringify(b64(jingle))};\n`)

const html = `<!doctype html>
<html lang="de">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">
<meta name="color-scheme" content="dark">
<meta name="description" content="TRS Launcher – the Minecraft launcher by TheRedstonee. Showcase.">
<title>TRS Launcher – Showcase</title>
<link rel="icon" href="assets/icon.png">
<link rel="stylesheet" href="assets/pitch.css">
<script defer src="assets/pitch.js"></script>
</head>
<body>
<canvas id="bg" aria-hidden="true"></canvas>
<canvas id="intro" aria-hidden="true"></canvas>
<div id="glow" aria-hidden="true"></div>
<div id="grain" aria-hidden="true"></div>
<main id="stage" aria-live="polite"></main>
<div id="sweep" aria-hidden="true"></div>
<div id="progress" aria-hidden="true"><i></i></div>
<div id="dots" aria-hidden="true"></div>
<div id="counter"></div>
<div id="hint" class="gone"></div>
<div id="demo-pill"></div>
<div id="toast" role="status"></div>
<noscript><p style="padding:40px;font:20px system-ui;color:#fff">TRS Launcher – https://trs-launcher.theredstonee.de/download</p></noscript>
</body>
</html>
`
writeFileSync(join(out, 'index.html'), html)

let total = 0
const walk = (d) => {
  for (const f of readdirSync(d)) {
    const p = join(d, f)
    const s = statSync(p)
    if (s.isDirectory()) walk(p)
    else total += s.size
  }
}
walk(out)
console.log(`showcase: ${join(out, 'index.html')} (${(total / 1024 / 1024).toFixed(2)} MB)`)
