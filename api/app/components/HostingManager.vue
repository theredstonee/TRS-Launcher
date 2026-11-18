<script setup lang="ts">
import { MINECRAFT_EULA_URL, MOTD_FOOTER_TEXT, type HostingState } from '~/utils/hosting-i18n'

// Eigener CraftingHosting-Server (§43) mit der Website-Sitzung: Zustand, Start/Stop, Konsole, Einstellungen, Zugang.
// Herzschlag alle 45 s, solange eine Sitzung läuft und der Tab sichtbar ist (oder erst kurz im Hintergrund).

interface HostServer {
  id: string
  name: string
  state: HostingState | string
  address: string | null
  domain?: string | null
  serverType: string | null
  version: string | null
  resources: { memoryMb: number, cpuPercent: number, diskMb: number }
  eulaAccepted: boolean
  access: 'friends' | 'open'
  session: {
    active: boolean
    remainingSeconds: number
    cooldownUntil: string | null
    lastStopReason: string | null
    hostPresent: boolean
  }
  lastStartAt: string | null
  deleteAt: string | null
  setup?: 'pending' | 'restarting' | 'done'
}

interface HostStats {
  cpuPercent: number | null
  memory: { usedBytes: number | null, limitBytes: number | null }
  players: { online: number, max: number } | null
}

interface HostSettings {
  motd: string
  maxPlayers: number
  gamemode: string
  difficulty: string
  hardcore: boolean
  pvp: boolean
  allowFlight: boolean
  seed: string
  levelType: string
  spawnProtection: number
  viewDistance: number
  simulationDistance: number
}

const HEARTBEAT_MS = 45_000
const LOGS_MS = 3000
const STATS_MS = 10_000
const HIDDEN_GRACE_MS = 5 * 60_000
const BASE = '/v1/craftinghosting'
const SETTING_KEYS = ['motd', 'maxPlayers', 'gamemode', 'difficulty', 'hardcore', 'pvp', 'allowFlight', 'seed', 'levelType',
  'spawnProtection', 'viewDistance', 'simulationDistance'] as const

const { h } = useHostingText()
const { fill, date, lang } = useLang()
const { account, loaded, load, api, loginUrl } = useAccount()
const lp = useLocalePath()
const t = computed(() => h.value.manage)

type Status = 'loading' | 'ready' | 'unavailable' | 'maintenance' | 'upstream' | 'error'
const status = ref<Status>('loading')
const server = ref<HostServer | null>(null)
const fetchedAt = ref(0)
const now = ref(Date.now())
const stats = ref<HostStats | null>(null)
const logs = ref<string[]>([])
const message = ref('')
const busy = ref(false)
const eula = ref(false)
const command = ref('')
const copied = ref(false)
const visible = ref(true)
const hiddenSince = ref(0)
const logBox = shallowRef<HTMLElement | null>(null)

const settings = ref<HostSettings | null>(null)
const saved = ref<HostSettings | null>(null)
const settingsState = ref<'idle' | 'loading' | 'ready' | 'notYet'>('idle')
const settingsMsg = ref('')
const settingsOk = ref(false)

const live = computed(() => !!server.value && ['starting', 'running', 'stopping'].includes(server.value.state))
const running = computed(() => server.value?.state === 'running')
const active = computed(() => !!server.value?.session.active)
const address = computed(() => server.value?.domain || server.value?.address || '')
const remaining = computed(() => {
  const s = server.value
  if (!s?.session.active) return 0
  return Math.max(0, s.session.remainingSeconds - Math.floor((now.value - fetchedAt.value) / 1000))
})
const canStart = computed(() => {
  const s = server.value
  if (!s || busy.value || s.session.active) return false
  if (!['offline', 'failed'].includes(s.state)) return false
  if (s.session.cooldownUntil && Date.parse(s.session.cooldownUntil) > now.value) return false
  return s.eulaAccepted || eula.value
})
const canStop = computed(() => !!server.value && !busy.value && (active.value || ['starting', 'running'].includes(server.value.state)))
/** Herzschlag: Sitzung läuft und Tab sichtbar oder höchstens HIDDEN_GRACE_MS im Hintergrund. */
const beating = computed(() => active.value && (visible.value || now.value - hiddenSince.value < HIDDEN_GRACE_MS))

