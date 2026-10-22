// The showcase slides. Every fact comes from the repo (README, CHANGELOG, client-mod docs, builds.json);
// see pitch/README.md for the sources. Durations in seconds (loop mode).

import { a, A, CHECK, chips, col, count, game, head, phone, points, t, vis, win, type T } from './h'

export interface Ctx {
  demo: boolean
  reduced: boolean
  qr: string
}

export interface Slide {
  id: string
  dur: number
  cls?: string
  html: (c: Ctx) => string
}

const DL = 'trs-launcher.theredstonee.de/download'
const FEEDBACK: T = {
  de: 'Feedback & Ideen: <b>trs-launcher.theredstonee.de/issues</b> · Discord <b>dc.theredstonee.de</b>',
  en: 'Feedback & ideas: <b>trs-launcher.theredstonee.de/issues</b> · Discord <b>dc.theredstonee.de</b>',
}

const intro: Slide = {
  id: 'intro',
  dur: 13,
  html: (c) => {
    const d = c.reduced ? 2600 : 9900
    return `<div class="intro-cap">${a(`<div class="t">${t({ de: 'Der Minecraft-Launcher von TheRedstonee', en: 'The Minecraft launcher by TheRedstonee' })}</div>`, d, 'f')}${a(
      `<div class="s">${t({ de: 'Kostenlos · Open Source · Windows · Linux · Android & iOS (Vorschau)', en: 'Free · Open source · Windows · Linux · Android & iOS (preview)' })}</div>`,
      d + 500,
    )}</div>`
  },
}

const problem: Slide = {
  id: 'problem',
  dur: 10,
  html: () => {
    const pains: [T, T, T][] = [
      [{ de: 'Mods & Modpacks', en: 'Mods & modpacks' }, { de: 'Prism oder CurseForge App', en: 'Prism or the CurseForge app' }, { de: 'eigener Launcher', en: 'its own launcher' }],
      [{ de: 'Client & HUD', en: 'Client & HUD' }, { de: 'Lunar oder Feather', en: 'Lunar or Feather' }, { de: 'noch ein Launcher', en: 'another launcher' }],
      [{ de: 'Freunde & Chat', en: 'Friends & chat' }, { de: 'Discord', en: 'Discord' }, { de: 'extra App', en: 'extra app' }],
      [{ de: 'Clips', en: 'Clips' }, { de: 'OBS oder ein Overlay', en: 'OBS or an overlay' }, { de: 'extra App', en: 'extra app' }],
      [{ de: 'Welt mit Freunden', en: 'World with friends' }, { de: 'Portfreigabe oder Hamachi', en: 'Port forwarding or Hamachi' }, { de: 'Router-Gefummel', en: 'router fiddling' }],
    ]
    return (
      col(
        head(
          { de: 'Das Problem', en: 'The problem' },
          { de: 'Ein Spiel.<br><em>Fünf Apps.</em>', en: 'One game.<br><em>Five apps.</em>' },
          {
            de: 'Wer heute Minecraft mit Mods und Freunden spielt, jongliert mit Launchern, Clients und Zusatz-Tools – jedes mit eigenem Konto, eigenen Einstellungen und eigenen Updates.',
            en: 'Playing Minecraft with mods and friends today means juggling launchers, clients and extra tools – each with its own account, its own settings and its own updates.',
          },
        ),
        150,
        760,
      ) +
      `<div class="pains">${pains
        .map(
          ([need, tool, cost], i) =>
            `<div class="pain a r" style="--d:${700 + i * 220}ms"><div class="need">${t(need)}</div><div class="tool">${t(tool)}</div><div class="cost">${t(cost)}</div></div>`,
        )
        .join('')}</div>` +
      a(
        `<div class="pain-answer">${t({ de: 'Der TRS Launcher macht daraus <b>eine App</b>.', en: 'The TRS Launcher turns that into <b>one app</b>.' })}</div>`,
        2200,
        'pop',
        '',
        'position:absolute;left:110px;bottom:130px',
      )
    )
  },
}

const what: Slide = {
  id: 'what',
  dur: 10,
  html: () =>
    col(
      head(
        { de: 'Der TRS Launcher', en: 'The TRS Launcher' },
        { de: 'Ein Launcher.<br><em>Alles drin.</em>', en: 'One launcher.<br><em>Everything in it.</em>' },
        {
          de: 'Startet jede <b>Minecraft: Java Edition</b> – mit Mods, Modpacks, eigenem Client, Freunden und Clips. Schnell, modern, in einer App.',
          en: 'Launches every <b>Minecraft: Java Edition</b> – with mods, modpacks, its own client, friends and clips. Fast, modern, all in one app.',
        },
      ) +
        chips(
          [
            { de: 'Kostenlos', en: 'Free' },
            'Open Source · GPL-3.0',
            'Windows',
            'Linux',
            { de: 'Android <small>Vorschau</small>', en: 'Android <small>preview</small>' },
            { de: 'iOS <small>Vorschau</small>', en: 'iOS <small>preview</small>' },
          ],
          900,
          110,
          (i) => (i === 0 ? 'hot' : ''),
        ),
      190,
      760,
    ) +
    vis(
      win('home', { de: 'Startseite des TRS Launchers', en: 'TRS Launcher start page' }, { s0: 1, s1: 1.12, ox: 75, oy: 30 }, [
        { x: 47, y: 13, label: { de: 'Live-Redstone-Schaltung', en: 'Live redstone circuit' }, d: 2600 },
        { x: 85, y: 31, label: { de: 'Lampe = Spielen', en: 'Lamp = Play' }, d: 3800, left: true },
      ]),
      { left: 920, top: 200, width: 920 },
      250,
      'r',
      { ry0: -16, ry1: -7, rx0: 4, rx1: 2 },
    ),
}

