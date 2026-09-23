<script setup lang="ts">
import type { Toast } from '~/utils/toastQueue'

const toasts = useToasts()

function act(toast: Toast) {
  toast.action?.run()
  toasts.dismiss(toast.id)
}
</script>

<template>
  <TransitionGroup name="toast" tag="div" class="pointer-events-none fixed right-4 bottom-4 z-[60] flex w-80 flex-col gap-2" aria-live="polite">
    <div
      v-for="toast in toasts.items"
      :key="toast.id"
      class="pointer-events-auto flex items-start gap-2.5 rounded-md border bg-base-850 px-3.5 py-2.5 text-sm shadow-xl"
      :class="{
        'border-ok/40 text-base-50': toast.kind === 'ok',
        'border-redstone-600/60 text-redstone-300': toast.kind === 'error',
        'border-base-700 text-base-200': toast.kind === 'info',
      }"
      :role="toast.kind === 'error' ? 'alert' : 'status'"
    >
      <span class="mt-1.5 size-1.5 shrink-0 rounded-full" :class="{ 'bg-ok': toast.kind === 'ok', 'bg-redstone-400': toast.kind === 'error', 'bg-base-400': toast.kind === 'info' }" />
      <p class="min-w-0 flex-1">
        {{ toast.text }}
        <button
          v-if="toast.action"
          class="mt-1 block text-xs font-semibold text-redstone-300 underline-offset-2 hover:text-redstone-200 hover:underline focus-visible:underline"
          @click="act(toast)"
        >
          {{ toast.action.label }}
        </button>
      </p>
      <span
        v-if="toast.count > 1"
        class="mt-px shrink-0 rounded bg-base-800 px-1.5 text-[11px] font-semibold tabular-nums text-base-200"
        :aria-label="t('toasts.times', { count: toast.count })"
      >×{{ toast.count }}</span>
      <button class="shrink-0 text-base-400 hover:text-base-50" :aria-label="t('common.actions.close')" @click="toasts.dismiss(toast.id)">
        <svg viewBox="0 0 10 10" class="size-2.5"><path d="M0 0l10 10M10 0L0 10" stroke="currentColor" stroke-width="1.5" /></svg>
      </button>
    </div>
  </TransitionGroup>
</template>