function duration(sec: number): string {
  const hh = Math.floor(sec / 3600)
  const mm = Math.floor((sec % 3600) / 60)
  return hh > 0 ? `${hh} h ${mm} min` : `${mm} min`
}
function clock(iso: string): string {
  const d = new Date(iso)
  if (Number.isNaN(d.getTime())) return ''
  return new Intl.DateTimeFormat(lang.value === 'en' ? 'en-GB' : lang.value, { hour: '2-digit', minute: '2-digit' }).format(d)
}
function bytes(n: number | null | undefined): string {
  if (n == null) return '–'
  return n >= 1024 ** 3 ? `${(n / 1024 ** 3).toFixed(1)} GB` : `${Math.round(n / 1024 ** 2)} MB`
}

function errorText(e: unknown): string {
  const code = apiCode(e)
  if (code === 'cooldown') {
    const until = (e as { data?: { error?: { cooldownUntil?: string } } }).data?.error?.cooldownUntil
    return fill(t.value.errors.cooldown!, { time: until ? clock(until) : '?' })
  }
  if (code === 'craftinghosting_maintenance') return t.value.maintenance
  if (code === 'craftinghosting_unavailable') return t.value.unavailable
  if (code === 'craftinghosting_upstream') return t.value.upstream
  return t.value.errors[code] ?? fill(t.value.failed, { error: apiMessage(e) })
}

function setServer(s: HostServer | null) {
  server.value = s
  fetchedAt.value = Date.now()
  if (!s) {
    stats.value = null
    logs.value = []
    settings.value = null
    settingsState.value = 'idle'
  }
}

async function loadServer(quiet = false) {
  if (!quiet) status.value = 'loading'
  try {
    const res = await api<{ server: HostServer | null }>(`${BASE}/server`)
    setServer(res.server)
    status.value = 'ready'
    if (res.server && settingsState.value === 'idle' && (res.server.lastStartAt || res.server.eulaAccepted)) void loadSettings()
  } catch (e) {
    const code = apiCode(e)
    if (quiet && status.value === 'ready' && code !== 'no_server') return
    if (code === 'no_server') {
      setServer(null)
      status.value = 'ready'
    } else if (code === 'craftinghosting_unavailable') status.value = 'unavailable'
    else if (code === 'craftinghosting_maintenance') status.value = 'maintenance'
    else if (code === 'craftinghosting_upstream') status.value = 'upstream'
    else {
      status.value = 'error'
      message.value = errorText(e)
    }
  }
}

async function heartbeat() {
  if (!beating.value) return
  try {
    const res = await api<{ server: HostServer }>(`${BASE}/server/heartbeat`, { method: 'POST' })
    setServer(res.server)
  } catch (e) {
    if (apiCode(e) === 'not_running' || apiCode(e) === 'no_server') await loadServer(true)
  }
}

async function loadLogs() {
  try {
    const res = await api<{ lines: string[] }>(`${BASE}/server/logs?lines=200`)
    const box = logBox.value
    const atBottom = !box || box.scrollHeight - box.scrollTop - box.clientHeight < 24
    logs.value = res.lines
    if (atBottom) void nextTick(() => { if (logBox.value) logBox.value.scrollTop = logBox.value.scrollHeight })
  } catch {
    // Konsole ist Beiwerk – nächster Versuch in 3 s.
  }
}

async function loadStats() {
  try {
    stats.value = await api<HostStats>(`${BASE}/server/stats`)
  } catch {
    stats.value = null
  }
}

async function loadSettings() {
  settingsState.value = 'loading'
  try {
    const res = await api<{ settings: HostSettings }>(`${BASE}/server/settings`)
    settings.value = { ...res.settings }
    saved.value = { ...res.settings }
    settingsState.value = 'ready'
  } catch (e) {
    settingsState.value = apiCode(e) === 'server_installing' ? 'notYet' : 'idle'
    if (settingsState.value === 'idle') settingsMsg.value = errorText(e)
  }
}

async function run(fn: () => Promise<void>) {
  busy.value = true
  message.value = ''
  try {
    await fn()
  } catch (e) {
    message.value = errorText(e)
    await loadServer(true)
  } finally {
    busy.value = false
  }
}

const start = () => run(async () => {
  const body = server.value?.eulaAccepted ? {} : { acceptEula: eula.value }
  const res = await api<{ server: HostServer }>(`${BASE}/server/start`, { method: 'POST', body })
  setServer(res.server)
  void loadLogs()
})

const stop = () => run(async () => {
  const res = await api<{ server: HostServer }>(`${BASE}/server/stop`, { method: 'POST', body: {} })
  setServer(res.server)
})

