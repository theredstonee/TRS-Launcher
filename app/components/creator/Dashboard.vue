<script setup lang="ts">
import { milestoneColor, tierAtLeast, visibleTabs, type CreatorSelf, type DashboardTab } from '~/utils/creator'
import { teamActionKey } from '~/utils/creatorStats'

// Creator-Dashboard: Kopf mit Stufe und Haupt-Code, darunter die Reiter
// Statistik, Codes, Haustier, Fan-Symbol, Fan-Umhang, Vorschau-Builds, Packs, Helfer und
// Stufe & Vorteile. Helfer (§35.9) sehen nur die Reiter ihrer Rechte; das Team
// (Team-Modus) sieht alles mit deutlichem Hinweis, dass Änderungen protokolliert
// werden. Änderungen des Teams stehen für den Creator unter „Vom Team geändert“.
// Der Reiter steht in `?tab=` (Sprung aus Hinweisen).
const props = defineProps<{ self: CreatorSelf }>()

const route = useRoute()
const router = useRouter()
const toasts = useToasts()

const helper = computed(() => !!props.self.viewer?.helper)
const team = computed(() => !!props.self.viewer?.team)
/** Fremdes Dashboard (Helfer oder Team) – Texte in der dritten Person. */
const foreign = computed(() => helper.value || team.value)
const changesOpen = ref(false)
const changes = computed(() => (changesOpen.value ? props.self.teamChanges : props.self.teamChanges.slice(0, 3)))
const tabs = computed(() => visibleTabs(props.self.viewer))
const tab = computed<DashboardTab | null>({
  get: () => {
    const q = route.query.tab
    const wanted = tabs.value.find((t) => t === q)
    return wanted ?? tabs.value[0] ?? null
  },
  set: (v) => void router.replace({ query: { ...route.query, tab: v ?? undefined } }),
})

const main = computed(() => props.self.codes.find((c) => c.kind === 'main') ?? null)
const partner = computed(() => tierAtLeast(props.self.tier, 'partner'))

async function copy(text: string) {
  try {
    await navigator.clipboard.writeText(text)
    toasts.ok(t('creator.copied', { code: text }))
  } catch {
    toasts.error(t('admin.toasts.copyFailed'))
  }
}

function channelLine(c: { platform: string; followers: number | null }): string {
  return c.followers !== null ? t('creator.channel', { platform: c.platform, n: formatNumber(c.followers) }) : c.platform
}
</script>

