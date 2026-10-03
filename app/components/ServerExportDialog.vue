<script setup lang="ts">
import type { Instance } from '~/types'
import {
  MINECRAFT_EULA_URL,
  type ServerExportPlan,
  type ServerExportProgress,
  type ServerMod,
  defaultServerMods,
  serverExportOptionsSchema,
  modSideLabel,
  modSourceLabel,
  splitMods,
} from '~/utils/serverExport'

// Instanz als Minecraft-Server: welche Mods mitkommen (reine Client-Mods
// bleiben weg), Welt, Configs, server.properties, RAM und die EULA – dann als
// ZIP speichern und/oder hier anlegen und starten. Bauen läuft als Aufgabe.
const props = defineProps<{ instance: Instance }>()
const emit = defineEmits<{ close: [] }>()

const toasts = useToasts()
const tasks = useTasksStore()
const router = useRouter()
const servers = useLocalServersStore()
const taskId = computed(() => taskKey('server-export', props.instance.id))
const task = computed(() => tasks.get(taskId.value))
const progress = computed(() => (task.value?.status === 'running' ? { percent: task.value.percent ?? 0, stage: task.value.stage } : null))

const plan = ref<ServerExportPlan | null>(null)
const loading = ref(true)
const selected = ref<string[]>([])
const filter = ref('')
const name = ref(props.instance.name)
const world = ref<string | null>(null)
const includeConfigs = ref(true)
const port = ref(25565)
const motd = ref('')
const maxPlayers = ref(20)
const onlineMode = ref(true)
const ramMb = ref(4096)
const eula = ref(false)
const asZip = ref(true)
const local = ref(false)
const formError = ref<string | null>(null)

onMounted(async () => {
  try {
    const p = await backend.serverExportPlan(props.instance.id)
    plan.value = p
    selected.value = defaultServerMods(p.mods)
    ramMb.value = p.defaultRamMb
    name.value = p.suggestedName.slice(0, 48) || props.instance.name
    motd.value = t('serverExport.defaultMotd', { name: name.value }).slice(0, 100)
    includeConfigs.value = p.configDirs.length > 0
  } catch (e) {
    toasts.error(e)
  } finally {
    loading.value = false
  }
})

const groups = computed(() => {
  const all = plan.value?.mods ?? []
  const q = filter.value.trim().toLowerCase()
  const shown = q ? all.filter((m) => m.name.toLowerCase().includes(q) || m.fileName.toLowerCase().includes(q)) : all
  return splitMods(shown, selected.value)
})
const counts = computed(() => {
  const all = plan.value?.mods ?? []
  const on = all.filter((m) => selected.value.includes(m.fileName)).length
  return { on, off: all.length - on, unknown: all.filter((m) => m.side === 'unknown').length }
})

function toggle(mod: ServerMod) {
  if (mod.locked || progress.value) return
  selected.value = selected.value.includes(mod.fileName)
    ? selected.value.filter((f) => f !== mod.fileName)
    : [...selected.value, mod.fileName]
}

const ramGb = computed(() => (ramMb.value / 1024).toLocaleString(undefined, { maximumFractionDigits: 1 }))
const sideTone: Record<ServerMod['side'], string> = {
  client: 'bg-base-800 text-base-300',
  server: 'bg-sky-500/15 text-sky-300',
  both: 'bg-ok/15 text-ok',
  unknown: 'bg-lamp-500/15 text-lamp-300',
}

const actionLabel = computed(() => {
  if (asZip.value && local.value) return t('serverExport.actions.both')
  if (local.value) return t('serverExport.actions.local')
  return t('serverExport.actions.zip')
})
const canStart = computed(() => !!plan.value?.supported && !loading.value && !progress.value && (asZip.value || local.value))

function openEula() {
  backend.openExternalUrl(MINECRAFT_EULA_URL).catch((e) => toasts.error(e))
}

function phaseLabel(phase: ServerExportProgress['phase']): string {
  return t(`serverExport.phase.${phase}`)
}

async function start() {
  const parsed = serverExportOptionsSchema.safeParse({
    name: name.value,
    mods: selected.value,
    includeConfigs: includeConfigs.value,
    world: world.value,
    port: Number(port.value),
    motd: motd.value,
    maxPlayers: Number(maxPlayers.value),
    onlineMode: onlineMode.value,
    ramMb: Number(ramMb.value),
    eulaAccepted: eula.value,
    zip: asZip.value,
    local: local.value,
  })
  if (!parsed.success) {
    formError.value = firstIssue(parsed.error)
    return
  }
  formError.value = null
  const options = parsed.data
  const instance = props.instance
  const result = await tasks.run(
    {
      key: taskId.value,
      kind: 'server-export',
      title: options.name,
      stage: options.zip ? t('serverExport.pickLocation') : t('serverExport.phase.java'),
      instanceId: instance.id,
      cancellable: true,
      pausable: false,
    },
    async (ctx) => {
      const res = await backend.exportServer(instance.id, options, (p) => ctx.progress(p.percent, `${phaseLabel(p.phase)} …`), ctx.taskId)
      if (!res) {
        ctx.discard()
        return null
      }
      ctx.update({
        doneText: res.zipFile
          ? t('serverExport.doneZip', { file: res.zipFile, size: formatBytes(res.zipBytes) })
          : t('serverExport.doneLocal', { name: options.name }),
      })
      return res
    },
  )
  if (!result.ok || !result.value) return
  const res = result.value
  if (res.local) {
    await servers.refresh().catch(() => {})
    const id = res.local.id
    if (res.startError) toasts.error(userErrorText(res.startError))
    toasts.info(t('serverExport.localCreated', { name: res.local.name }), {
      label: t('serverExport.openConsole'),
      run: () => router.push({ path: '/local-servers', query: { id } }),
    })
  }
  emit('close')
}
</script>

