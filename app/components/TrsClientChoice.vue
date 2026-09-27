<script setup lang="ts">
import type { TrsOffer } from '~/types'

// „Mit TRS Client“ / „Ohne TRS Client“ als zwei Karten – im Installations-
// und im Import-Dialog. Ohne Build ist „Mit“ ausgegraut (mit Begründung);
// Überschneidungen mit anderen Mods stehen als Hinweis darunter.
const props = defineProps<{
  offer: TrsOffer | null
  /** Vorschau lädt noch. */
  loading?: boolean
  /** Vorschau fehlgeschlagen – trotzdem wählbar. */
  failed?: boolean
  /** Kleinere Karten (Sammel-Import). */
  compact?: boolean
}>()
const model = defineModel<boolean>({ required: true })

const withDisabled = computed(() => !!props.offer && !props.offer.supported)
const strong = computed(() => trsStrongConflicts(props.offer))
const soft = computed(() => trsSoftConflicts(props.offer))
const recommended = computed(() => !props.offer || (props.offer.supported && props.offer.recommended))
const unsupportedText = computed(() => {
  const u = props.offer?.unsupported
  return u ? t('trsChoice.unsupported', { loader: loaderLabels[u.loader], version: u.gameVersion }) : null
})

function pick(value: boolean) {
  if (value && withDisabled.value) return
  model.value = value
}

function step() {
  pick(!model.value)
}

const options = computed(() => [
  { value: true, name: t('trsChoice.with'), hint: t('trsChoice.withHint'), badge: recommended.value },
  { value: false, name: t('trsChoice.without'), hint: t('trsChoice.withoutHint'), badge: !recommended.value && !withDisabled.value },
])
</script>

<template>
  <div>
    <div
      class="grid grid-cols-2 gap-2"
      role="radiogroup"
      :aria-label="t('trsChoice.title')"
      :aria-busy="loading || undefined"
      @keydown.left.prevent="step"
      @keydown.right.prevent="step"
    >
      <button
        v-for="o in options"
        :key="String(o.value)"
        type="button"
        role="radio"
        :aria-checked="model === o.value"
        :tabindex="model === o.value ? 0 : -1"
        :disabled="o.value && withDisabled"
        :title="o.value && withDisabled ? (unsupportedText ?? undefined) : undefined"
        class="flex flex-col gap-1 rounded-md border text-left transition-colors disabled:cursor-not-allowed disabled:opacity-45"
        :class="[
          compact ? 'px-2.5 py-2' : 'px-3 py-2.5',
          model === o.value
            ? 'border-redstone-500 bg-redstone-900 text-base-50'
            : 'border-base-700 bg-base-900 text-base-400 hover:border-base-600 hover:text-base-50',
        ]"
        @click="pick(o.value)"
      >
        <span class="flex flex-wrap items-center gap-x-1.5 gap-y-1 font-semibold" :class="compact ? 'text-xs' : 'text-sm'">
          <!-- Lampe: an = gewählt -->
          <span
            class="size-2 shrink-0 rounded-full"
            :class="model === o.value ? 'bg-lamp-400 shadow-[0_0_6px_var(--color-lamp-400)]' : 'bg-base-600'"
            aria-hidden="true"
          />
          <span>{{ o.name }}</span>
          <span
            v-if="o.badge && !loading"
            class="shrink-0 rounded bg-lamp-400/15 px-1.5 py-px text-[10px] font-medium uppercase tracking-wide text-lamp-300"
          >{{ t('trsChoice.recommended') }}</span>
        </span>
        <span v-if="!compact" class="text-[11px] leading-snug text-base-400">{{ o.hint }}</span>
      </button>
    </div>

    <div v-if="loading || failed || unsupportedText || strong.length || soft.length || !compact" class="mt-2 space-y-1 text-xs" aria-live="polite">
      <p v-if="loading" class="flex items-center gap-2 text-base-400">
        <span class="size-3 animate-spin rounded-full border-2 border-base-600 border-t-lamp-400" aria-hidden="true" />
        {{ t('trsChoice.checking') }}
      </p>
      <template v-else>
        <p v-if="unsupportedText" class="text-base-400">{{ unsupportedText }}</p>
        <p v-if="strong.length" class="text-warn">{{ t('trsChoice.conflicts', { list: strong.map((c) => c.name).join(', ') }) }}</p>
        <p v-if="soft.length" class="text-base-400">{{ t('trsChoice.zoom', { list: soft.map((c) => c.name).join(', ') }) }}</p>
        <p v-if="failed" class="text-base-400">{{ t('trsChoice.previewFailed') }}</p>
        <p v-if="!compact && !withDisabled" class="text-base-600">{{ t('trsChoice.later') }}</p>
      </template>
    </div>
  </div>
</template>