const setAccess = (access: 'friends' | 'open') => {
  if (!server.value || server.value.access === access) return
  void run(async () => {
    const res = await api<{ server: HostServer }>(`${BASE}/server`, { method: 'PATCH', body: { access } })
    setServer(res.server)
  })
}

async function sendCommand() {
  const cmd = command.value.trim()
  if (!cmd || busy.value) return
  await run(async () => {
    await api(`${BASE}/server/command`, { method: 'POST', body: { command: cmd } })
    command.value = ''
    await loadLogs()
  })
}

const settingsDirty = computed(() => {
  if (!settings.value || !saved.value) return false
  return SETTING_KEYS.some((k) => settings.value![k] !== saved.value![k])
})
const motdInvalid = computed(() => !!settings.value && (settings.value.motd.length > 60 || /[§\p{Cc}]/u.test(settings.value.motd)))

async function saveSettings() {
  if (!settings.value || !saved.value || motdInvalid.value) return
  const patch: Record<string, unknown> = {}
  for (const k of SETTING_KEYS) if (settings.value[k] !== saved.value[k]) patch[k] = settings.value[k]
  if (!Object.keys(patch).length) return
  settingsMsg.value = ''
  settingsOk.value = false
  busy.value = true
  try {
    const res = await api<{ settings: HostSettings }>(`${BASE}/server/settings`, { method: 'PATCH', body: patch })
    settings.value = { ...res.settings }
    saved.value = { ...res.settings }
    settingsOk.value = true
    setTimeout(() => (settingsOk.value = false), 2000)
  } catch (e) {
    settingsMsg.value = errorText(e)
  } finally {
    busy.value = false
  }
}

async function copyAddress() {
  if (!address.value) return
  try {
    await navigator.clipboard.writeText(address.value)
    copied.value = true
    setTimeout(() => (copied.value = false), 1600)
  } catch {
    // Zwischenablage gesperrt – die Adresse steht ja da.
  }
}

// --- Zeitgeber -------------------------------------------------------------------------------------------------------
const timers: ReturnType<typeof setInterval>[] = []
function every(ms: number, fn: () => unknown) {
  let inFlight = false
  timers.push(setInterval(async () => {
    if (inFlight) return
    inFlight = true
    try {
      await fn()
    } finally {
      inFlight = false
    }
  }, ms))
}

let polls = 0
function onVisibility() {
  visible.value = document.visibilityState === 'visible'
  now.value = Date.now()
  if (!visible.value) {
    hiddenSince.value = Date.now()
    return
  }
  if (!account.value || status.value !== 'ready' || !server.value) return
  void loadServer(true).then(() => (active.value ? heartbeat() : undefined))
}

onMounted(async () => {
  visible.value = document.visibilityState === 'visible'
  document.addEventListener('visibilitychange', onVisibility)
  if (!(await load())) return
  await loadServer()
  if (live.value) void loadLogs()
  if (running.value) void loadStats()
  every(HEARTBEAT_MS, heartbeat)
  every(LOGS_MS, () => (visible.value && live.value ? loadLogs() : undefined))
  every(STATS_MS, () => (visible.value && running.value ? loadStats() : undefined))
  // Zustand: schnell, solange er sich gerade ändert, sonst alle 30 s.
  every(5000, () => {
    now.value = Date.now()
    if (!visible.value || status.value !== 'ready' || !server.value) return
    const s = server.value
    const changing = ['installing', 'starting', 'stopping'].includes(s.state) || s.setup === 'restarting'
    polls++
    if (changing || polls % 6 === 0) return loadServer(true)
  })
})

onBeforeUnmount(() => {
  for (const id of timers) clearInterval(id)
  document.removeEventListener('visibilitychange', onVisibility)
})

watch(running, (r) => {
  if (r) void loadStats()
  else stats.value = null
})

