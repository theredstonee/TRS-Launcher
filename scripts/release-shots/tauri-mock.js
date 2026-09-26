// Tauri-Attrappe für Release-Screenshots im normalen Browser (npx nuxt dev + Headless-Chrome).
// scripts/release-shots/cdp.mjs spielt sie vor dem Laden ein. Nur erfundene Testdaten – keine echten
// Namen, Pfade oder Tokens. Spielstarts sind gesperrt.
//
// Einstellbar über window.__MOCK_CFG (JSON, von cdp.mjs gesetzt):
//   { version: '0.6.5', locale: 'de', seen: '0.6.4' }   seen = zuletzt gesehene Version → „Was ist neu“
// und window.__MOCK_RESPONSES (eigene Datei, cdp.mjs … --extra datei.js): { befehl: (args) => antwort }
;(() => {
  const cfg = window.__MOCK_CFG || {}
  const version = cfg.version || '0.6.4'
  const locale = cfg.locale || 'de'
  try {
    localStorage.setItem('trs.onboarding.done', '1')
    localStorage.setItem('trs.locale', locale)
    localStorage.setItem('trs.whatsNew.seen', cfg.seen || '9.9.9')
    localStorage.removeItem('trs.updateNews.hidden')
  } catch {}
  const iso = (msAgo) => new Date(Date.now() - msAgo).toISOString()
  const H = 36e5
  const settings = {
    minMemoryMb: 512, maxMemoryMb: 6144, javaPath: null, jvmArgs: '', resolution: { width: 1280, height: 720 },
    concurrentDownloads: 8, closeOnLaunch: false, showSnapshots: false, preferDedicatedGpu: true, performanceTuning: true,
    highPriority: false, autoFirewall: true, fullscreen: false, hooks: { preLaunch: null, wrapper: null, postExit: null },
    sync: { options: true, servers: true, resourcePacks: true, commandHistory: false, hotbar: false },
    ui: {
      theme: 'dark', accent: 'redstone', advancedRendering: true, animatedBackground: false, worldsTab: true, screenshotsTab: true,
      historyTab: true, sidebarRecent: true, sidebarAccount: true, hideRightSidebar: false, compactLibrary: false, showPlayTime: true,
      language: locale, motion: 'full',
    },
    allowLogUpload: true, discordPresence: true, java: { java8: null, java17: null, java21: null, java25: null },
    clips: { enabled: false, bufferSeconds: 30, resolution: '1080p', fps: 60, quality: 'medium', encoder: 'auto', systemAudio: true, microphone: false, folder: null, maxStorageGb: 20 },
  }
  const overrides = { maxMemoryMb: null, javaPath: null, jvmArgs: null, resolution: null, trsClient: null, boost: null, performanceTuning: null, updateChannel: null, fullscreen: null, hooks: null, env: null, syncSeparate: [] }
  const instance = { id: 'redstone-labor', name: 'Redstone-Labor', gameVersion: '1.21.1', loader: { kind: 'fabric', version: '0.16.5' }, createdAt: iso(40 * 24 * H), lastPlayed: iso(3 * H), totalPlaySeconds: 187 * 3600, overrides, icon: null, iconPath: null, bannerPath: null, group: null }
  const news = {
    fetchedAt: new Date().toISOString(),
    stale: false,
    items: [{ id: `trs-${version}`, source: 'launcher', title: `TRS Launcher v${version}`, summary: 'New version of the TRS Launcher.', date: iso(2 * H), tag: null, link: null }],
  }
  const opened = []
  window.__opened = opened
  const responses = {
    app_info: () => ({ version, os: 'windows', arch: 'x86_64' }),
    get_settings: () => settings,
    list_instances: () => [instance],
    get_instance: () => instance,
    list_accounts: () => [],
    running_games: () => [],
    list_servers: () => [],
    get_news: () => news,
    news_image: ({ url }) => url,
    open_external_url: ({ url }) => {
      opened.push(url)
      return null
    },
    'plugin:app|version': () => version,
    'plugin:app|name': () => 'TRS Launcher',
    'plugin:event|listen': ({ handler }) => handler,
    'plugin:event|unlisten': () => null,
    'plugin:window|is_maximized': () => false,
    'plugin:window|is_fullscreen': () => false,
    ...(window.__MOCK_RESPONSES || {}),
  }
  let cb = 1
  window.isTauri = true
  window.__TAURI_INTERNALS__ = {
    metadata: { currentWindow: { label: 'main' }, currentWebview: { windowLabel: 'main', label: 'main' } },
    plugins: {},
    transformCallback(callback, once) {
      const id = cb++
      window[`_${id}`] = (result) => {
        if (once) delete window[`_${id}`]
        callback?.(result)
      }
      return id
    },
    unregisterCallback(id) {
      delete window[`_${id}`]
    },
    convertFileSrc: (p) => p,
    async invoke(cmd, args) {
      if (/launch|join/i.test(cmd)) throw new Error('Im Screenshot-Modus gesperrt')
      const handler = responses[cmd]
      if (handler) return handler(args ?? {})
      if (cmd.startsWith('list_') || cmd.endsWith('_list') || cmd.endsWith('history')) return []
      return null
    },
  }
  window.__TAURI_EVENT_PLUGIN_INTERNALS__ = { unregisterListener() {} }
})()
