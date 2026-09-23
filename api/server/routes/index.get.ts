import { defineEventHandler, setResponseHeaders } from 'h3'

const STYLE = `
:root{color-scheme:dark light;--bg:#140b0b;--fg:#f3e9e4;--muted:#b9a7a0;--accent:#ff4d3d}
@media (prefers-color-scheme:light){:root{--bg:#fbf6f3;--fg:#2a1712;--muted:#6e5850;--accent:#c21b0e}}
*{box-sizing:border-box}
body{margin:0;min-height:100vh;display:grid;place-items:center;background:var(--bg);color:var(--fg);
font:16px/1.5 system-ui,-apple-system,"Segoe UI",sans-serif;padding:16px}
main{max-width:34rem}
h1{font-size:1.6rem;margin:0 0 .25rem}
.dot{display:inline-block;width:.7em;height:.7em;border-radius:2px;background:var(--accent);
box-shadow:0 0 12px var(--accent);margin-right:.5em}
p{color:var(--muted);margin:.5rem 0}
code{font-family:ui-monospace,Consolas,monospace;color:var(--fg)}
a{color:var(--accent)}
`.trim()

const HTML = `<!doctype html>
<html lang="de"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<meta name="robots" content="noindex">
<title>TRS API</title>
<style>${STYLE}</style></head>
<body><main>
<h1><span class="dot"></span>TRS API</h1>
<p>Dienst für den TRS Launcher und den TRS Client (Konten, Umhänge, Kosmetik, Emotes, Freunde).</p>
<p>Status: <code>GET /v1/health</code></p>
<p><a href="https://github.com/theredstonee/TRS-Launcher">github.com/theredstonee/TRS-Launcher</a></p>
</main></body></html>`

/** Kleine Statusseite. Eigene CSP: nur der eingebettete Stil ist erlaubt. */
export default defineEventHandler((event) => {
  setResponseHeaders(event, {
    'Content-Type': 'text/html; charset=utf-8',
    'Content-Security-Policy': "default-src 'none'; style-src 'unsafe-inline'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'",
    'Cache-Control': 'public, max-age=300',
  })
  return HTML
})
