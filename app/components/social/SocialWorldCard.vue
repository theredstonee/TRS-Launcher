<script lang="ts">
import type { HostingRoom } from '~/utils/hosting'

// Stand je Welt kurz merken – mehrere Karten derselben Welt fragen nur einmal.
const cache = new Map<string, { at: number; room: Promise<HostingRoom | null | undefined> }>()
const TTL_MS = 30_000

/** `null` = geschlossen/nicht sichtbar, `undefined` = unbekannt (offline). */
function roomFor(id: string): Promise<HostingRoom | null | undefined> {
  const hit = cache.get(id)
  if (hit && Date.now() - hit.at < TTL_MS) return hit.room
  const room = backend.hosting.room(id).catch(() => undefined)
  cache.set(id, { at: Date.now(), room })
  return room
}
</script>

<script setup lang="ts">
import { formatJoinCode, roomFull, worldVersionLabel, type ChatWorld } from '~/utils/hosting'

// Weltkarte im Chat („Welt von Bob – 1.21.11 Fabric – Beitreten“): Der Host hat
// seine Einzelspielerwelt im Spiel geöffnet. „Beitreten“ tritt über den Code bei
// (eingeladen → sofort, sonst Anfrage) und startet dann die passende Instanz.
const props = defineProps<{ world: ChatWorld }>()
const hosting = useHostingStore()
const chat = useChatStore()

const fetched = ref<HostingRoom | null | undefined>(undefined)
const loading = ref(true)

watch(
  () => props.world.roomId,
  async (id) => {
    loading.value = true
    const result = await roomFor(id)
    if (id === props.world.roomId) {
      fetched.value = result
      loading.value = false
    }
  },
  { immediate: true },
)

const own = computed(() => props.world.host.uuid === chat.me)
/** Live-Stand: aus der Liste (Echtzeit) oder einmal abgefragt. */
const room = computed<HostingRoom | null | undefined>(() => {
  if (own.value && hosting.mine?.id === props.world.roomId) return hosting.mine
  return hosting.rooms.find((r) => r.id === props.world.roomId) ?? fetched.value
})
const closed = computed(() => !loading.value && room.value === null)
const requested = computed(() => !!hosting.waiting[props.world.roomId] || room.value?.myState === 'requested')
const busy = computed(() => !!hosting.busy[props.world.roomId] || !!hosting.busy[props.world.code])

const status = computed(() => {
  if (loading.value && room.value === undefined) return t('social.invite.checking')
  if (closed.value) return t('social.hosting.closedShort')
  const r = room.value
  if (!r) return t('social.invite.unknown')
  return t('social.invite.players', { online: r.players, max: r.maxPlayers })
})

const title = computed(() => (own.value ? t('social.hosting.yourWorld') : t('social.hosting.cardTitle', { host: props.world.host.name })))

function join() {
  const r = room.value
  // Aus der Liste bekannt (z. B. eingeladen): per Raum-ID, sonst per Code der Karte.
  if (r && r.myState) void hosting.join({ room: r })
  else void hosting.join({ card: props.world })
}
</script>

<template>
  <div class="world-card flex items-center gap-3 rounded-lg p-2.5" data-testid="world-card">
    <span class="grid size-12 shrink-0 place-items-center rounded-md bg-redstone-900/50 ring-1 ring-redstone-600/40">
      <SocialIcon name="world" class="size-6 text-redstone-300" />
    </span>
    <span class="min-w-0 flex-1">
      <span class="block truncate text-[11px] font-semibold tracking-wide text-base-400 uppercase">{{ title }}</span>
      <span class="block truncate text-sm font-semibold text-base-50">{{ world.name }}</span>
      <span class="flex items-center gap-1.5 text-xs" :class="closed ? 'text-base-400' : 'text-base-200'">
        <span class="size-1.5 shrink-0 rounded-full" :class="closed || !room ? 'bg-base-600' : 'bg-ok'" />
        <span class="truncate">{{ worldVersionLabel(world) }} · {{ status }}</span>
      </span>
    </span>
    <span v-if="own" class="shrink-0 rounded-md bg-base-950 px-2 py-1 font-mono text-xs text-base-200" :title="t('social.hosting.code')">{{ formatJoinCode(world.code) }}</span>
    <button v-else-if="closed" class="btn btn-ghost shrink-0 px-3 py-1.5 text-xs" disabled>{{ t('social.hosting.closedShort') }}</button>
    <button v-else-if="requested" class="btn btn-ghost shrink-0 px-3 py-1.5 text-xs" disabled data-testid="world-requested">{{ t('social.hosting.requested') }}</button>
    <button
      v-else
      class="btn btn-primary shrink-0 px-3 py-1.5 text-xs"
      :disabled="busy || (!!room && roomFull(room) && room.myState !== 'accepted')"
      data-testid="world-join"
      @click="join"
    >
      {{ room && roomFull(room) && room.myState !== 'accepted' ? t('social.hosting.full') : t('social.hosting.join') }}
    </button>
  </div>
</template>

<style scoped>
.world-card {
  min-width: 17rem;
  background: var(--color-base-900);
  box-shadow: inset 0 0 0 1px var(--color-base-700);
  color: var(--color-base-50);
}
</style>
