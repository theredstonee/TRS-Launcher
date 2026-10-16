<script setup lang="ts">
import type { ListedPack } from '~/utils/creator'

// „Entdecken“ → Modpacks: gelistete Packs von Creatorn (API §35.4) über den
// Ergebnissen von Modrinth/CurseForge, mit Creator-Abzeichen. Installiert wird
// wie jedes geteilte Pack per Code (Dialog „Modpack per Code“). Ohne TRS-Dienste
// oder ohne Treffer bleibt der Abschnitt weg.
const props = defineProps<{ query: string }>()
const trs = useTrsStore()
const packs = usePacksStore()

const PAGE = 6
const list = ref<ListedPack[]>([])
const total = ref(0)
const loading = ref(false)
let token = 0

async function load(reset: boolean) {
  if (!trs.enabled) {
    list.value = []
    total.value = 0
    return
  }
  const mine = ++token
  loading.value = true
  try {
    const r = await backend.creator.packs(props.query.trim() || null, reset ? 0 : list.value.length, PAGE)
    if (mine !== token) return
    list.value = reset ? r.packs : [...list.value, ...r.packs.filter((p) => !list.value.some((x) => x.code === p.code))]
    total.value = r.total
  } catch {
    // Offline o. Ä.: Abschnitt bleibt still weg.
    if (reset) list.value = []
  } finally {
    if (mine === token) loading.value = false
  }
}

watch(() => [props.query, trs.enabled], () => void load(true), { immediate: true })
</script>

<template>
  <section v-if="list.length" class="card mb-3 p-4" :aria-label="t('creator.packsBrowse.title')" data-testid="creator-pack-section">
    <div class="mb-3 flex flex-wrap items-center gap-2">
      <SocialIcon name="star" class="size-4 text-violet-300" />
      <h2 class="section-title">{{ t('creator.packsBrowse.title') }}</h2>
      <span class="text-xs text-base-400">{{ t('creator.packsBrowse.subtitle') }}</span>
    </div>
    <ul class="grid gap-2.5 md:grid-cols-2">
      <li v-for="p in list" :key="p.code" class="flex gap-3 rounded-lg border border-base-800 bg-base-850 p-3">
        <span class="block size-10 shrink-0 overflow-hidden rounded-lg"><PlayerFace :uuid="p.creator.uuid" :name="p.creator.name" /></span>
        <div class="min-w-0 flex-1">
          <p class="truncate text-sm font-semibold text-base-50">{{ p.name }}</p>
          <p class="flex flex-wrap items-center gap-1.5 text-xs text-base-400">
            <span class="text-[11px] text-violet-300" aria-hidden="true">★</span>{{ p.creator.name }}
            <CreatorTierBadge :tier="p.creator.tier" />
          </p>
          <p v-if="p.summary" class="mt-1 line-clamp-2 text-xs text-base-200">{{ p.summary }}</p>
          <p class="mt-1 text-[11px] text-base-600">
            <template v-if="p.mcVersion">{{ p.mcVersion }} · </template><template v-if="p.loader">{{ loaderLabels[p.loader] }} · </template>{{ t('creator.packsBrowse.installs', { n: formatNumber(p.installs) }) }}
          </p>
        </div>
        <button class="btn btn-primary self-center px-3 py-1.5 text-xs" :data-testid="`creator-pack-${p.code}`" @click="packs.openCode(p.code)">
          {{ t('creator.packsBrowse.install') }}
        </button>
      </li>
    </ul>
    <button v-if="list.length < total" class="btn btn-ghost mt-3 w-full text-xs" :disabled="loading" @click="load(false)">{{ t('creator.packsBrowse.more') }}</button>
  </section>
</template>
