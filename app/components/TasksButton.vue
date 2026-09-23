<script setup lang="ts">
import type { TaskKind, TaskRecord } from '~/types'
import type { Task } from '~/stores/tasks'

// Titelleiste: Aufgaben-Knopf (Download-Pfeil mit Zähler) und links daneben,
// solange etwas lädt, eine Pille mit der aktuellen Aufgabe und Geschwindigkeit.
// Das Panel zeigt „Aktiv“ (Fortschritt, Pause, Abbrechen) und „Fertig“
// (Verlauf aus dem Kern, bleibt über Neustarts erhalten).
const tasks = useTasksStore()
const updater = useUpdaterStore()
const instances = useInstancesStore()
const router = useRouter()

const root = useTemplateRef<HTMLElement>('root')
const trigger = useTemplateRef<HTMLButtonElement>('trigger')
const panel = useTemplateRef<HTMLElement>('panel')
const showActive = ref(true)
const showDone = ref(true)
const now = ref(Date.now())

onMounted(() => {
  tasks.init((path) => router.push(path))
  document.addEventListener('pointerdown', onDocPointer)
  document.addEventListener('keydown', onDocKey)
})
onBeforeUnmount(() => {
  document.removeEventListener('pointerdown', onDocPointer)
  document.removeEventListener('keydown', onDocKey)
})

/** Launcher-Update zählt als Aufgabe, gehört aber dem Updater-Store. */
const updating = computed(() => updater.phase === 'downloading')
const count = computed(() => tasks.active.length + (updating.value ? 1 : 0))
const lead = computed(() => tasks.active[0] ?? null)

const leadLabel = computed(() => {
  const task = lead.value
  if (!task) return updating.value ? t('tasks.launcherUpdatePercent', { percent: updater.percent }) : ''
  if (task.paused) return t('tasks.paused')
  if (task.speed > 0) return formatSpeed(task.speed)
  if (task.doneBytes > 0) return formatSize(task.doneBytes)
  return task.percent !== null ? t('tasks.percent', { percent: task.percent }) : ''
})

function instanceOf(id: string | null | undefined) {
  return id ? (instances.items.find((i) => i.id === id) ?? null) : null
}

const kindIcons: Partial<Record<TaskKind, string>> = {
  java: icons.java,
  launch: icons.play,
  create: icons.plus,
  duplicate: 'M8 8h11v11H8zM5 16V5h11',
  import: 'M12 3v12m0 0-4-4m4 4 4-4M4 17v3h16v-3',
  export: 'M12 15V3m0 0L8 7m4-4 4 4M4 17v3h16v-3',
  repair: 'M14.5 6.5a4 4 0 0 0-5.3 5.3L4 17l3 3 5.2-5.2a4 4 0 0 0 5.3-5.3l-2.5 2.5-2.5-.5-.5-2.5z',
  reinstall: icons.sync,
  'version-change': icons.sync,
}
function kindIcon(kind: TaskKind): string {
  return kindIcons[kind] ?? icons.install
}

function rowMeta(task: Task): string {
  const parts: string[] = []
  if (task.doneBytes > 0 || task.totalBytes > 0) parts.push(formatProgressBytes(task.doneBytes, task.totalBytes))
  if (task.paused) parts.push(t('tasks.pausedShort'))
  else {
    if (task.speed > 0) parts.push(formatSpeed(task.speed))
    const eta = task.totalBytes > 0 ? formatEta(task.totalBytes - task.doneBytes, task.speed) : null
    if (eta) parts.push(t('tasks.remaining', { time: eta }))
  }
  if (!parts.length && task.percent !== null) parts.push(t('tasks.percent', { percent: task.percent }))
  return parts.join(' · ')
}

/** Fehlertext eines Verlaufseintrags – übersetzt, wenn der Schlüssel mitgespeichert wurde. */
function recordDetail(r: TaskRecord): string | null {
  const ref = r.detailRef
  return ref && hasKey(ref.key) ? tKey(ref.key, ref.params) : (r.detail ?? null)
}