const versions: Slide = {
  id: 'versions',
  dur: 10,
  html: () => {
    const loaders: [string, T | null][] = [
      ['Vanilla', { de: 'inkl. Snapshots', en: 'incl. snapshots' }],
      ['Fabric', null],
      ['Quilt', null],
      ['Forge', { de: 'eigener Installer', en: 'own installer' }],
      ['NeoForge', { de: 'eigener Installer', en: 'own installer' }],
    ]
    return (
      col(
        head({ de: 'Versionen & Loader', en: 'Versions & loaders' }, { de: 'Jede Version.<br><em>Jeder Loader.</em>', en: 'Every version.<br><em>Every loader.</em>' }),
        120,
        900,
      ) +
      `<div style="position:absolute;right:110px;top:150px;text-align:right">${a(`<div class="small">${t({ de: 'Minecraft: Java Edition', en: 'Minecraft: Java Edition' })}</div>`, 500, 'f')}${a(
        '<div class="big-range">1.5.2 <span>→</span> 26.x</div>',
        650,
        'z',
      )}${a(`<div class="small" style="margin-top:14px">${t({ de: 'Java wird automatisch passend geladen', en: 'The right Java is downloaded automatically' })}</div>`, 1100, 'f')}${a(`<div class="small" style="margin-top:6px">${t({ de: '26.x = neue Jahres-Versionen von Minecraft ab 2026 (nach 1.21.11)', en: '26.x = Minecraft’s new year-based versions from 2026 (after 1.21.11)' })}</div>`, 1300, 'f')}</div>` +
      `<div class="loaders" style="position:absolute;left:110px;top:560px">${loaders
        .map(([n, s], i) => `<div class="loader a pop" style="--d:${1400 + i * 160}ms"><div>${n}${s ? `<small>${t(s)}</small>` : ''}</div></div>`)
        .join('')}</div>` +
      `<div style="position:absolute;left:110px;top:800px;width:1700px">${a(
        `<p class="lead" style="max-width:1500px;font-size:28px"><b>TRS Boost:</b> ${t({
          de: 'Vanilla-Instanzen starten mit Fabric, dem TRS Client und bewährten Leistungs-Mods (Sodium, Lithium, FerriteCore, ImmediatelyFast, ModernFix …) – pro Instanz abschaltbar.',
          en: 'Vanilla instances start with Fabric, the TRS Client and proven performance mods (Sodium, Lithium, FerriteCore, ImmediatelyFast, ModernFix …) – can be turned off per instance.',
        })}</p>`,
        2400,
      )}</div>`
    )
  },
}

const modpacks: Slide = {
  id: 'modpacks',
  dur: 10,
  html: () =>
    col(
      head(
        { de: 'Modpacks & Import', en: 'Modpacks & import' },
        { de: 'Modrinth & CurseForge.<br><em>Ein Klick.</em>', en: 'Modrinth & CurseForge.<br><em>One click.</em>' },
        {
          de: 'Modpacks installieren, dazu Mods, Resource Packs, Data Packs und Shader. Oder dein Setup einfach mitnehmen – Import aus:',
          en: 'Install modpacks, plus mods, resource packs, data packs and shaders. Or bring your setup along – import from:',
        },
      ) +
        `<div class="imports">${[
          { de: 'Offizieller Launcher', en: 'Official launcher' },
          'Prism Launcher',
          'MultiMC',
          'CurseForge App',
          'Modrinth App',
          'Lunar Client',
          'Badlion',
          'ATLauncher',
          'GDLauncher',
          'OneClient',
          { de: 'jeder Ordner', en: 'any folder' },
        ]
          .map((x, i) => `<span class="chip a pop" style="--d:${1300 + i * 80}ms">${t(x)}</span>`)
          .join('')}</div>` +
        a(`<p class="small" style="margin-top:22px">${t({ de: 'Export als .mrpack · Modpacks mit oder ohne TRS Client', en: 'Export as .mrpack · modpacks with or without TRS Client' })}</p>`, 2500, 'f'),
      150,
      780,
    ) +
    vis(win('discover', 'Discover', { s0: 1, s1: 1.05 }), { left: 1130, top: 110, width: 700, z: 1 }, 200, 'f', { ry0: -12, ry1: -8 }) +
    vis(
      win('featured', { de: 'Empfohlene Modpacks', en: 'Featured modpacks' }, { s0: 1, s1: 1.1, ox: 50, oy: 35 }, [
        { x: 52, y: 30, label: { de: 'Vom TRS-Team empfohlen', en: 'Picked by the TRS team' }, d: 2200 },
        { x: 86, y: 46, label: { de: 'Ein Klick: Installieren', en: 'One click: install' }, d: 3300, left: true },
      ]),
      { left: 930, top: 360, width: 900, z: 2 },
      450,
      'r',
      { ry0: -14, ry1: -6 },
    ),
}

