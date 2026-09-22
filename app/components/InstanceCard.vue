<script setup lang="ts">
import type { Instance } from '~/types'

// Bibliothekskachel: Banner oben, darüber das Instanz-Bild, darunter Name,
// Loader und Version. Beim Überfahren erscheint der Spielen-Knopf auf dem
// Banner; laufende Instanzen leuchten wie eine Redstone-Lampe.
const props = defineProps<{ instance: Instance; groups: string[]; compact?: boolean; showPlayTime?: boolean }>()
const emit = defineEmits<{ delete: [instance: Instance]; move: [group: string | null]; newGroup: [instance: Instance] }>()

const games = useGamesStore()
const game = computed(() => games.state(props.instance.id))
const menu = ref<'main' | 'groups' | null>(null)

const percent = computed(() =>
  game.value.progress ? Math.floor(overallPercent(game.value.progress.stage, game.value.progress.percent)) : 0,
)

function play() {
  if (game.value.phase === 'idle') games.launch(props.instance.id)
  else if (game.value.phase === 'running') games.stop(props.instance.id)
}

function openFolder() {
  menu.value = null
  backend.openInstanceDir(props.instance.id).catch(() => {})
}

function closeMenu(e: MouseEvent) {
  if (!(e.target as HTMLElement | null)?.closest(`[data-card-menu="${props.instance.id}"]`)) menu.value = null
}
onMounted(() => document.addEventListener('mousedown', closeMenu))
onBeforeUnmount(() => document.removeEventListener('mousedown', closeMenu))
</script>

