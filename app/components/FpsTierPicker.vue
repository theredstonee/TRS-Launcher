<script setup lang="ts">
import type { LoaderKind } from '~/types'
import type { FpsTier } from '~/utils/presets'

// Stufe des FPS-Boosts: Max FPS / Shader leicht / Shader schön – genau eine.
const props = withDefaults(defineProps<{ loader: LoaderKind | null; disabled?: boolean }>(), { disabled: false })
const tier = defineModel<FpsTier>({ required: true })

const shadersOk = computed(() => loaderHasShaders(props.loader))

function unavailable(value: FpsTier): boolean {
  return props.disabled || (value !== 'fpsBoost' && !shadersOk.value)
}

function pick(value: FpsTier) {
  if (!unavailable(value)) tier.value = value
}

// Pfeiltasten wie bei Radio-Knöpfen.
function step(delta: number) {
  const usable = fpsTiers.filter((x) => !unavailable(x))
  const i = usable.indexOf(tier.value)
  const next = usable[(i + delta + usable.length) % usable.length]
  if (next) tier.value = next
}
</script>

<template>
  <div>
    <div
      class="grid grid-cols-3 gap-1.5"
      role="radiogroup"
      :aria-label="t('presets.tiers.label')"
      @keydown.left.prevent="step(-1)"
      @keydown.right.prevent="step(1)"
    >
      <button
        v-for="value in fpsTiers"
        :key="value"
        type="button"
        role="radio"
        :aria-checked="tier === value"
        :tabindex="tier === value ? 0 : -1"
        :disabled="unavailable(value)"
        :title="value !== 'fpsBoost' && !shadersOk ? t('presets.tiers.noShaders') : undefined"
        class="flex flex-col gap-0.5 rounded-md border px-2.5 py-2 text-left transition-colors disabled:cursor-not-allowed disabled:opacity-45"
        :class="tier === value
          ? 'border-redstone-500 bg-redstone-900 text-base-50'
          : 'border-base-700 bg-base-900 text-base-400 hover:border-base-600 hover:text-base-50'"
        @click="pick(value)"
      >
        <span class="flex items-center gap-1.5 text-xs font-semibold">
          <!-- Lampe: an = gewählt -->
          <span
            class="size-2 shrink-0 rounded-full"
            :class="tier === value ? 'bg-lamp-400 shadow-[0_0_6px_var(--color-lamp-400)]' : 'bg-base-600'"
            aria-hidden="true"
          />
          {{ t(fpsTierTexts[value].name) }}
        </span>
        <span class="text-[11px] leading-snug text-base-400">{{ t(fpsTierTexts[value].hint) }}</span>
      </button>
    </div>
    <p v-if="!shadersOk" class="mt-1.5 text-[11px] text-base-600">{{ t('presets.tiers.noShaders') }}</p>
    <p v-else-if="isShaderTier(tier)" class="mt-1.5 text-[11px] text-base-400">{{ t('presets.tiers.keyHint') }}</p>
  </div>
</template>