const client: Slide = {
  id: 'client',
  dur: 10,
  html: () =>
    col(
      head({ de: 'TRS Client', en: 'TRS Client' }, { de: 'Dein Client-Mod.<br><em>Gratis dabei.</em>', en: 'Your client mod.<br><em>Included, free.</em>' }) +
        points(
          [
            { de: '<b>HUD</b>: FPS, CPS, Tastenanzeige, Ping, Rüstung, Koordinaten', en: '<b>HUD</b>: FPS, CPS, keystrokes, ping, armour, coordinates' },
            { de: '<b>Minimap & Weltkarte</b> mit Wegpunkten', en: '<b>Minimap & world map</b> with waypoints' },
            { de: '<b>Zoom & Freelook</b>, Kamera-Fahrten mit Freecam', en: '<b>Zoom & freelook</b>, camera paths with freecam' },
            { de: '<b>PvP & Komfort</b>: Fadenkreuz, Trefferfarbe, Chat-Extras', en: '<b>PvP & comfort</b>: crosshair, hit colour, chat extras' },
          ],
          600,
        ) +
        a(
          `<p class="small" style="margin-top:30px">${t({
            de: 'Fair Play: kein Reach, keine Hitbox-Tricks, kein Autoklicker.<br>Fabric/Quilt 1.14.4–26.3 · Forge 1.7.10–26.3 · NeoForge 1.20.2–26.3',
            en: 'Fair play: no reach, no hitbox tricks, no auto-clicking.<br>Fabric/Quilt 1.14.4–26.3 · Forge 1.7.10–26.3 · NeoForge 1.20.2–26.3',
          })}</p>`,
          1400,
          'f',
        ),
      130,
      780,
    ) +
    vis(game('minimap', 'Minimap', 'Minimap', { s0: 1.0, s1: 1.12, ox: 85, oy: 30 }), { left: 900, top: 110, width: 640, z: 1 }, 200, 'z', null, true) +
    vis(game('pvp-hud', 'PvP-HUD', 'PvP-HUD', { s0: 1.05, s1: 1.0 }), { left: 1360, top: 290, width: 480, z: 3 }, 600, 'z') +
    vis(game('trs-menu', { de: 'TRS-Menü', en: 'TRS menu' }, { de: 'TRS-Menü', en: 'TRS menu' }, { s0: 1, s1: 1.08, ox: 30, oy: 30 }), { left: 860, top: 540, width: 560, z: 2 }, 1000, 'z') +
    vis(game('worldmap', { de: 'Weltkarte', en: 'World map' }, { de: 'Weltkarte', en: 'World map' }, { s0: 1.08, s1: 1, ox: 30, oy: 40 }), { left: 1340, top: 680, width: 480, z: 4 }, 1400, 'z'),
}

const perf: Slide = {
  id: 'perf',
  dur: 10,
  html: () => {
    const max = 1110
    const W = 400
    const rows: [T, T, number, string][] = [
      [{ de: 'Vanilla', en: 'Vanilla' }, { de: 'ohne Mods', en: 'no mods' }, 739, ''],
      [{ de: 'TRS Client', en: 'TRS Client' }, { de: 'Standard-Module', en: 'default modules' }, 740, 'hot'],
      [{ de: 'TRS Client', en: 'TRS Client' }, { de: '+ Preset „Max FPS“', en: '+ “Max FPS” preset' }, 1110, 'amber'],
    ]
    return (
      col(
        head(
          { de: 'Leistung', en: 'Performance' },
          { de: 'Schnell –<br><em>ohne Haken.</em>', en: 'Fast –<br><em>no catch.</em>' },
          {
            de: 'Der TRS Client selbst kostet laut Profiler nur <b>0,8 %</b> der Renderzeit. Mehr FPS gibt es per Klick: das Preset „Max FPS“ mit Sodium & Co.',
            en: 'According to the profiler the TRS Client itself costs just <b>0.8 %</b> of render time. More FPS is one click away: the “Max FPS” preset with Sodium & co.',
          },
        ),
        170,
        780,
      ) +
      `<div class="chart">${a(`<div class="small" style="margin:0 0 34px 294px">${t({ de: 'Ø Bilder pro Sekunde (FPS)', en: 'Average frames per second (FPS)' })}</div>`, 300, 'f')}${rows
        .map(
          ([l, s, v, cls], i) =>
            `<div class="bar-row">${a(`<div class="lbl">${t(l)}<small>${t(s)}</small></div>`, 500 + i * 350, 'l')}<div class="bar-track" style="--w:${Math.round((v / max) * W)}px"><div class="bar ${cls}" style="--d:${700 + i * 350}ms"></div>${a(
              `<span class="bar-val">${count(v, 1400, 700 + i * 350)}<small>FPS</small></span>`,
              900 + i * 350,
              'f',
              '',
              'position:absolute;inset:0',
            )}</div></div>`,
        )
        .join('')}</div>` +
      `<div class="foot a f" style="--d:2000ms">${t({
        de: 'TRS-Benchmark vom 25.09.2026: Minecraft 1.21.11 (Fabric), Dorf-Szene mit festem Seed, ein Test-PC (Windows 11, 1920×1009, VSync aus). Läufe derselben Messreihe; Läufe schwanken um ±15–25 %.',
        en: 'TRS benchmark, 25 Sep 2026: Minecraft 1.21.11 (Fabric), village scene with a fixed seed, one test PC (Windows 11, 1920×1009, VSync off). Runs from the same series; runs vary by ±15–25 %.',
      })}</div>`
    )
  },
}

const redstone: Slide = {
  id: 'redstone',
  dur: 10,
  html: () =>
    col(
      head({ de: 'Für Redstone gebaut', en: 'Built for redstone' }, { de: 'Redstone,<br><em>sichtbar gemacht.</em>', en: 'Redstone,<br><em>made visible.</em>' }) +
        points(
          [
            { de: '<b>Signalstärke</b> über jedem Redstone-Staub', en: '<b>Signal strength</b> above every redstone dust' },
            { de: '<b>Takt-Messer</b> für Timings und Verzögerungen', en: '<b>Tick meter</b> for timings and delays' },
            { de: '<b>Schaltungs-Bibliothek</b>: fertige Schaltungen als Geisterblöcke in deiner Welt', en: '<b>Circuit library</b>: ready-made circuits as ghost blocks in your world' },
            { de: 'Mit <b>Materialliste</b> und 3D-Vorschau Schicht für Schicht', en: 'With a <b>material list</b> and a 3D preview, layer by layer' },
          ],
          600,
        ),
      150,
      780,
    ) +
    vis(
      game('redstone-overlay', { de: 'Signalstärke', en: 'Signal strength' }, { de: 'Signalstärke', en: 'Signal strength' }, { s0: 1, s1: 1.14, ox: 30, oy: 45 }, [
        { x: 36, y: 38, label: { de: '15 … 1', en: '15 … 1' }, d: 2200 },
      ]),
      { left: 920, top: 120, width: 820, z: 1 },
      250,
      'r',
      { ry0: -12, ry1: -6 },
    ) +
    vis(
      game('circuit-ghost', { de: 'Schaltung als Geisterblöcke', en: 'Circuit as ghost blocks' }, { de: 'Geisterblöcke', en: 'Ghost blocks' }, { s0: 1.1, s1: 1, ox: 50, oy: 55 }),
      { left: 1240, top: 560, width: 600, z: 2 },
      900,
      'z',
    ),
}

