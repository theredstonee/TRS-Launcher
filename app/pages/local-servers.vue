<script setup lang="ts">
import { cleanCommand, type LocalServer } from '~/utils/serverExport'

// Lokale Minecraft-Server (aus „Als Server exportieren“): Liste links,
// rechts die Konsole des gewählten Servers mit Live-Log, Befehlszeile,
// Start/Stopp/Neustart und Spielerzahl. Beim Beenden des Launchers stoppt
// der Kern alle Server sauber.
const servers = useLocalServersStore()
const tasks = useTasksStore()
const toasts = useToasts()
const route = useRoute()
const router = useRouter()

const selectedId = ref<string | null>(typeof route.query.id === 'string' ? route.query.id : null)
const selected = computed<LocalServer | null>(() => servers.get(selectedId.value ?? '') ?? servers.items[0] ?? null)
const lines = computed(() => (selected.value ? (servers.logs[selected.value.id] ?? []) : []))
/** Für flüssiges Scrollen nur die letzten Zeilen zeigen. */
const SHOWN_LINES = 1500
const shownLines = computed(() => lines.value.slice(-SHOWN_LINES))
const hiddenLines = computed(() => Math.max(0, lines.value.length - SHOWN_LINES))
const starting = computed(() => (selected.value ? tasks.get(taskKey('server-start', selected.value.id))?.status === 'running' : false))
const confirmDelete = ref(false)

onMounted(async () => {
  await servers.init()
  await servers.refresh().catch((e) => toasts.error(e))
  loadFirewall()
})

watch(
  () => selected.value?.id,
  (id) => {
    confirmDelete.value = false
    if (id) {
      void servers.loadLogs(id)
      if (route.query.id !== id) void router.replace({ query: { ...route.query, id } })
    }
  },
  { immediate: true },
)

function select(id: string) {
  selectedId.value = id
}

// --- Log -----------------------------------------------------------------------------
const logBox = useTemplateRef<HTMLElement>('logBox')
const follow = ref(true)
function onScroll() {
  const el = logBox.value
  if (el) follow.value = el.scrollHeight - el.scrollTop - el.clientHeight < 40
}
watch(
  () => shownLines.value.length + (selected.value?.id ?? ''),
  async () => {
    if (!follow.value) return
    await nextTick()
    logBox.value?.scrollTo({ top: logBox.value.scrollHeight })
  },
)
function lineTone(line: string): string {
  if (/\/(ERROR|FATAL)\]/.test(line) || line.startsWith('Exception') || line.startsWith('\tat ')) return 'text-redstone-300'
  if (/\/WARN\]/.test(line)) return 'text-lamp-300'
  if (line.includes('joined the game') || line.includes('left the game')) return 'text-ok'
  return 'text-base-300'
}

// --- Befehle -------------------------------------------------------------------------
const commandText = ref('')
const history = ref<string[]>([])
let historyIndex = -1
async function send() {
  const server = selected.value
  const text = cleanCommand(commandText.value)
  if (!server || !text) return
  if (await servers.command(server.id, text)) {
    history.value = [text, ...history.value.filter((h) => h !== text)].slice(0, 50)
    historyIndex = -1
    commandText.value = ''
    follow.value = true
  }
}
function browseHistory(step: 1 | -1) {
  if (!history.value.length) return
  historyIndex = Math.min(history.value.length - 1, Math.max(-1, historyIndex + step))
  commandText.value = historyIndex < 0 ? '' : (history.value[historyIndex] ?? '')
}
/** Schnellbefehle: fertige (list) oder angefangene (say …, op …) Eingaben. */
const quick = [
  { label: 'list', text: 'list', send: true },
  { label: 'say …', text: 'say ', send: false },
  { label: 'op …', text: 'op ', send: false },
  { label: 'whitelist …', text: 'whitelist add ', send: false },
  { label: 'save-all', text: 'save-all', send: true },
]
const commandInput = useTemplateRef<HTMLInputElement>('commandInput')
function applyQuick(q: (typeof quick)[number]) {
  commandText.value = q.text
  if (q.send) void send()
  else commandInput.value?.focus()
}

