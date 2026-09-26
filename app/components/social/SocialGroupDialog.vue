<script setup lang="ts">
import { MAX_GROUP_MEMBERS, conversationTitle } from '~/utils/chat'

// Gruppe erstellen (Name + Freunde auswählen) oder verwalten: umbenennen,
// Mitglieder hinzufügen/entfernen, Besitz übergeben, löschen – das darf nur
// der Besitzer; alle anderen sehen die Mitglieder und können gehen.
const props = defineProps<{ conversationId?: string | null }>()
const emit = defineEmits<{ close: []; created: [id: string] }>()
const chat = useChatStore()
const trs = useTrsStore()
const toasts = useToasts()

const group = computed(() => (props.conversationId ? (chat.conversations[props.conversationId] ?? null) : null))
const isOwner = computed(() => !group.value || group.value.owner === chat.me)
const name = ref(group.value?.name ?? '')
const search = ref('')
const picked = ref<string[]>([])
const busy = ref(false)
const error = ref<string | null>(null)
const confirmDelete = ref(false)

const memberIds = computed(() => new Set(group.value?.members.map((m) => m.uuid) ?? []))
const candidates = computed(() => {
  const q = search.value.trim().toLocaleLowerCase()
  return trsSortFriends(trs.friends?.friends ?? []).filter((f) => !memberIds.value.has(f.uuid) && (!q || f.name.toLocaleLowerCase().includes(q)))
})
const room = computed(() => MAX_GROUP_MEMBERS - (group.value ? group.value.members.length : 1))

function toggle(uuid: string) {
  if (picked.value.includes(uuid)) picked.value = picked.value.filter((u) => u !== uuid)
  else if (picked.value.length < room.value) picked.value = [...picked.value, uuid]
  else toasts.info(t('social.group.full', { max: MAX_GROUP_MEMBERS }))
}

async function run(action: () => Promise<void>) {
  if (busy.value) return
  busy.value = true
  error.value = null
  try {
    await action()
  } catch (e) {
    error.value = errorMessage(e)
  } finally {
    busy.value = false
  }
}

const create = () =>
  run(async () => {
    const trimmed = name.value.trim()
    if (!trimmed || [...trimmed].length > 32) {
      error.value = t('social.group.nameRule')
      return
    }
    const id = await chat.createGroup(trimmed, picked.value)
    toasts.ok(t('social.group.created', { name: trimmed }))
    emit('created', id)
    emit('close')
  })

const rename = () =>
  run(async () => {
    const trimmed = name.value.trim()
    if (!group.value || !trimmed || trimmed === group.value.name) return
    if ([...trimmed].length > 32) {
      error.value = t('social.group.nameRule')
      return
    }
    await chat.renameGroup(group.value.id, trimmed)
    toasts.ok(t('social.group.renamed'))
  })

const addPicked = () =>
  run(async () => {
    if (!group.value || !picked.value.length) return
    await chat.addMembers(group.value.id, picked.value)
    picked.value = []
    toasts.ok(t('social.group.added'))
  })

const removeMember = (uuid: string) =>
  run(async () => {
    if (group.value) await chat.removeMember(group.value.id, uuid)
  })

const makeOwner = (uuid: string) =>
  run(async () => {
    if (group.value) await chat.transferOwner(group.value.id, uuid)
  })

const deleteGroup = () =>
  run(async () => {
    if (!group.value) return
    const title = conversationTitle(group.value, chat.me)
    await chat.deleteGroup(group.value.id)
    toasts.ok(t('social.group.deleted', { name: title }))
    emit('close')
  })

onMounted(() => {
  if (!trs.friends) void trs.loadFriends()
})
</script>

