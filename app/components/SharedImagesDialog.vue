<script setup lang="ts">
import { daysLeft, type SharedImage, type SharesPage } from '~/utils/share'

// „Meine geteilten Bilder“: alle öffentlichen Links des aktiven Kontos mit
// Vorschau, Ablaufdatum, Kopieren, Öffnen und vorzeitigem Löschen (API §23).
const emit = defineEmits<{ close: []; deleted: [id: string] }>()
const toasts = useToasts()

const page = ref<SharesPage | null>(null)
const loading = ref(true)
const error = ref<string | null>(null)
const toDelete = ref<SharedImage | null>(null)
const busy = ref(false)

async function load() {
  loading.value = true
  try {
    page.value = await backend.social.shares()
    error.value = null
  } catch (e) {
    error.value = errorMessage(e)
  } finally {
    loading.value = false
  }
}
onMounted(load)

async function copy(share: SharedImage) {
  try {
    await navigator.clipboard.writeText(share.url)
    toasts.ok(t('shareLink.toasts.copiedAgain'))
  } catch (e) {
    toasts.error(e)
  }
}

function open(share: SharedImage) {
  backend.openExternalUrl(share.url).catch((e) => toasts.error(e))
}

async function confirmDelete() {
  const share = toDelete.value
  if (!share || busy.value) return
  busy.value = true
  try {
    await backend.social.deleteShare(share.id)
    if (page.value) {
      page.value = {
        shares: page.value.shares.filter((s) => s.id !== share.id),
        limits: { ...page.value.limits, active: Math.max(0, page.value.limits.active - 1) },
      }
    }
    emit('deleted', share.id)
    toasts.ok(t('shareLink.toasts.deleted'))
    toDelete.value = null
  } catch (e) {
    toasts.error(e)
  } finally {
    busy.value = false
  }
}

function expiry(share: SharedImage): string {
  const days = daysLeft(share.expiresAt)
  if (days === null) return ''
  return days === 0 ? t('shareLink.dialog.expiresToday') : t('shareLink.dialog.expiresIn', { days }, days)
}
</script>

<template>
  <BaseDialog :title="t('shareLink.dialog.title')" wide @close="emit('close')">
    <TrsGate what="shares">
      <p class="text-sm text-base-300">{{ t('shareLink.dialog.intro') }}</p>
      <p v-if="page" class="mt-2 flex flex-wrap gap-x-4 gap-y-1 text-xs text-base-400" data-testid="share-limits">
        <span>{{ t('shareLink.dialog.active', { n: page.limits.active, max: page.limits.maxActive }) }}</span>
        <span>{{ t('shareLink.dialog.today', { n: page.limits.uploadsToday, max: page.limits.maxPerDay }) }}</span>
      </p>

      <p v-if="error" role="alert" class="mt-3 rounded-md bg-redstone-900/40 px-3 py-2 text-sm text-redstone-300">{{ error }}</p>
      <div v-else-if="loading" class="mt-3 grid gap-2">
        <div v-for="i in 3" :key="i" class="skeleton h-16" />
      </div>
      <p v-else-if="!page?.shares.length" class="mt-4 rounded-md bg-base-900 px-3 py-6 text-center text-sm text-base-400">
        {{ t('shareLink.dialog.empty') }}
      </p>
      <ul v-else class="mt-3 grid max-h-[26rem] gap-2 overflow-y-auto pr-1" data-testid="share-list">
        <li v-for="share in page.shares" :key="share.id" class="flex items-center gap-3 rounded-lg bg-base-900 p-2 ring-1 ring-base-800">
          <img :src="share.thumbUrl" alt="" class="aspect-video h-14 shrink-0 rounded-md bg-base-950 object-cover" loading="lazy" referrerpolicy="no-referrer" />
          <div class="min-w-0 flex-1 text-xs">
            <p class="truncate font-mono text-base-100">{{ share.url }}</p>
            <p class="truncate text-base-400">
              {{ formatDate(share.createdAt) }} · {{ share.width }}×{{ share.height }} · {{ formatBytes(share.bytes) }}
            </p>
            <p class="truncate text-lamp-300">{{ expiry(share) }}</p>
          </div>
          <div class="flex shrink-0 items-center gap-0.5">
            <button class="btn-icon size-8" :title="t('shareLink.copyLink')" :aria-label="t('shareLink.copyLink')" @click="copy(share)">
              <SocialIcon name="copy" class="size-4" />
            </button>
            <button class="btn-icon size-8" :title="t('shareLink.openInBrowser')" :aria-label="t('shareLink.openInBrowser')" @click="open(share)">
              <SocialIcon name="external" class="size-4" />
            </button>
            <button class="btn-icon size-8 hover:text-redstone-300" :title="t('common.actions.delete')" :aria-label="t('common.actions.delete')" @click="toDelete = share">
              <SocialIcon name="trash" class="size-4" />
            </button>
          </div>
        </li>
      </ul>
    </TrsGate>
    <template #actions>
      <button class="btn btn-ghost" @click="emit('close')">{{ t('common.actions.close') }}</button>
    </template>
  </BaseDialog>

  <BaseDialog v-if="toDelete" :title="t('shareLink.deleteDialog.title')" @close="toDelete = null">
    <p class="text-sm text-base-200">{{ t('shareLink.deleteDialog.text') }}</p>
    <template #actions>
      <button class="btn btn-ghost" @click="toDelete = null">{{ t('common.actions.cancel') }}</button>
      <button class="btn btn-danger" :disabled="busy" data-testid="share-delete-confirm" @click="confirmDelete">{{ t('common.actions.delete') }}</button>
    </template>
  </BaseDialog>
</template>