function recordMeta(r: TaskRecord): string {
  const ago = formatAgo(Date.parse(r.finishedAt), now.value)
  return `${ago} · ${r.outcome === 'failed' ? t('tasks.failed') : taskKindLabel(r.kind)}`
}

// Offen: relative Zeiten ab und zu auffrischen, Fokus ins Panel, fokussierte Aufgabe zeigen.
let clock: ReturnType<typeof setInterval> | undefined
watch(
  () => tasks.panelOpen,
  async (open) => {
    clearInterval(clock)
    if (!open) return
    now.value = Date.now()
    clock = setInterval(() => (now.value = Date.now()), 30_000)
    await nextTick()
    // Laufende Aufgabe – oder ihr Verlaufseintrag, wenn sie schon fertig ist.
    const recordId = tasks.focused ? tasks.get(tasks.focused)?.recordId : null
    const selector = tasks.focused
      ? `[data-task="${CSS.escape(tasks.focused)}"]${recordId ? `, [data-task="record:${CSS.escape(recordId)}"]` : ''}`
      : null
    const focusedRow = selector ? panel.value?.querySelector<HTMLElement>(selector) : null
    if (focusedRow) {
      focusedRow.scrollIntoView({ block: 'nearest' })
      focusedRow.focus()
    } else {
      panel.value?.focus()
    }
  },
)
onBeforeUnmount(() => clearInterval(clock))

function toggle() {
  if (tasks.panelOpen) tasks.closePanel()
  else tasks.openPanel()
}

function close(returnFocus = true) {
  tasks.closePanel()
  if (returnFocus) trigger.value?.focus()
}

/** Esc schließt auch, wenn der Fokus gerade nicht im Panel liegt. */
function onDocKey(e: KeyboardEvent) {
  if (e.key === 'Escape' && tasks.panelOpen && !panel.value?.contains(document.activeElement)) close()
}

function onDocPointer(e: PointerEvent) {
  if (tasks.panelOpen && root.value && !root.value.contains(e.target as Node)) tasks.closePanel()
}

/** Esc schließt; Pfeiltasten wandern durch die Knöpfe im Panel. */
function onPanelKey(e: KeyboardEvent) {
  if (e.key === 'Escape') {
    e.stopPropagation()
    close()
    return
  }
  if (e.key !== 'ArrowDown' && e.key !== 'ArrowUp') return
  const items = [...(panel.value?.querySelectorAll<HTMLElement>('button:not(:disabled), [data-task]') ?? [])]
  if (!items.length) return
  e.preventDefault()
  const at = items.indexOf(document.activeElement as HTMLElement)
  const next = e.key === 'ArrowDown' ? (at + 1) % items.length : (at - 1 + items.length) % items.length
  items[next]?.focus()
}

function open(id: string) {
  tasks.openInstance(id)
}
</script>

