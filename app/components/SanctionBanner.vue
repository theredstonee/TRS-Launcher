<script setup lang="ts">
import { endText, kindEffect, kindLabel, sanctionReasonText } from '~/utils/sanctions'

// Hinweis oben im Inhalt, solange eine eigene Strafe wirkt (API §22): Art, was
// gesperrt ist, Ende (relativ + Datum) und Grund. „Einspruch einlegen“ bzw.
// „Details“ öffnet „Meine Strafen“. Wegklicken geht je Strafe (nicht beim Konto-Bann).
const sanctions = useSanctionsStore()
const top = computed(() => sanctions.banner[0] ?? null)
const more = computed(() => Math.max(0, sanctions.banner.length - 1))
const severe = computed(() => top.value?.kind === 'account_ban')
</script>

<template>
  <div v-if="top" class="px-6 pt-3 pb-1" data-testid="sanction-banner">
    <div
      class="flex flex-wrap items-center gap-x-4 gap-y-2 rounded-xl border px-4 py-3 shadow-lg shadow-black/30 backdrop-blur-sm"
      :class="severe ? 'border-redstone-600/70 bg-redstone-900/85' : top.kind === 'warn' ? 'border-lamp-400/40 bg-lamp-900/85' : 'border-base-700 bg-base-850/95'"
      role="status"
    >
      <span
        class="grid size-9 shrink-0 place-items-center rounded-lg"
        :class="severe ? 'bg-redstone-600/40 text-redstone-300' : top.kind === 'warn' ? 'bg-lamp-400/20 text-lamp-300' : 'bg-base-800 text-redstone-300'"
      >
        <SocialIcon :name="top.kind === 'warn' ? 'flag' : 'shield'" class="size-5" />
      </span>
      <div class="min-w-0 flex-1 text-sm">
        <p class="font-semibold text-base-50">
          {{ kindLabel(top.kind) }}
          <span class="font-normal text-base-200">· {{ endText(top) }}</span>
          <span v-if="more" class="ml-1 text-xs font-normal text-base-400">{{ t('sanctions.banner.more', { n: more }) }}</span>
        </p>
        <p class="truncate text-xs text-base-200" :title="sanctionReasonText(top)">
          {{ kindEffect(top.kind) }} {{ t('sanctions.banner.reason', { reason: sanctionReasonText(top) }) }}
        </p>
        <p v-if="top.appeal" class="mt-0.5 text-xs text-base-400">{{ t(`sanctions.appeal.status.${top.appeal.status}`) }}</p>
      </div>
      <div class="flex shrink-0 items-center gap-2">
        <button v-if="top.appealable" class="btn btn-primary px-3 py-1.5 text-xs" data-testid="banner-appeal" @click="sanctions.open(top.id)">
          {{ t('sanctions.appeal.button') }}
        </button>
        <button class="btn btn-ghost px-3 py-1.5 text-xs" data-testid="banner-details" @click="sanctions.open(null)">{{ t('sanctions.banner.details') }}</button>
        <button v-if="!severe" class="btn-icon size-8" :aria-label="t('sanctions.banner.hide')" :title="t('sanctions.banner.hide')" @click="sanctions.dismiss(top.id)">
          <SocialIcon name="close" class="size-3.5" />
        </button>
      </div>
    </div>
  </div>
</template>
