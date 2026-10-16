<script setup lang="ts">
import { milestoneColor, tierAtLeast, visibleTabs, type CreatorSelf, type DashboardTab } from '~/utils/creator'

// Creator-Dashboard: Kopf mit Stufe und Haupt-Code, darunter die Reiter
// Statistik, Codes, Fan-Symbol, Fan-Umhang, Vorschau-Builds, Packs, Helfer und
// Stufe & Vorteile. Helfer (§35.9) sehen nur die Reiter ihrer Rechte. Der
// Reiter steht in `?tab=` (Sprung aus Hinweisen).
const props = defineProps<{ self: CreatorSelf }>()

const route = useRoute()
const router = useRouter()
const toasts = useToasts()

const helper = computed(() => !!props.self.viewer?.helper)
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
            {{ helper ? t('creator.helpers.dashboardOf', { name: self.name }) : t('creator.dashboard.title') }}
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
        <p class="text-[11px] text-base-400">{{ helper ? t('creator.dashboard.mainCode') : t('creator.dashboard.yourCode') }}</p>
        <div class="mt-1 flex items-center gap-2">
          <span class="rounded-md border border-dashed border-redstone-500 bg-base-850 px-3 py-1 font-mono text-lg font-bold tracking-widest text-redstone-300" data-testid="creator-main-code">{{ main.code }}</span>
          <button class="btn-icon" :aria-label="t('creator.copy')" :title="t('creator.copy')" @click="copy(main.code)"><SocialIcon name="copy" class="size-4" /></button>
        </div>
      </div>
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
    <CreatorSymbolTab v-else-if="tab === 'symbol'" :self="self" />
    <CreatorCapeTab v-else-if="tab === 'cape'" :self="self" @tiers="tab = 'tier'" />
    <CreatorPreviewsTab v-else-if="tab === 'previews'" :partner="partner" />
    <CreatorPacksTab v-else-if="tab === 'packs'" :self="self" />
    <CreatorHelpersTab v-else-if="tab === 'helpers'" :self="self" />
    <CreatorTierTab v-else-if="tab === 'tier'" :self="self" />
  </div>
</template>