const cosmetics: Slide = {
  id: 'cosmetics',
  dur: 10,
  html: () =>
    col(
      head({ de: 'Kosmetik', en: 'Cosmetics' }, { de: 'Zeig, wer<br><em>du bist.</em>', en: 'Show who<br><em>you are.</em>' }) +
        points(
          [
            { de: '<b>HD-Umhänge</b> bis 1024×512 – animiert mit bis zu 32 Bildern', en: '<b>HD capes</b> up to 1024×512 – animated with up to 32 frames' },
            { de: '<b>Umhang-Werkstatt</b>: Zeitleiste, Pixel-Editor, 3D-Vorschau', en: '<b>Cape workshop</b>: timeline, pixel editor, 3D preview' },
            { de: '<b>Echte 3D-Kosmetik</b>: Kronen, Hüte, Heiligenschein – und Flügel', en: '<b>Real 3D cosmetics</b>: crowns, hats, halo – and wings' },
            { de: '<b>Emotes</b> mit Emote-Rad und Skin-Editor', en: '<b>Emotes</b> with an emote wheel, plus a skin editor' },
          ],
          600,
        ),
      150,
      780,
    ) +
    vis(game('cape-hd-ingame', { de: 'HD-Umhang', en: 'HD cape' }, { de: 'HD-Umhang', en: 'HD cape' }, { s0: 1.0, s1: 1.18, ox: 50, oy: 60 }), { left: 900, top: 120, width: 560, z: 1 }, 200, 'z', null, true) +
    vis(game('crown', { de: 'Redstone-Krone', en: 'Redstone crown' }, { de: '3D-Krone', en: '3D crown' }, { s0: 1.1, s1: 1.0, ox: 50, oy: 35 }), { left: 1400, top: 200, width: 440, z: 3 }, 600, 'z') +
    vis(game('wings', { de: 'Flügel', en: 'Wings' }, { de: 'Flügel', en: 'Wings' }, { s0: 1, s1: 1.12, ox: 50, oy: 50 }), { left: 940, top: 560, width: 480, z: 2 }, 1000, 'z') +
    vis(game('emote-wheel', { de: 'Emote-Rad', en: 'Emote wheel' }, { de: 'Emote-Rad', en: 'Emote wheel' }, { s0: 1.06, s1: 1 }), { left: 1380, top: 620, width: 460, z: 4 }, 1400, 'z'),
}

const social: Slide = {
  id: 'social',
  dur: 10,
  html: () =>
    col(
      head({ de: 'Sozial', en: 'Social' }, { de: 'Zusammen<br><em>spielen.</em>', en: 'Play<br><em>together.</em>' }) +
        points(
          [
            { de: '<b>Freunde</b>, Direktnachrichten und Gruppen', en: '<b>Friends</b>, direct messages and groups' },
            { de: 'Bis zu <b>10 Bilder</b> pro Nachricht, Reaktionen, Lesebestätigungen', en: 'Up to <b>10 pictures</b> per message, reactions, read receipts' },
            { de: '<b>Server-Einladungen</b> mit „Beitreten“ – die passende Version startet von selbst', en: '<b>Server invites</b> with “Join” – the right version starts by itself' },
            { de: 'Chat auch <b>im Spiel</b>, mit Schnellantwort', en: 'Chat <b>in game</b> too, with quick reply' },
          ],
          600,
        ) +
        a(`<p class="small" style="margin-top:30px">${t({ de: 'Alle Online-Funktionen sind aus, bis du zustimmst.', en: 'All online features stay off until you agree.' })}</p>`, 1400, 'f'),
      150,
      780,
    ) +
    vis(win('friends', { de: 'Freunde', en: 'Friends' }, { s0: 1, s1: 1.04 }), { left: 1170, top: 110, width: 660, z: 1 }, 200, 'f', { ry0: -12, ry1: -8 }) +
    vis(
      win('chat', 'Chat', { s0: 1, s1: 1.1, ox: 60, oy: 40 }, [
        { x: 40, y: 34, label: { de: 'Gruppen', en: 'Groups' }, d: 2200 },
        { x: 82, y: 75, label: { de: 'Server-Einladung', en: 'Server invite' }, d: 3200, left: true },
      ]),
      { left: 930, top: 330, width: 900, z: 2 },
      450,
      'r',
      { ry0: -14, ry1: -6 },
    ),
}

