<script setup lang="ts">
// Titelleiste: laufende Instanzen (wie „No instances running“ in der Modrinth App).
// Ohne Spiel ein ruhiger grauer Punkt, mit einem Spiel Name + Stoppen + Logs,
// mit mehreren ein Menü mit allen.
const games = useGamesStore()
const instances = useInstancesStore()
const router = useRouter()

const open = ref(false)
const root = useTemplateRef<HTMLElement>('root')
const trigger = useTemplateRef<HTMLButtonElement>('trigger')
const now = ref(Date.now())
const stopping = ref<Set<string>>(new Set())

const rows = computed(() =>
  games.running.map((g) => {
    const instance = instances.items.find((i) => i.id === g.instanceId) ?? null
    return { id: g.instanceId, name: instance?.name ?? g.instanceId, instance, startedAt: g.startedAt }
  }),
)
const primary = computed(() => rows.value[0] ?? null)

// Laufzeit sekundengenau – der Zeitgeber läuft nur, solange etwas läuft.
let timer: ReturnType<typeof setInterval> | undefined
watch(
  () => rows.value.length > 0,
  (any) => {
    clearInterval(timer)
    timer = undefined
    if (any) {
      now.value = Date.now()
      timer = setInterval(() => (now.value = Date.now()), 1000)
    } else {
      open.value = false
    }
  },
  { immediate: true },
)

function uptime(startedAt: number): string {
  const s = Math.max(0, Math.floor((now.value - startedAt) / 1000))
  const h = Math.floor(s / 3600)
  const m = Math.floor((s % 3600) / 60)
  const sec = String(s % 60).padStart(2, '0')
  return h > 0 ? `${h}:${String(m).padStart(2, '0')}:${sec}` : `${m}:${sec}`
}

async function stop(id: string) {
  stopping.value = new Set([...stopping.value, id])
  try {
    await games.stop(id)
  } finally {
    const next = new Set(stopping.value)
    next.delete(id)
    stopping.value = next
  }
}

function logs(id: string) {
  open.value = false
  router.push({ path: `/instances/${id}`, query: { tab: 'logs' } })
}

function onDocPointer(e: PointerEvent) {
  if (open.value && root.value && !root.value.contains(e.target as Node)) open.value = false
}
function onKey(e: KeyboardEvent) {
  if (open.value && e.key === 'Escape') {
    open.value = false
    trigger.value?.focus()
  }
}
onMounted(() => {
  document.addEventListener('pointerdown', onDocPointer)
  document.addEventListener('keydown', onKey)
})
onBeforeUnmount(() => {
  clearInterval(timer)
  document.removeEventListener('pointerdown', onDocPointer)
  document.removeEventListener('keydown', onKey)
})
</script>

