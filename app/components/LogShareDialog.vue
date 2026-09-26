<script setup lang="ts">
// Log teilen: erst bestätigen (öffentlich lesbar!), dann geschwärzt auf
// mclo.gs hochladen und den Link mit QR-Code, Kopieren und Öffnen zeigen.
// `source` = 'live' (neuester Log der Instanz) oder die ID einer Log-Datei.
const props = defineProps<{ instanceId: string; source: string; label: string }>()
const emit = defineEmits<{ close: [] }>()

const toasts = useToasts()
const settings = useSettingsStore()
type Step = 'confirm' | 'uploading' | 'done'
const step = ref<Step>('confirm')
const url = ref<string | null>(null)
const error = ref<string | null>(null)
const copied = ref(false)

const uploadAllowed = computed(() => settings.current?.allowLogUpload !== false)

async function upload() {
  error.value = null
  step.value = 'uploading'
  try {
    url.value = props.source === 'live' ? await backend.shareLog(props.instanceId) : await backend.shareLogSource(props.instanceId, props.source)
    step.value = 'done'
    await copy(false)
  } catch (e) {
    error.value = errorMessage(e)
    step.value = 'confirm'
  }
}

async function copy(announce = true) {
  if (!url.value) return
  try {
    await navigator.clipboard.writeText(url.value)
    copied.value = true
    setTimeout(() => (copied.value = false), 1500)
    if (announce) toasts.ok(t('crash.linkCopied'))
  } catch {
    // Ohne Zwischenablage bleibt der Link sichtbar und markierbar.
  }
}

function openInBrowser() {
  if (url.value) backend.openExternalUrl(url.value).catch((e) => toasts.error(e))
}

onMounted(() => {
  if (!settings.current) settings.load().catch(() => {})
})
</script>

<template>
  <BaseDialog :title="step === 'done' ? t('logShare.doneTitle') : t('crash.shareTitle')" @close="emit('close')">
    <template v-if="step !== 'done'">
      <p class="mb-3 flex items-center gap-2 text-xs text-base-400">
        <svg viewBox="0 0 24 24" class="size-3.5 shrink-0" fill="none" stroke="currentColor" stroke-width="2"><path d="M6 3h9l4 4v14H6zM14 3v5h5" /></svg>
        <span class="truncate font-mono text-base-200">{{ label }}</span>
      </p>
      <i18n-t :keypath="isLinux ? 'crash.shareTextLinux' : 'crash.shareText'" tag="p" scope="global" class="text-sm text-base-200">
        <template #site><strong>mclo.gs</strong></template>
      </i18n-t>
      <ul class="mt-3 space-y-1 text-xs text-base-400">
        <li class="flex gap-2"><span class="text-ok">✓</span>{{ t('logShare.redactTokens') }}</li>
        <li class="flex gap-2"><span class="text-ok">✓</span>{{ t('logShare.redactUser') }}</li>
        <li class="flex gap-2"><span class="text-lamp-300">!</span>{{ t('logShare.publicHint') }}</li>
      </ul>
      <p v-if="!uploadAllowed" class="mt-3 rounded-md border border-lamp-400/30 bg-lamp-900/40 px-3 py-2 text-xs text-lamp-300">{{ t('logShare.disabled') }}</p>
      <p v-if="error" role="alert" class="mt-3 text-sm text-redstone-300">{{ error }}</p>
    </template>

    <div v-else-if="url" class="flex flex-col items-center gap-4 sm:flex-row sm:items-start">
      <QrCode :text="url" :size="148" class="shrink-0" />
      <div class="min-w-0 flex-1 space-y-3">
        <p class="text-sm text-base-200">{{ t('logShare.doneText') }}</p>
        <div class="flex items-center gap-1.5 rounded-md border border-base-700 bg-base-950 py-1 pr-1 pl-3">
          <span class="min-w-0 flex-1 truncate font-mono text-xs text-lamp-300 select-text">{{ url }}</span>
          <button class="btn-icon size-7" :title="t('common.actions.copy')" :aria-label="t('common.actions.copy')" @click="copy()">
            <svg v-if="!copied" viewBox="0 0 24 24" class="size-3.5" fill="none" stroke="currentColor" stroke-width="2" stroke-linejoin="round"><path d="M9 9h11v11H9zM5 15H4V4h11v1" /></svg>
            <svg v-else viewBox="0 0 24 24" class="size-3.5 text-ok" fill="none" stroke="currentColor" stroke-width="2.5"><path :d="icons.check" /></svg>
          </button>
        </div>
        <p class="text-xs text-base-400">{{ t('logShare.qrHint') }}</p>
      </div>
    </div>

    <template #actions>
      <template v-if="step !== 'done'">
        <button class="btn btn-ghost" @click="emit('close')">{{ t('common.actions.cancel') }}</button>
        <button class="btn btn-primary" :disabled="step === 'uploading' || !uploadAllowed" @click="upload">
          {{ step === 'uploading' ? t('crash.uploading') : t('crash.upload') }}
        </button>
      </template>
      <template v-else>
        <button class="btn btn-ghost" @click="openInBrowser">{{ t('logShare.openBrowser') }}</button>
        <button class="btn btn-primary" @click="emit('close')">{{ t('common.actions.done') }}</button>
      </template>
    </template>
  </BaseDialog>
</template>
