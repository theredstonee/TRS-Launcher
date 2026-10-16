<script setup lang="ts">
import { TEAM_WEBSITE } from '~/utils/teamAccess'
import { cleanCodeInput, normalizeCreatorCode, supportDays, switchState, type CreatorRedeem } from '~/utils/creator'

// Creator-Seite als Fan: Code einlösen, wen man unterstützt (mit „Nicht mehr
// unterstützen“), Vorschau im Spiel, Creator per Code blockieren, eigene
// Fan-Umhänge und „Selbst Creator werden“ mit den drei Stufen.
const store = useCreatorStore()
const accounts = useAccountsStore()
const applications = useApplicationsStore()
const toasts = useToasts()

const me = computed(() => store.me!)
const supported = computed(() => me.value.support.creator)
const sw = computed(() => switchState(me.value.support))
const days = computed(() => supportDays(me.value.support.startedAt))
const player = computed(() => accounts.active?.name ?? 'Steve')
const own = computed(() => me.value.creator)

// --- Einlösen -------------------------------------------------------------------------
const code = ref('')
const redeeming = ref(false)

async function redeem() {
  const c = normalizeCreatorCode(code.value)
  if (!c) {
    toasts.error(t('creator.fan.invalidCode'))
    return
  }
  const before = supported.value?.uuid ?? null
  redeeming.value = true
  try {
    const r = await store.redeem(c)
    code.value = ''
    toasts.ok(redeemText(r, before))
  } catch (e) {
    toasts.error(e)
  } finally {
    redeeming.value = false
  }
}

function redeemText(r: CreatorRedeem, before: string | null): string {
  const name = r.creator.name
  const cape = r.creator.fanCape?.name ?? ''
  if (r.supportChanged) return r.fanCapeGranted ? t('creator.fan.redeemed.nowWithCape', { name, cape }) : t('creator.fan.redeemed.now', { name })
  const left = switchState({ startedAt: null, lastCreatorUuid: before, switchAvailableAt: r.switchAvailableAt }).daysLeft
  if (r.fanCapeGranted) return t('creator.fan.redeemed.capeOnly', { name, cape, days: left }, left)
  if (before === r.creator.uuid) return t('creator.fan.redeemed.already', { name })
  return t('creator.fan.redeemed.locked', { days: left }, left)
}

// --- Nicht mehr unterstützen ------------------------------------------------------------
const confirmEnd = ref(false)
const ending = ref(false)
async function endSupport() {
  const name = supported.value?.name ?? ''
  ending.value = true
  try {
    await store.endSupport()
    confirmEnd.value = false
    toasts.ok(t('creator.fan.ended', { name }))
  } catch (e) {
    toasts.error(e)
  } finally {
    ending.value = false
  }
}

// --- Anzeigen & Blockieren ----------------------------------------------------------------
const showOthers = computed({
  get: () => me.value.showFanSymbols,
  set: (v: boolean) => void store.setShowSymbols(v).catch((e) => toasts.error(e)),
})

const blockCode = ref('')
const blocking = ref(false)
async function block() {
  const c = normalizeCreatorCode(blockCode.value)
  if (!c) {
    toasts.error(t('creator.fan.invalidCode'))
    return
  }
  blocking.value = true
  try {
    await store.block(c)
    blockCode.value = ''
    toasts.ok(t('creator.fan.blocked'))
  } catch (e) {
    toasts.error(e)
  } finally {
    blocking.value = false
  }
}

async function unblock(uuid: string, name: string) {
  try {
    await store.unblock(uuid)
    toasts.ok(t('creator.fan.unblocked', { name }))
  } catch (e) {
    toasts.error(e)
  }
}

function openUrl(url: string) {
  void backend.openExternalUrl(url).catch((e) => toasts.error(e))
}

// --- Selbst Creator werden ---------------------------------------------------------------
const application = computed(() => me.value.application)
const applicationOpen = computed(() => !!application.value && ['new', 'review', 'interview'].includes(application.value.status))
const applyUrl = `${TEAM_WEBSITE}/team/creator`

function channelLine(c: { platform: string; followers: number | null }): string {
  return c.followers !== null ? t('creator.channel', { platform: c.platform, n: formatNumber(c.followers) }) : c.platform
}
</script>