const hosting: Slide = {
  id: 'hosting',
  dur: 10,
  html: () =>
    col(
      head({ de: 'Welten & Modpacks teilen', en: 'Share worlds & modpacks' }, { de: 'Deine Welt.<br><em>Ohne Portfreigabe.</em>', en: 'Your world.<br><em>No port forwarding.</em>' }) +
        points(
          [
            { de: '<b>Einzelspielerwelt hosten</b> für bis zu 10 Spieler', en: '<b>Host your singleplayer world</b> for up to 10 players' },
            { de: 'Direkt (P2P) oder über das <b>TRS-Relay</b>', en: 'Direct (P2P) or through the <b>TRS relay</b>' },
            { de: 'Freunde treten <b>aus dem Launcher</b> bei – per Code oder Einladung', en: 'Friends join <b>from the launcher</b> – by code or invite' },
            { de: '<b>Modpacks teilen</b> per Code, Link oder direkt an Freunde – bis 1 GB', en: '<b>Share modpacks</b> by code, link or straight to friends – up to 1 GB' },
          ],
          600,
        ),
      150,
      800,
    ) +
    vis(win('worlds', { de: 'Welten von Freunden', en: "Friends' worlds" }, { s0: 1, s1: 1.08, ox: 30, oy: 30 }), { left: 960, top: 110, width: 860, z: 1 }, 200, 'r', { ry0: -12, ry1: -6 }) +
    vis(
      win('share-result', { de: 'Modpack geteilt', en: 'Modpack shared' }, { s0: 1.06, s1: 1, ox: 50, oy: 30 }, [
        { x: 30, y: 20, label: { de: 'Code: TRS-…', en: 'Code: TRS-…' }, d: 2600 },
      ]),
      { left: 1180, top: 560, width: 640, z: 2 },
      900,
      'z',
    ),
}

const clips: Slide = {
  id: 'clips',
  dur: 10,
  html: () =>
    col(
      head({ de: 'Clips & Screenshots', en: 'Clips & screenshots' }, { de: 'Der Moment?<br><em>Gesichert.</em>', en: 'That moment?<br><em>Saved.</em>' }) +
        points(
          [
            { de: '<b>F9</b> speichert die letzten 15–120 Sekunden, <b>F10</b> nimmt auf', en: '<b>F9</b> saves the last 15–120 seconds, <b>F10</b> records' },
            { de: 'Hardware-Encoding (NVENC, AMF, QSV) mit Systemton – <b>vorerst nur Windows</b>', en: 'Hardware encoding (NVENC, AMF, QSV) with system sound – <b>Windows only for now</b>' },
            { de: 'Clips <b>zuschneiden</b> und teilen', en: '<b>Trim</b> and share clips' },
            { de: '<b>Screenshot-Editor</b>: zuschneiden, Pfeile, Text, verpixeln – als Link teilen', en: '<b>Screenshot editor</b>: crop, arrows, text, pixelate – share as a link' },
          ],
          600,
        ),
      150,
      780,
    ) +
    vis(
      win('clip-trim', { de: 'Clip zuschneiden', en: 'Trim a clip' }, { s0: 1, s1: 1.08, ox: 50, oy: 80 }, [
        { x: 50, y: 82, label: { de: 'Zuschneiden mit I / O', en: 'Trim with I / O' }, d: 2200 },
      ], 'TRS Launcher – Clips'),
      { left: 940, top: 110, width: 880, z: 1 },
      250,
      'r',
      { ry0: -12, ry1: -6 },
    ) +
    vis(game('screenshot-editor', { de: 'Screenshot-Editor', en: 'Screenshot editor' }, { de: 'Screenshot-Editor', en: 'Screenshot editor' }, { s0: 1.05, s1: 1 }), { left: 1280, top: 610, width: 560, z: 2 }, 900, 'z'),
}

const designs: Slide = {
  id: 'designs',
  dur: 10,
  html: () => {
    const cards: [string, T, T, number, number][] = [
      ['home', { de: 'Redstone', en: 'Redstone' }, { de: 'Live-Schaltung auf der Startseite', en: 'A live circuit on the start page' }, 140, -4],
      ['winter-home', { de: 'Winternacht', en: 'Winter Night' }, { de: 'Polarlicht und Pinguine', en: 'Northern lights and penguins' }, 650, 0],
      ['halloween-library', { de: 'Halloween · Mondnacht', en: 'Halloween · Moon Night' }, { de: 'Event-Design mit Blutmond', en: 'Event design with a blood moon' }, 1160, 4],
    ]
    return (
      col(head({ de: 'Startseite & Designs', en: 'Start page & designs' }, { de: 'Dein Launcher.<br><em>Dein Look.</em>', en: 'Your launcher.<br><em>Your look.</em>' }), 110, 820) +
      `<div style="position:absolute;left:1000px;top:190px;width:810px">${a(
        `<p class="lead" style="margin:0">${t({
          de: 'Startseite mit News und vom Team empfohlenen Modpacks. Designs wie Winternacht, Events wie Halloween – dazu Dunkel, OLED oder Hell und Akzentfarben. Auch die Start-Animation passt sich an.',
          en: 'A start page with news and modpacks picked by the team. Designs like Winter Night, events like Halloween – plus dark, OLED or light and accent colours. Even the start animation follows along.',
        })}</p>`,
        400,
      )}</div>` +
      `<div class="trio">${cards
        .map(
          ([n, l, s, x, r], i) =>
            `<div class="card a z" style="--d:${800 + i * 300}ms;left:${x}px;z-index:${i === 1 ? 3 : 1}"><div style="transform:rotate(${r}deg) ${i === 1 ? 'scale(1.06)' : 'scale(0.94)'};transform-origin:50% 100%">${win(
              n,
              t(l),
              { s0: 1, s1: 1.1, ox: 50, oy: 30 },
            )}<div class="lbl">${t(l)}<small>${t(s)}</small></div></div></div>`,
        )
        .join('')}</div>`
    )
  },
}