const stateClass = computed(() => {
  const s = server.value?.state
  if (s === 'running') return 'st-ok'
  if (s === 'starting' || s === 'stopping' || s === 'installing') return 'st-busy'
  if (s === 'failed' || s === 'suspended') return 'st-bad'
  return 'st-off'
})
const stateLabel = computed(() => {
  const s = server.value?.state ?? 'offline'
  return (t.value.state as Record<string, string>)[s] ?? s
})
const cpuText = computed(() => {
  const c = stats.value?.cpuPercent
  return c == null ? '–' : `${Math.round(c)} %`
})
const cpuMax = computed(() => (server.value?.resources.cpuPercent ? `/ ${server.value.resources.cpuPercent} %` : ''))
const playersText = computed(() => (stats.value?.players ? `${stats.value.players.online} / ${stats.value.players.max}` : '–'))
const ramText = computed(() => bytes(stats.value?.memory.usedBytes))
const ramMax = computed(() => {
  const limit = stats.value?.memory.limitBytes ?? (server.value ? server.value.resources.memoryMb * 1024 ** 2 : null)
  return limit ? `/ ${bytes(limit)}` : ''
})
const cooldownActive = computed(() => {
  const c = server.value?.session.cooldownUntil
  return !!c && Date.parse(c) > now.value
})
</script>