<template>
  <article
    class="card group relative flex flex-col transition-[border-color,background-color,box-shadow,transform] hover:-translate-y-0.5 hover:border-base-700 hover:bg-base-850"
    :class="{ 'border-lamp-400/50 shadow-[0_0_26px_-8px_var(--color-lamp-400)]': game.phase === 'running' }"
  >
    <div class="relative">
      <NuxtLink
        :to="`/instances/${instance.id}`"
        class="block overflow-hidden rounded-t-xl outline-none focus-visible:ring-2 focus-visible:ring-redstone-500"
        :aria-label="instance.name"
      >
        <InstanceBanner :instance="instance" shade="none" class="w-full" :class="compact ? 'h-20' : 'h-28'" />
      </NuxtLink>

      <!-- Spielen beim Überfahren; während der Vorbereitung Prozent, beim Laufen Stopp -->
      <button
        v-if="game.phase !== 'preparing'"
        class="absolute right-2 -bottom-4 grid place-items-center rounded-full shadow-lg shadow-black/50 transition-all"
        :class="[
          compact ? 'size-9' : 'size-11',
          game.phase === 'running'
            ? 'bg-lamp-400 text-base-950 opacity-100'
            : 'translate-y-1 bg-redstone-500 text-white opacity-0 group-hover:translate-y-0 group-hover:opacity-100 focus-visible:translate-y-0 focus-visible:opacity-100 hover:bg-redstone-400',
        ]"
        :aria-label="game.phase === 'running' ? `${instance.name} stoppen` : `${instance.name} spielen`"
        :title="game.phase === 'running' ? 'Läuft – stoppen' : 'Spielen'"
        @click="play"
      >
        <svg v-if="game.phase === 'running'" viewBox="0 0 24 24" class="size-4" fill="currentColor"><rect x="7" y="7" width="10" height="10" rx="1" /></svg>
        <svg v-else viewBox="0 0 24 24" class="ml-0.5 size-5" fill="currentColor"><path d="M7 4v16l13-8z" /></svg>
      </button>
      <div v-else class="absolute inset-x-2 bottom-2 rounded-lg bg-base-950/85 px-2.5 py-1.5 backdrop-blur" role="progressbar" :aria-valuenow="percent" aria-valuemin="0" aria-valuemax="100">
        <div class="flex justify-between text-[11px]"><span class="text-base-200">Starte …</span><span class="display text-redstone-300">{{ percent }} %</span></div>
        <RedstoneWire :percent="percent" :segments="16" class="mt-1" />
      </div>

      <span v-if="game.phase === 'running'" class="badge absolute top-2 left-2 bg-lamp-400 text-base-950">
        <span class="size-1.5 animate-lamp rounded-full bg-base-950" />Läuft
      </span>
    </div>

    <div class="flex items-start gap-2.5 px-2.5 pt-1.5 pb-2.5">
      <InstanceIcon
        :instance="instance"
        :size="compact ? 34 : 42"
        class="-mt-7 shadow-lg shadow-black/50 ring-2 ring-base-900 transition-transform duration-150 group-hover:scale-105"
      />
      <div class="min-w-0 flex-1">
        <NuxtLink :to="`/instances/${instance.id}`" class="block truncate font-semibold text-base-50 hover:text-redstone-300" :class="compact ? 'text-sm' : ''" :title="instance.name">
          {{ instance.name }}
        </NuxtLink>
        <p class="mt-0.5 flex items-center gap-1.5 truncate text-xs text-base-400">
          <span class="size-1.5 shrink-0 rounded-full" :style="{ background: loaderColors[instance.loader.kind] }" />
          {{ loaderLabels[instance.loader.kind] }} <span class="font-mono text-base-200">{{ instance.gameVersion }}</span>
        </p>
        <p v-if="!compact" class="mt-0.5 truncate text-[11px] text-base-600">
          {{ game.phase === 'running' ? 'Läuft gerade' : formatRelative(instance.lastPlayed) }}<template v-if="showPlayTime && instance.totalPlaySeconds >= 60"> · {{ formatPlayTime(instance.totalPlaySeconds) }}</template>
        </p>
      </div>

      <div class="relative shrink-0" :data-card-menu="instance.id">
        <button class="btn-icon size-7 bg-transparent text-base-400 opacity-0 group-hover:opacity-100 focus-visible:opacity-100" :class="{ 'opacity-100': menu }" :aria-label="`Aktionen für ${instance.name}`" :aria-expanded="!!menu" @click="menu = menu ? null : 'main'">
          <svg viewBox="0 0 24 24" class="size-4" fill="currentColor"><circle cx="12" cy="5.5" r="1.7" /><circle cx="12" cy="12" r="1.7" /><circle cx="12" cy="18.5" r="1.7" /></svg>
        </button>
        <div v-if="menu === 'main'" class="menu right-0 bottom-8" role="menu">
          <button class="menu-item" role="menuitem" :disabled="game.phase !== 'idle'" @click="menu = null; games.launch(instance.id)">Spielen</button>
          <NuxtLink :to="{ path: `/instances/${instance.id}`, query: { settings: 'general' } }" class="menu-item" role="menuitem">Einstellungen</NuxtLink>
          <button class="menu-item justify-between" role="menuitem" @click="menu = 'groups'">
            In Gruppe verschieben
            <svg viewBox="0 0 24 24" class="size-3.5" fill="none" stroke="currentColor" stroke-width="2.5"><path d="m9 5 7 7-7 7" /></svg>
          </button>
          <button class="menu-item" role="menuitem" @click="openFolder">Ordner öffnen</button>
          <div class="my-1 border-t border-base-700" />
          <button class="menu-item text-redstone-300" role="menuitem" :disabled="game.phase !== 'idle'" @click="menu = null; emit('delete', instance)">Löschen</button>
        </div>
        <div v-else-if="menu === 'groups'" class="menu right-0 bottom-8 max-h-72 overflow-y-auto" role="menu" aria-label="In Gruppe verschieben">
          <button class="menu-item text-base-400" role="menuitem" @click="menu = 'main'">
            <svg viewBox="0 0 24 24" class="size-3.5" fill="none" stroke="currentColor" stroke-width="2.5"><path d="m15 5-7 7 7 7" /></svg>
            Zurück
          </button>
          <button v-for="g in groups" :key="g" class="menu-item" role="menuitemradio" :aria-checked="instance.group === g" @click="menu = null; emit('move', g)">
            <span class="size-1.5 rounded-full" :class="instance.group === g ? 'bg-redstone-400' : 'bg-transparent'" />{{ g }}
          </button>
          <button v-if="instance.group" class="menu-item" role="menuitem" @click="menu = null; emit('move', null)">Aus der Gruppe nehmen</button>
          <div class="my-1 border-t border-base-700" />
          <button class="menu-item" role="menuitem" @click="menu = null; emit('newGroup', instance)">+ Neue Gruppe …</button>
        </div>
      </div>
    </div>
  </article>
</template>