<template>
  <BaseDialog :title="group ? t('social.group.manageTitle') : t('social.group.createTitle')" wide @close="emit('close')">
    <label class="label" for="group-name">{{ t('social.group.name') }}</label>
    <div class="flex gap-2">
      <input
        id="group-name"
        v-model="name"
        class="field flex-1"
        maxlength="32"
        :placeholder="t('social.group.namePlaceholder')"
        :disabled="!isOwner"
        data-testid="group-name"
        @keydown.enter="group ? rename() : create()"
      />
      <button v-if="group && isOwner" class="btn btn-ghost" :disabled="busy || !name.trim() || name.trim() === group.name" @click="rename">
        {{ t('social.group.rename') }}
      </button>
    </div>

    <!-- Mitglieder einer bestehenden Gruppe -->
    <template v-if="group">
      <p class="label mt-4">{{ t('social.group.memberCount', group.members.length) }}</p>
      <ul class="max-h-48 space-y-1 overflow-y-auto">
        <li v-for="m in group.members" :key="m.uuid" class="flex items-center gap-2 rounded-md px-2 py-1.5 hover:bg-base-800">
          <span class="block size-7 overflow-hidden rounded"><PlayerFace :uuid="m.uuid" :name="m.name" /></span>
          <span class="min-w-0 flex-1 truncate text-sm text-base-50">{{ m.name }}<span v-if="m.uuid === chat.me" class="text-base-400"> ({{ t('social.chat.youShort') }})</span></span>
          <span v-if="m.role === 'owner'" class="badge bg-lamp-900 text-lamp-300"><SocialIcon name="crown" class="size-3" />{{ t('social.group.owner') }}</span>
          <template v-else-if="isOwner">
            <button class="btn btn-ghost px-2 py-1 text-xs" :disabled="busy" :title="t('social.group.makeOwner')" @click="makeOwner(m.uuid)">
              <SocialIcon name="crown" class="size-3.5" />
            </button>
            <button class="btn btn-ghost px-2 py-1 text-xs" :disabled="busy" :title="t('social.group.remove')" @click="removeMember(m.uuid)">
              <SocialIcon name="close" class="size-3.5" />
            </button>
          </template>
        </li>
      </ul>
    </template>

    <!-- Freunde auswählen (Erstellen oder Hinzufügen) -->
    <template v-if="isOwner">
      <p class="label mt-4">{{ group ? t('social.group.addFriends') : t('social.group.pickFriends', { n: picked.length, max: MAX_GROUP_MEMBERS - 1 }) }}</p>
      <input v-model="search" class="field mb-2 py-1.5" :placeholder="t('social.group.searchFriends')" :aria-label="t('social.group.searchFriends')" />
      <ul v-if="candidates.length" class="max-h-52 space-y-1 overflow-y-auto" data-testid="group-candidates">
        <li v-for="f in candidates" :key="f.uuid">
          <label class="flex cursor-pointer items-center gap-2 rounded-md px-2 py-1.5 hover:bg-base-800">
            <input type="checkbox" class="accent-redstone-500" :checked="picked.includes(f.uuid)" @change="toggle(f.uuid)" />
            <span class="block size-7 overflow-hidden rounded"><PlayerFace :uuid="f.uuid" :name="f.name" /></span>
            <span class="min-w-0 flex-1 truncate text-sm text-base-50">{{ f.name }}</span>
          </label>
        </li>
      </ul>
      <p v-else class="text-xs text-base-400">{{ t('social.group.noCandidates') }}</p>
      <button v-if="group && picked.length" class="btn btn-ghost mt-2 w-full" :disabled="busy" @click="addPicked">
        {{ t('social.group.addPicked', picked.length) }}
      </button>
    </template>
    <p class="mt-3 text-xs text-base-400">{{ t('social.group.hint') }}</p>

    <p v-if="confirmDelete" role="alert" class="mt-3 rounded-md bg-redstone-900/60 p-3 text-xs text-redstone-300">
      {{ t('social.group.deleteConfirm') }}
      <button class="btn btn-danger mt-2 w-full text-xs" :disabled="busy" @click="deleteGroup">{{ t('social.group.delete') }}</button>
    </p>
    <p v-if="error" role="alert" class="mt-2 text-xs text-redstone-300">{{ error }}</p>

    <template #actions>
      <button v-if="group && isOwner" class="btn btn-danger mr-auto" :disabled="busy" @click="confirmDelete = !confirmDelete">
        <SocialIcon name="trash" class="size-4" />{{ t('social.group.delete') }}
      </button>
      <button class="btn btn-ghost" @click="emit('close')">{{ group ? t('common.actions.close') : t('common.actions.cancel') }}</button>
      <button v-if="!group" class="btn btn-primary" :disabled="busy || !name.trim()" data-testid="group-create" @click="create">{{ t('social.group.create') }}</button>
    </template>
  </BaseDialog>
</template>