<template>
  <div ref="root" class="relative flex items-center gap-1.5">
    <!-- Aktuelle Aufgabe: Spinner + Name + Geschwindigkeit bzw. Größe. -->
    <button
      v-if="lead || updating"
      class="lead-pill hidden sm:flex"
      :aria-label="t('tasks.showNamed', { title: lead?.title ?? t('tasks.launcherUpdate') })"
      tabindex="-1"
      @click="toggle"
    >
      <span class="charge" :class="{ 'charge-paused': lead?.paused }" aria-hidden="true">
        <svg viewBox="0 0 16 16" class="size-3.5">
          <circle cx="8" cy="8" r="6" fill="none" stroke="currentColor" stroke-width="2" class="text-redstone-900" />
          <circle cx="8" cy="8" r="6" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="square" stroke-dasharray="10 28" class="ring text-redstone-400" />
        </svg>
      </span>
      <span class="max-w-36 truncate text-base-50 lg:max-w-48">{{ lead?.title ?? t('tasks.launcherUpdate') }}</span>
      <RedstoneWire class="hidden w-14 lg:flex" :percent="lead ? (lead.percent ?? 0) : updater.percent" :indeterminate="!!lead && lead.percent == null" :segments="8" />
      <span v-if="leadLabel" class="font-mono text-[10px] tabular-nums text-base-400">{{ leadLabel }}</span>
    </button>

    <button
      ref="trigger"
      class="tasks-btn"
      :class="{ 'tasks-btn-open': tasks.panelOpen, 'text-redstone-300': count > 0 }"
      :aria-expanded="tasks.panelOpen"
      aria-controls="tasks-panel"
      :aria-label="count ? t('tasks.activeCount', { count }) : t('tasks.title')"
      :title="t('tasks.title')"
      @click="toggle"
    >
      <svg viewBox="0 0 24 24" class="size-4" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round"><path :d="icons.install" /></svg>
      <span v-if="count" class="count" aria-hidden="true">{{ count }}</span>
    </button>

    <div
      v-if="tasks.panelOpen"
      id="tasks-panel"
      ref="panel"
      class="menu top-full right-0 mt-1.5 flex max-h-[min(34rem,calc(100vh-4rem))] w-[23rem] animate-pop flex-col p-0 outline-none"
      role="dialog"
      :aria-label="t('tasks.title')"
      tabindex="-1"
      @keydown="onPanelKey"
    >
      <header class="flex items-center gap-2 border-b border-base-700 px-3.5 py-2.5">
        <h2 class="display text-sm text-base-50">{{ t('tasks.title') }}</h2>
        <span v-if="count" class="badge bg-redstone-900 text-redstone-300">{{ count }}</span>
        <span v-if="tasks.totalSpeed > 0" class="ml-auto font-mono text-[11px] tabular-nums text-base-400">{{ formatSpeed(tasks.totalSpeed) }}</span>
        <button class="grid size-6 place-items-center rounded-md text-base-400 hover:bg-base-700 hover:text-base-50" :class="tasks.totalSpeed > 0 ? 'ml-2' : 'ml-auto'" :aria-label="t('tasks.close')" @click="close()">
          <svg viewBox="0 0 24 24" class="size-3.5" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round"><path :d="icons.close" /></svg>
        </button>
      </header>

      <div class="min-h-0 flex-1 overflow-y-auto p-1.5">
        <!-- Ruhezustand -->
        <RedstoneEmpty
          v-if="!count && !tasks.history.length"
          compact
          :seed="0x3a"
          :title="t('tasks.empty.title')"
          :text="t('tasks.empty.text')"
        />

        <!-- Aktiv -->
        <section v-if="count" :aria-label="t('tasks.activeSection')">
          <button class="section-toggle" :aria-expanded="showActive" @click="showActive = !showActive">
            <svg viewBox="0 0 24 24" class="size-3 transition-transform" :class="{ '-rotate-90': !showActive }" fill="none" stroke="currentColor" stroke-width="2.6" stroke-linecap="round" stroke-linejoin="round"><path d="m6 9 6 6 6-6" /></svg>
            {{ t('common.status.active') }}
            <span class="text-base-400">{{ count }}</span>
          </button>
          <ul v-show="showActive" class="flex flex-col gap-1 pb-1">
            <li
              v-for="task in tasks.active"
              :key="task.key"
              :data-task="task.key"
              tabindex="-1"
              class="row"
              :class="{ 'row-focused': tasks.focused === task.key }"
            >
              <InstanceIcon v-if="instanceOf(task.instanceId)" :instance="instanceOf(task.instanceId)!" :size="36" />
              <ModIcon v-else-if="task.iconUrl" :src="task.iconUrl" :name="task.title" :size="36" />
              <span v-else class="kind-icon"><svg viewBox="0 0 24 24" class="size-4" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path :d="kindIcon(task.kind)" /></svg></span>
              <div class="min-w-0 flex-1">
                <div class="flex items-start gap-1">
                  <div class="min-w-0 flex-1">
                    <p class="truncate text-sm font-medium text-base-50">{{ task.title }}</p>
                    <p class="truncate text-[11px] text-base-400">{{ task.stage }}</p>
                  </div>
                  <button
                    v-if="task.pausable"
                    class="row-btn"
                    :aria-label="task.paused ? t('tasks.resumeNamed', { title: task.title }) : t('tasks.pauseNamed', { title: task.title })"
                    :title="task.paused ? t('tasks.resume') : t('tasks.pause')"
                    :disabled="task.cancelling"
                    @click="tasks.pause(task.key, !task.paused)"
                  >
                    <svg v-if="task.paused" viewBox="0 0 24 24" class="size-3.5" fill="currentColor"><path :d="icons.play" /></svg>
                    <svg v-else viewBox="0 0 24 24" class="size-3.5" fill="currentColor"><path d="M7 5h3.5v14H7zM13.5 5H17v14h-3.5z" /></svg>
                  </button>
                  <button
                    v-if="task.cancellable"
                    class="row-btn hover:text-redstone-300"
                    :aria-label="t('tasks.cancelNamed', { title: task.title })"
                    :title="t('common.actions.cancel')"
                    :disabled="task.cancelling"
                    @click="tasks.cancel(task.key)"
                  >
                    <svg viewBox="0 0 24 24" class="size-3.5" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round"><path :d="icons.close" /></svg>
                  </button>
                </div>
                <div
                  class="mt-1.5"
                  :class="{ 'opacity-50 grayscale': task.paused }"
                  role="progressbar"
                  :aria-label="t('tasks.progressNamed', { title: task.title })"
                  aria-valuemin="0"
                  aria-valuemax="100"
                  :aria-valuenow="task.percent ?? undefined"
                >
                  <RedstoneWire :percent="task.percent ?? 0" :indeterminate="task.percent == null" :segments="28" />
                </div>
                <p class="mt-1 flex justify-between gap-2 font-mono text-[10px] tabular-nums text-base-400">
                  <span class="truncate">{{ rowMeta(task) }}</span>
                  <span v-if="task.percent !== null" class="shrink-0">{{ t('tasks.percent', { percent: task.percent }) }}</span>
                </p>
              </div>
            </li>
            <li v-if="updating" class="row">
              <span class="kind-icon"><img src="/icon.png" alt="" class="size-4 [image-rendering:pixelated]" /></span>
              <div class="min-w-0 flex-1">
                <p class="truncate text-sm font-medium text-base-50">TRS Launcher {{ updater.version }}</p>
                <p class="truncate text-[11px] text-base-400">{{ t('tasks.updateBackground') }}</p>
                <div class="mt-1.5" role="progressbar" :aria-label="t('tasks.launcherUpdate')" aria-valuemin="0" aria-valuemax="100" :aria-valuenow="updater.percent">
                  <RedstoneWire :percent="updater.percent" :segments="28" />
                </div>
                <p class="mt-1 text-right font-mono text-[10px] tabular-nums text-base-400">{{ t('tasks.percent', { percent: updater.percent }) }}</p>
              </div>
            </li>
          </ul>
        </section>

        <!-- Fertig -->
        <section v-if="tasks.history.length" :aria-label="t('tasks.doneSection')" :class="{ 'mt-1 border-t border-base-700 pt-1': count }">
          <div class="flex items-center">
            <button class="section-toggle flex-1" :aria-expanded="showDone" @click="showDone = !showDone">
              <svg viewBox="0 0 24 24" class="size-3 transition-transform" :class="{ '-rotate-90': !showDone }" fill="none" stroke="currentColor" stroke-width="2.6" stroke-linecap="round" stroke-linejoin="round"><path d="m6 9 6 6 6-6" /></svg>
              {{ t('common.actions.done') }}
              <span class="text-base-400">{{ tasks.history.length }}</span>
            </button>
            <button class="mr-1 rounded px-2 py-1 text-[11px] text-base-400 hover:bg-base-700 hover:text-base-50" @click="tasks.clearHistory()">{{ t('tasks.clearAll') }}</button>
          </div>
          <ul v-show="showDone" class="flex flex-col">
            <li
              v-for="r in tasks.history"
              :key="r.id"
              :data-task="`record:${r.id}`"
              tabindex="-1"
              class="row py-1.5"
              :class="{ 'row-focused': tasks.focused && tasks.get(tasks.focused)?.recordId === r.id }"
            >
              <InstanceIcon v-if="instanceOf(r.instanceId)" :instance="instanceOf(r.instanceId)!" :size="28" />
              <ModIcon v-else-if="r.iconUrl" :src="r.iconUrl" :name="r.title" :size="28" />
              <span v-else class="kind-icon size-7"><svg viewBox="0 0 24 24" class="size-3.5" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path :d="kindIcon(r.kind)" /></svg></span>
              <div class="min-w-0 flex-1">
                <p class="truncate text-[13px] text-base-50">{{ r.title }}</p>
                <p class="truncate text-[11px]" :class="r.outcome === 'failed' ? 'text-redstone-300' : 'text-base-400'" :title="recordDetail(r) ?? undefined">
                  {{ recordMeta(r) }}<template v-if="r.outcome === 'failed' && recordDetail(r)"> – {{ recordDetail(r) }}</template>
                </p>
              </div>
              <button v-if="tasks.canRetry(r.id)" class="row-btn w-auto px-1.5 text-[11px]" :aria-label="t('tasks.retryNamed', { title: r.title })" @click="tasks.retry(r.id)">{{ t('tasks.retryShort') }}</button>
              <button
                v-else-if="r.outcome === 'done' && instanceOf(r.instanceId)"
                class="row-btn w-auto px-1.5 text-[11px]"
                :aria-label="t('tasks.openNamed', { title: r.title })"
                @click="open(r.instanceId!)"
              >
                {{ t('common.actions.open') }}
              </button>
              <button class="row-btn" :aria-label="t('tasks.removeNamed', { title: r.title })" :title="t('common.actions.remove')" @click="tasks.removeRecord(r.id)">
                <svg viewBox="0 0 24 24" class="size-3.5" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M4 7h16M9 7V4h6v3M6 7l1 13h10l1-13M10 11v6M14 11v6" /></svg>
              </button>
            </li>
          </ul>
        </section>
      </div>
    </div>
  </div>
