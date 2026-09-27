<script setup lang="ts">
import type { SharedPack } from '~/utils/packs'

// Code + Link eines geteilten Packs zum Weitergeben (kopieren, im Browser öffnen).
const props = defineProps<{ pack: SharedPack }>()
const toasts = useToasts()
const copied = ref<'code' | 'link' | null>(null)

async function copy(what: 'code' | 'link') {
  try {
    await navigator.clipboard.writeText(what === 'code' ? props.pack.code : props.pack.url)
    copied.value = what
    setTimeout(() => (copied.value = null), 1500)
  } catch {
    // Ohne Zwischenablage bleibt der Code sichtbar und markierbar.
  }
}

function open() {
  backend.openExternalUrl(props.pack.url).catch((e) => toasts.error(e))
}
</script>

<template>
  <div class="rounded-lg border border-base-700 bg-base-900 p-4">
    <p class="text-[11px] font-semibold tracking-wider text-base-400 uppercase">{{ t('packs.code.label') }}</p>
    <p class="display mt-1 text-2xl tracking-wider text-base-50 select-all" data-testid="pack-code">{{ pack.code }}</p>
    <p class="mt-1 truncate font-mono text-xs text-base-400 select-all">{{ pack.url }}</p>
    <div class="mt-3 flex flex-wrap gap-2">
      <button class="btn btn-primary px-3 py-1.5 text-xs" @click="copy('code')">{{ copied === 'code' ? t('packs.code.copied') : t('packs.code.copyCode') }}</button>
      <button class="btn btn-ghost px-3 py-1.5 text-xs" @click="copy('link')">{{ copied === 'link' ? t('packs.code.copied') : t('packs.code.copyLink') }}</button>
      <button class="btn btn-ghost px-3 py-1.5 text-xs" @click="open">{{ t('packs.code.openPage') }}</button>
    </div>
    <p class="mt-2 text-xs text-base-400">{{ packExpiry(pack.expiresAt) }}</p>
  </div>
</template>