<template>
  <BaseDialog :title="t('serverExport.title')" huge @close="emit('close')">
    <div v-if="loading" class="space-y-2">
      <p class="text-sm text-base-400">{{ t('serverExport.analyzing') }}</p>
      <div v-for="i in 5" :key="i" class="skeleton h-9" />
    </div>
    <template v-else-if="plan">
      <p v-if="!plan.supported" role="alert" class="mb-3 rounded-md bg-redstone-500/10 px-3 py-2 text-sm text-redstone-300">
        {{ t('serverExport.unsupported', { version: plan.gameVersion }) }}
      </p>
      <p v-if="plan.offline" class="mb-3 rounded-md bg-lamp-500/10 px-3 py-2 text-xs text-lamp-300">{{ t('serverExport.offline') }}</p>

      <div class="grid gap-5 lg:grid-cols-[minmax(0,1.25fr)_minmax(0,1fr)]">
        <!-- Mods -->
        <section class="min-w-0">
          <div class="mb-2 flex flex-wrap items-baseline gap-x-3 gap-y-1">
            <h3 class="text-sm font-semibold text-base-50">{{ t('serverExport.mods.title') }}</h3>
            <span class="text-xs text-base-400">{{ t('serverExport.mods.summary', { on: counts.on, off: counts.off }) }}</span>
          </div>
          <p v-if="!plan.mods.length" class="text-sm text-base-400">{{ t('serverExport.mods.none') }}</p>
          <template v-else>
            <p class="mb-2 text-xs leading-relaxed text-base-400">{{ t('serverExport.mods.hint') }}</p>
            <input
              v-if="plan.mods.length > 12"
              v-model="filter"
              class="field mb-2"
              type="search"
              maxlength="64"
              :placeholder="t('serverExport.mods.filter')"
              :aria-label="t('serverExport.mods.filter')"
            />
            <div class="max-h-[46vh] space-y-3 overflow-y-auto pr-1">
              <div v-for="group in (['server', 'leftOut'] as const)" :key="group">
                <p class="label">{{ t(`serverExport.mods.${group}`, { count: groups[group].length }) }}</p>
                <p v-if="!groups[group].length" class="px-2.5 py-1 text-xs text-base-500">{{ t('serverExport.mods.empty') }}</p>
                <ul class="space-y-0.5">
                  <li v-for="mod in groups[group]" :key="mod.fileName">
                    <label
                      class="flex items-center gap-3 rounded-md px-2.5 py-1.5"
                      :class="mod.locked ? 'cursor-not-allowed opacity-70' : 'cursor-pointer hover:bg-base-800'"
                      :title="mod.fileName"
                    >
                      <input
                        type="checkbox"
                        class="size-4 accent-redstone-500"
                        :checked="selected.includes(mod.fileName)"
                        :disabled="mod.locked || !!progress"
                        @change="toggle(mod)"
                      />
                      <span class="min-w-0 flex-1 truncate text-sm" :class="group === 'leftOut' ? 'text-base-400' : 'text-base-100'">{{ mod.name }}</span>
                      <span
                        class="shrink-0 rounded px-1.5 py-0.5 text-[10px] font-semibold tracking-wide uppercase"
                        :class="sideTone[mod.side]"
                        :title="mod.locked ? t('serverExport.mods.trsLocked') : modSourceLabel(mod.source)"
                      >
                        {{ mod.locked ? 'TRS' : modSideLabel(mod.side) }}
                      </span>
                      <span class="w-14 shrink-0 text-right text-[11px] text-base-500 tabular-nums">{{ formatBytes(mod.size) }}</span>
                    </label>
                  </li>
                </ul>
              </div>
            </div>
            <p v-if="counts.unknown" class="mt-2 text-xs text-base-400">{{ t('serverExport.mods.unknownHint', { count: counts.unknown }) }}</p>
          </template>
        </section>

        <!-- Einstellungen -->
        <section class="min-w-0 space-y-3">
          <div>
            <label class="label" for="se-name">{{ t('common.labels.name') }}</label>
            <input id="se-name" v-model="name" class="field" maxlength="48" :disabled="!!progress" />
          </div>
          <div>
            <label class="label" for="se-world">{{ t('serverExport.world') }}</label>
            <select id="se-world" v-model="world" class="field" :disabled="!!progress">
              <option :value="null">{{ t('serverExport.newWorld') }}</option>
              <option v-for="w in plan.worlds" :key="w.folder" :value="w.folder">{{ w.name }}</option>
            </select>
          </div>
          <div v-if="plan.configDirs.length" class="flex items-center justify-between gap-4">
            <div class="min-w-0">
              <p class="text-sm text-base-100">{{ t('serverExport.configs') }}</p>
              <p class="truncate font-mono text-[11px] text-base-500">{{ plan.configDirs.join(', ') }}</p>
            </div>
            <ToggleSwitch v-model="includeConfigs" :label="t('serverExport.configs')" :disabled="!!progress" />
          </div>
          <div class="grid grid-cols-2 gap-3">
            <div>
              <label class="label" for="se-port">{{ t('serverExport.port') }}</label>
              <input id="se-port" v-model.number="port" type="number" min="1024" max="65535" class="field" :disabled="!!progress" />
            </div>
            <div>
              <label class="label" for="se-players">{{ t('serverExport.maxPlayers') }}</label>
              <input id="se-players" v-model.number="maxPlayers" type="number" min="1" max="1000" class="field" :disabled="!!progress" />
            </div>
          </div>
          <div>
            <label class="label" for="se-motd">{{ t('serverExport.motd') }}</label>
            <input id="se-motd" v-model="motd" class="field" maxlength="100" :disabled="!!progress" />
          </div>
          <div class="flex items-center justify-between gap-4">
            <div class="min-w-0">
              <p class="text-sm text-base-100">{{ t('serverExport.onlineMode') }}</p>
              <p class="text-xs text-base-400">{{ onlineMode ? t('serverExport.onlineModeOn') : t('serverExport.onlineModeOff') }}</p>
            </div>
            <ToggleSwitch v-model="onlineMode" :label="t('serverExport.onlineMode')" :disabled="!!progress" />
          </div>
          <div>
            <label class="label flex justify-between" for="se-ram">
              <span>{{ t('serverExport.ram') }}</span>
              <span class="tabular-nums text-base-300">{{ t('serverExport.ramValue', { gb: ramGb }) }}</span>
            </label>
            <input id="se-ram" v-model.number="ramMb" type="range" min="1024" max="16384" step="512" class="w-full accent-redstone-500" :disabled="!!progress" />
            <p class="mt-1 text-xs text-base-400">{{ t('serverExport.javaHint', { major: plan.javaMajor }) }}</p>
          </div>

          <div class="rounded-md border border-base-700 bg-base-900/60 p-3">
            <label class="flex cursor-pointer items-start gap-3">
              <input v-model="eula" type="checkbox" class="mt-0.5 size-4 accent-redstone-500" :disabled="!!progress" />
              <span class="text-sm text-base-200">
                {{ t('serverExport.eula') }}
                <button type="button" class="text-redstone-300 underline hover:text-redstone-200" @click.prevent="openEula">{{ t('serverExport.eulaLink') }}</button>
              </span>
            </label>
            <p class="mt-1.5 pl-7 text-xs text-base-400">{{ eula ? t('serverExport.eulaAccepted') : t('serverExport.eulaNotAccepted') }}</p>
          </div>

          <div class="space-y-1.5">
            <label class="flex cursor-pointer items-center gap-3">
              <input v-model="asZip" type="checkbox" class="size-4 accent-redstone-500" :disabled="!!progress" />
              <span class="text-sm text-base-100">{{ t('serverExport.asZip') }}</span>
            </label>
            <label class="flex cursor-pointer items-center gap-3">
              <input v-model="local" type="checkbox" class="size-4 accent-redstone-500" :disabled="!!progress" />
              <span class="text-sm text-base-100">{{ t('serverExport.local') }}</span>
            </label>
            <p v-if="local" class="pl-7 text-xs text-base-400">{{ t('serverExport.localHint') }}</p>
            <p v-if="local && !eula" class="pl-7 text-xs text-lamp-300">{{ t('errors.serverExport.eulaRequired') }}</p>
          </div>
        </section>
      </div>

      <p v-if="formError" role="alert" class="mt-3 text-xs text-redstone-300">{{ formError }}</p>
      <div v-if="progress" class="mt-4 flex items-center gap-3">
        <RedstoneWire class="flex-1" :percent="progress.percent" :segments="40" />
        <span class="display shrink-0 text-sm text-redstone-300 tabular-nums">{{ Math.floor(progress.percent) }} %</span>
      </div>
      <p v-if="progress" class="mt-1 text-xs text-base-400">{{ t('serverExport.runsInBackground', { stage: progress.stage }) }}</p>
    </template>

    <template #actions>
      <button v-if="progress" class="btn btn-ghost" @click="tasks.cancel(taskId)">{{ t('common.actions.cancel') }}</button>
      <button class="btn btn-ghost" @click="emit('close')">{{ progress ? t('common.actions.close') : t('common.actions.cancel') }}</button>
      <button class="btn btn-primary" :disabled="!canStart || (local && !eula)" @click="start">
        {{ progress ? t('serverExport.exporting') : actionLabel }}
      </button>
    </template>
  </BaseDialog>
</template>