// --- Zustand -------------------------------------------------------------------------
const stateTone: Record<LocalServer['status']['state'], string> = {
  starting: 'bg-lamp-400 animate-lamp',
  running: 'bg-ok',
  stopping: 'bg-lamp-400',
  stopped: 'bg-base-600',
}
function playersText(s: LocalServer): string {
  if (s.status.state === 'stopped') return t('localServers.stopped')
  return t('localServers.players', { now: s.status.players ?? '?', max: s.status.maxPlayers })
}
function loaderText(s: LocalServer): string {
  const loader = loaderLabels[s.loader]
  return s.loaderVersion && s.loader !== 'vanilla' ? `${loader} ${s.loaderVersion}` : loader
}

async function remove() {
  const server = selected.value
  if (!server) return
  confirmDelete.value = false
  await servers.remove(server.id)
  selectedId.value = servers.items[0]?.id ?? null
}

// --- Firewall (nur Windows, nur Launcher-Java) ---------------------------------------
const firewall = ref<{ total: number; missing: number } | null>(null)
const firewallBusy = ref(false)
function loadFirewall() {
  if (!defaultCapabilities().firewall) return
  backend
    .firewallStatus()
    .then((s) => (firewall.value = s))
    .catch(() => {})
}
async function allowFirewall() {
  firewallBusy.value = true
  try {
    const n = await backend.firewallAllowAll()
    toasts.ok(n ? t('settings.network.allowed', n) : t('settings.network.noJava'))
    loadFirewall()
  } catch (e) {
    if (!isCancelled(e)) toasts.error(e)
  } finally {
    firewallBusy.value = false
  }
}
</script>

