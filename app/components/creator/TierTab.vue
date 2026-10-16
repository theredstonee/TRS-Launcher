<script setup lang="ts">
import { nextTier, tierAtLeast, tierFollowers, type CreatorSelf, type CreatorTier } from '~/utils/creator'
import type { IconName } from '~/utils/icons'
import type { MessageKey } from '~/utils/i18n'

// Stufe & Vorteile: eigene Stufe, Aufstieg beantragen (Kanal-Link, Follower,
// Notiz), Verlauf der Anträge und welche Vorteile schon gelten.
const props = defineProps<{ self: CreatorSelf }>()
const store = useCreatorStore()
const toasts = useToasts()

const next = computed(() => nextTier(props.self.tier))
const openUpgrade = computed(() => props.self.requests.find((r) => r.kind === 'upgrade' && r.status === 'open') ?? null)
/** Größte Reichweite laut letzter Prüfung durch das Team. */
const followers = computed(() => Math.max(0, ...props.self.channels.map((c) => c.followers ?? 0)))
const progress = computed(() => (next.value ? Math.min(100, Math.round((followers.value / tierFollowers[next.value]) * 100)) : 100))

const perks: { icon: IconName; title: MessageKey; text: MessageKey; tier: CreatorTier }[] = [
  { icon: 'star', title: 'creator.perks.badge', text: 'creator.perks.badgeText', tier: 'starter' },
  { icon: 'ticket', title: 'creator.perks.code', text: 'creator.perks.codeText', tier: 'starter' },
  { icon: 'skins', title: 'creator.perks.fanCape', text: 'creator.perks.fanCapeText', tier: 'partner' },
  { icon: 'bolt', title: 'creator.perks.previews', text: 'creator.perks.previewsText', tier: 'partner' },
  { icon: 'crown', title: 'creator.perks.teamCape', text: 'creator.perks.teamCapeText', tier: 'top' },
  { icon: 'image', title: 'creator.perks.hdCape', text: 'creator.perks.hdCapeText', tier: 'top' },
]

// --- Aufstieg beantragen ------------------------------------------------------------------
const dialog = ref(false)
const form = reactive({ channelUrl: '', followers: 0, note: '' })
const formError = ref<string | null>(null)
const sending = ref(false)

function openDialog() {
  const c = props.self.channels.find((x) => x.url)
  form.channelUrl = c?.url ?? ''
  form.followers = followers.value
  form.note = ''
  formError.value = null
  dialog.value = true
}

async function send() {
  if (!/^https:\/\/\S+\.\S+/.test(form.channelUrl.trim())) {
    formError.value = t('creator.tier.urlInvalid')
    return
  }
  const n = Number(form.followers)
  if (!Number.isInteger(n) || n < 0) {
    formError.value = t('creator.tier.followersInvalid')
    return
  }
  sending.value = true
  formError.value = null
  try {
    const requests = await backend.creator.request({ kind: 'upgrade', channelUrl: form.channelUrl.trim(), followers: n, ...(form.note.trim() ? { note: form.note.trim() } : {}) })
    if (store.dashboard) store.dashboard = { ...store.dashboard, requests }
    dialog.value = false
    toasts.ok(t('creator.tier.sent'))
  } catch (e) {
    formError.value = errorMessage(e)
  } finally {
    sending.value = false
  }
}
</script>

