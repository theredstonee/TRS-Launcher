<script setup lang="ts">
import type { TrsHeadCosmetic } from '~/utils/trs'

// Kopf-Kosmetik auf der Skins-Seite: alle Teile als Bild-Karten (besessen und
// gesperrt – versteckte nur, wenn man sie hat). Klick = in der großen 3D-Vorschau
// der Seite anprobieren (per `preview`), Aufsetzen/Absetzen, und bei gesperrten
// Teilen steht dabei, wie man sie bekommt (Code, Erfolg, nur Team).
const props = defineProps<{ previewId: string | null; night: boolean }>()
const emit = defineEmits<{
  preview: [item: TrsHeadCosmetic | null]
  equipped: [item: TrsHeadCosmetic | null]
  redeem: []
}>()

const trs = useTrsStore()
const accounts = useAccountsStore()
const achievements = useAchievementsStore()
const toasts = useToasts()

const items = ref<TrsHeadCosmetic[] | null>(null)
const loading = ref(false)
const offline = ref(false)
const busy = ref(false)

const equipped = computed(() => items.value?.find((c) => c.equipped) ?? null)
// Die Seite zeigt das getragene Teil in der Vorschau – so wie andere es im Spiel sehen.
watch(equipped, (item) => emit('equipped', item), { immediate: true })
const selected = computed(() => items.value?.find((c) => c.id === props.previewId) ?? null)

async function load() {
  if (!trs.enabled || !accounts.active) {
    items.value = null
    return
  }
  loading.value = true
  try {
    items.value = await backend.trs.headCosmetics()
    offline.value = false
    // Die Vorschau zeigt die aktuellen Daten (z. B. nach dem Einlösen).
    if (props.previewId) emit('preview', items.value.find((c) => c.id === props.previewId) ?? null)
  } catch (e) {
    if (e instanceof BackendError && e.kind === 'trs_offline') offline.value = true
    else if (!(e instanceof BackendError && trsIsQuiet(e.kind))) toasts.error(e)
  } finally {
    loading.value = false
  }
}

onMounted(load)
watch(() => [trs.enabled, accounts.active?.id], load)
// Code eingelöst, Erfolgs-Belohnung aufgesetzt …
watch(() => trs.capesRevision, load)

function preview(item: TrsHeadCosmetic) {
  emit('preview', props.previewId === item.id ? null : item)
}

async function wear(item: TrsHeadCosmetic | null) {
  if (busy.value) return
  busy.value = true
  try {
    await backend.trs.setHat(item?.id ?? null)
    toasts.ok(item ? t('capes.toasts.hatOn', { name: item.name }) : t('capes.toasts.hatOff'))
    await load()
  } catch (e) {
    toasts.error(e)
  } finally {
    busy.value = false
  }
}

/** Bild der Karte: nachts das Nacht-Bild (falls vorhanden). */
function cardOf(item: TrsHeadCosmetic): string | null {
  return (props.night ? item.cardNight : null) ?? item.card ?? item.cardNight
}

function unlockLabel(item: TrsHeadCosmetic): string {
  switch (item.unlock) {
    case 'free':
      return t('trs.unlock.free')
    case 'code':
      return t('trs.unlock.code')
    case 'achievement':
      return t('trs.unlock.achievement')
    case 'admin':
      return t('trs.unlock.team')
    case 'owner':
      return t('trs.unlock.own')
    default:
      return item.owned ? t('headCosmetics.unlocked') : t('trs.unlock.locked')
  }
}

function lockClass(item: TrsHeadCosmetic) {
  if (item.unlock === 'free') return 'bg-ok/10 text-ok'
  if (item.unlock === 'code') return 'bg-lamp-900/60 text-lamp-300'
  if (item.unlock === 'achievement') return 'bg-base-800 text-lamp-200'
  return 'bg-redstone-900/50 text-redstone-300'
}

// Erfolg, der das Teil freischaltet (Liste der Erfolge nur bei Bedarf laden).
const rewardAchievement = computed(() => {
  const id = selected.value?.id
  if (!id) return null
  return achievements.data?.achievements.find((a) => a.reward?.kind === 'cosmetic' && a.reward.id === id) ?? null
})
watch(selected, (item) => {
  if (item && !item.owned && item.unlock === 'achievement' && !achievements.data) void achievements.load()
})

const howText = computed(() => {
  const item = selected.value
  if (!item || item.owned) return null
  switch (item.unlock) {
    case 'code':
      return t('headCosmetics.how.code')
    case 'achievement':
      return rewardAchievement.value
        ? t('headCosmetics.how.achievement', { name: achievementTitle(rewardAchievement.value) })
        : t('headCosmetics.how.achievementAny')
    case 'admin':
      return t('headCosmetics.how.team')
    default:
      return t('headCosmetics.how.other')
  }
})
</script>

