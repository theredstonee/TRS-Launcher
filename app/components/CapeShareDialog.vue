<script setup lang="ts">
import type { TrsCape, TrsCapeHolder, TrsCapeHolders } from '~/utils/trs'

// Einen Umhang mit Freunden teilen: Freund wählen → Angebot. Darunter, wer ihn
// schon hat (oder ein offenes Angebot) – mit Entziehen. Entziehen nimmt ihn
// auch allen weg, an die dieser Spieler ihn weitergegeben hat.
const props = defineProps<{ cape: TrsCape }>()
const emit = defineEmits<{ close: []; changed: [] }>()

const trs = useTrsStore()
const accounts = useAccountsStore()
const toasts = useToasts()

const holders = ref<TrsCapeHolders | null>(null)
const loading = ref(true)
const busy = ref<string | null>(null)
const armed = ref<string | null>(null)
const filter = ref('')

const me = computed(() => accounts.active?.id ?? '')
const taken = computed(() => new Set((holders.value?.holders ?? []).map((h) => h.uuid)))
const friends = computed(() => {
  const q = filter.value.trim().toLowerCase()
  return trsSortFriends(trs.friends?.friends ?? []).filter(
    (f) => !taken.value.has(f.uuid) && (!q || f.name.toLowerCase().includes(q)),
  )
})
const full = computed(() => !!holders.value && holders.value.limit > 0 && holders.value.count >= holders.value.limit)

async function load() {
  loading.value = true
  try {
    holders.value = await backend.trs.capeHolders(props.cape.id)
  } catch (e) {
    toasts.error(e)
  } finally {
    loading.value = false
  }
}

onMounted(async () => {
  if (!trs.friends) await trs.loadFriends()
  await load()
})

async function run(key: string, action: () => Promise<void>) {
  if (busy.value) return
  busy.value = key
  try {
    await action()
  } catch (e) {
    toasts.error(e)
  } finally {
    busy.value = null
  }
}

const share = (uuid: string, name: string) =>
  run(`share:${uuid}`, async () => {
    await backend.trs.offerCape(props.cape.id, uuid)
    toasts.ok(t('capeShare.toasts.offered', { name, cape: props.cape.name }))
    await load()
    emit('changed')
  })

function revoke(h: TrsCapeHolder) {
  // Zweiter Klick bestätigt (wie beim Löschen in der Garderobe).
  if (armed.value !== h.uuid) {
    armed.value = h.uuid
    return
  }
  armed.value = null
  void run(`revoke:${h.uuid}`, async () => {
    await backend.trs.revokeCapeShare(props.cape.id, h.uuid)
    toasts.ok(
      h.status === 'offered'
        ? t('capeShare.toasts.withdrawn', { name: h.name })
        : t('capeShare.toasts.revoked', { name: h.name }),
    )
    await load()
    emit('changed')
  })
}
</script>

<template>
  <BaseDialog :title="t('capeShare.dialog.title', { cape: cape.name })" wide @close="emit('close')">
    <div class="flex gap-4">
      <CapeThumb :texture="cape.texture" :scale="cape.scale" :frames="cape.frames" :frame-time-ms="cape.frameTimeMs" :width="40" class="shrink-0" />
      <div class="min-w-0 flex-1 text-sm text-base-200">
        <p>{{ t('capeShare.dialog.intro') }}</p>
        <p v-if="cape.shared" class="mt-1 text-xs text-base-400">
          {{ t('capeShare.dialog.reshare', { creator: cape.shared.creator.name }) }}
        </p>
      </div>
    </div>

    <h3 class="section-title mt-4 mb-2">{{ t('capeShare.dialog.pickFriend') }}</h3>
    <p v-if="full" class="card px-3 py-2 text-xs text-lamp-300">{{ t('capeShare.dialog.full', { limit: holders!.limit }) }}</p>
    <template v-else>
      <input
        v-if="(trs.friends?.friends.length ?? 0) > 6"
        v-model="filter"
        class="field mb-2 py-1.5"
        maxlength="16"
        :placeholder="t('capeShare.dialog.search')"
        :aria-label="t('capeShare.dialog.search')"
      />
      <ul v-if="friends.length" class="max-h-52 space-y-1.5 overflow-y-auto pr-1" data-testid="cape-share-friends">
        <li v-for="f in friends" :key="f.uuid" class="flex items-center gap-3 rounded-lg bg-base-900/60 px-2.5 py-1.5">
          <span class="block size-7 shrink-0 overflow-hidden rounded"><PlayerFace :uuid="f.uuid" :name="f.name" /></span>
          <span class="min-w-0 flex-1 truncate text-sm">{{ f.name }}</span>
          <button class="btn btn-primary px-3 py-1 text-xs" :disabled="!!busy || loading" @click="share(f.uuid, f.name)">
            {{ busy === `share:${f.uuid}` ? t('capeShare.dialog.sharing') : t('capeShare.dialog.share') }}
          </button>
        </li>
      </ul>
      <p v-else class="text-xs text-base-400">
        {{ trs.friends?.friends.length ? t('capeShare.dialog.allHaveIt') : t('capeShare.dialog.noFriends') }}
      </p>
    </template>

    <h3 class="section-title mt-5 mb-2">
      {{ t('capeShare.dialog.holders') }}
      <span v-if="holders" class="ml-1 text-base-400">{{ holders.count }}/{{ holders.limit }}</span>
    </h3>
    <div v-if="loading && !holders" class="skeleton h-10" />
    <ul v-else-if="holders?.holders.length" class="space-y-1.5" data-testid="cape-share-holders">
      <li v-for="h in holders.holders" :key="h.uuid" class="flex items-center gap-3 rounded-lg bg-base-900/60 px-2.5 py-1.5">
        <span class="block size-7 shrink-0 overflow-hidden rounded"><PlayerFace :uuid="h.uuid" :name="h.name" /></span>
        <div class="min-w-0 flex-1">
          <p class="truncate text-sm">{{ h.name }}</p>
          <p class="truncate text-[11px] text-base-400">
            {{ h.status === 'offered' ? t('capeShare.holders.offered') : t('capeShare.holders.accepted') }}
            <template v-if="h.grantedBy.uuid !== me"> · {{ t('capeShare.holders.via', { name: h.grantedBy.name }) }}</template>
          </p>
        </div>
        <button
          class="btn px-3 py-1 text-xs"
          :class="armed === h.uuid ? 'btn-danger' : 'btn-ghost'"
          :disabled="!!busy"
          @click="revoke(h)"
          @blur="armed === h.uuid && (armed = null)"
        >
          <template v-if="armed === h.uuid">{{ t('capeShare.holders.confirm') }}</template>
          <template v-else>{{ h.status === 'offered' ? t('capeShare.holders.withdraw') : t('capeShare.holders.revoke') }}</template>
        </button>
      </li>
    </ul>
    <p v-else class="text-xs text-base-400">{{ t('capeShare.holders.none') }}</p>
    <p class="mt-3 text-[11px] text-base-600">{{ t('capeShare.dialog.cascade') }}</p>

    <template #actions>
      <button class="btn btn-ghost" @click="emit('close')">{{ t('common.actions.close') }}</button>
    </template>
  </BaseDialog>
</template>
