<script setup lang="ts">
import type { CreatorSelf } from '~/utils/creator'

// Fan-Umhang (ab Partner): aktueller und neuer Umhang in Prüfung, selbst in der
// Umhang-Werkstatt gestalten (Partner 512×256 ohne Animation, Top HD + animiert)
// oder als Wunsch ans Team schicken (nur der Creator selbst, §35.9).
const props = defineProps<{ self: CreatorSelf }>()
const emit = defineEmits<{ tiers: [] }>()
const store = useCreatorStore()
const toasts = useToasts()

const mode = computed(() => props.self.limits.fanCape)
const helper = computed(() => !!props.self.viewer?.helper)
const current = computed(() => props.self.fanCape.current)
const pending = computed(() => props.self.fanCape.pending)
const rejected = computed(() => props.self.fanCape.rejected)
const teamWish = computed(() => props.self.requests.find((r) => r.kind === 'team_cape' && r.status === 'open') ?? null)

const withdrawing = ref(false)
async function withdraw() {
  withdrawing.value = true
  try {
    await backend.creator.withdrawFanCape(store.actingAs)
    await store.loadDashboard()
  } catch (e) {
    toasts.error(e)
  } finally {
    withdrawing.value = false
  }
}

const wishOpen = ref(false)
const wish = ref('')
const wishError = ref<string | null>(null)
const sending = ref(false)
async function sendWish() {
  const text = wish.value.trim()
  if (!text) {
    wishError.value = t('creator.cape.wishEmpty')
    return
  }
  sending.value = true
  wishError.value = null
  try {
    const requests = await backend.creator.request({ kind: 'team_cape', text })
    if (store.dashboard) store.dashboard = { ...store.dashboard, requests }
    wishOpen.value = false
    wish.value = ''
    toasts.ok(t('creator.cape.wishSent'))
  } catch (e) {
    wishError.value = errorMessage(e)
  } finally {
    sending.value = false
  }
}
</script>

