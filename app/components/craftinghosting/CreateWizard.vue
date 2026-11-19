<script setup lang="ts">
import type { Instance } from '~/types'
import {
  ACCESS_MODES,
  DOMAIN_SUFFIX,
  NAME_MAX,
  type WizardState,
  type WizardStep,
  defaultServerType,
  planFromInstance,
  suggestServerName,
  suggestSubdomain,
  validServerName,
  wizardStepValid,
} from '~/utils/craftinghosting'

// Assistent für den kostenlosen Server: 1. Woher (Instanz oder neu), 2. Name & Adresse, 3. Zugang & Start.
const props = defineProps<{ locked?: boolean }>()

const store = useCraftingHostingStore()
const instances = useInstancesStore()
const info = computed(() => store.info)

const STEPS: { n: WizardStep; key: 'source' | 'name' | 'access' }[] = [
  { n: 1, key: 'source' },
  { n: 2, key: 'name' },
  { n: 3, key: 'access' },
]
const step = ref<WizardStep>(1)
const dir = ref<1 | -1>(1)
const heading = useTemplateRef<HTMLElement>('heading')

const w = reactive<WizardState>({
  source: null,
  instanceId: '',
  serverType: '',
  version: '',
  name: '',
  subdomain: '',
  subdomainEnabled: true,
  subdomainStatus: 'idle',
  access: 'friends',
  eula: false,
})
const nameTouched = ref(false)
const subdomainTouched = ref(false)
const instancesLoading = ref(false)

const sortedInstances = computed(() =>
  [...instances.items].sort((a, b) => (b.lastPlayed ?? b.createdAt).localeCompare(a.lastPlayed ?? a.createdAt)),
)

onMounted(async () => {
  if (!instances.items.length) {
    instancesLoading.value = true
    await instances.load().catch(() => {})
    instancesLoading.value = false
  }
  // Mit Instanzen startet der Assistent direkt bei „Aus einer Instanz“.
  if (!w.source) chooseSource(sortedInstances.value.length ? 'instance' : 'new')
})

const instance = computed(() => instances.items.find((i) => i.id === w.instanceId) ?? null)
const plan = computed(() => (instance.value ? planFromInstance(instance.value, info.value) : null))
const types = computed(() => info.value?.serverTypes ?? [])
const type = computed(() => types.value.find((t) => t.id === w.serverType) ?? null)
const versions = computed(() => type.value?.versions ?? [])
const versionLabel = (v: string) => (v === 'latest' ? t('craftinghosting.latest') : v)

function applyPlan() {
  const p = plan.value
  if (!p) return
  w.serverType = p.serverType
  w.version = p.version
}

function chooseSource(source: 'instance' | 'new') {
  w.source = source
  if (source === 'new') {
    if (!types.value.some((t) => t.id === w.serverType)) Object.assign(w, defaultServerType(info.value))
  } else {
    if (!w.instanceId && sortedInstances.value[0]) chooseInstance(sortedInstances.value[0])
    else applyPlan()
  }
}

function chooseInstance(i: Instance) {
  w.instanceId = i.id
  applyPlan()
  if (!nameTouched.value) w.name = suggestServerName(i.name)
}

function chooseType(id: string) {
  w.serverType = id
  if (!versions.value.includes(w.version)) w.version = versions.value[0] ?? ''
}

// Server-Infos kommen evtl. erst nach dem Mount.
watch(info, () => {
  if (w.serverType) return
  if (w.source === 'instance') applyPlan()
  else if (w.source === 'new') Object.assign(w, defaultServerType(info.value))
})

// Adresse folgt dem Namen, bis sie jemand selbst ändert.
watch(
  () => w.name,
  (name) => {
    if (!subdomainTouched.value) w.subdomain = suggestSubdomain(name)
  },
)
function onSubdomain(value: string) {
  subdomainTouched.value = true
  w.subdomain = value
}

const canNext = computed(() => !props.locked && wizardStepValid(step.value, w))
// Anlegen schaltet die TRS API frei (Schalter `create`, keine Wartung).
const createOff = computed(() => !store.features.create || !!info.value?.maintenance.active)
const canCreate = computed(
  () => canNext.value && !createOff.value && !info.value?.full && !store.busy && ([1, 2, 3] as const).every((n) => wizardStepValid(n, w)),
)