<template>
  <div class="space-y-4" data-testid="creator-tier">
    <section class="card p-4">
      <div class="flex flex-wrap items-center gap-2">
        <h3 class="section-title">{{ t('creator.tier.yours') }}</h3>
        <CreatorTierBadge :tier="self.tier" />
        <span class="flex-1" />
        <template v-if="next">
          <span v-if="openUpgrade" class="badge bg-lamp-900 text-lamp-300">{{ t('creator.tier.pending', { tier: t(`creator.tiers.${next}.name`) }) }}</span>
          <button v-else class="btn btn-primary px-3 py-1.5 text-xs" data-testid="creator-upgrade" @click="openDialog">
            {{ t('creator.tier.request', { tier: t(`creator.tiers.${next}.name`) }) }}
          </button>
        </template>
        <span v-else class="text-xs text-base-400">{{ t('creator.tier.highest') }}</span>
      </div>
      <template v-if="next">
        <p class="mt-3 mb-1 text-xs text-base-400">
          {{ t('creator.tier.progress', { tier: t(`creator.tiers.${next}.name`), n: formatNumber(followers), need: formatNumber(tierFollowers[next]) }) }}
        </p>
        <div class="h-2 overflow-hidden rounded-full bg-base-800" role="progressbar" :aria-valuenow="progress" aria-valuemin="0" aria-valuemax="100">
          <div class="h-full rounded-full bg-redstone-500" :style="{ width: `${progress}%` }" />
        </div>
      </template>
    </section>

    <CreatorTierTable :current="self.tier" />

    <section class="card p-4">
      <h3 class="section-title">{{ t('creator.tier.perksTitle') }}</h3>
      <ul class="mt-2 divide-y divide-base-800">
        <li v-for="p in perks" :key="p.title" class="flex items-start gap-3 py-2.5" :class="{ 'opacity-50': !tierAtLeast(self.tier, p.tier) }">
          <span class="grid size-7 shrink-0 place-items-center rounded-md bg-base-800 text-base-200"><SocialIcon :name="p.icon" class="size-4" /></span>
          <div class="min-w-0 flex-1">
            <p class="flex flex-wrap items-center gap-2 text-sm font-medium text-base-50">
              {{ t(p.title) }}
              <CreatorTierBadge v-if="!tierAtLeast(self.tier, p.tier)" :tier="p.tier" />
            </p>
            <p class="text-xs text-base-400">{{ t(p.text) }}</p>
          </div>
          <span v-if="tierAtLeast(self.tier, p.tier)" class="badge bg-ok/15 text-ok">{{ t('creator.tier.active') }}</span>
          <SocialIcon v-else name="lock" class="size-4 text-base-600" />
        </li>
      </ul>
    </section>

    <section v-if="self.requests.length" class="card p-4">
      <h3 class="section-title">{{ t('creator.tier.requests') }}</h3>
      <ul class="mt-2 divide-y divide-base-800 text-sm">
        <li v-for="r in self.requests" :key="r.id" class="flex flex-wrap items-center gap-2 py-2">
          <span class="text-base-50">{{ t(`creator.tier.kind.${r.kind}`) }}</span>
          <span class="badge" :class="r.status === 'open' ? 'bg-lamp-900 text-lamp-300' : r.status === 'accepted' ? 'bg-ok/15 text-ok' : 'bg-redstone-900 text-redstone-300'">
            {{ t(`creator.tier.status.${r.status}`) }}
          </span>
          <span v-if="r.createdAt" class="text-[11px] text-base-600">{{ formatRelative(r.createdAt, true) }}</span>
          <p v-if="r.reason" class="w-full text-xs text-base-400">{{ r.reason }}</p>
        </li>
      </ul>
    </section>

    <BaseDialog v-if="dialog && next" :title="t('creator.tier.dialogTitle', { tier: t(`creator.tiers.${next}.name`) })" @close="dialog = false">
      <p class="mb-3 text-xs text-base-400">{{ t('creator.tier.dialogText', { need: t(`creator.tiers.${next}.need`) }) }}</p>
      <form class="space-y-3" @submit.prevent="send">
        <label class="block">
          <span class="label">{{ t('creator.tier.channelUrl') }}</span>
          <input v-model="form.channelUrl" class="field" type="url" maxlength="300" placeholder="https://youtube.com/@…" />
        </label>
        <label class="block">
          <span class="label">{{ t('creator.tier.followers') }}</span>
          <input v-model.number="form.followers" class="field" type="number" min="0" />
        </label>
        <label class="block">
          <span class="label">{{ t('creator.tier.note') }}</span>
          <input v-model="form.note" class="field" maxlength="500" placeholder="https://…" />
        </label>
        <p v-if="formError" role="alert" class="text-xs text-redstone-300">{{ formError }}</p>
      </form>
      <template #actions>
        <button class="btn btn-ghost" @click="dialog = false">{{ t('common.actions.cancel') }}</button>
        <button class="btn btn-primary" :disabled="sending" @click="send">{{ t('creator.tier.send') }}</button>
      </template>
    </BaseDialog>
  </div>
</template>
