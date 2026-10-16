<script setup lang="ts">
import type { CreatorSymbol, Milestone } from '~/utils/creator'

// „So sieht es im Spiel aus“: Namensschild über dem Kopf und Tab-Liste mit dem
// echten Fan-Symbol (inklusive Animation und Meilenstein-Rahmen). Andere Fans
// erscheinen nur, wenn „Fan-Symbole anderer Spieler anzeigen“ an ist; der
// Creator selbst trägt das Creator-Abzeichen (★).
const props = withDefaults(
  defineProps<{
    /** Eigener Spielername. */
    player: string
    symbol: CreatorSymbol | null
    milestone?: Milestone | null
    /** Name des Creators (Zeile mit ★ in der Tab-Liste). */
    creator?: string | null
    /** Andere Fans mit Symbol zeigen. */
    showOthers?: boolean
    /** Niedriger (Dashboard). */
    compact?: boolean
  }>(),
  { milestone: null, creator: null, showOthers: true, compact: false },
)

interface Row {
  name: string
  fan: boolean
  creator: boolean
}

const rows = computed<Row[]>(() => {
  const list: Row[] = [{ name: props.player, fan: !!props.symbol, creator: false }]
  if (props.creator) list.push({ name: props.creator, fan: false, creator: true })
  // Beispiel-Mitspieler: einer ist auch Fan, einer nicht.
  list.push({ name: 'Alex_PvP', fan: !!props.symbol && props.showOthers, creator: false })
  list.push({ name: 'Notch', fan: false, creator: false })
  return list
})
</script>

<template>
  <div class="flex flex-wrap items-stretch gap-3" data-testid="creator-ingame-preview">
    <!-- Über dem Kopf -->
    <div
      class="relative grid min-w-56 flex-1 place-items-center overflow-hidden rounded-lg"
      :class="compact ? 'h-28' : 'h-36'"
      style="background: linear-gradient(#78a7ff, #b8d4ff 58%, #6aa84f 58%, #4e8a3a)"
    >
      <span class="flex items-center gap-1.5 bg-black/55 px-2 py-1 font-display text-[15px] leading-none text-white">
        <CreatorSymbol v-if="symbol" :image="symbol.image" :frames="symbol.frames" :frame-ms="symbol.frameMs" :px="18" :milestone="milestone" />
        {{ player }}
      </span>
    </div>
    <!-- Tab-Liste -->
    <div class="min-w-60 flex-1 bg-black/70 p-1 font-mono text-[13px] text-white sm:flex-none" role="list" :aria-label="t('creator.preview.tabList')">
      <div v-for="r in rows" :key="r.name" class="my-0.5 flex items-center gap-1.5 bg-white/[0.06] px-1.5 py-0.5" role="listitem">
        <span v-if="r.creator" class="text-[11px] text-violet-300" :title="t('creator.preview.creatorBadge')">★</span>
        <CreatorSymbol v-if="r.fan && symbol" :image="symbol.image" :frames="symbol.frames" :frame-ms="symbol.frameMs" :px="14" :milestone="milestone" />
        <span class="truncate">{{ r.name }}</span>
        <span class="ml-auto text-[10px] tracking-tighter text-[#55ff55]" aria-hidden="true">▮▮▮▮</span>
      </div>
    </div>
  </div>
</template>
