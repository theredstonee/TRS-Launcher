// Vorschau der erzeugten Doku ohne Website: liefert .output/public unter http://127.0.0.1:3480/docs/ aus – mit
// derselben CSP wie später die Website (Hashes aus .output/docs-meta/csp.json). Nur lokal, nur zum Anschauen.

import { createServer } from 'node:http'
import { existsSync, readFileSync, statSync } from 'node:fs'
import { extname, join, normalize } from 'node:path'
import { ROOT } from '../build/content-routes.mjs'

const OUT = join(ROOT, '.output', 'public')
const PORT = Number(process.env.PORT ?? 3480)
const csp = JSON.parse(readFileSync(join(ROOT, '.output', 'docs-meta', 'csp.json'), 'utf8'))
const CSP = [
  "default-src 'self'",
  `script-src 'self' 'wasm-unsafe-eval' ${csp.scriptHashes.join(' ')}`,
  "style-src 'self' 'unsafe-inline'",
  "img-src 'self' data: blob: https://raw.githubusercontent.com https://textures.minecraft.net",
  "font-src 'self'",
  "connect-src 'self'",
  "worker-src 'self' blob:",
  "object-src 'none'",
  "base-uri 'none'",
  "frame-ancestors 'none'",
].join('; ')
const TYPES = { '.html': 'text/html; charset=utf-8', '.js': 'text/javascript', '.css': 'text/css', '.json': 'application/json', '.png': 'image/png', '.svg': 'image/svg+xml', '.wasm': 'application/wasm', '.txt': 'text/plain; charset=utf-8', '.md': 'text/markdown; charset=utf-8', '.woff2': 'font/woff2', '.ttf': 'font/ttf' }

createServer((req, res) => {
  const url = new URL(req.url ?? '/', 'http://localhost')
  if (url.pathname === '/' || url.pathname === '/docs' || url.pathname === '/docs/') {
    res.writeHead(302, { Location: '/docs/en' }).end()
    return
  }
  if (!url.pathname.startsWith('/docs/')) {
    res.writeHead(404).end('Not found')
    return
  }
  const rel = normalize(decodeURIComponent(url.pathname.slice('/docs'.length))).replace(/^([/\\])+/, '')
  if (rel.startsWith('..')) {
    res.writeHead(400).end()
    return
  }
  const candidates = [join(OUT, rel), join(OUT, rel, 'index.html'), join(OUT, `${rel}.html`)]
  const file = candidates.find((f) => existsSync(f) && statSync(f).isFile())
  if (!file) {
    res.writeHead(404, { 'Content-Type': 'text/plain' }).end('Not found')
    return
  }
  res.writeHead(200, { 'Content-Type': TYPES[extname(file)] ?? 'application/octet-stream', 'Content-Security-Policy': CSP, 'X-Content-Type-Options': 'nosniff' })
  res.end(readFileSync(file))
}).listen(PORT, '127.0.0.1', () => console.log(`Doku-Vorschau: http://127.0.0.1:${PORT}/docs/en`))