</template>

<style scoped>
@reference "~/assets/css/main.css";

.lead-pill {
  @apply h-6 items-center gap-2 rounded-full border border-base-800 bg-base-850 px-2.5 text-[11px] whitespace-nowrap transition-colors hover:border-base-700;
}
/* Redstone-Ring, der sich „auflädt“ und dreht. */
.charge {
  @apply grid place-items-center;
}
.charge .ring {
  transform-origin: 50% 50%;
  animation: charge 1.1s linear infinite;
  filter: drop-shadow(0 0 2px var(--color-redstone-500));
}
.charge-paused .ring {
  animation-play-state: paused;
  @apply text-base-400;
  filter: none;
}
@keyframes charge {
  to {
    transform: rotate(360deg);
  }
}
.tasks-btn {
  @apply relative grid size-7 place-items-center rounded-md text-base-400 transition-colors hover:bg-base-800 hover:text-base-50;
}
.tasks-btn-open {
  @apply bg-base-800 text-base-50;
}
/* Zähler als kleine Redstone-Lampe. */
.count {
  @apply absolute -top-0.5 -right-0.5 grid h-3.5 min-w-3.5 place-items-center bg-lamp-400 px-0.5 font-mono text-[9px] leading-none font-bold text-base-950;
  box-shadow: 0 0 6px var(--color-lamp-400);
}
.section-toggle {
  @apply flex items-center gap-1.5 rounded px-2 py-1.5 text-[11px] font-semibold tracking-wide text-base-200 uppercase hover:text-base-50;
}
.row {
  @apply flex items-start gap-2.5 rounded-md px-2 py-2 outline-none transition-colors hover:bg-base-800 focus-visible:bg-base-800;
}
.row-focused {
  @apply bg-base-800 ring-1 ring-redstone-600/60;
}
.row-btn {
  @apply grid size-6 shrink-0 place-items-center rounded-md text-base-400 transition-colors hover:bg-base-700 hover:text-base-50 disabled:opacity-40;
}
.kind-icon {
  @apply grid size-9 shrink-0 place-items-center rounded-lg bg-base-800 text-base-200 ring-1 ring-white/5;
}
@media (prefers-reduced-motion: reduce) {
  .charge .ring {
    animation: none;
  }
}
</style>