<template>
  <section aria-labelledby="trs-head-title" data-testid="trs-head-cosmetics">
    <div class="mb-2 flex flex-wrap items-center gap-2">
      <h2 id="trs-head-title" class="section-title">{{ t('headCosmetics.title') }}</h2>
      <span v-if="equipped" class="badge bg-ok/10 text-ok">{{ t('headCosmetics.wearing', { name: equipped.name }) }}</span>
    </div>
    <p class="mb-3 text-xs text-base-400">{{ t('headCosmetics.intro') }}</p>

    <TrsGate what="cosmetics">
      <div v-if="offline" class="card flex items-center gap-3 px-4 py-3 text-sm text-base-400">
        <span class="size-2 rounded-full bg-base-600" />
        <span class="flex-1">{{ t('headCosmetics.offline') }}</span>
        <button class="btn btn-ghost px-3 py-1 text-xs" :disabled="loading" @click="load">{{ t('common.actions.retry') }}</button>
      </div>
      <div v-else-if="loading && !items" class="grid grid-cols-[repeat(auto-fill,minmax(9rem,1fr))] gap-3">
        <div v-for="i in 6" :key="i" class="skeleton aspect-[4/5]" />
      </div>
      <template v-else-if="items">
        <ul class="grid grid-cols-[repeat(auto-fill,minmax(9rem,1fr))] gap-3" data-testid="trs-head-list">
          <li v-for="item in items" :key="item.id">
            <button
              class="card card-hover group flex h-full w-full flex-col items-center gap-2 p-2 pb-3"
              :class="{ 'border-redstone-600/60 bg-redstone-900/20': previewId === item.id }"
              :aria-pressed="previewId === item.id"
              :title="item.owned ? t('headCosmetics.tryOn', { name: item.name }) : t('headCosmetics.tryOnLocked', { name: item.name })"
              :data-cosmetic="item.id"
              @click="preview(item)"
            >
              <span
                class="relative block aspect-square w-full overflow-hidden rounded-md bg-gradient-to-b"
                :class="night ? 'from-[#0b0d18] to-[#05060b]' : 'from-base-800 to-base-950'"
              >
                <img
                  v-if="cardOf(item)"
                  :src="cardOf(item)!"
                  alt=""
                  class="size-full object-cover transition-transform duration-300 group-hover:scale-105"
                  :class="{ 'opacity-55 grayscale-[0.7]': !item.owned }"
                  draggable="false"
                />
                <span v-else class="grid size-full place-items-center text-base-600">
                  <svg viewBox="0 0 24 24" class="size-10" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
                    <path d="M4 17h16M6 17l1-8 5 3 5-3 1 8M12 12V6" />
                  </svg>
                </span>
                <span v-if="item.equipped" class="badge absolute top-1.5 left-1.5 bg-ok/20 px-1.5 py-0 text-[10px] text-ok backdrop-blur-sm">
                  {{ t('headCosmetics.worn') }}
                </span>
                <svg
                  v-if="!item.owned"
                  viewBox="0 0 24 24"
                  class="absolute right-1.5 bottom-1.5 size-5 rounded bg-base-900/90 p-0.5 text-base-200"
                  fill="none"
                  stroke="currentColor"
                  stroke-width="2"
                  stroke-linecap="round"
                  :aria-label="t('headCosmetics.locked')"
                >
                  <path d="M7 11V8a5 5 0 0 1 10 0v3M5 11h14v10H5z" />
                </svg>
              </span>
              <span class="w-full truncate px-1 text-center text-xs font-medium">{{ item.name }}</span>
              <span class="flex flex-wrap justify-center gap-1">
                <span class="badge px-1.5 py-0 text-[10px]" :class="lockClass(item)">{{ unlockLabel(item) }}</span>
                <span v-if="item.glowFrames > 0" class="badge bg-redstone-900/40 px-1.5 py-0 text-[10px] text-redstone-200">{{ t('headCosmetics.glows') }}</span>
              </span>
            </button>
          </li>
        </ul>
        <p v-if="!items.length" class="card px-4 py-6 text-center text-sm text-base-400">{{ t('headCosmetics.empty') }}</p>

        <!-- Aktionen für das angeprobte Teil -->
        <div v-if="selected" class="card mt-3 flex flex-wrap items-center gap-3 px-4 py-3" data-testid="trs-head-actions">
          <div class="min-w-0 flex-1">
            <p class="truncate text-sm font-semibold text-base-50">{{ selected.name }}</p>
            <p v-if="howText" class="text-xs text-base-300">
              <span class="font-semibold text-lamp-300">{{ t('headCosmetics.how.title') }}:</span> {{ howText }}
            </p>
            <p v-else class="text-xs text-base-400">
              {{ selected.equipped ? t('headCosmetics.selected.worn') : t('headCosmetics.selected.owned') }}
            </p>
            <p v-if="!selected.preview" class="text-xs text-base-600">{{ t('headCosmetics.selected.noPreview') }}</p>
          </div>
          <button
            v-if="selected.owned && !selected.equipped"
            class="btn btn-primary px-3 py-1.5 text-xs"
            :disabled="busy"
            data-testid="trs-head-wear"
            @click="wear(selected)"
          >
            {{ busy ? t('headCosmetics.actions.wearing') : t('headCosmetics.actions.wear') }}
          </button>
          <button v-if="selected.equipped" class="btn btn-ghost px-3 py-1.5 text-xs" :disabled="busy" @click="wear(null)">
            {{ busy ? t('headCosmetics.actions.removing') : t('headCosmetics.actions.remove') }}
          </button>
          <button v-if="!selected.owned && selected.unlock === 'code'" class="btn btn-ghost px-3 py-1.5 text-xs" @click="emit('redeem')">
            {{ t('capes.redeem') }}
          </button>
          <NuxtLink v-if="!selected.owned && selected.unlock === 'achievement'" to="/achievements" class="btn btn-ghost px-3 py-1.5 text-xs">
            {{ t('headCosmetics.actions.achievements') }}
          </NuxtLink>
          <button class="btn btn-ghost px-3 py-1.5 text-xs" @click="emit('preview', null)">{{ t('capes.actions.endPreview') }}</button>
        </div>
      </template>
    </TrsGate>
  </section>
</template>
