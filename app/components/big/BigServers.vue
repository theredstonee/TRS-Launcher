<script setup lang="ts">
import type { Server } from '~/types'

// Big Picture „Server“: gespeicherte Server als Kacheln. A tritt mit der gewählten
// Instanz bei (Standard: zuletzt gespielt) – derselbe Weg wie „Beitreten“ in der Server-Liste.
const servers = useServersStore()
const instances = useInstancesStore()
const games = useGamesStore()
const store = useBigPictureStore()

const target = computed(() => instances.items.find((i) => i.id === store.joinTarget) ?? instances.items[0] ?? null)
const busy = computed(() => (target.value ? games.state(target.value.id).phase !== 'idle' : true))

let timer: ReturnType<typeof setInterval> | undefined
onMounted(() => {
  if (!servers.loaded) void servers.load().catch(() => {})
  else void servers.refresh()
  timer = setInterval(() => void servers.refresh(), 30_000)
})
onBeforeUnmount(() => clearInterval(timer))

function favicon(server: Server): string | null {
  // Das Backend lässt nur geprüfte PNG-Data-URLs durch; hier zur Sicherheit noch einmal.
  const icon = servers.statuses[server.id]?.favicon
  return icon?.startsWith('data:image/png;base64,') ? icon : null
}

function pingClass(ms: number) {
  return ms < 80 ? 'text-ok' : ms < 180 ? 'text-lamp-400' : 'text-redstone-300'
}

function join(server: Server) {
  if (!target.value || busy.value) return
  void games.launch(target.value.id, server.id)
}
</script>

<template>
  <section aria-labelledby="bp-servers">
    <div class="mb-6 flex flex-wrap items-end justify-between gap-4">
      <h2 id="bp-servers" class="bp-heading">{{ t('bigPicture.sections.servers') }}</h2>
      <button v-if="target && instances.items.length > 1" type="button" class="bp-btn" @click="store.openLayer({ kind: 'target' })">
        <InstanceIcon :instance="target" :size="36" />
        {{ t('bigPicture.servers.joinWith', { name: target.name }) }}
      </button>
      <p v-else-if="target" class="text-lg text-base-400">{{ t('bigPicture.servers.joinWith', { name: target.name }) }}</p>
    </div>

    <div v-if="!servers.loaded" class="grid gap-6" style="grid-template-columns: repeat(auto-fill, minmax(28rem, 1fr))">
      <div v-for="i in 4" :key="i" class="skeleton h-32 rounded-2xl" />
    </div>

    <div v-else-if="servers.items.length" class="grid gap-6" style="grid-template-columns: repeat(auto-fill, minmax(28rem, 1fr))">
      <button
        v-for="s in servers.items"
        :key="s.id"
        type="button"
        class="bp-tile flex-row! items-center gap-5 p-5"
        :aria-disabled="!target || busy"
        :aria-label="t('bigPicture.servers.joinNamed', { name: s.name })"
        @click="join(s)"
      >
        <span class="relative shrink-0">
          <img v-if="favicon(s)" :src="favicon(s)!" alt="" class="size-20 ring-2 ring-base-800 [image-rendering:pixelated]" />
          <span v-else class="display grid size-20 place-items-center bg-base-800 text-4xl text-base-600 ring-2 ring-base-700">{{ s.name.charAt(0).toUpperCase() }}</span>
          <span
            class="absolute -right-1.5 -bottom-1.5 size-5 border-[3px] border-base-900"
            :class="servers.statuses[s.id] === undefined ? 'animate-lamp bg-base-600' : servers.statuses[s.id]!.online ? 'bg-ok' : 'bg-redstone-500'"
          />
        </span>
        <span class="min-w-0 flex-1">
          <span class="block truncate text-2xl font-semibold text-base-50">{{ s.name }}</span>
          <span class="block truncate font-mono text-base text-base-400">{{ s.address }}</span>
          <span v-if="servers.statuses[s.id]?.online" class="mt-1 block truncate text-base text-base-200">
            {{ servers.statuses[s.id]!.motd.split('\n')[0] || servers.statuses[s.id]!.version }}
          </span>
          <span v-else-if="servers.statuses[s.id]" class="mt-1 block text-base text-base-600">{{ t('servers.card.unreachable') }}</span>
        </span>
        <span v-if="servers.statuses[s.id]?.online" class="shrink-0 text-right">
          <span class="display block text-2xl tabular-nums text-base-50">{{ formatCount(servers.statuses[s.id]!.playersOnline) }}<span class="text-base-600"> / {{ formatCount(servers.statuses[s.id]!.playersMax) }}</span></span>
          <span class="block text-base tabular-nums" :class="pingClass(servers.statuses[s.id]!.latencyMs)">{{ servers.statuses[s.id]!.latencyMs }} ms</span>
        </span>
      </button>
    </div>

    <div v-else class="bp-empty">
      <p class="text-2xl text-base-50">{{ t('servers.empty.title') }}</p>
      <p class="mt-3">{{ t('bigPicture.servers.emptyText') }}</p>
    </div>
  </section>
</template>