<template>
  <section v-if="!mode" class="card p-4" data-testid="creator-cape-locked">
    <h3 class="section-title">{{ t('creator.cape.title') }}</h3>
    <p class="mt-2 text-sm text-base-400">{{ t('creator.cape.locked') }}</p>
    <button v-if="!helper" class="btn btn-ghost mt-3" @click="emit('tiers')">{{ t('creator.cape.seeTiers') }}</button>
  </section>

  <div v-else class="grid gap-4 lg:grid-cols-2" data-testid="creator-cape">
    <section class="card p-4">
      <div class="flex flex-wrap items-center gap-2">
        <h3 class="section-title mr-auto">{{ t('creator.cape.yours') }}</h3>
        <span v-if="pending" class="badge bg-lamp-900 text-lamp-300">{{ t('creator.cape.pending') }}</span>
        <span v-else-if="current" class="badge bg-ok/15 text-ok">{{ t('creator.cape.approved') }}</span>
        <span v-else class="badge bg-base-800 text-base-400">{{ t('creator.cape.none') }}</span>
      </div>
      <p v-if="rejected" class="mt-2 text-xs text-redstone-300" role="status">
        {{ rejected.reason ? t('creator.cape.rejectedReason', { reason: rejected.reason }) : t('creator.cape.rejectedNoReason') }}
      </p>
      <div class="mt-4 flex flex-wrap items-start gap-5">
        <div class="rounded-lg bg-base-850 p-2">
          <CapeThumb v-if="current?.texture" :texture="current.texture" :scale="current.scale" :frames="current.frames" :frame-time-ms="current.frames > 1 ? 100 : null" :width="72" />
          <div v-else class="grid h-[115px] w-[72px] place-items-center rounded-md border-2 border-dashed border-base-700 text-base-600">?</div>
        </div>
        <div v-if="pending" class="rounded-lg border border-lamp-400/30 bg-base-850 p-2 text-center">
          <CapeThumb v-if="pending.texture" :texture="pending.texture" :scale="pending.scale" :frames="pending.frames" :frame-time-ms="pending.frames > 1 ? 100 : null" :width="56" />
          <p class="mt-1 text-[11px] text-lamp-300">{{ t('creator.cape.newInReview') }}</p>
        </div>
        <dl class="min-w-40 flex-1 space-y-1 text-xs">
          <div v-if="current"><dt class="inline text-base-400">{{ t('creator.cape.name') }}:</dt> <dd class="inline text-base-50">{{ current.name }}</dd></div>
          <div><dt class="inline text-base-400">{{ t('creator.cape.size') }}:</dt> <dd class="inline text-base-50">{{ mode === 'hd' ? t('creator.cape.sizeHd') : t('creator.cape.sizeStandard') }}</dd></div>
          <div><dt class="inline text-base-400">{{ t('creator.cape.holders') }}:</dt> <dd class="inline text-base-50 tabular-nums">{{ formatNumber(self.stats.fanCapeHolders) }}</dd></div>
          <p class="pt-1 text-base-400">{{ t('creator.cape.reviewNote') }}</p>
        </dl>
      </div>
    </section>

    <section class="card p-4">
      <h3 class="section-title">{{ t('creator.cape.makeTitle') }}</h3>
      <div class="mt-3 flex gap-3 border-b border-base-800 pb-3">
        <span class="grid size-7 shrink-0 place-items-center rounded-md bg-base-800 text-base-200"><SocialIcon name="upload" class="size-4" /></span>
        <div class="min-w-0">
          <p class="text-sm font-medium text-base-50">{{ t('creator.cape.selfTitle') }}</p>
          <p class="text-xs text-base-400">{{ mode === 'hd' ? t('creator.cape.selfTextHd') : t('creator.cape.selfText') }}</p>
          <div class="mt-2 flex flex-wrap gap-2">
            <button class="btn btn-primary px-3 py-1.5 text-xs" data-testid="creator-cape-workshop" @click="store.fanCapeWorkshop = true">{{ t('creator.cape.openWorkshop') }}</button>
            <button v-if="pending" class="btn btn-ghost px-3 py-1.5 text-xs" :disabled="withdrawing" @click="withdraw">{{ t('creator.cape.withdraw') }}</button>
          </div>
        </div>
      </div>
      <div v-if="!helper" class="flex gap-3 pt-3">
        <span class="grid size-7 shrink-0 place-items-center rounded-md bg-base-800 text-base-200"><SocialIcon name="edit" class="size-4" /></span>
        <div class="min-w-0">
          <p class="text-sm font-medium text-base-50">{{ t('creator.cape.teamTitle') }}</p>
          <p class="text-xs text-base-400">{{ t('creator.cape.teamText') }}</p>
          <p v-if="teamWish" class="mt-2 badge bg-lamp-900 text-lamp-300">{{ t('creator.cape.wishOpen') }}</p>
          <button v-else class="btn btn-ghost mt-2 px-3 py-1.5 text-xs" @click="wishOpen = true">{{ t('creator.cape.sendWish') }}</button>
        </div>
      </div>
      <p class="mt-3 text-[11px] text-base-400">{{ t('creator.cape.rules') }}</p>
    </section>

    <BaseDialog v-if="wishOpen" :title="t('creator.cape.wishTitle')" @close="wishOpen = false">
      <label class="label" for="creator-wish">{{ t('creator.cape.wishLabel') }}</label>
      <textarea id="creator-wish" v-model="wish" class="field" rows="4" maxlength="1000" :placeholder="t('creator.cape.wishPlaceholder')" />
      <p v-if="wishError" role="alert" class="mt-2 text-xs text-redstone-300">{{ wishError }}</p>
      <template #actions>
        <button class="btn btn-ghost" @click="wishOpen = false">{{ t('common.actions.cancel') }}</button>
        <button class="btn btn-primary" :disabled="sending" @click="sendWish">{{ t('creator.cape.send') }}</button>
      </template>
    </BaseDialog>
  </div>
</template>
