<script setup lang="ts">
import type { TrsCape, TrsUserRef } from '~/utils/trs'

// Von der Freunde-Seite aus: einem Freund einen eigenen (freigegebenen) oder
// selbst geteilt bekommenen Umhang anbieten.
const props = defineProps<{ friend: TrsUserRef }>()
const emit = defineEmits<{ close: [] }>()

const toasts = useToasts()
const capes = ref<TrsCape[] | null>(null)
const busy = ref<string | null>(null)
const done = ref<Set<string>>(new Set())

const shareable = computed(() => (capes.value ?? []).filter((c) => c.shareable))
const pendingOwn = computed(() => (capes.value ?? []).some((c) => c.kind === 'upload' && !c.shared && c.status === 'pending'))

onMounted(async () => {
  try {
    capes.value = await backend.trs.capes()
  } catch (e) {
    toasts.error(e)
    capes.value = []
  }
})

async function share(cape: TrsCape) {
  if (busy.value) return
  busy.value = cape.id
  try {
    await backend.trs.offerCape(cape.id, props.friend.uuid)
    done.value = new Set([...done.value, cape.id])
    toasts.ok(t('capeShare.toasts.offered', { name: props.friend.name, cape: cape.name }))
  } catch (e) {
    toasts.error(e)
  } finally {
    busy.value = null
  }
}
</script>

<template>
  <BaseDialog :title="t('capeShare.friendDialog.title', { name: friend.name })" @close="emit('close')">
    <div v-if="!capes" class="grid grid-cols-3 gap-2">
      <div v-for="i in 3" :key="i" class="skeleton h-28" />
    </div>
    <template v-else-if="shareable.length">
      <p class="mb-3 text-sm text-base-200">{{ t('capeShare.friendDialog.intro') }}</p>
      <ul class="grid grid-cols-[repeat(auto-fill,minmax(7rem,1fr))] gap-2" data-testid="friend-cape-share">
        <li v-for="c in shareable" :key="c.id" class="card flex flex-col items-center gap-2 p-2.5">
          <CapeThumb :texture="c.texture" :scale="c.scale" :frames="c.frames" :frame-time-ms="c.frameTimeMs" :width="26" />
          <span class="w-full truncate text-center text-xs">{{ c.name }}</span>
          <button
            class="btn w-full px-2 py-1 text-xs"
            :class="done.has(c.id) ? 'btn-ghost' : 'btn-primary'"
            :disabled="!!busy || done.has(c.id)"
            @click="share(c)"
          >
            {{ done.has(c.id) ? t('capeShare.friendDialog.offered') : busy === c.id ? t('capeShare.dialog.sharing') : t('capeShare.dialog.share') }}
          </button>
        </li>
      </ul>
    </template>
    <p v-else class="text-sm text-base-400">
      {{ pendingOwn ? t('capeShare.friendDialog.onlyPending') : t('capeShare.friendDialog.none') }}
    </p>
    <template #actions>
      <button class="btn btn-ghost" @click="emit('close')">{{ t('common.actions.close') }}</button>
    </template>
  </BaseDialog>
</template>
