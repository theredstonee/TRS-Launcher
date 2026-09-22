<script setup lang="ts">
import type { Server } from '~/types'

const props = defineProps<{ server: Server; joinDisabled?: boolean; joinHint?: string; compact?: boolean }>()
const emit = defineEmits<{ join: [server: Server]; edit: [server: Server] }>()

const servers = useServersStore()
const status = computed(() => servers.statuses[props.server.id])

// Das Backend lässt nur geprüfte PNG-Data-URLs durch; hier zur Sicherheit noch einmal.
const favicon = computed(() => {
  const icon = status.value?.favicon
  return icon?.startsWith('data:image/png;base64,') ? icon : null
})

const pingClass = computed(() => {
  const ms = status.value?.latencyMs ?? 0
  return ms < 80 ? 'text-ok' : ms < 180 ? 'text-lamp-400' : 'text-redstone-300'
})
</script>

<template>
  <article class="card flex items-center gap-3 p-3 transition-colors hover:border-base-700">
    <div class="relative shrink-0">
      <img v-if="favicon" :src="favicon" alt="" class="size-12 rounded-md [image-rendering:pixelated]" />
      <div v-else class="display flex size-12 items-center justify-center rounded-md bg-base-800 text-xl text-base-600">
        {{ server.name.charAt(0).toUpperCase() }}
      </div>
      <span
        class="absolute -right-1 -bottom-1 size-3 rounded-full border-2 border-base-900"
        :class="status === undefined ? 'animate-lamp bg-base-600' : status.online ? 'bg-ok' : 'bg-redstone-500'"
        :title="status === undefined ? 'Wird abgefragt' : status.online ? 'Online' : 'Nicht erreichbar'"
      />
    </div>

    <div class="min-w-0 flex-1">
      <div class="flex items-baseline gap-2">
        <h3 class="truncate text-sm font-medium">{{ server.name }}</h3>
        <span class="truncate font-mono text-[11px] text-base-600">{{ server.address }}</span>
      </div>
      <template v-if="status === undefined">
        <div class="skeleton mt-1.5 h-3 w-40" />
      </template>
      <p v-else-if="status.online" class="truncate text-xs text-base-400" :title="status.motd">
        {{ status.motd.split('\n')[0] || status.version }}
      </p>
      <p v-else class="text-xs text-base-600">Nicht erreichbar</p>
    </div>

    <div v-if="status?.online" class="shrink-0 text-right text-xs">
      <p class="display tabular-nums text-base-50">{{ formatCount(status.playersOnline) }}<span class="text-base-600"> / {{ formatCount(status.playersMax) }}</span></p>
      <p class="tabular-nums" :class="pingClass">{{ status.latencyMs }} ms</p>
    </div>

    <div class="flex shrink-0 items-center gap-1.5">
      <button class="btn btn-primary px-3 py-1.5 text-xs" :disabled="joinDisabled" :title="joinHint" @click="emit('join', server)">
        Beitreten
      </button>
      <button v-if="!compact" class="btn btn-ghost px-2 py-1.5" title="Bearbeiten" aria-label="Bearbeiten" @click="emit('edit', server)">
        <svg viewBox="0 0 24 24" class="size-4" fill="none" stroke="currentColor" stroke-width="2"><path d="M4 20h4L19 9l-4-4L4 16zM13 7l4 4" /></svg>
      </button>
    </div>
  </article>
</template>
