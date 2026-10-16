<script setup lang="ts">
import { tierAtLeast, type CreatorSelf } from '~/utils/creator'

// Fan-Symbol: aktuelles (64/32/16 px), neues in Prüfung, Ablehnung mit Grund,
// Vorschau im Spiel und „Bild auswählen …“ (PNG, GIF oder APNG → Streifen).
const props = defineProps<{ self: CreatorSelf }>()
const store = useCreatorStore()
const accounts = useAccountsStore()
const toasts = useToasts()

const current = computed(() => props.self.symbol.current)
const pending = computed(() => props.self.symbol.pending)
const rejected = computed(() => props.self.symbol.rejected)
const animated = computed(() => props.self.limits.symbolFrames > 1)
const shown = computed(() => current.value ?? pending.value)
const uploading = ref(false)
const withdrawing = ref(false)

async function withdraw() {
  withdrawing.value = true
  try {
    await backend.creator.withdrawSymbol(store.actingAs)
    await store.loadDashboard()
  } catch (e) {
    toasts.error(e)
  } finally {
    withdrawing.value = false
  }
}

async function uploaded() {
  uploading.value = false
  toasts.ok(t('creator.symbol.uploaded'))
  await store.loadDashboard()
}
</script>

<template>
  <div class="grid gap-4 lg:grid-cols-2" data-testid="creator-symbol">
    <section class="card p-4">
      <div class="flex flex-wrap items-center gap-2">
        <h3 class="section-title mr-auto">{{ t('creator.symbol.title') }}</h3>
        <span v-if="pending" class="badge bg-lamp-900 text-lamp-300">{{ t('creator.symbol.pending') }}</span>
        <span v-else-if="rejected" class="badge bg-redstone-900 text-redstone-300">{{ t('creator.symbol.rejected') }}</span>
        <span v-else-if="current" class="badge bg-ok/15 text-ok">{{ t('creator.symbol.approved') }}</span>
        <span v-else class="badge bg-base-800 text-base-400">{{ t('creator.symbol.none') }}</span>
      </div>
      <p v-if="rejected" class="mt-2 text-xs text-redstone-300" role="status">
        {{ rejected.reason ? t('creator.symbol.rejectedReason', { reason: rejected.reason }) : t('creator.symbol.rejectedNoReason') }}
      </p>
      <div class="mt-4 flex flex-wrap items-end gap-5">
        <template v-if="current">
          <div v-for="px in [64, 32, 16]" :key="px" class="text-center">
            <CreatorSymbol :image="current.image" :frames="current.frames" :frame-ms="current.frameMs" :px="px" :milestone="self.milestone" />
            <p class="mt-1 text-[11px] text-base-400">{{ px }}×{{ px }}</p>
          </div>
        </template>
        <div v-else class="grid size-16 place-items-center rounded-md border-2 border-dashed border-base-700 text-base-600">?</div>
        <div v-if="pending" class="border-l border-base-800 pl-5 text-center">
          <CreatorSymbol :image="pending.image" :frames="pending.frames" :frame-ms="pending.frameMs" :px="64" />
          <p class="mt-1 text-[11px] text-lamp-300">{{ t('creator.symbol.newInReview') }}</p>
        </div>
      </div>
      <div class="mt-4">
        <CreatorIngamePreview :player="accounts.active?.name ?? 'Steve'" :symbol="shown" :milestone="self.milestone" :creator="self.name" compact />
      </div>
      <p class="mt-2 text-xs text-base-400">{{ pending && current ? t('creator.symbol.oldUntilReview') : t('creator.symbol.hint') }}</p>
    </section>

    <section class="card p-4">
      <h3 class="section-title">{{ t('creator.symbol.uploadTitle') }}</h3>
      <ul class="mt-3 divide-y divide-base-800 text-sm">
        <li class="flex gap-3 py-2.5">
          <span class="grid size-7 shrink-0 place-items-center rounded-md bg-base-800 text-base-200"><SocialIcon name="image" class="size-4" /></span>
          <div>
            <p class="font-medium text-base-50">{{ t('creator.symbol.formatTitle') }}</p>
            <p class="text-xs text-base-400">{{ animated ? t('creator.symbol.formatAnimated') : t('creator.symbol.formatStatic') }}</p>
          </div>
        </li>
        <li class="flex gap-3 py-2.5">
          <span class="grid size-7 shrink-0 place-items-center rounded-md bg-base-800 text-base-200"><SocialIcon name="search" class="size-4" /></span>
          <div>
            <p class="font-medium text-base-50">{{ t('creator.symbol.smallTitle') }}</p>
            <p class="text-xs text-base-400">{{ t('creator.symbol.smallText') }}</p>
          </div>
        </li>
        <li class="flex gap-3 py-2.5">
          <span class="grid size-7 shrink-0 place-items-center rounded-md bg-base-800 text-base-200"><SocialIcon name="check" class="size-4" /></span>
          <div>
            <p class="font-medium text-base-50">{{ t('creator.symbol.reviewTitle') }}</p>
            <p class="text-xs text-base-400">{{ t('creator.symbol.reviewText') }}</p>
          </div>
        </li>
      </ul>
      <div class="mt-3 flex flex-wrap gap-2">
        <button class="btn btn-primary" data-testid="creator-symbol-pick" @click="uploading = true">{{ t('creator.symbol.pick') }}</button>
        <button v-if="pending" class="btn btn-ghost" :disabled="withdrawing" @click="withdraw">{{ t('creator.symbol.withdraw') }}</button>
      </div>
      <p v-if="!tierAtLeast(self.tier, 'partner')" class="mt-2 text-[11px] text-base-400">{{ t('creator.symbol.animatedFromPartner') }}</p>
    </section>

    <CreatorSymbolDialog v-if="uploading" :max-frames="self.limits.symbolFrames" :player="accounts.active?.name ?? 'Steve'" @close="uploading = false" @uploaded="uploaded" />
  </div>
</template>