const creator: Slide = {
  id: 'creator',
  dur: 9,
  html: () =>
    col(
      head({ de: 'Creator-Programm', en: 'Creator programme' }, { de: 'Für Creator<br><em>und ihre Fans.</em>', en: 'For creators<br><em>and their fans.</em>' }) +
        points(
          [
            { de: '<b>Creator-Codes</b>: Fans unterstützen ihren Lieblings-Creator', en: '<b>Creator codes</b>: fans support their favourite creator' },
            { de: '<b>Fan-Umhänge & Fan-Symbole</b> – sichtbar im Spiel', en: '<b>Fan capes & fan symbols</b> – visible in game' },
            { de: '<b>Dashboard</b> mit Statistik, eigenen Codes und Helfern', en: '<b>Dashboard</b> with statistics, own codes and helpers' },
            { de: 'Stufen <b>Starter · Partner · Top</b>, Meilensteine bis 5.000 Fans', en: 'Tiers <b>Starter · Partner · Top</b>, milestones up to 5,000 fans' },
          ],
          600,
        ),
      150,
      780,
    ) +
    vis(win('creator-dashboard', { de: 'Creator-Dashboard', en: 'Creator dashboard' }, { s0: 1, s1: 1.08, ox: 40, oy: 20 }), { left: 960, top: 90, width: 640, z: 1 }, 200, 'r', { ry0: -12, ry1: -6 }) +
    vis(
      game('fan-symbol', { de: 'Fan-Symbol im Spiel', en: 'Fan symbol in game' }, { de: 'Fan-Symbol im Spiel', en: 'Fan symbol in game' }, { s0: 1, s1: 1.12, ox: 50, oy: 45 }, [
        { x: 50, y: 46, label: { de: 'Fan-Symbol', en: 'Fan symbol' }, d: 2400, left: true },
      ]),
      { left: 1180, top: 560, width: 660, z: 2 },
      900,
      'z',
    ),
}

const mobile: Slide = {
  id: 'mobile',
  dur: 10,
  html: () =>
    col(
      head({ de: 'Mobil', en: 'Mobile' }, { de: 'Minecraft Java<br><em>für unterwegs.</em>', en: 'Minecraft Java<br><em>on the go.</em>' }) +
        points(
          [
            { de: '<b>Android & iOS</b> (Vorschau): Minecraft: Java Edition auf dem Handy', en: '<b>Android & iOS</b> (preview): Minecraft: Java Edition on your phone' },
            { de: '<b>Touch-Steuerung</b> mit Editor – oder Controller und Maus', en: '<b>Touch controls</b> with an editor – or controller and mouse' },
            { de: 'Freunde, Chat, Skins und Modpacks immer dabei', en: 'Friends, chat, skins and modpacks always with you' },
            { de: '<b>Big Picture</b> für Controller, Fernseher und Steam Deck', en: '<b>Big Picture</b> for controllers, TVs and the Steam Deck' },
          ],
          600,
        ) +
        a(`<p class="small" style="margin-top:30px">${t({ de: 'Vorschau = noch in Entwicklung, nicht im App Store / Play Store.<br>Android per APK von der Website · iOS per AltStore/SideStore · am besten ab Minecraft 1.21', en: 'Preview = still in development, not in the App Store / Play Store.<br>Android via APK from the website · iOS via AltStore/SideStore · best on Minecraft 1.21 and newer' })}</p>`, 1400, 'f'),
      150,
      780,
    ) +
    vis(win('big-picture', 'Big Picture', { s0: 1, s1: 1.06 }), { left: 1150, top: 250, width: 680, z: 1 }, 700, 'r', { ry0: -12, ry1: -6 }) +
    vis(phone('mobile-app', { de: 'TRS Launcher auf dem Handy', en: 'TRS Launcher on a phone' }), { left: 930, top: 110, width: 400, z: 2 }, 200, 'z', null, true),
}

const compare: Slide = {
  id: 'compare',
  dur: 15,
  html: () => {
    const Y = `<span class="mk y">${CHECK}</span>`
    const N = '<span class="mk n">–</span>'
    const P = `<span class="mk p">${t({ de: 'teilweise', en: 'partly' })}</span>`
    const U = '<span class="mk u">?</span>'
    // Prism leaves these out on purpose (a plain launcher) – that is scope, not a gap.
    const G = `<span class="mk g">${t({ de: 'kein Ziel', en: 'not a goal' })}</span>`
    const cols = ['TRS Launcher', t({ de: 'Offizieller Launcher', en: 'Official launcher' }), 'Modrinth App', 'Prism Launcher', 'CurseForge App', 'Lunar Client', 'Feather Client']
    const rows: [T, string[]][] = [
      [{ de: 'Kostenlos nutzbar', en: 'Free to use' }, [Y, Y, Y, Y, Y, Y, Y]],
      [{ de: 'Ohne Werbung', en: 'No ads' }, [Y, Y, P, Y, N, U, U]],
      [{ de: 'Open Source', en: 'Open source' }, [Y, N, Y, Y, N, N, N]],
      [{ de: 'Windows, macOS & Linux', en: 'Windows, macOS & Linux' }, [P, Y, Y, Y, P, Y, P]],
      [{ de: 'Bedrock Edition starten', en: 'Launch Bedrock Edition' }, [N, Y, N, N, N, N, N]],
      [{ de: 'Fabric, Quilt, Forge & NeoForge', en: 'Fabric, Quilt, Forge & NeoForge' }, [Y, N, Y, Y, Y, P, P]],
      [{ de: 'Modpacks von Modrinth & CurseForge', en: 'Modpacks from Modrinth & CurseForge' }, [Y, N, P, Y, P, N, N]],
      [{ de: 'Eigener Client-Mod (HUD, PvP, FPS)', en: 'Own client mod (HUD, PvP, FPS)' }, [Y, N, N, G, N, Y, Y]],
      [{ de: 'Freunde & Chat', en: 'Friends & chat' }, [Y, N, U, G, N, Y, Y]],
      [{ de: 'Welt hosten ohne Portfreigabe', en: 'Host a world without port forwarding' }, [Y, N, N, N, N, U, Y]],
      [{ de: 'App für Android & iOS', en: 'App for Android & iOS' }, [P, N, N, N, N, N, N]],
    ]
    let k = 0
    const cell = (v: string, i: number) => `<td class="${i === 0 ? 'trs' : ''}"><span class="a pop" style="--d:${900 + (k++ % 60) * 30 + (i === 0 ? 0 : 300)}ms;display:inline-block">${v}</span></td>`
    return (
      col(head({ de: 'Im Vergleich', en: 'Compared' }, { de: 'Ehrlich <em>verglichen.</em>', en: 'An honest <em>comparison.</em>' }), 70, 1700) +
      `<table class="cmp cmp7"><thead><tr><th></th>${cols.map((c, i) => `<th class="${i === 0 ? 'trs' : ''}"><span class="a f" style="--d:${500 + i * 80}ms;display:inline-block">${c}</span></th>`).join('')}</tr></thead><tbody>${rows
        .map(([l, v], r) => `<tr><td><span class="a l" style="--d:${600 + r * 70}ms;display:inline-block">${t(l)}</span></td>${v.map(cell).join('')}</tr>`)
        .join('')}</tbody></table>` +
      `<div class="cmp-legend a f" style="--d:2600ms">${t({
        de: 'Stand Oktober 2026, nach bestem Wissen (Herstellerseiten), ohne Gewähr. „teilweise“ = mit Einschränkungen (TRS: kein macOS, Handy als Vorschau) · „?“ = nicht sicher geprüft · „kein Ziel“ = bewusst nicht im Umfang.',
        en: 'As of October 2026, to the best of our knowledge (vendor sites), no guarantee. “partly” = with limits (TRS: no macOS, phones as a preview) · “?” = not reliably checked · “not a goal” = deliberately out of scope.',
      })}</div>`
    )
  },
}