<template>
  <div class="space-y-4" data-testid="creator-fan">
    <!-- Eigener Status entzogen/gesperrt -->
    <div v-if="own && own.state !== 'active'" class="card flex items-start gap-3 border-redstone-600/50 px-4 py-4" role="status" data-testid="creator-revoked">
      <span class="grid size-9 shrink-0 place-items-center rounded-lg bg-redstone-900/50 text-redstone-300"><SocialIcon name="lock" class="size-5" /></span>
      <div class="min-w-0 text-sm">
        <p class="font-semibold text-base-50">{{ own.state === 'banned' ? t('creator.revoked.bannedTitle') : t('creator.revoked.title') }}</p>
        <p v-if="own.reason" class="mt-0.5 text-base-200">{{ t('creator.revoked.reason', { reason: own.reason }) }}</p>
        <p class="mt-1 text-xs text-base-400">{{ own.state === 'banned' ? t('creator.revoked.bannedText') : t('creator.revoked.text') }}</p>
      </div>
    </div>

    <div class="grid gap-4 md:grid-cols-2">
      <!-- Code einlösen -->
      <section class="card p-4" :aria-label="t('creator.fan.redeemTitle')">
        <h2 class="section-title">{{ t('creator.fan.redeemTitle') }}</h2>
        <form class="mt-3 flex flex-wrap gap-2" @submit.prevent="redeem">
          <input
            :value="code"
            class="field w-56 flex-1 font-mono text-base font-bold tracking-widest uppercase"
            :placeholder="t('creator.fan.codePlaceholder')"
            :maxlength="20"
            autocomplete="off"
            spellcheck="false"
            :aria-label="t('creator.fan.codeLabel')"
            data-testid="creator-code-input"
            @input="code = cleanCodeInput(($event.target as HTMLInputElement).value)"
          />
          <button class="btn btn-primary" :disabled="redeeming || !code" data-testid="creator-redeem">
            {{ redeeming ? t('creator.fan.redeeming') : t('creator.fan.redeem') }}
          </button>
        </form>
        <p class="mt-2 text-xs text-base-400">{{ t('creator.fan.redeemHint') }}</p>
        <p v-if="me.support.lastCreatorUuid" class="mt-3 rounded-lg border-l-2 border-redstone-500 bg-base-850 px-3 py-2 text-xs text-base-400">
          {{ sw.locked ? t('creator.fan.switchIn', { days: sw.daysLeft }, sw.daysLeft) : t('creator.fan.switchNow') }}
        </p>
      </section>

      <!-- Wen ich unterstütze -->
      <section class="card p-4" :aria-label="t('creator.fan.supportingTitle')" data-testid="creator-supporting">
        <h2 class="section-title">{{ t('creator.fan.supportingTitle') }}</h2>
        <template v-if="supported">
          <div class="mt-3 flex items-start gap-3">
            <span class="block size-11 shrink-0 overflow-hidden rounded-lg"><PlayerFace :uuid="supported.uuid" :name="supported.name" /></span>
            <div class="min-w-0 flex-1">
              <div class="flex flex-wrap items-center gap-2">
                <span class="text-base font-semibold text-base-50">{{ supported.name }}</span>
                <CreatorTierBadge :tier="supported.tier" />
                <CreatorSymbol v-if="supported.symbol" :image="supported.symbol.image" :frames="supported.symbol.frames" :frame-ms="supported.symbol.frameMs" :px="20" :milestone="supported.milestone" />
              </div>
              <p v-for="c in supported.channels" :key="c.platform" class="text-xs text-base-400">
                <button v-if="c.url" type="button" class="hover:text-base-50 hover:underline" @click="openUrl(c.url)">{{ channelLine(c) }}</button>
                <span v-else>{{ channelLine(c) }}</span>
              </p>
              <p class="text-xs text-base-400">{{ t('creator.fan.since', { days }, days) }}</p>
            </div>
            <div v-if="supported.fanCape?.texture" class="rounded-lg bg-base-850 p-1.5" :title="supported.fanCape.name">
              <CapeThumb :texture="supported.fanCape.texture" :scale="supported.fanCape.scale" :frames="supported.fanCape.frames" :frame-time-ms="supported.fanCape.frames > 1 ? 100 : null" :width="34" />
            </div>
          </div>
          <div class="mt-3 flex flex-wrap items-center gap-2">
            <button class="btn btn-ghost px-3 py-1.5 text-xs" data-testid="creator-end-support" @click="confirmEnd = true">{{ t('creator.fan.end') }}</button>
            <span class="text-[11px] text-base-400">{{ t('creator.fan.endHint') }}</span>
          </div>
        </template>
        <p v-else class="mt-3 text-sm text-base-400">{{ t('creator.fan.nobody') }}</p>
      </section>
    </div>

    <!-- Im Spiel -->
    <section class="card p-4" :aria-label="t('creator.preview.title')">
      <div class="mb-3 flex flex-wrap items-center gap-3">
        <h2 class="section-title">{{ t('creator.preview.title') }}</h2>
        <label class="ml-auto flex items-center gap-2 text-xs text-base-200">
          {{ t('creator.preview.showOthers') }}
          <ToggleSwitch v-model="showOthers" :label="t('creator.preview.showOthers')" />
        </label>
      </div>
      <CreatorIngamePreview
        :player="player"
        :symbol="supported?.symbol ?? null"
        :milestone="supported?.milestone ?? null"
        :creator="supported?.name ?? null"
        :show-others="showOthers"
      />
      <p class="mt-2 text-xs text-base-400">{{ t('creator.preview.hint') }}</p>
    </section>

    <!-- Blockieren -->
    <section class="card p-4" :aria-label="t('creator.fan.blockTitle')" data-testid="creator-blocks">
      <div class="flex flex-wrap items-center gap-2">
        <h2 class="section-title mr-auto">{{ t('creator.fan.blockTitle') }}</h2>
        <form class="flex flex-wrap gap-2" @submit.prevent="block">
          <input
            :value="blockCode"
            class="field w-48 font-mono font-bold tracking-widest uppercase"
            :placeholder="t('creator.fan.blockPlaceholder')"
            :maxlength="20"
            autocomplete="off"
            spellcheck="false"
            :aria-label="t('creator.fan.blockLabel')"
            @input="blockCode = cleanCodeInput(($event.target as HTMLInputElement).value)"
          />
          <button class="btn btn-ghost" :disabled="blocking || !blockCode">{{ t('creator.fan.block') }}</button>
        </form>
      </div>
      <p class="mt-2 text-xs text-base-400">{{ t('creator.fan.blockHint') }}</p>
      <ul v-if="me.blocked.length" class="mt-2 divide-y divide-base-800">
        <li v-for="b in me.blocked" :key="b.uuid" class="flex items-center gap-3 py-2">
          <span class="block size-7 shrink-0 overflow-hidden rounded-md"><PlayerFace :uuid="b.uuid" :name="b.name" /></span>
          <span class="text-sm font-medium text-base-50">{{ b.name }}</span>
          <button class="btn btn-ghost ml-auto px-3 py-1 text-xs" @click="unblock(b.uuid, b.name)">{{ t('creator.fan.unblock') }}</button>
        </li>
      </ul>
      <p v-else class="mt-2 text-xs text-base-600">{{ t('creator.fan.noneBlocked') }}</p>
    </section>

    <!-- Fan-Umhänge -->
    <section v-if="me.fanCapes.length" class="card p-4" :aria-label="t('creator.fan.capesTitle')">
      <h2 class="section-title">{{ t('creator.fan.capesTitle') }}</h2>
      <ul class="mt-3 flex flex-wrap gap-2">
        <li v-for="c in me.fanCapes" :key="c.capeId" class="chip gap-1.5">
          <SocialIcon name="skins" class="size-3.5" />
          <span class="font-medium text-base-50">{{ c.name }}</span>
          <span class="text-base-400">{{ t('creator.fan.capeBy', { name: c.creator.name }) }}</span>
        </li>
      </ul>
      <p class="mt-2 text-xs text-base-400">
        {{ t('creator.fan.capesHint') }}
        <NuxtLink to="/skins" class="text-base-200 underline hover:text-base-50">{{ t('creator.fan.openSkins') }}</NuxtLink>
      </p>
    </section>

    <!-- Selbst Creator werden -->
    <section v-if="!own || own.state === 'revoked'" class="card p-4" :aria-label="t('creator.become.title')" data-testid="creator-become">
      <div class="flex flex-wrap items-center gap-2">
        <h2 class="section-title mr-auto">{{ t('creator.become.title') }}</h2>
        <span v-if="application" class="badge" :class="applicationOpen ? 'bg-lamp-900 text-lamp-300' : 'bg-base-800 text-base-200'">
          {{ t(`creator.become.status.${application.status}`) }}<template v-if="application.createdAt"> · {{ formatRelative(application.createdAt, true) }}</template>
        </span>
      </div>
      <p class="mt-1 mb-3 text-xs text-base-400">{{ t('creator.become.text') }}</p>
      <CreatorTierTable />
      <div class="mt-3 flex flex-wrap gap-2">
        <button v-if="application" class="btn btn-ghost" @click="applications.show(application.id)">{{ t('creator.become.view') }}</button>
        <button v-if="!applicationOpen" class="btn btn-primary" data-testid="creator-apply" @click="openUrl(applyUrl)">
          {{ t('creator.become.apply') }} <SocialIcon name="external" class="size-4" />
        </button>
      </div>
    </section>

    <BaseDialog v-if="confirmEnd && supported" :title="t('creator.fan.endTitle', { name: supported.name })" @close="confirmEnd = false">
      <p class="text-sm text-base-200">{{ t('creator.fan.endText', { name: supported.name }) }}</p>
      <p v-if="supported.fanCape" class="mt-2 text-sm text-base-200">{{ t('creator.fan.endKeepCape', { cape: supported.fanCape.name }) }}</p>
      <p class="mt-2 text-xs text-base-400">
        {{ sw.locked ? t('creator.fan.endSwitchIn', { days: sw.daysLeft, name: supported.name }, sw.daysLeft) : t('creator.fan.endSwitchNow', { name: supported.name }) }}
      </p>
      <template #actions>
        <button class="btn btn-ghost" @click="confirmEnd = false">{{ t('common.actions.cancel') }}</button>
        <button class="btn btn-danger" :disabled="ending" data-testid="creator-end-confirm" @click="endSupport">{{ t('creator.fan.end') }}</button>
      </template>
    </BaseDialog>
  </div>
</template>