<template>
  <section id="my-server" class="manage card mt-16 scroll-mt-24 p-5 sm:p-8" aria-labelledby="manage-title">
    <div class="flex flex-wrap items-end justify-between gap-3">
      <div>
        <h2 id="manage-title" class="heading text-3xl">{{ t.title }}</h2>
        <p class="mt-2 text-base-400">{{ t.lead }}</p>
      </div>
      <button v-if="account && status !== 'loading'" type="button" class="btn-icon" :aria-label="t.retry" :disabled="busy" @click="loadServer()">
        <SiteIcon name="refresh" class="size-4" />
      </button>
    </div>

    <div v-if="!loaded || (account && status === 'loading')" class="mt-6 grid gap-3" aria-busy="true">
      <span class="sr-only">{{ t.loading }}</span>
      <div class="skeleton h-24 rounded-xl" />
      <div class="skeleton h-40 rounded-xl" />
    </div>

    <div v-else-if="!account" class="empty mt-6">
      <SiteIcon name="user" class="size-6 text-base-400" />
      <p class="text-base-300">{{ t.signIn }}</p>
      <NuxtLink :to="loginUrl()" class="btn btn-primary"><SiteIcon name="user" class="size-4" />{{ t.signInButton }}</NuxtLink>
    </div>

    <div v-else-if="status !== 'ready'" class="empty mt-6" role="alert">
      <SiteIcon name="server" class="size-6 text-base-400" />
      <p class="text-base-300">{{ status === 'error' ? message : t[status as 'unavailable' | 'maintenance' | 'upstream'] }}</p>
      <button type="button" class="btn btn-ghost" @click="loadServer()"><SiteIcon name="refresh" class="size-4" />{{ t.retry }}</button>
    </div>

    <div v-else-if="!server" class="empty mt-6">
      <SiteIcon name="server" class="size-6 text-base-400" />
      <p class="font-semibold text-base-50">{{ t.noneTitle }}</p>
      <p class="max-w-md text-sm text-base-400">{{ t.noneText }}</p>
      <NuxtLink :to="lp('/download')" class="btn btn-primary"><SiteIcon name="download" class="size-4" />{{ t.noneButton }}</NuxtLink>
    </div>

    <div v-else class="mt-6 grid gap-6">
      <!-- Kopf: Name, Zustand, Adresse, Laufzeit -->
      <div class="grid gap-4 lg:grid-cols-[minmax(0,1fr)_auto] lg:items-start">
        <div class="min-w-0">
          <div class="flex flex-wrap items-center gap-2">
            <h3 class="truncate text-xl font-semibold text-base-50">{{ server.name }}</h3>
            <span class="state" :class="stateClass" role="status" aria-live="polite"><span class="state-dot" aria-hidden="true" />{{ stateLabel }}</span>
            <span v-if="server.serverType" class="chip">{{ server.serverType }} {{ server.version }}</span>
          </div>
          <div v-if="address" class="mt-3 flex min-w-0 items-center gap-2">
            <span class="text-xs text-base-400">{{ t.address }}</span>
            <code class="addr min-w-0 truncate">{{ address }}</code>
            <button type="button" class="btn-icon size-8" :aria-label="copied ? t.copied : t.copy" @click="copyAddress">
              <SiteIcon :name="copied ? 'check' : 'copy'" class="size-4" />
            </button>
          </div>
          <ul class="mt-3 grid gap-1 text-sm text-base-400">
            <li v-if="active" class="text-base-200"><SiteIcon name="clock" class="inline size-4 align-[-3px]" /> {{ fill(t.remaining, { time: duration(remaining) }) }}</li>
            <li v-if="active">{{ t.keepAlive }}</li>
            <li v-if="active && !visible">{{ t.hiddenPaused }}</li>
            <li v-if="active && !server.session.hostPresent" class="text-lamp-300">{{ t.hostAway }}</li>
            <li v-if="server.setup === 'restarting'" class="text-lamp-300">{{ t.setupRestarting }}</li>
            <li v-if="!active && server.session.lastStopReason && t.stopReason[server.session.lastStopReason]">{{ t.stopReason[server.session.lastStopReason] }}</li>
            <li v-if="cooldownActive">{{ fill(t.cooldown, { time: clock(server.session.cooldownUntil!) }) }}</li>
            <li v-if="!active && server.deleteAt">{{ fill(t.deleteAt, { date: date(server.deleteAt) }) }}</li>
          </ul>
        </div>

        <div class="flex flex-col gap-2 lg:items-end">
          <label v-if="!server.eulaAccepted && !active" class="flex items-center gap-2 text-sm text-base-300">
            <input v-model="eula" type="checkbox" class="check">
            <span>{{ t.eula }} <a :href="MINECRAFT_EULA_URL" class="text-lamp-300 underline-offset-2 hover:underline" rel="noopener" target="_blank">{{ t.eulaLink }}</a></span>
          </label>
          <div class="flex flex-wrap gap-2">
            <button type="button" class="btn btn-primary h-11 px-5" :disabled="!canStart" @click="start"><SiteIcon name="play" class="size-4" />{{ t.start }}</button>
            <button type="button" class="btn btn-ghost h-11 px-5" :disabled="!canStop" @click="stop"><SiteIcon name="pause" class="size-4" />{{ t.stop }}</button>
          </div>
        </div>
      </div>
      <p v-if="message" role="alert" class="text-sm text-redstone-300">{{ message }}</p>

      <!-- Live-Werte -->
      <dl class="grid grid-cols-3 gap-3">
        <div class="tile"><dt>{{ t.tiles.players }}</dt><dd>{{ playersText }}</dd></div>
        <div class="tile"><dt>{{ t.tiles.cpu }}</dt><dd>{{ cpuText }} <span class="tile-max">{{ cpuMax }}</span></dd></div>
        <div class="tile"><dt>{{ t.tiles.ram }}</dt><dd>{{ ramText }} <span class="tile-max">{{ ramMax }}</span></dd></div>
      </dl>

      <!-- Konsole -->
      <section aria-labelledby="console-title">
        <h3 id="console-title" class="section-title flex items-center gap-2"><SiteIcon name="terminal" class="size-4" />{{ t.console.title }}</h3>
        <div ref="logBox" class="console mt-2" role="log" :aria-label="t.console.label" tabindex="0">
          <p v-if="!logs.length" class="text-base-600">{{ t.console.empty }}</p>
          <div v-for="(line, i) in logs" :key="i" class="console-line">{{ line }}</div>
        </div>
        <form class="mt-2 flex gap-2" @submit.prevent="sendCommand">
          <label for="host-command" class="sr-only">{{ t.console.command }}</label>
          <input id="host-command" v-model="command" class="field mono" maxlength="256" autocomplete="off" spellcheck="false" :placeholder="t.console.placeholder" :disabled="!running">
          <button type="submit" class="btn btn-ghost shrink-0" :disabled="!running || !command.trim() || busy">{{ t.console.send }}</button>
        </form>
        <p v-if="!running" class="mt-1 text-xs text-base-400">{{ t.console.offline }}</p>
      </section>

      <div class="grid gap-6 lg:grid-cols-[minmax(0,1.4fr)_minmax(0,1fr)]">
        <!-- Einstellungen -->
        <section aria-labelledby="settings-title">
          <h3 id="settings-title" class="section-title flex items-center gap-2"><SiteIcon name="filter" class="size-4" />{{ t.settings.title }}</h3>
          <p v-if="settingsState === 'notYet' || (settingsState === 'idle' && !settingsMsg)" class="mt-2 text-sm text-base-400">{{ t.settings.notYet }}</p>
          <div v-else-if="settingsState === 'loading'" class="skeleton mt-2 h-48 rounded-xl" />
          <p v-else-if="settingsState === 'idle'" class="mt-2 text-sm text-redstone-300">{{ settingsMsg }}</p>
          <form v-else-if="settings" class="mt-3 grid gap-3" @submit.prevent="saveSettings">
            <div>
              <label class="label" for="hs-motd">{{ t.settings.motd }}</label>
              <input id="hs-motd" v-model="settings.motd" class="field" maxlength="60" :aria-invalid="motdInvalid" aria-describedby="hs-motd-hint">
              <p id="hs-motd-hint" class="mt-1 text-xs" :class="motdInvalid ? 'text-redstone-300' : 'text-base-400'">{{ t.settings.motdHint }} ({{ settings.motd.length }}/60)</p>
            </div>
            <div>
              <p class="label">{{ t.settings.footer }}</p>
              <p class="footer-line mono">{{ MOTD_FOOTER_TEXT }}</p>
            </div>
            <div class="grid gap-3 sm:grid-cols-2">
              <div>
                <label class="label" for="hs-gamemode">{{ t.settings.gamemode }}</label>
                <select id="hs-gamemode" v-model="settings.gamemode" class="field">
                  <option v-for="(label, id) in t.settings.gamemodes" :key="id" :value="id">{{ label }}</option>
                </select>
              </div>
              <div>
                <label class="label" for="hs-difficulty">{{ t.settings.difficulty }}</label>
                <select id="hs-difficulty" v-model="settings.difficulty" class="field">
                  <option v-for="(label, id) in t.settings.difficulties" :key="id" :value="id">{{ label }}</option>
                </select>
              </div>
              <div>
                <label class="label" for="hs-players">{{ t.settings.maxPlayers }}</label>
                <input id="hs-players" v-model.number="settings.maxPlayers" type="number" min="1" max="100" class="field">
              </div>
              <div>
                <label class="label" for="hs-spawn">{{ t.settings.spawnProtection }}</label>
                <input id="hs-spawn" v-model.number="settings.spawnProtection" type="number" min="0" max="64" class="field">
              </div>
              <div>
                <label class="label" for="hs-view">{{ t.settings.viewDistance }}</label>
                <input id="hs-view" v-model.number="settings.viewDistance" type="number" min="3" max="16" class="field">
              </div>
              <div>
                <label class="label" for="hs-sim">{{ t.settings.simulationDistance }}</label>
                <input id="hs-sim" v-model.number="settings.simulationDistance" type="number" min="3" max="16" class="field">
              </div>
              <div>
                <label class="label" for="hs-level">{{ t.settings.levelType }}</label>
                <select id="hs-level" v-model="settings.levelType" class="field">
                  <option v-for="(label, id) in t.settings.levelTypes" :key="id" :value="id">{{ label }}</option>
                </select>
              </div>
              <div>
                <label class="label" for="hs-seed">{{ t.settings.seed }}</label>
                <input id="hs-seed" v-model="settings.seed" class="field mono" maxlength="64" autocomplete="off" spellcheck="false">
              </div>
            </div>
            <div class="flex flex-wrap gap-x-5 gap-y-2">
              <label class="flex items-center gap-2 text-sm text-base-200"><input v-model="settings.pvp" type="checkbox" class="check">{{ t.settings.pvp }}</label>
              <label class="flex items-center gap-2 text-sm text-base-200"><input v-model="settings.hardcore" type="checkbox" class="check">{{ t.settings.hardcore }}</label>
              <label class="flex items-center gap-2 text-sm text-base-200"><input v-model="settings.allowFlight" type="checkbox" class="check">{{ t.settings.allowFlight }}</label>
            </div>
            <p class="text-xs text-base-400">{{ t.settings.note }}</p>
            <p v-if="settingsMsg" role="alert" class="text-sm text-redstone-300">{{ settingsMsg }}</p>
            <div class="flex items-center gap-3">
              <button type="submit" class="btn btn-primary" :disabled="!settingsDirty || motdInvalid || busy">{{ t.settings.save }}</button>
              <span v-if="settingsOk" class="text-sm text-[var(--color-ok)]" role="status">{{ t.settings.saved }}</span>
            </div>
          </form>
        </section>

        <!-- Zugang -->
        <section aria-labelledby="access-title">
          <h3 id="access-title" class="section-title flex items-center gap-2"><SiteIcon name="users" class="size-4" />{{ t.access.title }}</h3>
          <fieldset class="mt-3 grid gap-2" :disabled="busy">
            <legend class="sr-only">{{ t.access.title }}</legend>
            <label v-for="opt in (['friends', 'open'] as const)" :key="opt" class="access" :class="{ on: server.access === opt }">
              <input type="radio" name="host-access" class="check" :value="opt" :checked="server.access === opt" @change="setAccess(opt)">
              <span>
                <span class="block font-semibold text-base-50">{{ t.access[opt] }}</span>
                <span class="block text-sm text-base-400">{{ opt === 'friends' ? t.access.friendsText : t.access.openText }}</span>
              </span>
            </label>
          </fieldset>
          <p class="mt-4 flex gap-2 text-sm text-base-400"><SiteIcon name="blocks" class="mt-0.5 size-4 shrink-0" />{{ t.launcherOnly }}</p>
        </section>
      </div>
    </div>
  </section>