<template>
  <div data-testid="creator-dashboard">
    <!-- Kopf -->
    <section class="card mb-4 flex flex-wrap items-center gap-4 px-5 py-4 mobile:px-4">
      <span class="block size-14 shrink-0 overflow-hidden rounded-lg"><PlayerFace :uuid="self.uuid" :name="self.name" /></span>
      <div class="min-w-0 flex-1">
        <div class="flex flex-wrap items-center gap-2">
          <h2 class="display text-2xl leading-none text-base-50">
            {{ foreign ? t('creator.helpers.dashboardOf', { name: self.name }) : t('creator.dashboard.title') }}
          </h2>
          <CreatorTierBadge :tier="self.tier" star />
          <span v-if="self.milestone" class="badge bg-base-800 text-base-200 ring-1 ring-base-700">
            <span class="inline-block size-2 rounded-full" :style="{ background: milestoneColor[self.milestone] }" aria-hidden="true" />{{ t(`creator.milestones.${self.milestone}`) }}
          </span>
        </div>
        <p class="mt-1 text-xs text-base-400">
          {{ self.name }}<template v-for="c in self.channels" :key="c.platform"> · {{ channelLine(c) }}</template><template v-if="self.since"> · {{ t('creator.dashboard.since', { date: formatDate(self.since) }) }}</template>
        </p>
        <p v-if="helper" class="mt-1 text-xs text-lamp-300" data-testid="creator-helper-banner">
          {{ t('creator.helpers.youHelp', { rights: (self.viewer?.rights ?? []).map((r) => t(`creator.helpers.rights.${r}`)).join(', ') || '–' }) }}
        </p>
      </div>
      <div v-if="main" class="text-right mobile:text-left">
        <p class="text-[11px] text-base-400">{{ foreign ? t('creator.dashboard.mainCode') : t('creator.dashboard.yourCode') }}</p>
        <div class="mt-1 flex items-center gap-2">
          <span class="rounded-md border border-dashed border-redstone-500 bg-base-850 px-3 py-1 font-mono text-lg font-bold tracking-widest text-redstone-300" data-testid="creator-main-code">{{ main.code }}</span>
          <button class="btn-icon" :aria-label="t('creator.copy')" :title="t('creator.copy')" @click="copy(main.code)"><SocialIcon name="copy" class="size-4" /></button>
        </div>
      </div>
    </section>

    <!-- Team-Modus: nicht zu übersehen -->
    <section
      v-if="team"
      class="mb-4 flex items-start gap-3 rounded-xl border border-lamp-400/60 bg-lamp-400/10 px-4 py-3"
      role="status"
      data-testid="creator-team-banner"
    >
      <SocialIcon name="shield" class="mt-0.5 size-5 shrink-0 text-lamp-300" />
      <div class="min-w-0">
        <p class="text-sm font-semibold text-lamp-200">{{ t('creator.teamMode.bannerTitle') }}</p>
        <p class="mt-0.5 text-xs text-base-200">{{ t('creator.teamMode.bannerText', { name: self.name }) }}</p>
      </div>
    </section>

    <!-- Änderungen des Teams (für den Creator und das Team sichtbar) -->
    <section v-if="self.teamChanges.length" class="card mb-4 px-4 py-3" :aria-label="t('creator.teamMode.changesTitle')" data-testid="creator-team-changes">
      <div class="flex items-center gap-2">
        <SocialIcon name="shield" class="size-4 text-lamp-300" />
        <h3 class="section-title mr-auto">{{ t('creator.teamMode.changesTitle') }}</h3>
        <button v-if="self.teamChanges.length > 3" class="text-xs text-base-400 hover:text-base-50" @click="changesOpen = !changesOpen">
          {{ changesOpen ? t('creator.teamMode.less') : t('creator.teamMode.all', { n: self.teamChanges.length }) }}
        </button>
      </div>
      <ul class="mt-1.5 space-y-1">
        <li v-for="(c, i) in changes" :key="`${c.action}-${c.at}-${i}`" class="flex flex-wrap items-baseline gap-x-2 text-xs">
          <span class="text-base-200">{{ tKey(teamActionKey(c.action)) }}</span>
          <span v-if="c.at" class="text-base-400">{{ formatRelative(c.at, true) }}</span>
        </li>
      </ul>
      <p v-if="!foreign" class="mt-2 text-[11px] text-base-400">{{ t('creator.teamMode.changesHint') }}</p>
    </section>

    <!-- Reiter -->
    <div v-if="tabs.length" class="mb-4 flex gap-1 mobile-scroll-x" role="tablist" :aria-label="t('creator.dashboard.tabsLabel')">
      <button
        v-for="k in tabs"
        :key="k"
        class="tab whitespace-nowrap"
        :class="{ 'tab-on': tab === k }"
        role="tab"
        :aria-selected="tab === k"
        :data-testid="`creator-tab-${k}`"
        @click="tab = k"
      >
        {{ t(`creator.dashboard.tabs.${k}`) }}
        <span v-if="(k === 'symbol' && self.symbol.pending) || (k === 'cape' && self.fanCape.pending)" class="ml-1 inline-block size-1.5 rounded-full bg-lamp-400 align-middle" />
        <span v-if="k === 'helpers' && self.helpers.length" class="ml-1 text-[11px] text-base-400 tabular-nums">{{ self.helpers.length }}</span>
      </button>
    </div>
    <p v-else class="card px-4 py-5 text-sm text-base-400">{{ t('creator.helpers.noRights') }}</p>

    <CreatorStatsTab v-if="tab === 'stats'" :self="self" />
    <CreatorCodesTab v-else-if="tab === 'codes'" :self="self" />
    <CreatorPetTab v-else-if="tab === 'pet'" :self="self" />
    <CreatorSymbolTab v-else-if="tab === 'symbol'" :self="self" />
    <CreatorCapeTab v-else-if="tab === 'cape'" :self="self" @tiers="tab = 'tier'" />
    <CreatorPreviewsTab v-else-if="tab === 'previews'" :partner="partner" />
    <CreatorPacksTab v-else-if="tab === 'packs'" :self="self" />
    <CreatorHelpersTab v-else-if="tab === 'helpers'" :self="self" />
    <CreatorTierTab v-else-if="tab === 'tier'" :self="self" />
  </div>
</template>
