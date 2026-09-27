<script setup lang="ts">
import { PACK_DURATIONS, type MyPacks, type OwnPack, type PackDuration } from '~/utils/packs'

// „Meine Modpacks“: alle eigenen geteilten Packs – Code/Link, Laufzeit ändern,
// an Freunde schicken, löschen (Code und Link gelten dann sofort nicht mehr).
const emit = defineEmits<{ close: [] }>()
const packs = usePacksStore()
const toasts = useToasts()

const data = ref<MyPacks | null>(null)
const error = ref<string | null>(null)
const open = ref<string | null>(null)
const sendingFor = ref<string | null>(null)
const confirmDelete = ref<OwnPack | null>(null)
const busy = ref(false)

async function load() {
  error.value = null
  try {
    data.value = await backend.packs.mine()
    if (!open.value && data.value.packs.length === 1) open.value = data.value.packs[0]!.id
  } catch (e) {
    error.value = errorMessage(e)
  }
}
onMounted(load)

async function setDuration(p: OwnPack, d: PackDuration) {
  try {
    const updated = await backend.packs.setDuration(p.id, d)
    if (data.value) data.value.packs = data.value.packs.map((x) => (x.id === p.id ? updated : x))
  } catch (e) {
    toasts.error(e)
  }
}

async function remove() {
  const p = confirmDelete.value
  if (!p || busy.value) return
  busy.value = true
  try {
    await backend.packs.remove(p.id)
    if (data.value) data.value.packs = data.value.packs.filter((x) => x.id !== p.id)
    toasts.ok(t('packs.mine.deleted', { name: p.name }))
    confirmDelete.value = null
    void packs.loadLinks()
  } catch (e) {
    toasts.error(e)
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <BaseDialog :title="t('packs.mine.title')" wide @close="emit('close')">
    <p v-if="error" role="alert" class="text-sm text-redstone-300">{{ error }}</p>
    <div v-else-if="!data" class="space-y-2">
      <div v-for="i in 3" :key="i" class="skeleton h-14" />
    </div>
    <p v-else-if="!data.packs.length" class="text-sm text-base-400">{{ t('packs.mine.empty') }}</p>
    <template v-else>
      <p class="mb-3 text-xs text-base-400">
        {{ t('packs.mine.limits', { active: data.limits.active, max: data.limits.maxActive, today: data.limits.uploadsToday, perDay: data.limits.maxPerDay }) }}
      </p>
      <ul class="space-y-2" data-testid="my-packs">
        <li v-for="p in data.packs" :key="p.id" class="card p-3">
          <button class="flex w-full items-center gap-3 text-left" :aria-expanded="open === p.id" @click="open = open === p.id ? null : p.id">
            <span class="min-w-0 flex-1">
              <span class="block truncate font-medium text-base-50">{{ p.name }} <span class="text-xs text-base-400">· {{ p.packVersion }}</span></span>
              <span class="block truncate text-xs text-base-400">{{ packVersionLine(p) }} · {{ p.code }} · {{ packExpiry(p.expiresAt) }}</span>
            </span>
            <span class="shrink-0 text-xs text-base-400">{{ t('packs.mine.stats', { installs: p.installs, sent: p.sentTo }) }}</span>
          </button>
          <div v-if="open === p.id" class="mt-3 space-y-3 border-t border-base-800 pt-3">
            <PackCodeCard :pack="p" />
            <div class="flex flex-wrap items-end gap-3">
              <label class="text-xs text-base-400">
                {{ t('packs.duration.label') }}
                <select class="field mt-1 h-8 w-44 py-0 text-xs text-base-50" :value="p.duration" @change="setDuration(p, ($event.target as HTMLSelectElement).value as PackDuration)">
                  <option v-for="d in PACK_DURATIONS" :key="d" :value="d">{{ durationLabel(d) }}</option>
                </select>
              </label>
              <button class="btn btn-ghost px-3 py-1.5 text-xs" @click="sendingFor = sendingFor === p.id ? null : p.id">{{ t('packs.send.title') }}</button>
              <span class="flex-1" />
              <button class="btn btn-danger px-3 py-1.5 text-xs" @click="confirmDelete = p">{{ t('packs.mine.delete') }}</button>
            </div>
            <p class="text-[11px] text-base-400">{{ t('packs.duration.hint') }}</p>
            <PackSendPicker v-if="sendingFor === p.id" :pack-id="p.id" :pack-name="p.name" />
          </div>
        </li>
      </ul>
    </template>


    <template #actions>
      <button class="btn btn-primary" @click="emit('close')">{{ t('common.actions.close') }}</button>
    </template>
  </BaseDialog>
  <BaseDialog v-if="confirmDelete" :title="t('packs.mine.deleteTitle', { name: confirmDelete.name })" @close="confirmDelete = null">
    <p class="text-sm text-base-200">{{ t('packs.mine.deleteText') }}</p>
    <template #actions>
      <button class="btn btn-ghost" @click="confirmDelete = null">{{ t('common.actions.cancel') }}</button>
      <button class="btn btn-danger" :disabled="busy" @click="remove">{{ t('packs.mine.delete') }}</button>
    </template>
  </BaseDialog>
</template>