async function go(next: WizardStep) {
  if (next > step.value && !wizardStepValid(step.value, w)) return
  dir.value = next > step.value ? 1 : -1
  step.value = next
  await nextTick()
  heading.value?.focus({ preventScroll: true })
}

async function create() {
  if (!canCreate.value) return
  await store.create(
    {
      name: w.name.trim(),
      serverType: w.serverType,
      version: w.version,
      access: w.access,
      subdomain: w.subdomainEnabled && w.subdomain ? w.subdomain : undefined,
      acceptEula: w.eula,
    },
    w.source === 'instance' ? w.instanceId : undefined,
  )
}

function openEula() {
  if (info.value) void backend.openExternalUrl(info.value.eulaUrl).catch((e) => useToasts().error(e))
}

function typeName(id: string) {
  return types.value.find((t) => t.id === id)?.name ?? id
}
const KNOWN_TYPES = ['paper', 'purpur', 'vanilla', 'fabric', 'forge'] as const
function typeHint(id: string) {
  const known = KNOWN_TYPES.find((k) => k === id)
  return known ? t(`craftinghosting.wizard.types.${known}`) : t('craftinghosting.wizard.types.other')
}
const previewAddress = computed(() => (w.subdomainEnabled && w.subdomain ? `${w.subdomain}${DOMAIN_SUFFIX}` : null))
</script>