const numbers: Slide = {
  id: 'numbers',
  dur: 9,
  html: () => {
    const stats: [string, string, T, T][] = [
      ['47', '', { de: 'Updates in 30 Tagen', en: 'updates in 30 days' }, { de: 'vom 22.09. bis 21.10.2026', en: '22 Sep to 21 Oct 2026' }],
      ['105', '', { de: 'TRS-Client-Builds', en: 'TRS Client builds' }, { de: 'Fabric 39 · Forge 44 · NeoForge 22', en: 'Fabric 39 · Forge 44 · NeoForge 22' }],
      ['8', '', { de: 'Sprachen', en: 'languages' }, { de: 'DE, EN, ES – dazu FR, NL, PL, PT-BR, TR (Beta)', en: 'EN, DE, ES – plus FR, NL, PL, PT-BR, TR (beta)' }],
      ['1800', '+', { de: 'automatische Tests', en: 'automated tests' }, { de: 'für Rust-Kern und Oberfläche', en: 'for the Rust core and the UI' }],
    ]
    return (
      col(head({ de: 'Zahlen & Technik', en: 'Numbers & tech' }, { de: 'Unter der <em>Haube.</em>', en: 'Under the <em>hood.</em>' }), 110, 1700) +
      `<div class="stats">${stats
        .map(
          ([n, sup, l, d], i) =>
            `<div class="stat a z" style="--d:${500 + i * 200}ms"><div class="n">${count(Number(n), 1600, 700 + i * 200)}${sup ? `<sup>${sup}</sup>` : ''}</div><div class="l">${t(l)}</div><div class="d">${t(d)}</div></div>`,
        )
        .join('')}</div>` +
      `<div class="tech">${[
        'Tauri 2',
        { de: 'Rust-Kern', en: 'Rust core' },
        'Vue 3 + Nuxt 4',
        'TypeScript',
        'Tailwind CSS 4',
        'Minecraft 1.5.2 → 26.x',
        { de: 'Installer ohne Admin-Rechte', en: 'Installer without admin rights' },
      ]
        .map((x, i) => `<span class="chip a pop" style="--d:${1700 + i * 100}ms">${t(x)}</span>`)
        .join('')}</div>`
    )
  },
}

// Usage: GitHub release downloads (installers + update downloads, all releases) and the TRS API
// (accounts = people who turned on the optional online features). Read on 22 Oct 2026 – refresh before each event.
const traction: Slide = {
  id: 'traction',
  dur: 10,
  html: () => {
    const stats: [number, T, T][] = [
      [415, { de: 'Downloads', en: 'downloads' }, { de: 'Installer & Updates auf GitHub', en: 'installers & updates on GitHub' }],
      [32, { de: 'TRS-Konten', en: 'TRS accounts' }, { de: 'Online-Funktionen sind freiwillig', en: 'online features are optional' }],
      [18, { de: 'aktiv in 7 Tagen', en: 'active in 7 days' }, { de: 'mit TRS-Konto', en: 'with a TRS account' }],
      [14, { de: 'Ideen & Bugs gemeldet', en: 'ideas & bugs reported' }, { de: 'im öffentlichen Issue-Tracker', en: 'in the public issue tracker' }],
    ]
    return (
      col(
        head(
          { de: 'Wo wir stehen', en: 'Where we are' },
          { de: 'Früh dran.<br><em>Ehrliche Zahlen.</em>', en: 'Early days.<br><em>Honest numbers.</em>' },
          {
            de: 'Den TRS Launcher gibt es seit gut einem Monat. Die Zahlen sind klein – deshalb zählt jede Rückmeldung.',
            en: 'The TRS Launcher has been out for just over a month. The numbers are small – so every bit of feedback counts.',
          },
        ),
        110,
        1500,
      ) +
      `<div class="stats" style="top:500px">${stats
        .map(
          ([n, l, d], i) =>
            `<div class="stat a z" style="--d:${600 + i * 200}ms"><div class="n">${count(n, 1600, 800 + i * 200)}</div><div class="l">${t(l)}</div><div class="d">${t(d)}</div></div>`,
        )
        .join('')}</div>` +
      `<div class="cmp-legend a f" style="--d:2200ms">${t({ de: 'Stand 22.10.2026 · seit dem ersten Release am 22.09.2026', en: 'As of 22 Oct 2026 · since the first release on 22 Sep 2026' })}</div>`
    )
  },
}

