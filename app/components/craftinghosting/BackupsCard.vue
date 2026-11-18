<script setup lang="ts">
import type { CraftingBackup } from '~/utils/craftinghosting'

// Backups des Servers (nur mit Schalter `backups`): anlegen, einspielen (Server aus), herunterladen, löschen.
const emit = defineEmits<{ needStop: [action: () => Promise<unknown>] }>()
const store = useCraftingHostingStore()

onMounted(() => {
  if (store.server?.state !== 'installing') void store.loadBackups()
})

const list = computed(() => store.backups?.backups ?? [])
const full = computed(() => !!store.backups?.max && list.value.length >= store.backups.max)
const busy = computed(() => !!store.busy)
const restoring = ref<CraftingBackup | null>(null)
const deleting = ref<CraftingBackup | null>(null)
const downloading = computed(() => store.backupDownload)

function confirmRestore() {
  const b = restoring.value
  restoring.value = null
  if (b) emit('needStop', () => store.restoreBackup(b))
}
function confirmDelete() {
  const b = deleting.value
  deleting.value = null
  if (b) void store.deleteBackup(b)
}
</script>

<template>
  <section class="card" aria-labelledby="ch-backups-title" data-testid="hosting-backups">
    <div class="flex flex-wrap items-center gap-2 border-b border-base-800 px-4 py-3">
      <div class="min-w-0 flex-1">
        <h3 id="ch-backups-title" class="text-sm font-semibold text-base-50">
          {{ t('craftinghosting.backups.title') }}
          <span v-if="store.backups?.max" class="ml-1 rounded-full bg-base-800 px-2 py-0.5 text-xs font-normal text-base-300 tabular-nums">{{ list.length }}/{{ store.backups.max }}</span>
        </h3>
        <p class="text-xs text-base-400">{{ t('craftinghosting.backups.text') }}</p>
      </div>
      <button class="btn btn-primary px-3 py-1.5 text-xs" :disabled="busy || full || !store.backups" data-testid="hosting-backup-create" @click="store.createBackup()">
        {{ store.busy === 'backup' ? t('craftinghosting.backups.working') : t('craftinghosting.backups.create') }}
      </button>
    </div>
    <p v-if="full" class="px-4 pt-3 text-xs text-lamp-300">{{ t('craftinghosting.backups.full') }}</p>
    <div v-if="!store.backups && store.backupsLoading" class="space-y-2 p-4"><div v-for="i in 2" :key="i" class="skeleton h-12" /></div>
    <p v-else-if="!store.backups" class="p-4 text-sm text-base-400">{{ t('craftinghosting.notAvailable') }}</p>
    <p v-else-if="!list.length" class="p-4 text-sm text-base-500">{{ t('craftinghosting.backups.empty') }}</p>
    <ul v-else class="divide-y divide-base-800/60" data-testid="hosting-backups-list">
      <li v-for="b in list" :key="b.id" class="flex flex-wrap items-center gap-3 px-4 py-2.5">
        <span class="grid size-9 shrink-0 place-items-center rounded-lg bg-base-800 text-sky-300 ring-1 ring-base-700">
          <svg viewBox="0 0 24 24" class="size-4" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M4 7h16v13H4zM4 7l2-3h12l2 3M10 12h4" /></svg>
        </span>
        <div class="min-w-0 flex-1">
          <p class="truncate text-sm text-base-50">{{ b.name }}</p>
          <p class="text-xs text-base-500">{{ formatBytes(b.size) }}<template v-if="b.createdAt"> · {{ formatDate(b.createdAt) }}</template></p>
        </div>
        <button class="btn btn-ghost px-2.5 py-1 text-xs" :disabled="busy" @click="restoring = b">{{ t('craftinghosting.backups.restore') }}</button>
        <button class="btn btn-ghost px-2.5 py-1 text-xs" :disabled="!!downloading" @click="store.downloadBackup(b)">{{ t('craftinghosting.worlds.download') }}</button>
        <button class="btn-icon size-8 text-base-400 hover:text-redstone-300" :disabled="busy" :aria-label="t('craftinghosting.backups.deleteLabel', { name: b.name })" @click="deleting = b">
          <svg viewBox="0 0 24 24" class="size-4" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" aria-hidden="true"><path d="M4 7h16M10 11v6M14 11v6M6 7l1 13h10l1-13M9 7V4h6v3" /></svg>
        </button>
      </li>
    </ul>

    <div v-if="downloading" class="border-t border-base-800 px-4 py-3" aria-live="polite">
      <div class="flex items-center gap-3">
        <div class="min-w-0 flex-1">
          <div class="h-1.5 overflow-hidden rounded-full bg-base-800">
            <div class="h-full bg-sky-400 transition-[width] motion-reduce:transition-none" :style="{ width: `${downloading.progress?.percent ?? 0}%` }" />
          </div>
        </div>
        <button class="btn btn-ghost px-2.5 py-1 text-xs" @click="store.cancelBackupDownload()">{{ t('common.actions.cancel') }}</button>
      </div>
    </div>

    <BaseDialog v-if="restoring" :title="t('craftinghosting.backups.restoreTitle', { name: restoring.name })" @close="restoring = null">
      <p class="text-sm text-base-200">{{ t('craftinghosting.backups.restoreText') }}</p>
      <template #actions>
        <button class="btn btn-ghost" @click="restoring = null">{{ t('common.actions.cancel') }}</button>
        <button class="btn btn-danger" data-testid="hosting-backup-restore-confirm" @click="confirmRestore">{{ t('craftinghosting.backups.restore') }}</button>
      </template>
    </BaseDialog>
    <BaseDialog v-if="deleting" :title="t('craftinghosting.backups.deleteTitle', { name: deleting.name })" @close="deleting = null">
      <p class="text-sm text-base-200">{{ t('craftinghosting.backups.deleteText') }}</p>
      <template #actions>
        <button class="btn btn-ghost" @click="deleting = null">{{ t('common.actions.cancel') }}</button>
        <button class="btn btn-danger" @click="confirmDelete">{{ t('common.actions.delete') }}</button>
      </template>
    </BaseDialog>
  </section>
</template>
