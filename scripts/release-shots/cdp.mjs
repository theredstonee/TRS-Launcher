// Release-Screenshots des Launchers: steuert eine EIGENE Headless-Chrome-Instanz über das DevTools-Protokoll.
// Ablauf und Regeln: docs/release-screenshots.md.
//
//   node scripts/release-shots/cdp.mjs <port> load <url> ['<cfg-json>'] [--extra <datei.js>]
//        → Tauri-Attrappe (tauri-mock.js) einspielen, Seite in 1280×800 laden
//   node scripts/release-shots/cdp.mjs <port> run "<javascript>"     → im Fenster ausführen (Klicks, Scrollen …)
//   node scripts/release-shots/cdp.mjs <port> key <Taste>            → Taste drücken (ArrowRight, Escape …)
//   node scripts/release-shots/cdp.mjs <port> shot <datei.png> [w h] → Screenshot (Standard 1280×800)
import { readFileSync, writeFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

const here = dirname(fileURLToPath(import.meta.url))
const args = process.argv.slice(2)
const extraAt = args.indexOf('--extra')
const extraFile = extraAt >= 0 ? args.splice(extraAt, 2)[1] : null
const [port, mode, a1, a2, a3] = args
if (!port || !mode) {
  console.error('Aufruf: cdp.mjs <port> load|run|key|shot …')
  process.exit(2)
}

const targets = await (await fetch(`http://127.0.0.1:${port}/json/list`)).json()
const page = targets.find((t) => t.type === 'page' && t.webSocketDebuggerUrl)
if (!page) throw new Error('Kein Tab in der Chrome-Instanz gefunden')
const ws = new WebSocket(page.webSocketDebuggerUrl)
let nextId = 1
const pending = new Map()
ws.addEventListener('message', (event) => {
  const msg = JSON.parse(event.data)
  const entry = pending.get(msg.id)
  if (!entry) return
  pending.delete(msg.id)
  if (msg.error) entry.reject(new Error(JSON.stringify(msg.error)))
  else entry.resolve(msg.result)
})
const send = (method, params = {}) =>
  new Promise((resolve, reject) => {
    const id = nextId++
    pending.set(id, { resolve, reject })
    ws.send(JSON.stringify({ id, method, params }))
  })
await new Promise((resolve, reject) => {
  ws.addEventListener('open', resolve, { once: true })
  ws.addEventListener('error', reject, { once: true })
})
const sleep = (ms) => new Promise((r) => setTimeout(r, ms))
const size = (w = 1280, h = 800) => send('Emulation.setDeviceMetricsOverride', { width: w, height: h, deviceScaleFactor: 1, mobile: false })

if (mode === 'load') {
  await send('Page.enable')
  await size()
  const extra = extraFile ? readFileSync(extraFile, 'utf8') : ''
  const mock = readFileSync(join(here, 'tauri-mock.js'), 'utf8')
  await send('Page.addScriptToEvaluateOnNewDocument', { source: `window.__MOCK_CFG = ${a2 || '{}'};\n${extra}\n${mock}` })
  // Zweimal laden: beim ersten Aufruf optimiert Vite Abhängigkeiten und lädt die Seite selbst neu – dann wäre
  // die Attrappe weg, sobald dieses Skript die Verbindung schließt.
  await send('Page.navigate', { url: a1 })
  await sleep(8000)
  await send('Page.navigate', { url: a1 })
  await sleep(6000)
  const r = await send('Runtime.evaluate', { expression: '!!window.__TAURI_INTERNALS__', returnByValue: true })
  console.log(r.result.value ? 'geladen (Attrappe aktiv)' : 'geladen – ACHTUNG: Attrappe fehlt')
} else if (mode === 'run') {
  const r = await send('Runtime.evaluate', { expression: a1, awaitPromise: true, returnByValue: true })
  console.log(JSON.stringify(r.result?.value ?? r.exceptionDetails ?? null))
} else if (mode === 'key') {
  const codes = { ArrowLeft: 37, ArrowRight: 39, ArrowUp: 38, ArrowDown: 40, Escape: 27, Enter: 13, Tab: 9 }
  for (const type of ['keyDown', 'keyUp']) {
    await send('Input.dispatchKeyEvent', { type, key: a1, code: a1, windowsVirtualKeyCode: codes[a1] ?? 0 })
  }
  await sleep(300)
  console.log('Taste', a1)
} else if (mode === 'shot') {
  if (a2 && a3) await size(Number(a2), Number(a3))
  await sleep(500)
  const { data } = await send('Page.captureScreenshot', { format: 'png' })
  writeFileSync(a1, Buffer.from(data, 'base64'))
  console.log('Screenshot:', a1)
} else {
  console.error(`Unbekannter Modus „${mode}“`)
  process.exitCode = 2
}
ws.close()