const honest: Slide = {
  id: 'honest',
  dur: 13,
  html: () => {
    const qa: [T, T][] = [
      [
        { de: 'Was, wenn der Entwickler aufhört?', en: 'What if the developer stops?' },
        {
          de: '<b>Open Source (GPL-3.0):</b> Code, Builds und Doku liegen öffentlich auf GitHub – jeder darf weitermachen. Dazu ein <b>Team aus 6 Leuten</b> und über 1.800 automatische Tests bei jeder Änderung. Und der Launcher läuft auch <b>ohne TRS-Server</b>: Online-Funktionen sind freiwillig, deine Instanzen liegen auf deinem PC.',
          en: '<b>Open source (GPL-3.0):</b> code, builds and docs are public on GitHub – anyone may carry on. Plus a <b>team of 6</b> and more than 1,800 automated tests on every change. And the launcher also runs <b>without TRS servers</b>: online features are optional, your instances stay on your PC.',
        },
      ],
      [
        { de: 'Wer bezahlt das?', en: 'Who pays for it?' },
        {
          de: '<b>Kostenlos – ohne Abo, ohne Werbung, ohne Bezahl-Kosmetik.</b> Die TRS-Server (Konten, Freunde, Chat, Relay) betreibt TheRedstonee selbst. Grenzen wie 10 Spieler pro gehosteter Welt und 1 GB pro geteiltem Modpack halten die Kosten im Rahmen.',
          en: '<b>Free – no subscription, no ads, no paid cosmetics.</b> TheRedstonee runs the TRS servers (accounts, friends, chat, relay) himself. Limits such as 10 players per hosted world and 1 GB per shared modpack keep costs in check.',
        },
      ],
    ]
    return (
      col(head({ de: 'Ehrlich gefragt', en: 'Honest answers' }, { de: 'Die Fragen,<br><em>die jeder stellt.</em>', en: 'The questions<br><em>everyone asks.</em>' }), 110, 1700) +
      `<div class="qa">${qa
        .map(([q, ans], i) => `<div class="qa-item a r" style="--d:${700 + i * 450}ms"><div class="q">${t(q)}</div><p>${t(ans)}</p></div>`)
        .join('')}</div>`
    )
  },
}

const tryIt: Slide = {
  id: 'try',
  dur: 9,
  html: (c) => {
    const left = c.demo
      ? head(
          { de: 'Jetzt du', en: 'Your turn' },
          { de: 'Probier’s<br><em>selbst aus.</em>', en: 'Try it<br><em>yourself.</em>' },
          { de: 'Hier am Stand kannst du den TRS Launcher direkt testen.', en: 'Test the TRS Launcher right here at the booth.' },
        ) +
        a(`<button class="cta" type="button" data-demo>▶ ${t({ de: 'Jetzt selbst ausprobieren', en: 'Try it yourself' })}</button>`, 900, 'pop') +
        a(`<p class="small" style="margin-top:26px">${t({ de: 'Einfach irgendwo antippen.', en: 'Just tap anywhere.' })}</p>`, 1300, 'f') +
        a(`<p class="small" style="margin-top:10px">${t(FEEDBACK)}</p>`, 1500, 'f')
      : head({ de: 'Jetzt du', en: 'Your turn' }, { de: 'Probier’s<br><em>selbst aus.</em>', en: 'Try it<br><em>yourself.</em>' }) +
        `<div class="steps">${[
          { de: `QR-Code scannen oder öffnen:<br><b class="url">${DL}</b>`, en: `Scan the QR code or open:<br><b class="url">${DL}</b>` },
          { de: 'Installieren – unter Windows ohne Admin-Rechte', en: 'Install – no admin rights needed on Windows' },
          { de: 'Mit deinem Microsoft-Konto anmelden und spielen', en: 'Sign in with your Microsoft account and play' },
        ]
          .map((s, i) => `<div class="step a l" style="--d:${600 + i * 250}ms"><b>${i + 1}</b><span>${t(s)}</span></div>`)
          .join('')}</div>` +
        a(`<p class="small" style="margin-top:30px">${t(FEEDBACK)}</p>`, 1500, 'f')
    return (
      col(left, c.demo ? 200 : 230, 900) +
      vis(
        `<div class="qr">${c.qr}</div><div class="qr-cap">${t(c.demo ? { de: 'Für zu Hause: kostenlos laden', en: 'For home: free download' } : { de: 'Kostenlos herunterladen', en: 'Free download' })}</div>`,
        { left: 1250, top: c.demo ? 260 : 250, width: c.demo ? 400 : 460 },
        500,
        'z',
        null,
        true,
      )
    )
  },
}

const outro: Slide = {
  id: 'outro',
  dur: 8,
  html: (c) =>
    `<div class="outro"><div>${a(`<img class="logo-big" src="${A('icon.png')}" alt="TRS Launcher">`, 100, 'pop')}${a('<h1>TRS Launcher</h1>', 350)}${a(
      `<div class="sub">${t({ de: 'Der Minecraft-Launcher von TheRedstonee · Kostenlos · Open Source', en: 'The Minecraft launcher by TheRedstonee · Free · Open source' })}</div>`,
      600,
      'f',
    )}<div class="links">${[
      ['Download', DL],
      ['Discord', 'dc.theredstonee.de'],
      ['Wiki', 'trs-launcher.wiki'],
    ]
      .map(([k, v], i) => `<div class="link a" style="--d:${900 + i * 180}ms"><small>${k}</small><span>${v}</span></div>`)
      .join('')}</div></div>${c.demo ? '' : a(`<div class="qr">${c.qr}</div>`, 1500, 'pop', '', 'position:absolute;right:110px;top:90px')}</div>`,
}

export const SLIDES: Slide[] = [intro, problem, what, versions, modpacks, client, perf, redstone, cosmetics, social, hosting, clips, designs, creator, mobile, compare, numbers, traction, honest, tryIt, outro]
