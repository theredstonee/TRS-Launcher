// Baut die eigenständige Vorschau der Start-Animation: eine HTML-Datei (Motor, Logo, Schrift eingebettet) plus
// audio/<design>.js (Jingles als Base64 – per file:// ist fetch() gesperrt, <script> geht).
// Aufruf: node scripts/intro-preview/build.mjs [Zielordner]
import { existsSync, mkdirSync, readdirSync, readFileSync, writeFileSync } from 'node:fs'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath, pathToFileURL } from 'node:url'

const root = resolve(dirname(fileURLToPath(import.meta.url)), '../..')
const out = resolve(process.argv[2] ?? join(root, '.intro-preview'))
const esbuildPath = [join(root, 'node_modules/esbuild/lib/main.js'), join(root, 'node_modules/.pnpm/node_modules/esbuild/lib/main.js')].find(existsSync)
if (!esbuildPath) throw new Error('esbuild nicht gefunden – erst pnpm install')
const esbuild = await import(pathToFileURL(esbuildPath).href)

const res = await esbuild.build({
  entryPoints: [join(root, 'scripts/intro-preview/main.ts')],
  bundle: true,
  format: 'iife',
  target: 'es2022',
  minify: true,
  write: false,
  define: { 'import.meta.env': '{"DEV":false}' },
})
const js = res.outputFiles[0].text.replace(/<\/script/g, '<\\/script')
const logo = `data:image/png;base64,${readFileSync(join(root, 'public/icon.png')).toString('base64')}`
const font = readFileSync(join(root, 'node_modules/@fontsource-variable/pixelify-sans/files/pixelify-sans-latin-wght-normal.woff2')).toString('base64')

mkdirSync(join(out, 'audio'), { recursive: true })
const audioDir = join(root, 'app/assets/intro')
const designs = []
if (existsSync(audioDir)) {
  for (const f of readdirSync(audioDir)) {
    const m = /^([a-z0-9-]+)\.ogg$/.exec(f)
    if (!m) continue
    designs.push(m[1])
    const b64 = readFileSync(join(audioDir, f)).toString('base64')
    writeFileSync(join(out, 'audio', `${m[1]}.js`), `(window.__TRS_INTRO_AUDIO=window.__TRS_INTRO_AUDIO||{})[${JSON.stringify(m[1])}]=${JSON.stringify(b64)};\n`)
  }
}