<template>
  <div ref="root" class="relative flex items-center">
    <!-- Nichts läuft: ruhiger Zustand. -->
    <span v-if="!primary" class="pill text-base-400" role="status">
      <span class="dot bg-base-600" aria-hidden="true" />
      <span class="hidden md:inline">Keine Instanz läuft</span>
    </span>

    <div v-else class="pill gap-1 pr-1 text-base-100">
      <component
        :is="rows.length > 1 ? 'button' : 'span'"
        ref="trigger"
        class="flex min-w-0 items-center gap-2"
        :class="rows.length > 1 ? 'rounded-sm pr-0.5 hover:text-base-50' : ''"
        v-bind="rows.length > 1 ? { 'aria-expanded': open, 'aria-haspopup': 'menu', 'aria-label': `${rows.length} Instanzen laufen` } : { role: 'status' }"
        @click="rows.length > 1 && (open = !open)"
      >
        <span class="dot animate-lamp bg-ok shadow-[0_0_6px_var(--color-ok)]" aria-hidden="true" />
        <span class="max-w-32 truncate font-medium lg:max-w-44">{{ primary.name }}</span>
        <span v-if="rows.length > 1" class="text-[10px] text-base-400">+{{ rows.length - 1 }}</span>
        <svg v-if="rows.length > 1" viewBox="0 0 24 24" class="size-3 text-base-400 transition-transform" :class="{ 'rotate-180': open }" fill="none" stroke="currentColor" stroke-width="2.6" stroke-linecap="round" stroke-linejoin="round"><path d="m6 9 6 6 6-6" /></svg>
      </component>
      <template v-if="rows.length === 1">
        <button
          class="stop-btn"
          :aria-label="`${primary.name} stoppen`"
          title="Stoppen"
          :disabled="stopping.has(primary.id)"
          @click="stop(primary.id)"
        >
          <span class="size-1.5 bg-current" />
        </button>
        <button class="icon-btn" :aria-label="`Logs von ${primary.name} öffnen`" title="Logs öffnen" @click="logs(primary.id)">
          <svg viewBox="0 0 24 24" class="size-3.5" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round"><path d="M4 5h16v14H4zM7.5 10l2.5 2-2.5 2M12.5 14.5h4" /></svg>
        </button>
      </template>
    </div>

    <div v-if="open && rows.length > 1" class="menu top-full right-0 mt-1.5 w-72 animate-pop" role="menu" aria-label="Laufende Instanzen">
      <p class="px-2.5 pt-1.5 pb-1 text-[11px] text-base-400">Läuft</p>
      <div v-for="(r, i) in rows" :key="r.id" class="flex items-center gap-2.5 rounded-md px-2.5 py-1.5 hover:bg-base-800">
        <span class="dot shrink-0 bg-ok shadow-[0_0_6px_var(--color-ok)]" aria-hidden="true" />
        <InstanceIcon v-if="r.instance" :instance="r.instance" :size="24" />
        <span class="min-w-0 flex-1">
          <span class="flex items-center gap-1 truncate text-sm text-base-50">
            {{ r.name }}
            <svg v-if="i === 0" viewBox="0 0 24 24" class="size-3 shrink-0 text-lamp-400" fill="currentColor" role="img" aria-label="Zuerst gestartet"><path d="m12 3 2.7 5.6 6.1.9-4.4 4.3 1 6.1L12 17l-5.4 2.9 1-6.1-4.4-4.3 6.1-.9z" /></svg>
          </span>
          <span class="block font-mono text-[10px] tabular-nums text-base-400">läuft seit {{ uptime(r.startedAt) }}</span>
        </span>
        <button class="stop-btn" role="menuitem" :aria-label="`${r.name} stoppen`" title="Stoppen" :disabled="stopping.has(r.id)" @click="stop(r.id)">
          <span class="size-1.5 bg-current" />
        </button>
        <button class="icon-btn" role="menuitem" :aria-label="`Logs von ${r.name} öffnen`" title="Logs öffnen" @click="logs(r.id)">
          <svg viewBox="0 0 24 24" class="size-3.5" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round"><path d="M4 5h16v14H4zM7.5 10l2.5 2-2.5 2M12.5 14.5h4" /></svg>
        </button>
      </div>
    </div>
  </div>
</template>

<style scoped>
@reference "~/assets/css/main.css";

.pill {
  @apply flex h-6 items-center gap-2 rounded-full border border-base-800 bg-base-850 px-2.5 text-[11px] whitespace-nowrap;
}
/* Eckiger Punkt – wie ein Pixel/eine Redstone-Lampe. */
.dot {
  @apply inline-block size-1.5 shrink-0;
}
.stop-btn {
  @apply grid size-5 shrink-0 place-items-center rounded-full bg-redstone-500 text-white transition-colors hover:bg-redstone-400 disabled:opacity-50;
  box-shadow: inset 0 -1px 0 rgb(0 0 0 / 0.25);
}
.icon-btn {
  @apply grid size-5 shrink-0 place-items-center rounded-full text-base-400 transition-colors hover:bg-base-700 hover:text-base-50;
}
</style>