<template>
  <div class="mx-auto flex h-full max-w-6xl flex-col p-6">
    <PageHeader :title="t('localServers.title')" :subtitle="t('localServers.subtitle')" />

    <div v-if="!servers.loaded" class="space-y-2">
      <div v-for="i in 3" :key="i" class="skeleton h-14" />
    </div>
    <RedstoneEmpty v-else-if="!servers.items.length" :seed="0x5e" :title="t('localServers.empty.title')" :text="t('localServers.empty.text')">
      <NuxtLink to="/instances" class="btn btn-primary">{{ t('localServers.empty.action') }}</NuxtLink>
    </RedstoneEmpty>

    <div v-else class="grid min-h-0 flex-1 gap-4 md:grid-cols-[15rem_minmax(0,1fr)]">
      <!-- Liste -->
      <ul class="space-y-1.5 md:overflow-y-auto" :aria-label="t('localServers.title')">
        <li v-for="s in servers.items" :key="s.id">
          <button
            class="card card-hover flex w-full items-center gap-3 px-3 py-2.5 text-left"
            :class="{ 'ring-1 ring-redstone-500/60': selected?.id === s.id }"
            :aria-current="selected?.id === s.id"
            @click="select(s.id)"
          >
            <span class="size-2 shrink-0 rounded-full" :class="stateTone[s.status.state]" />
            <span class="min-w-0 flex-1">
              <span class="block truncate text-sm font-medium text-base-50">{{ s.name }}</span>
              <span class="block truncate text-xs text-base-400">{{ s.gameVersion }} · {{ playersText(s) }}</span>
            </span>
          </button>
        </li>
      </ul>

      <!-- Konsole -->
      <section v-if="selected" class="card flex min-h-0 flex-col p-4">
        <div class="flex flex-wrap items-start gap-3">
          <div class="min-w-0 flex-1">
            <h2 class="flex items-center gap-2 truncate font-semibold text-base-50">
              <span class="size-2 shrink-0 rounded-full" :class="stateTone[selected.status.state]" />
              {{ selected.name }}
              <span class="text-xs font-normal text-base-400">{{ t(`localServers.state.${selected.status.state}`) }}</span>
            </h2>
            <p class="mt-0.5 text-xs text-base-400">
              {{ t('localServers.meta', { version: selected.gameVersion, loader: loaderText(selected), port: selected.port, ram: selected.ramMb, java: selected.javaMajor }) }}
            </p>
            <p v-if="selected.status.state !== 'stopped'" class="mt-0.5 text-xs text-base-300" :title="selected.status.playerNames.join(', ')">
              {{ playersText(selected) }}<template v-if="selected.status.playerNames.length">: {{ selected.status.playerNames.join(', ') }}</template>
            </p>
            <p v-else-if="selected.status.exitCode" class="mt-0.5 text-xs text-redstone-300">
              {{ t('localServers.exitCode', { code: selected.status.exitCode }) }}
            </p>
          </div>
          <div class="flex flex-wrap items-center gap-2">
            <button
              v-if="selected.status.state === 'stopped'"
              class="btn btn-primary px-3 py-1.5 text-xs"
              :disabled="starting || !selected.eulaAccepted"
              @click="servers.start(selected.id)"
            >
              {{ starting ? t('localServers.starting') : t('localServers.start') }}
            </button>
            <template v-else>
              <button class="btn btn-ghost px-3 py-1.5 text-xs" :disabled="selected.status.state === 'stopping'" @click="servers.stop(selected.id)">
                {{ t('localServers.stop') }}
              </button>
              <button class="btn btn-ghost px-3 py-1.5 text-xs" :disabled="selected.status.state === 'stopping'" @click="servers.restart(selected.id)">
                {{ t('localServers.restart') }}
              </button>
              <button class="btn btn-ghost px-3 py-1.5 text-xs text-redstone-300" :title="t('localServers.killHint')" @click="servers.kill(selected.id)">
                {{ t('localServers.kill') }}
              </button>
            </template>
            <button class="btn-icon" :title="t('common.actions.openFolder')" :aria-label="t('common.actions.openFolder')" @click="servers.openFolder(selected.id)">
              <svg viewBox="0 0 24 24" class="size-4" fill="none" stroke="currentColor" stroke-width="2"><path d="M3 6a1 1 0 0 1 1-1h5l2 2h9a1 1 0 0 1 1 1v10a1 1 0 0 1-1 1H4a1 1 0 0 1-1-1z" /></svg>
            </button>
            <template v-if="selected.status.state === 'stopped'">
              <button v-if="!confirmDelete" class="btn-icon" :title="t('localServers.delete')" :aria-label="t('localServers.delete')" @click="confirmDelete = true">
                <svg viewBox="0 0 24 24" class="size-4" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><path :d="icons.trash" /></svg>
              </button>
              <button v-else class="btn btn-ghost px-3 py-1.5 text-xs text-redstone-300" @click="remove">{{ t('localServers.deleteConfirm') }}</button>
            </template>
          </div>
        </div>

        <p v-if="!selected.eulaAccepted" class="mt-3 rounded-md bg-lamp-500/10 px-3 py-2 text-xs text-lamp-300">{{ t('localServers.eulaMissing') }}</p>
        <div class="mt-3 rounded-md bg-base-900/60 px-3 py-2 text-xs leading-relaxed text-base-400">
          {{ t('localServers.network', { port: selected.port }) }}
          <button v-if="firewall && firewall.missing > 0" class="ml-1 text-redstone-300 underline hover:text-redstone-200" :disabled="firewallBusy" @click="allowFirewall">
            {{ t('localServers.allowFirewall') }}
          </button>
        </div>

        <div
          ref="logBox"
          class="mt-3 min-h-64 flex-1 overflow-y-auto rounded-md bg-base-950 p-3 font-mono text-[11.5px] leading-relaxed select-text"
          role="log"
          aria-live="off"
          :aria-label="t('localServers.log')"
          @scroll="onScroll"
        >
          <p v-if="hiddenLines" class="text-base-500">{{ t('localServers.hiddenLines', { count: hiddenLines }) }}</p>
          <p v-if="!shownLines.length" class="text-base-500">{{ t('localServers.noLog') }}</p>
          <p v-for="(line, i) in shownLines" :key="i" class="break-all whitespace-pre-wrap" :class="lineTone(line)">{{ line }}</p>
        </div>

        <form class="mt-3 flex gap-2" @submit.prevent="send">
          <span class="display self-center text-redstone-400" aria-hidden="true">&gt;</span>
          <input
            ref="commandInput"
            v-model="commandText"
            class="field flex-1 font-mono text-sm"
            maxlength="256"
            :placeholder="t('localServers.commandPlaceholder')"
            :aria-label="t('localServers.command')"
            :disabled="selected.status.state === 'stopped'"
            @keydown.up.prevent="browseHistory(1)"
            @keydown.down.prevent="browseHistory(-1)"
          />
          <button class="btn btn-primary" :disabled="selected.status.state === 'stopped' || !cleanCommand(commandText)">{{ t('localServers.send') }}</button>
        </form>
        <div class="mt-2 flex flex-wrap gap-1.5">
          <button
            v-for="q in quick"
            :key="q.label"
            type="button"
            class="rounded-md bg-base-800 px-2 py-1 font-mono text-[11px] text-base-300 hover:bg-base-700 disabled:opacity-40"
            :disabled="selected.status.state === 'stopped'"
            @click="applyQuick(q)"
          >
            {{ q.label }}
          </button>
        </div>
      </section>
    </div>
  </div>
</template>
