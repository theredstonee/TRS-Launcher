<script setup lang="ts">
import { ICON_FILLS, ICON_PATTERNS, type IconBackground } from '~/utils/iconEditor'

// Hintergrund des Symbols: Füllung, Muster wie auf der Startseite, Rahmen, Leuchten, Motivgröße.
const bg = defineModel<IconBackground>({ required: true })
const scale = defineModel<number>('scale', { required: true })

function set<K extends keyof IconBackground>(key: K, value: IconBackground[K]) {
  bg.value = { ...bg.value, [key]: value }
}

/** Fertige Kombinationen zum schnellen Ausprobieren. */
const PRESETS: Pick<IconBackground, 'fill' | 'color' | 'color2' | 'angle' | 'pattern' | 'patternColor'>[] = [
  { fill: 'gradient', color: '#3a1d24', color2: '#1b1420', angle: 135, pattern: 'wire', patternColor: '#ff3b2f' },
  { fill: 'solid', color: '#2a2630', color2: '#2a2630', angle: 0, pattern: 'deepslate', patternColor: '#ffffff' },
  { fill: 'gradient', color: '#1f3b6e', color2: '#0f1a33', angle: 160, pattern: 'dots', patternColor: '#8fd3ff' },
  { fill: 'gradient', color: '#2f5d1f', color2: '#16290f', angle: 180, pattern: 'none', patternColor: '#8be06a' },
  { fill: 'gradient', color: '#f2c230', color2: '#e8862a', angle: 135, pattern: 'dots', patternColor: '#ffffff' },
  { fill: 'gradient', color: '#5b2a8e', color2: '#1b1420', angle: 200, pattern: 'wire', patternColor: '#c39af5' },
]

function presetStyle(p: (typeof PRESETS)[number]) {
  return { background: p.fill === 'solid' ? p.color : `linear-gradient(${p.angle}deg, ${p.color}, ${p.color2})` }
}
</script>

<template>
  <div class="space-y-3 text-sm">
    <div class="flex flex-wrap gap-1.5">
      <button
        v-for="(p, i) in PRESETS"
        :key="i"
        type="button"
        class="size-7 rounded-md ring-1 ring-black/40 transition-transform hover:scale-110"
        :style="presetStyle(p)"
        :aria-label="t('iconEditor.bg.preset', { n: i + 1 })"
        :title="t('iconEditor.bg.preset', { n: i + 1 })"
        @click="bg = { ...bg, ...p }"
      />
    </div>

    <div>
      <span class="label">{{ t('iconEditor.bg.fill.label') }}</span>
      <div class="flex gap-1 rounded-lg bg-base-900 p-1 text-xs" role="radiogroup" :aria-label="t('iconEditor.bg.fill.label')">
        <button v-for="f in ICON_FILLS" :key="f" type="button" class="seg flex-1 rounded-md" :class="{ 'seg-on': bg.fill === f }" role="radio" :aria-checked="bg.fill === f" @click="set('fill', f)">
          {{ t(`iconEditor.bg.fill.${f}`) }}
        </button>
      </div>
    </div>

    <div v-if="bg.fill !== 'none'" class="flex flex-wrap items-end gap-3">
      <label class="text-xs text-base-400">
        {{ t('iconEditor.bg.color') }}
        <input type="color" class="mt-1 block h-8 w-14 cursor-pointer rounded border border-base-700 bg-base-900" :value="bg.color" @input="set('color', ($event.target as HTMLInputElement).value)" />
      </label>
      <template v-if="bg.fill === 'gradient'">
        <label class="text-xs text-base-400">
          {{ t('iconEditor.bg.color2') }}
          <input type="color" class="mt-1 block h-8 w-14 cursor-pointer rounded border border-base-700 bg-base-900" :value="bg.color2" @input="set('color2', ($event.target as HTMLInputElement).value)" />
        </label>
        <label class="min-w-24 flex-1 text-xs text-base-400">
          {{ t('iconEditor.bg.angle', { angle: bg.angle }) }}
          <input type="range" min="0" max="359" step="1" class="mt-2 block w-full accent-redstone-500" :value="bg.angle" @input="set('angle', Number(($event.target as HTMLInputElement).value))" />
        </label>
      </template>
    </div>

    <div v-if="bg.fill !== 'none'">
      <span class="label">{{ t('iconEditor.bg.pattern.label') }}</span>
      <div class="grid grid-cols-2 gap-1 text-xs">
        <button
          v-for="p in ICON_PATTERNS"
          :key="p"
          type="button"
          class="rounded-md border px-2 py-1.5 transition-colors"
          :class="bg.pattern === p ? 'border-redstone-500 bg-redstone-900 text-base-50' : 'border-base-700 bg-base-900 text-base-300 hover:border-base-600'"
          :aria-pressed="bg.pattern === p"
          @click="set('pattern', p)"
        >
          {{ t(`iconEditor.bg.pattern.${p}`) }}
        </button>
      </div>
      <label v-if="bg.pattern === 'wire' || bg.pattern === 'dots'" class="mt-2 flex items-center gap-2 text-xs text-base-400">
        <input type="color" class="h-7 w-10 cursor-pointer rounded border border-base-700 bg-base-900" :value="bg.patternColor" @input="set('patternColor', ($event.target as HTMLInputElement).value)" />
        {{ t('iconEditor.bg.patternColor') }}
      </label>
    </div>

    <div class="flex items-center justify-between gap-3">
      <span class="text-base-200">{{ t('iconEditor.bg.border') }}</span>
      <div class="flex items-center gap-2">
        <input v-if="bg.border" type="color" class="h-7 w-10 cursor-pointer rounded border border-base-700 bg-base-900" :aria-label="t('iconEditor.bg.borderColor')" :value="bg.borderColor" @input="set('borderColor', ($event.target as HTMLInputElement).value)" />
        <ToggleSwitch :model-value="bg.border" :label="t('iconEditor.bg.border')" @update:model-value="set('border', $event)" />
      </div>
    </div>
    <div class="flex items-center justify-between gap-3">
      <span class="text-base-200">{{ t('iconEditor.bg.glow') }}</span>
      <div class="flex items-center gap-2">
        <input v-if="bg.glow" type="color" class="h-7 w-10 cursor-pointer rounded border border-base-700 bg-base-900" :aria-label="t('iconEditor.bg.glowColor')" :value="bg.glowColor" @input="set('glowColor', ($event.target as HTMLInputElement).value)" />
        <ToggleSwitch :model-value="bg.glow" :label="t('iconEditor.bg.glow')" @update:model-value="set('glow', $event)" />
      </div>
    </div>

    <label class="block text-xs text-base-400">
      {{ t('iconEditor.bg.scale', { percent: Math.round(scale * 100) }) }}
      <input v-model.number="scale" type="range" min="0.4" max="1" step="0.05" class="mt-1 block w-full accent-redstone-500" />
    </label>
  </div>
</template>
