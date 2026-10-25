<script setup lang="ts">
import { canInviteHelper, helperLimit, helperRights, invitableFriends, type CreatorHelper, type CreatorSelf, type HelperRight } from '~/utils/creator'

// Helfer (§35.9, nur für den Creator): TRS-Freunde einladen, Rechte je Helfer
// als Häkchen (Statistik, Codes, Symbol/Umhang, Packs), ändern und entfernen.
// Grenze je Stufe: Starter 1, Partner 3, Top 10 (angenommen + eingeladen).
// Im Team-Modus darf das Team Rechte ändern und Helfer entfernen, aber niemanden
// einladen – wem der Creator Zugriff gibt, entscheidet nur er selbst.
const props = defineProps<{ self: CreatorSelf }>()
const team = computed(() => !!props.self.viewer?.team)
const store = useCreatorStore()
const trs = useTrsStore()
const toasts = useToasts()

const limit = computed(() => helperLimit[props.self.tier])
const canInvite = computed(() => canInviteHelper(props.self.helpers, props.self.tier))

// --- Rechte bearbeiten -------------------------------------------------------------------
const edits = reactive<Record<string, HelperRight[]>>({})
function rightsOf(h: CreatorHelper): HelperRight[] {
  return edits[h.uuid] ?? h.rights
}
function toggle(h: CreatorHelper, right: HelperRight) {
  const now = rightsOf(h)
  edits[h.uuid] = now.includes(right) ? now.filter((r) => r !== right) : helperRights.filter((r) => r === right || now.includes(r))
}
function changed(h: CreatorHelper): boolean {
  const e = edits[h.uuid]
  return !!e && (e.length !== h.rights.length || e.some((r) => !h.rights.includes(r)))
}

const busy = ref<string | null>(null)
async function save(h: CreatorHelper) {
  const rights = rightsOf(h)
  if (!rights.length) {
    toasts.error(t('creator.helpers.needRight'))
    return
  }
  busy.value = h.uuid
  try {
    await store.updateHelper(h.uuid, rights)
    delete edits[h.uuid]
    toasts.ok(t('creator.helpers.saved', { name: h.name }))
  } catch (e) {
    toasts.error(e)
  } finally {
    busy.value = null
  }
}

const removing = ref<CreatorHelper | null>(null)
async function remove() {
  const h = removing.value
  if (!h) return
  busy.value = h.uuid
  try {
    await store.removeHelper(h.uuid)
    removing.value = null
    toasts.ok(t('creator.helpers.removed', { name: h.name }))
  } catch (e) {
    toasts.error(e)
  } finally {
    busy.value = null
  }
}

// --- Einladen ----------------------------------------------------------------------------
const inviting = ref(false)
const pick = ref<string | null>(null)
const filter = ref('')
const newRights = ref<HelperRight[]>(['stats'])
const inviteError = ref<string | null>(null)

const candidates = computed(() => {
  const list = invitableFriends(trs.friends?.friends ?? [], props.self.helpers, trs.me?.uuid ?? null)
  const f = filter.value.trim().toLowerCase()
  return (f ? list.filter((x) => x.name.toLowerCase().includes(f)) : list).sort((a, b) => compareText(a.name, b.name))
})

function openInvite() {
  pick.value = null
  filter.value = ''
  newRights.value = ['stats']
  inviteError.value = null
  inviting.value = true
  if (!trs.friends) void trs.loadFriends()
}

function toggleNew(right: HelperRight) {
  newRights.value = newRights.value.includes(right) ? newRights.value.filter((r) => r !== right) : helperRights.filter((r) => r === right || newRights.value.includes(r))
}

async function invite() {
  if (!pick.value) {
    inviteError.value = t('creator.helpers.pickFriend')
    return
  }
  if (!newRights.value.length) {
    inviteError.value = t('creator.helpers.needRight')
    return
  }
  busy.value = 'invite'
  inviteError.value = null
  try {
    await store.inviteHelper(pick.value, newRights.value)
    const name = candidates.value.find((c) => c.uuid === pick.value)?.name ?? ''
    inviting.value = false
    toasts.ok(t('creator.helpers.invited', { name }))
  } catch (e) {
    inviteError.value = errorMessage(e)
  } finally {
    busy.value = null
  }
}
</script>