<template>
  <div class="grid gap-5 lg:grid-cols-[1fr_19rem]" data-testid="hosting-create">
    <div class="card relative overflow-hidden p-5 mobile:p-4">
      <!-- Schritte -->
      <ol class="mb-5 flex items-center gap-2" :aria-label="t('craftinghosting.wizard.stepsLabel')">
        <template v-for="(s, idx) in STEPS" :key="s.n">
          <li class="flex min-w-0 items-center">
            <button
              type="button"
              class="group flex min-w-0 items-center gap-2 rounded-full py-1 pr-2 pl-1 text-left outline-none focus-visible:ring-2 focus-visible:ring-redstone-500 disabled:cursor-default"
              :disabled="s.n >= step"
              :aria-current="s.n === step ? 'step' : undefined"
              :data-testid="`hosting-step-${s.n}`"
              @click="go(s.n)"
            >
              <span
                class="flex size-7 shrink-0 items-center justify-center rounded-full text-xs font-semibold tabular-nums ring-1 transition-colors"
                :class="
                  s.n === step
                    ? 'ch-step-on bg-redstone-500 text-white ring-redstone-400'
                    : s.n < step
                      ? 'bg-redstone-900/60 text-redstone-300 ring-redstone-600 group-hover:bg-redstone-900'
                      : 'bg-base-850 text-base-400 ring-base-700'
                "
              >
                <svg v-if="s.n < step" viewBox="0 0 16 16" class="size-3.5" fill="none" stroke="currentColor" stroke-width="2.4" aria-hidden="true"><path d="m3.5 8.5 3 3 6-7" /></svg>
                <template v-else>{{ s.n }}</template>
              </span>
              <span class="truncate text-sm" :class="s.n === step ? 'font-semibold text-base-50' : 'text-base-400 mobile:hidden'">
                {{ t(`craftinghosting.wizard.steps.${s.key}`) }}
              </span>
            </button>
          </li>
          <li v-if="idx < STEPS.length - 1" aria-hidden="true" class="h-px min-w-4 flex-1 rounded-full" :class="s.n < step ? 'bg-redstone-600' : 'bg-base-800'" />
        </template>
      </ol>

      <Transition :name="dir === 1 ? 'ch-next' : 'ch-prev'" mode="out-in">
        <!-- 1: Woher? -->
        <section v-if="step === 1" key="1" aria-labelledby="ch-step-title">
          <h2 id="ch-step-title" ref="heading" tabindex="-1" class="display text-2xl text-base-50 outline-none">{{ t('craftinghosting.wizard.source.title') }}</h2>
          <div class="mt-4 grid gap-3 sm:grid-cols-2" role="radiogroup" :aria-label="t('craftinghosting.wizard.source.title')">
            <label
              v-for="src in ['instance', 'new'] as const"
              :key="src"
              class="ch-choice relative flex cursor-pointer items-start gap-3 rounded-xl border p-4 transition-colors has-[:focus-visible]:ring-2 has-[:focus-visible]:ring-redstone-500"
              :class="w.source === src ? 'ch-choice-on border-redstone-500/70 bg-base-850' : 'border-base-800 bg-base-900 hover:border-base-700 hover:bg-base-850'"
              :data-testid="`hosting-source-${src}`"
            >
              <input type="radio" name="ch-source" class="sr-only" :value="src" :checked="w.source === src" @change="chooseSource(src)" />
              <span class="flex size-11 shrink-0 items-center justify-center rounded-lg" :class="w.source === src ? 'bg-redstone-500/15 text-redstone-300' : 'bg-base-800 text-base-200'">
                <svg v-if="src === 'instance'" viewBox="0 0 24 24" class="size-6" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linejoin="round" aria-hidden="true">
                  <rect x="3" y="4" width="8" height="8" rx="1.5" /><rect x="13" y="4" width="8" height="8" rx="1.5" /><rect x="3" y="14" width="8" height="6" rx="1.5" /><path d="M16 15v6M13 18h6" stroke-linecap="round" />
                </svg>
                <svg v-else viewBox="0 0 24 24" class="size-6" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linejoin="round" aria-hidden="true">
                  <path d="M12 3 20 7.5v9L12 21l-8-4.5v-9z" /><path d="M12 8.5v7M8.5 12h7" stroke-linecap="round" />
                </svg>
              </span>
              <span class="min-w-0">
                <span class="block font-semibold text-base-50">{{ t(`craftinghosting.wizard.source.${src}`) }}</span>
                <span class="mt-0.5 block text-xs leading-relaxed text-base-400">{{ t(`craftinghosting.wizard.source.${src}Hint`) }}</span>
              </span>
            </label>
          </div>

          <!-- Instanzen -->
          <template v-if="w.source === 'instance'">
            <h3 class="mt-6 mb-2 text-xs font-semibold tracking-wide text-base-400 uppercase">{{ t('craftinghosting.wizard.source.pickInstance') }}</h3>
            <div v-if="instancesLoading" class="grid gap-2 sm:grid-cols-2 xl:grid-cols-3">
              <div v-for="n in 3" :key="n" class="skeleton h-16" />
            </div>
            <p v-else-if="!sortedInstances.length" class="rounded-lg bg-base-850 px-3 py-3 text-sm text-base-400">{{ t('craftinghosting.wizard.source.noInstances') }}</p>
            <div v-else class="grid max-h-80 gap-2 overflow-y-auto pr-1 sm:grid-cols-2 xl:grid-cols-3" role="radiogroup" :aria-label="t('craftinghosting.wizard.source.pickInstance')">
              <label
                v-for="i in sortedInstances"
                :key="i.id"
                class="flex cursor-pointer items-center gap-3 rounded-lg border p-2.5 transition-colors has-[:focus-visible]:ring-2 has-[:focus-visible]:ring-redstone-500"
                :class="w.instanceId === i.id ? 'border-redstone-500/70 bg-base-850' : 'border-base-800 hover:border-base-700 hover:bg-base-850'"
                :data-testid="`hosting-instance-${i.id}`"
              >
                <input type="radio" name="ch-instance" class="sr-only" :value="i.id" :checked="w.instanceId === i.id" @change="chooseInstance(i)" />
                <InstanceIcon :instance="i" :size="40" />
                <span class="min-w-0 flex-1">
                  <span class="block truncate text-sm font-semibold text-base-50">{{ i.name }}</span>
                  <span class="mt-0.5 flex items-center gap-1.5 text-xs text-base-400">
                    <span class="badge bg-base-800 text-base-200">
                      <span class="size-1.5 rounded-full" :style="{ background: loaderColors[i.loader.kind] }" />{{ loaderLabels[i.loader.kind] }}
                    </span>
                    <span class="font-mono text-base-200">{{ i.gameVersion }}</span>
                  </span>
                </span>
                <svg v-if="w.instanceId === i.id" viewBox="0 0 16 16" class="size-4 shrink-0 text-redstone-300" fill="none" stroke="currentColor" stroke-width="2.2" aria-hidden="true"><path d="m3.5 8.5 3 3 6-7" /></svg>
              </label>
            </div>

            <!-- Was daraus wird -->
            <div v-if="plan && instance" class="mt-4 rounded-xl border border-base-800 bg-base-950/60 p-4" data-testid="hosting-plan">
              <div class="flex items-center gap-3">
                <CraftinghostingTypeIcon :type="plan.serverType" class="size-8 shrink-0 text-redstone-300" />
                <p class="text-sm text-base-200">
                  <i18n-t keypath="craftinghosting.wizard.source.becomes" scope="global">
                    <template #server>
                      <strong class="text-base-50">{{ typeName(w.serverType) }} {{ versionLabel(w.version) }}</strong>
                    </template>
                  </i18n-t>
                </p>
              </div>
              <p v-if="plan.note === 'vanilla'" class="mt-2 text-xs text-base-400">{{ t('craftinghosting.wizard.source.vanillaNote') }}</p>
              <p v-else-if="plan.note === 'loaderUnsupported'" class="mt-2 rounded-lg bg-lamp-900/40 px-3 py-2 text-xs text-lamp-300" data-testid="hosting-plan-loader">
                {{ t('craftinghosting.wizard.source.loaderNote', { loader: loaderLabels[instance.loader.kind] }) }}
              </p>
              <div v-if="!plan.versionExact" class="mt-3" data-testid="hosting-plan-version">
                <p class="mb-2 text-xs text-lamp-300">{{ t('craftinghosting.wizard.source.versionMissing', { version: instance.gameVersion, type: typeName(w.serverType) }) }}</p>
                <select v-model="w.version" class="field max-w-56 font-mono" :aria-label="t('craftinghosting.create.version')">
                  <option v-for="v in versions" :key="v" :value="v">{{ versionLabel(v) }}</option>
                </select>
              </div>
              <p v-else-if="plan.modsTransferable" class="mt-2 text-xs text-base-400">{{ t('craftinghosting.wizard.source.modsLater') }}</p>
            </div>
          </template>

          <!-- Servertypen -->
          <template v-else-if="w.source === 'new'">
            <h3 class="mt-6 mb-2 text-xs font-semibold tracking-wide text-base-400 uppercase">{{ t('craftinghosting.wizard.source.pickType') }}</h3>
            <div class="grid gap-2 sm:grid-cols-2 xl:grid-cols-3" role="radiogroup" :aria-label="t('craftinghosting.wizard.source.pickType')">
              <label
                v-for="ty in types"
                :key="ty.id"
                class="flex cursor-pointer items-start gap-3 rounded-lg border p-3 transition-colors has-[:focus-visible]:ring-2 has-[:focus-visible]:ring-redstone-500"
                :class="w.serverType === ty.id ? 'border-redstone-500/70 bg-base-850' : 'border-base-800 hover:border-base-700 hover:bg-base-850'"
                :data-testid="`hosting-type-${ty.id}`"
              >
                <input type="radio" name="ch-type" class="sr-only" :value="ty.id" :checked="w.serverType === ty.id" @change="chooseType(ty.id)" />
                <span class="flex size-9 shrink-0 items-center justify-center rounded-lg" :class="w.serverType === ty.id ? 'bg-redstone-500/15 text-redstone-300' : 'bg-base-800 text-base-200'">
                  <CraftinghostingTypeIcon :type="ty.id" class="size-5" />
                </span>
                <span class="min-w-0">
                  <span class="block text-sm font-semibold text-base-50">{{ ty.name }}</span>
                  <span class="mt-0.5 block text-xs leading-snug text-base-400">{{ typeHint(ty.id) }}</span>
                  <span class="mt-1 block font-mono text-[11px] text-base-400">{{ ty.versions.filter((v) => v !== 'latest').slice(-1)[0] }} – {{ ty.versions.filter((v) => v !== 'latest')[0] ?? '' }}</span>
                </span>
              </label>
            </div>
            <div v-if="type" class="mt-4 max-w-56">
              <label for="ch-version" class="mb-1 block text-xs text-base-400">{{ t('craftinghosting.create.version') }}</label>
              <select id="ch-version" v-model="w.version" class="field font-mono">
                <option v-for="v in versions" :key="v" :value="v">{{ versionLabel(v) }}</option>
              </select>
            </div>
          </template>
        </section>

        <!-- 2: Name & Adresse -->
        <section v-else-if="step === 2" key="2" aria-labelledby="ch-step-title">
          <h2 id="ch-step-title" ref="heading" tabindex="-1" class="display text-2xl text-base-50 outline-none">{{ t('craftinghosting.wizard.name.title') }}</h2>
          <div class="mt-4 space-y-5">
            <div>
              <label for="ch-name" class="mb-1 block text-xs text-base-400">{{ t('craftinghosting.create.name') }}</label>
              <input
                id="ch-name"
                v-model="w.name"
                class="field text-base"
                :maxlength="NAME_MAX"
                :placeholder="t('craftinghosting.create.namePlaceholder')"
                autocomplete="off"
                spellcheck="false"
                data-testid="hosting-name"
                @input="nameTouched = true"
              />
              <p class="mt-1 text-xs" :class="w.name && !validServerName(w.name) ? 'text-redstone-300' : 'text-base-400'">{{ t('craftinghosting.create.nameHint') }}</p>
            </div>
            <div v-if="w.subdomainEnabled">
              <label for="ch-sub" class="mb-1 block text-xs text-base-400">{{ t('craftinghosting.address.label') }}</label>
              <CraftinghostingSubdomainField
                input-id="ch-sub"
                :model-value="w.subdomain"
                @update:model-value="onSubdomain"
                @status="(s) => (w.subdomainStatus = s)"
                @unavailable="w.subdomainEnabled = false"
              />
            </div>
          </div>
        </section>

        <!-- 3: Zugang & Start -->
        <section v-else key="3" aria-labelledby="ch-step-title">
          <h2 id="ch-step-title" ref="heading" tabindex="-1" class="display text-2xl text-base-50 outline-none">{{ t('craftinghosting.wizard.access.title') }}</h2>
          <div class="mt-4 grid gap-3 sm:grid-cols-2" role="radiogroup" :aria-label="t('craftinghosting.access.label')">
            <label
              v-for="mode in ACCESS_MODES"
              :key="mode"
              class="flex cursor-pointer items-start gap-3 rounded-xl border p-4 transition-colors has-[:focus-visible]:ring-2 has-[:focus-visible]:ring-redstone-500"
              :class="w.access === mode ? 'border-redstone-500/70 bg-base-850' : 'border-base-800 hover:border-base-700 hover:bg-base-850'"
              :data-testid="`hosting-access-${mode}`"
            >
              <input v-model="w.access" type="radio" name="ch-access" class="sr-only" :value="mode" />
              <span class="flex size-10 shrink-0 items-center justify-center rounded-lg" :class="w.access === mode ? 'bg-redstone-500/15 text-redstone-300' : 'bg-base-800 text-base-200'">
                <svg v-if="mode === 'friends'" viewBox="0 0 24 24" class="size-5" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" aria-hidden="true">
                  <circle cx="9" cy="8" r="3.2" /><path d="M3 19c.6-3.2 3-5 6-5s5.4 1.8 6 5" /><circle cx="17" cy="9" r="2.6" /><path d="M16.5 14.2c2.4.2 4 1.8 4.5 4.3" />
                </svg>
                <svg v-else viewBox="0 0 24 24" class="size-5" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" aria-hidden="true">
                  <circle cx="12" cy="12" r="8.5" /><path d="M3.5 12h17M12 3.5c2.4 2.4 3.5 5.2 3.5 8.5s-1.1 6.1-3.5 8.5c-2.4-2.4-3.5-5.2-3.5-8.5s1.1-6.1 3.5-8.5" />
                </svg>
              </span>
              <span>
                <span class="block font-semibold text-base-50">{{ t(`craftinghosting.access.${mode}`) }}</span>
                <span class="mt-0.5 block text-xs text-base-400">{{ t(`craftinghosting.access.${mode}Hint`) }}</span>
              </span>
            </label>
          </div>
          <div class="mt-4 rounded-xl border border-base-800 bg-base-850 p-4" data-testid="hosting-wizard-eula">
            <p class="text-sm text-base-200">{{ t('craftinghosting.eula.text') }}</p>
            <button type="button" class="mt-1 text-sm text-redstone-300 underline underline-offset-2" @click="openEula">{{ t('craftinghosting.eula.link') }}</button>
            <label class="mt-3 flex cursor-pointer items-center gap-2 text-sm text-base-50">
              <input v-model="w.eula" type="checkbox" class="accent-redstone-500" data-testid="hosting-wizard-eula-check" />
              {{ t('craftinghosting.eula.accept') }}
            </label>
          </div>
          <CraftinghostingPlanTiles v-if="info" class="mt-5" :info="info" />
          <p v-if="info?.full" class="mt-4 rounded-lg bg-lamp-900/40 px-3 py-2 text-sm text-lamp-300">{{ t('craftinghosting.create.full') }}</p>
        </section>
      </Transition>

      <!-- Navigation -->
      <div class="mt-6 flex items-center gap-2 border-t border-base-800 pt-4">
        <button v-if="step > 1" type="button" class="btn btn-ghost" data-testid="hosting-wizard-back" @click="go((step - 1) as WizardStep)">
          {{ t('craftinghosting.wizard.back') }}
        </button>
        <span class="flex-1" />
        <span class="text-xs text-base-400 tabular-nums mobile:hidden">{{ t('craftinghosting.wizard.stepOf', { n: step, total: STEPS.length }) }}</span>
        <button v-if="step < 3" type="button" class="btn btn-primary" :disabled="!canNext" data-testid="hosting-wizard-next" @click="go((step + 1) as WizardStep)">
          {{ t('craftinghosting.wizard.next') }}
        </button>
        <span v-if="step === 3 && createOff" class="text-xs text-lamp-300" data-testid="hosting-create-off">
          {{ info?.maintenance.active ? t('craftinghosting.team.maintenance') : t('craftinghosting.team.createOff') }}
        </span>
        <button v-if="step === 3" type="button" class="btn btn-primary px-5" :disabled="!canCreate" data-testid="hosting-create-submit" @click="create">
          {{ store.busy === 'create' ? t('craftinghosting.create.creating') : t('craftinghosting.create.submit') }}
        </button>
      </div>
    </div>

    <!-- Live-Vorschau -->
    <aside class="space-y-4">
      <div class="ch-preview card relative overflow-hidden p-4" data-testid="hosting-preview">
        <span class="ch-preview-glow" aria-hidden="true" />
        <p class="relative text-[11px] font-semibold tracking-wide text-base-400 uppercase">{{ t('craftinghosting.wizard.preview') }}</p>
        <div class="relative mt-3 flex items-center gap-3">
          <InstanceIcon v-if="w.source === 'instance' && instance" :instance="instance" :size="44" />
          <span v-else class="flex size-11 shrink-0 items-center justify-center rounded-lg bg-base-800 text-redstone-300">
            <CraftinghostingTypeIcon :type="w.serverType" class="size-6" />
          </span>
          <div class="min-w-0">
            <p class="display truncate text-lg text-base-50">{{ w.name.trim() || t('craftinghosting.create.namePlaceholder') }}</p>
            <p class="truncate text-xs text-base-400">{{ typeName(w.serverType) }} · {{ versionLabel(w.version) }}</p>
          </div>
        </div>
        <p v-if="previewAddress" class="relative mt-3 truncate rounded-md bg-base-950/70 px-2.5 py-1.5 font-mono text-xs text-base-200 ring-1 ring-base-800">{{ previewAddress }}</p>
        <p class="relative mt-2 text-xs text-base-400">{{ t(`craftinghosting.access.${w.access}`) }}</p>
      </div>
      <CraftinghostingPlanTiles v-if="info && step < 3" :info="info" compact />
    </aside>
  </div>
</template>

<style scoped>
.ch-next-enter-active,
.ch-next-leave-active,
.ch-prev-enter-active,
.ch-prev-leave-active {
  transition:
    opacity 0.16s ease,
    transform 0.16s ease;
}
.ch-next-enter-from,
.ch-prev-leave-to {
  opacity: 0;
  transform: translateX(14px);
}
.ch-next-leave-to,
.ch-prev-enter-from {
  opacity: 0;
  transform: translateX(-14px);
}
.ch-step-on {
  box-shadow: 0 0 14px -2px color-mix(in srgb, var(--color-redstone-500) 70%, transparent);
}
.ch-choice-on {
  box-shadow:
    inset 0 1px 0 rgb(255 255 255 / 0.04),
    0 0 24px -12px var(--color-redstone-500);
}
.ch-preview-glow {
  position: absolute;
  inset: 0;
  pointer-events: none;
  background: radial-gradient(110% 80% at 100% 0%, color-mix(in srgb, var(--color-redstone-500) 16%, transparent), transparent 60%);
}
</style>