</template>

<style scoped>
.manage {
  border-color: color-mix(in srgb, var(--color-redstone-600) 35%, var(--color-base-800));
}
.empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 0.75rem;
  padding: 2rem 1rem;
  text-align: center;
  border: 1px dashed var(--color-base-700);
  border-radius: 0.75rem;
}
.mono,
.addr {
  font-family: var(--font-mono);
}
.addr {
  padding: 0.25rem 0.55rem;
  border-radius: 0.4rem;
  font-size: 0.85rem;
  color: var(--color-base-50);
  background: var(--color-base-950);
  border: 1px solid var(--color-base-800);
}
.state {
  display: inline-flex;
  align-items: center;
  gap: 0.4rem;
  padding: 0.15rem 0.6rem;
  border-radius: 999px;
  font-size: 0.75rem;
  font-weight: 600;
  background: var(--color-base-800);
  color: var(--color-base-200);
}
.state-dot {
  width: 0.5rem;
  height: 0.5rem;
  border-radius: 999px;
  background: currentColor;
}
.st-ok {
  color: var(--color-ok);
  background: color-mix(in srgb, var(--color-ok) 14%, var(--color-base-900));
}
.st-ok .state-dot {
  box-shadow: 0 0 8px var(--color-ok);
}
.st-busy {
  color: var(--color-warn);
  background: color-mix(in srgb, var(--color-warn) 14%, var(--color-base-900));
}
.st-busy .state-dot {
  animation: blink 1.2s ease-in-out infinite;
}
.st-bad {
  color: var(--color-redstone-300);
  background: color-mix(in srgb, var(--color-redstone-500) 18%, var(--color-base-900));
}
.st-off {
  color: var(--color-base-400);
}
@keyframes blink {
  50% {
    opacity: 0.3;
  }
}
.tile {
  padding: 0.85rem 1rem;
  border-radius: 0.6rem;
  background: var(--color-base-950);
  border: 1px solid var(--color-base-800);
}
.tile dt {
  font-size: 0.75rem;
  color: var(--color-base-400);
}
.tile dd {
  margin-top: 0.2rem;
  font-size: 1.15rem;
  font-weight: 600;
  color: var(--color-base-50);
  font-variant-numeric: tabular-nums;
}
.tile-max {
  font-size: 0.75rem;
  font-weight: 400;
  color: var(--color-base-400);
}
.console {
  height: 16rem;
  overflow: auto;
  padding: 0.75rem 0.9rem;
  border-radius: 0.6rem;
  background: var(--color-base-950);
  border: 1px solid var(--color-base-800);
  font-family: var(--font-mono);
  font-size: 0.75rem;
  line-height: 1.5;
  color: var(--color-base-200);
}
.console:focus-visible,
.check:focus-visible {
  outline: 2px solid var(--color-redstone-400);
  outline-offset: 2px;
}
.console-line {
  white-space: pre-wrap;
  word-break: break-word;
}
.footer-line {
  font-size: 0.8rem;
  padding: 0.45rem 0.75rem;
  border-radius: 0.4rem;
  color: var(--color-base-400);
  background: var(--color-base-950);
  border: 1px dashed var(--color-base-700);
}
.check {
  width: 1rem;
  height: 1rem;
  accent-color: var(--color-redstone-500);
  flex-shrink: 0;
}
.access {
  display: flex;
  gap: 0.75rem;
  align-items: flex-start;
  padding: 0.75rem 0.9rem;
  border-radius: 0.6rem;
  border: 1px solid var(--color-base-800);
  background: var(--color-base-950);
  cursor: pointer;
}
.access .check {
  margin-top: 0.2rem;
}
.access.on {
  border-color: var(--color-redstone-600);
}
@media (prefers-reduced-motion: reduce) {
  .st-busy .state-dot {
    animation: none;
  }
}
</style>
