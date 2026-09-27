<script setup lang="ts">
// Geteiltes Modpack an Freunde schicken: Freunde anhaken, senden.
// Wer es schon bekommen hat, bleibt markiert (für diesen Dialog).
const props = defineProps<{ packId: string; packName: string }>()

const trs = useTrsStore()
const toasts = useToasts()
const picked = ref<Set<string>>(new Set())
const sent = ref<Set<string>>(new Set())
const busy = ref(false)
const filter = ref('')

onMounted(() => {
  if (!trs.friends) void trs.loadFriends()
})

const friends = computed(() => {
  const q = filter.value.trim().toLowerCase()
  return (trs.friends?.friends ?? []).filter((f) => !q || f.name.toLowerCase().includes(q))
})

function toggle(uuid: string) {
  const next = new Set(picked.value)
  if (next.has(uuid)) next.delete(uuid)
  else next.add(uuid)
  picked.value = next
}

async function send() {
  if (!picked.value.size || busy.value) return
  busy.value = true
  try {
    const r = await backend.packs.send(props.packId, [...picked.value].slice(0, 20))
    sent.value = new Set([...sent.value, ...r.sent.map((u) => u.uuid)])
    picked.value = new Set()
    toasts.ok(t('packs.send.done', { name: props.packName, count: r.sent.length }, r.sent.length))
  } catch (e) {
    toasts.error(e)
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <div>
    <p v-if="trs.friends && !trs.friends.friends.length" class="text-sm text-base-400">{{ t('packs.send.noFriends') }}</p>
    <template v-else>
      <input v-if="(trs.friends?.friends.length ?? 0) > 6" v-model="filter" class="field mb-2 h-8 py-0 text-xs" maxlength="16" :placeholder="t('packs.send.search')" :aria-label="t('packs.send.search')" />
      <ul class="max-h-44 space-y-0.5 overflow-y-auto pr-1" data-testid="pack-send-friends">
        <li v-for="f in friends" :key="f.uuid">
          <label class="flex cursor-pointer items-center gap-2.5 rounded-md px-2 py-1.5 hover:bg-base-850" :class="{ 'opacity-60': sent.has(f.uuid) }">
            <input type="checkbox" class="size-4 accent-redstone-500" :checked="picked.has(f.uuid) || sent.has(f.uuid)" :disabled="sent.has(f.uuid) || busy" @change="toggle(f.uuid)" />
            <span class="block size-5 overflow-hidden rounded"><PlayerFace :uuid="f.uuid" :name="f.name" /></span>
            <span class="min-w-0 flex-1 truncate text-sm">{{ f.name }}</span>
            <span v-if="sent.has(f.uuid)" class="text-[11px] text-ok">{{ t('packs.send.sent') }}</span>
          </label>
        </li>
      </ul>
      <button class="btn btn-primary mt-2 px-3 py-1.5 text-xs" :disabled="!picked.size || busy" @click="send">
        {{ busy ? t('packs.send.sending') : t('packs.send.action', { count: picked.size }, picked.size) }}
      </button>
    </template>
  </div>
</template>