<template>
  <div class="space-y-3" data-testid="creator-helpers">
    <p class="rounded-lg border-l-2 border-redstone-500 bg-base-900 px-3 py-2 text-xs text-base-400">{{ t('creator.helpers.note') }}</p>
    <section class="card p-4">
      <div class="flex flex-wrap items-center gap-2">
        <h3 class="section-title mr-auto">{{ t('creator.helpers.title') }}</h3>
        <span class="text-xs text-base-400">{{ t('creator.helpers.count', { n: self.helpers.length, max: limit }) }}</span>
        <button
          class="btn btn-primary px-3 py-1.5 text-xs"
          :disabled="!canInvite || team"
          :title="team ? t('creator.teamMode.noInvite') : undefined"
          data-testid="creator-helper-invite"
          @click="openInvite"
        >
          <SocialIcon name="userPlus" class="size-4" />{{ t('creator.helpers.invite') }}
        </button>
      </div>
      <p v-if="!self.helpers.length" class="mt-3 text-sm text-base-400">{{ t('creator.helpers.empty') }}</p>
      <ul v-else class="mt-2 divide-y divide-base-800">
        <li v-for="h in self.helpers" :key="h.uuid" class="flex flex-wrap items-center gap-3 py-3" :data-testid="`creator-helper-${h.uuid}`">
          <span class="block size-8 shrink-0 overflow-hidden rounded-md"><PlayerFace :uuid="h.uuid" :name="h.name" /></span>
          <div class="min-w-0">
            <p class="text-sm font-medium text-base-50">{{ h.name }}</p>
            <span class="badge" :class="h.status === 'active' ? 'bg-ok/15 text-ok' : 'bg-lamp-900 text-lamp-300'">
              {{ h.status === 'active' ? t('creator.helpers.active') : t('creator.helpers.pending') }}
            </span>
          </div>
          <div class="flex flex-1 flex-wrap gap-x-4 gap-y-1">
            <label v-for="r in helperRights" :key="r" class="flex items-center gap-1.5 text-xs text-base-200">
              <input type="checkbox" class="accent-redstone-500" :checked="rightsOf(h).includes(r)" @change="toggle(h, r)" />
              {{ t(`creator.helpers.rights.${r}`) }}
            </label>
          </div>
          <button v-if="changed(h)" class="btn btn-primary px-3 py-1 text-xs" :disabled="busy === h.uuid" @click="save(h)">{{ t('creator.helpers.save') }}</button>
          <button class="btn btn-danger px-3 py-1 text-xs" :disabled="busy === h.uuid" @click="removing = h">{{ t('creator.helpers.remove') }}</button>
        </li>
      </ul>
      <p v-if="team" class="mt-3 text-[11px] text-lamp-300" data-testid="creator-team-no-invite">{{ t('creator.teamMode.noInvite') }}</p>
      <p class="mt-3 text-[11px] text-base-400">{{ t('creator.helpers.creatorOnly') }}</p>
    </section>

    <BaseDialog v-if="inviting" :title="t('creator.helpers.inviteTitle')" @close="inviting = false">
      <input v-model="filter" class="field" :placeholder="t('creator.helpers.searchFriends')" :aria-label="t('creator.helpers.searchFriends')" />
      <ul class="mt-2 max-h-56 overflow-y-auto rounded-lg border border-base-800" role="listbox" :aria-label="t('creator.helpers.friends')">
        <li v-for="f in candidates" :key="f.uuid">
          <button
            type="button"
            class="flex w-full items-center gap-2.5 px-3 py-2 text-left text-sm transition-colors hover:bg-base-800"
            :class="pick === f.uuid ? 'bg-base-800 text-base-50' : 'text-base-200'"
            role="option"
            :aria-selected="pick === f.uuid"
            @click="pick = f.uuid"
          >
            <span class="block size-6 shrink-0 overflow-hidden rounded"><PlayerFace :uuid="f.uuid" :name="f.name" /></span>
            {{ f.name }}
            <SocialIcon v-if="pick === f.uuid" name="check" class="ml-auto size-4 text-ok" />
          </button>
        </li>
        <li v-if="!candidates.length" class="px-3 py-3 text-xs text-base-400">{{ t('creator.helpers.noFriends') }}</li>
      </ul>
      <p class="label mt-3">{{ t('creator.helpers.rightsLabel') }}</p>
      <div class="grid grid-cols-2 gap-2">
        <label v-for="r in helperRights" :key="r" class="flex items-center gap-2 text-sm text-base-200">
          <input type="checkbox" class="accent-redstone-500" :checked="newRights.includes(r)" @change="toggleNew(r)" />
          {{ t(`creator.helpers.rights.${r}`) }}
        </label>
      </div>
      <p v-if="inviteError" role="alert" class="mt-2 text-xs text-redstone-300">{{ inviteError }}</p>
      <template #actions>
        <button class="btn btn-ghost" @click="inviting = false">{{ t('common.actions.cancel') }}</button>
        <button class="btn btn-primary" :disabled="busy === 'invite'" @click="invite">{{ t('creator.helpers.send') }}</button>
      </template>
    </BaseDialog>

    <BaseDialog v-if="removing" :title="t('creator.helpers.removeTitle', { name: removing.name })" @close="removing = null">
      <p class="text-sm text-base-200">{{ t('creator.helpers.removeText', { name: removing.name }) }}</p>
      <template #actions>
        <button class="btn btn-ghost" @click="removing = null">{{ t('common.actions.cancel') }}</button>
        <button class="btn btn-danger" :disabled="busy === removing.uuid" @click="remove">{{ t('creator.helpers.remove') }}</button>
      </template>
    </BaseDialog>
  </div>
</template>