const btn = (attr, val, label) => `<button ${attr}="${val}">${label}</button>`
const html = `<!doctype html>
<html lang="de">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>TRS Launcher – Start-Animation</title>
<style>
@font-face { font-family: "Pixelify Sans Variable"; font-weight: 400 700; src: url(data:font/woff2;base64,${font}) format("woff2"); }
:root { color-scheme: dark; }
html, body { margin: 0; height: 100%; background: #0b0c0f; color: #e8e6e3; font: 14px/1.4 "Segoe UI", system-ui, sans-serif; overflow: hidden; }
.app { position: fixed; inset: 0; display: grid; place-items: center; background: radial-gradient(ellipse at 30% 20%, #1d1d26, #0b0c0f 70%); }
.app h1 { font: 600 28px "Pixelify Sans Variable", system-ui; letter-spacing: .06em; margin: 0 0 6px; }
.app p { opacity: .6; margin: 0; text-align: center; }
#stage { position: fixed; inset: 0; transition: opacity .45s ease; cursor: pointer; }
#stage.gone { opacity: 0; pointer-events: none; }
#cv { width: 100%; height: 100%; display: block; image-rendering: pixelated; }
.bar { position: fixed; left: 50%; bottom: 14px; transform: translateX(-50%); display: flex; flex-wrap: wrap; gap: 6px; align-items: center; justify-content: center;
  padding: 8px 10px; background: rgba(12,12,16,.72); border: 1px solid rgba(255,255,255,.08); border-radius: 12px; backdrop-filter: blur(8px); z-index: 5; max-width: calc(100vw - 32px); }
.bar button { font: inherit; color: inherit; background: rgba(255,255,255,.06); border: 1px solid rgba(255,255,255,.1); border-radius: 8px; padding: 6px 10px; cursor: pointer; }
.bar button:hover { background: rgba(255,255,255,.12); }
.bar button.on { background: #e0281e; border-color: #ff5a4d; color: #fff; }
.bar .sep { width: 1px; height: 22px; background: rgba(255,255,255,.12); margin: 0 4px; }
.hint { position: fixed; left: 88px; top: 54px; z-index: 6; width: min(380px, calc(100vw - 32px)); padding: 14px 16px; border-radius: 12px;
  background: #17171e; border: 1px solid #333343; box-shadow: 0 18px 40px rgba(0,0,0,.6); animation: hint-in .4s ease; }
.hint[hidden] { display: none; }
@keyframes hint-in { from { opacity: 0; transform: translateY(10px); } }
.hint-row { display: flex; gap: 10px; align-items: flex-start; }
.hint-icon { font-size: 22px; line-height: 1; }
.hint-title { margin: 0; font-weight: 600; color: #f4f4f6; }
.hint-text { margin: 2px 0 0; font-size: 12px; color: #8b8ba2; }
.hint-check { display: flex; gap: 8px; align-items: center; margin-top: 8px; font-size: 12px; color: #b6b6c8; cursor: pointer; }
.hint-check input { accent-color: #e0281e; }
.hint-actions { display: flex; gap: 8px; margin-top: 10px; }
.hint-actions button { font: inherit; font-size: 14px; color: inherit; height: 32px; padding: 0 12px; border-radius: 8px; background: rgba(255,255,255,.06); border: 1px solid rgba(255,255,255,.1); cursor: pointer; }
.hint-actions button.primary { background: #e0281e; border-color: #ff5a4d; color: #fff; }
.note { margin-top: 10px; color: #ffb84d; min-height: 1.4em; }
#status { font-variant-numeric: tabular-nums; opacity: .7; min-width: 150px; text-align: right; }
</style>
${designs.map((d) => `<script src="audio/${d}.js"></script>`).join('\n')}
</head>
<body>
<div class="app"><div><h1>TRS LAUNCHER</h1><p>(hier übernimmt die App nach der Animation)</p><p id="note" class="note"></p></div></div>
<div id="stage"><canvas id="cv"></canvas></div>
<section id="banner" class="hint" role="dialog" aria-label="Start-Animation" hidden>
  <div class="hint-row"><div class="hint-icon" aria-hidden="true">🎬</div><div>
    <p class="hint-title">Start-Animation</p>
    <p class="hint-text">Soll beim Start jedes Mal die kurze 4-Sekunden-Animation laufen? Beim ersten Start des Tages und nach Updates läuft sie immer.</p>
    <label class="hint-check"><input id="banner-calm" type="checkbox"> Ohne Flackern (ruhig)</label>
    <div class="hint-actions"><button data-keep="1" class="primary">Anlassen</button><button data-keep="0">Ausschalten</button></div>
  </div></div>
</section>
<div class="bar">
${btn('data-design', 'redstone', 'Redstone')}${btn('data-design', 'halloween', 'Halloween · Mondnacht')}${btn('data-design', 'winter', 'Winternacht')}
<span class="sep"></span>
${btn('data-length', 'short', '4 s')}${btn('data-length', 'medium', '7 s')}${btn('data-length', 'long', '10 s')}
<span class="sep"></span>
<button id="replay">↻ Nochmal</button><button id="sound">Ton aus</button><button id="calm">Mit Flackern</button><button id="reduced">Reduziert</button>
<span class="sep"></span>
<button id="first">Erster Start (Banner)</button>
<span id="status"></span>
</div>
<script>window.__TRS_INTRO_LOGO=${JSON.stringify(logo)};</script>
<script>${js}</script>
</body>
</html>
`
writeFileSync(join(out, 'index.html'), html)
console.log(`Vorschau: ${join(out, 'index.html')} (${Math.round(html.length / 1024)} KB, Jingles: ${designs.join(', ') || 'keine'})`)
