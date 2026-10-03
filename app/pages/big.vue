<script setup lang="ts">
import { isTauri } from '@tauri-apps/api/core'
import { getCurrentWindow } from '@tauri-apps/api/window'
import type { IconName } from '~/utils/icons'
import type { BigSection } from '~/utils/bigPicture'

// Big-Picture-Modus: Vollbild für Fernseher, Steam Deck und Controller. Große
// Kacheln, Bereiche mit LB/RB, A wählt, B zurück, Y Optionen, Start Menü.
// Maus, Touch und Tastatur (Pfeile, Enter, Esc) gehen genauso. Den Rahmen
// (ohne Titelleiste/Seitenleiste) setzt das Standard-Layout für diese Route.

const store = useBigPictureStore()
const instances = useInstancesStore()
const accounts = useAccountsStore()
const games = useGamesStore()
const trs = useTrsStore()

const root = ref<HTMLElement | null>(null)
const section = ref<BigSection>('home')
const direction = ref<1 | -1>(1)
const sections = computed(() => bigSections.filter((s) => s !== 'friends' || trs.enabled))
const sectionIcons: Record<BigSection, IconName> = {
  home: 'home',
  instances: 'library',
  servers: 'server',
  friends: 'friends',
  settings: 'defaults',
}

const { mode, focusFirst } = useBigPictureInput(root, {
  back() {
    if (store.layer) {
      store.closeLayer()
      return true
    }
    if (section.value !== 'home') {
      go('home')
      return true
    }
    return false
  },
  context(el) {
    const id = el?.closest<HTMLElement>('[data-instance]')?.dataset.instance
    if (id && instances.items.some((i) => i.id === id)) store.openLayer({ kind: 'instance', id })
  },
  menu: () => toggleMenu(),
  section(delta) {
    go(cycleSection(sections.value, section.value, delta))
  },
})

function go(next: BigSection) {
  if (next === section.value) return
  const list = sections.value
  direction.value = list.indexOf(next) >= list.indexOf(section.value) ? 1 : -1
  section.value = next
}

/** Nach dem Bereichswechsel (Übergang fertig) den Fokus in den neuen Bereich. */
function onEntered() {
  if (mode.value === 'nav') focusFirst()
}

// Friends verschwinden, wenn die TRS-Dienste ausgehen.
watch(sections, (list) => {
  if (!list.includes(section.value)) section.value = 'home'
})

// Neue Ebene: Fokus hinein.
watch(
  () => store.layer,
  (layer) => {
    if (layer) void nextTick(() => focusFirst())
  },
)

// Uhr oben rechts.
const now = ref(new Date())
let clock: ReturnType<typeof setInterval> | undefined
const time = computed(() => {
  void currentLocale.value
  return now.value.toLocaleTimeString(intlLocale(), { hour: '2-digit', minute: '2-digit' })
})

// Nach dem Spielen: Fenster zurückholen (minimiert durch „Launcher beim Start minimieren“).
watch(
  () => games.runningCount,
  (n, before) => {
    if (before > 0 && n === 0) void store.bringBack()
  },
)

onMounted(async () => {
  void store.activate()
  clock = setInterval(() => (now.value = new Date()), 15_000)
  if (!instances.loaded) await instances.load()
  if (!accounts.loaded) void accounts.load().catch(() => {})
  await nextTick()
  focusFirst()
})
onBeforeUnmount(() => {
  clearInterval(clock)
  void store.deactivate()
})

// --- Ebenen ---------------------------------------------------------------------------
const layerInstance = computed(() => {
  const layer = store.layer
  return layer?.kind === 'instance' ? (instances.items.find((i) => i.id === layer.id) ?? null) : null
})
const layerGame = computed(() => (layerInstance.value ? games.state(layerInstance.value.id) : null))

function playFromLayer() {
  const instance = layerInstance.value
  if (!instance) return
  store.closeLayer()
  if (games.state(instance.id).phase === 'idle') void games.launch(instance.id)
}

function stopFromLayer() {
  const instance = layerInstance.value
  if (instance) void games.stop(instance.id)
  store.closeLayer()
}

/** Instanz in der normalen Ansicht öffnen (verlässt Big Picture). */
function openDesktop() {
  const instance = layerInstance.value
  if (!instance) return
  store.layer = null
  void navigateTo(`/instances/${instance.id}`)
}

function pickTarget(id: string) {
  store.joinTarget = id
  store.closeLayer()
}

function menuSettings() {
  store.closeLayer()
  go('settings')
}

function quit() {
  if (isTauri()) void getCurrentWindow().close().catch(() => {})
}

// Hinweis „Optionen“ nur, wenn eine Instanz den Fokus hat.
const focusedInstance = ref<string | null>(null)
function onFocusIn(e: FocusEvent) {
  focusedInstance.value = e.target instanceof HTMLElement ? (e.target.closest<HTMLElement>('[data-instance]')?.dataset.instance ?? null) : null
}

function openFocusedOptions() {
  const id = focusedInstance.value
  if (id && !store.layer) store.openLayer({ kind: 'instance', id })
}

function toggleMenu() {
  if (store.layer?.kind === 'menu') store.closeLayer()
  else store.openLayer({ kind: 'menu' })
}

function backPress() {
  if (store.layer) store.closeLayer()
  else go('home')
}
</script>

<template>
  <div ref="root" class="bp-page relative flex h-full flex-col" :data-input="mode" @focusin="onFocusIn">
    <!-- Kopf: Logo, Bereiche (LB/RB), Uhr und Konto. -->
    <header class="relative z-10 flex shrink-0 items-center gap-6 px-12 pt-8 pb-4">
      <div class="flex items-center gap-3">
        <img src="/icon.png" alt="" class="size-10 [image-rendering:pixelated]" />
        <span class="display hidden text-2xl text-base-50 2xl:inline">{{ t('bigPicture.title') }}</span>
      </div>

      <nav class="mx-auto flex items-center gap-2" :aria-label="t('bigPicture.sectionsLabel')">
        <BigHint action="prevSection" @press="go(cycleSection(sections, section, -1))" />
        <button
          v-for="s in sections"
          :key="s"
          type="button"
          tabindex="-1"
          class="tab"
          :class="{ 'tab-on': section === s }"
          :aria-current="section === s ? 'page' : undefined"
          @click="go(s)"
        >
          <svg viewBox="0 0 24 24" class="hidden size-6 2xl:block" fill="none" stroke="currentColor" stroke-width="1.9" stroke-linecap="round" stroke-linejoin="round"><path :d="icons[sectionIcons[s]]" /></svg>
          {{ t(`bigPicture.sections.${s}`) }}
        </button>
        <BigHint action="nextSection" @press="go(cycleSection(sections, section, 1))" />
      </nav>

      <div class="flex items-center gap-5">
        <span v-if="games.runningCount" class="badge bg-lamp-400 px-3 py-1 text-base text-base-950">
          <span class="size-2 animate-lamp bg-base-950" />{{ t('common.status.running') }}
        </span>
        <span class="display text-2xl tabular-nums text-base-200">{{ time }}</span>
        <span v-if="accounts.active" class="flex items-center gap-3 text-lg text-base-200">
          <SkinHead :skin-url="accounts.active.skinUrl" :name="accounts.active.name" :size="36" />
          <span class="hidden max-w-48 truncate 2xl:inline">{{ accounts.active.name }}</span>
        </span>
      </div>
    </header>

    <!-- Inhalt des Bereichs; scrollt, Kopf und Hinweise bleiben stehen. -->
    <main class="relative min-h-0 flex-1 overflow-y-auto px-12 pt-6 pb-16" :inert="!!store.layer || undefined">
      <Transition :name="direction > 0 ? 'bp-next' : 'bp-prev'" mode="out-in" @after-enter="onEntered">
        <BigHome v-if="section === 'home'" key="home" />
        <BigInstances v-else-if="section === 'instances'" key="instances" />
        <BigServers v-else-if="section === 'servers'" key="servers" />
        <BigFriends v-else-if="section === 'friends'" key="friends" />
        <BigSettings v-else key="settings" />
      </Transition>
    </main>

    <!-- Knopf-Hinweise passend zum Controller. -->
    <footer class="relative z-10 flex shrink-0 items-center justify-end gap-6 border-t border-base-800/70 bg-base-950/80 px-12 py-4 backdrop-blur">
      <BigHint v-if="focusedInstance && !store.layer" action="context" :label="t('bigPicture.hints.options')" @press="openFocusedOptions" />
      <BigHint action="menu" :label="t('bigPicture.hints.menu')" @press="toggleMenu" />
      <BigHint action="back" :label="t('bigPicture.hints.back')" @press="backPress" />
      <BigHint action="confirm" :label="t('bigPicture.hints.select')" />
    </footer>

    <!-- Ebenen: Menü (Start), Instanz-Optionen (Y), Instanz zum Beitreten. -->
    <Transition name="bp-layer">
      <div v-if="store.layer" class="layer-backdrop" @mousedown.self="store.closeLayer()">
        <section
          v-if="store.layer.kind === 'menu'"
          data-bp-layer
          role="dialog"
          aria-modal="true"
          :aria-label="t('bigPicture.menu.title')"
          class="layer-panel"
        >
          <h2 class="bp-heading mb-6">{{ t('bigPicture.menu.title') }}</h2>
          <div class="flex flex-col gap-3">
            <button type="button" class="bp-btn justify-start" data-bp-autofocus @click="store.closeLayer()">{{ t('bigPicture.menu.resume') }}</button>
            <button type="button" class="bp-btn justify-start" @click="menuSettings">{{ t('bigPicture.sections.settings') }}</button>
            <button type="button" class="bp-btn justify-start" @click="store.close()">{{ t('bigPicture.exit') }}</button>
            <button type="button" class="bp-btn justify-start" @click="quit">{{ t('bigPicture.quit') }}</button>
          </div>
        </section>

        <section
          v-else-if="store.layer.kind === 'instance' && layerInstance"
          data-bp-layer
          role="dialog"
          aria-modal="true"
          :aria-label="layerInstance.name"
          class="layer-panel w-[44rem]"
        >
          <div class="mb-8 flex items-center gap-5">
            <InstanceIcon :instance="layerInstance" :size="80" class="shrink-0" />
            <div class="min-w-0">
              <h2 class="bp-heading truncate">{{ layerInstance.name }}</h2>
              <p class="mt-1 text-lg text-base-400"><span class="font-mono">{{ layerInstance.gameVersion }}</span> {{ loaderLabels[layerInstance.loader.kind] }}</p>
            </div>
          </div>
          <div class="flex flex-col gap-3">
            <button v-if="layerGame?.phase === 'idle'" type="button" class="bp-btn bp-btn-primary justify-start" data-bp-autofocus @click="playFromLayer">
              <svg viewBox="0 0 24 24" class="size-6" fill="currentColor"><path :d="icons.play" /></svg>
              {{ t('common.actions.play') }}
            </button>
            <button v-else-if="layerGame?.phase === 'preparing'" type="button" class="bp-btn justify-start" data-bp-autofocus @click="games.cancelLaunch(layerInstance.id); store.closeLayer()">
              {{ t('play.cancelLaunch') }}
            </button>
            <button v-else type="button" class="bp-btn justify-start" data-bp-autofocus @click="stopFromLayer">{{ t('play.stopGame') }}</button>
            <div class="my-3 rounded-2xl border-2 border-base-800 bg-base-900/70 p-5">
              <BigMemory :instance="layerInstance" />
            </div>
            <button type="button" class="bp-btn justify-start" @click="openDesktop">{{ t('bigPicture.instance.openDesktop') }}</button>
            <button type="button" class="bp-btn justify-start" @click="store.closeLayer()">{{ t('common.actions.close') }}</button>
          </div>
        </section>

        <section
          v-else-if="store.layer.kind === 'target'"
          data-bp-layer
          role="dialog"
          aria-modal="true"
          :aria-label="t('bigPicture.servers.chooseInstance')"
          class="layer-panel w-[44rem]"
        >
          <h2 class="bp-heading mb-6">{{ t('bigPicture.servers.chooseInstance') }}</h2>
          <div class="flex max-h-[60vh] flex-col gap-3 overflow-y-auto p-2">
            <button
              v-for="(i, index) in instances.items"
              :key="i.id"
              type="button"
              class="bp-btn justify-start"
              :class="{ 'bp-btn-primary': (store.joinTarget ?? instances.items[0]?.id) === i.id }"
              :data-bp-autofocus="(store.joinTarget ?? instances.items[0]?.id) === i.id || (index === 0 && !store.joinTarget) ? '' : undefined"
              @click="pickTarget(i.id)"
            >
              <InstanceIcon :instance="i" :size="40" class="shrink-0" />
              <span class="min-w-0 truncate">{{ i.name }}</span>
              <span class="ml-auto font-mono text-base opacity-70">{{ i.gameVersion }}</span>
            </button>
          </div>
        </section>
      </div>
    </Transition>
  </div>
</template>

<style scoped>
.tab {
  display: inline-flex;
  align-items: center;
  gap: 0.6rem;
  padding: 0.7rem 1.25rem;
  border-radius: 0.8rem;
  color: var(--color-base-400);
  font-size: 1.2rem;
  font-weight: 600;
  transition:
    color 0.15s ease,
    background-color 0.15s ease;
}
.tab:hover {
  color: var(--color-base-50);
}
.tab-on {
  color: var(--color-base-50);
  background: color-mix(in srgb, var(--color-redstone-500) 22%, var(--color-base-850));
  box-shadow: inset 0 -3px 0 var(--color-redstone-500);
}

/* Bereichswechsel: kurzes Gleiten in Richtung LB/RB. */
.bp-next-enter-active,
.bp-next-leave-active,
.bp-prev-enter-active,
.bp-prev-leave-active {
  transition:
    opacity 0.16s ease,
    transform 0.2s cubic-bezier(0.2, 0.8, 0.2, 1);
}
.bp-next-enter-from,
.bp-prev-leave-to {
  opacity: 0;
  transform: translateX(3rem);
}
.bp-next-leave-to,
.bp-prev-enter-from {
  opacity: 0;
  transform: translateX(-3rem);
}

.layer-backdrop {
  position: absolute;
  inset: 0;
  z-index: 30;
  display: grid;
  place-items: center;
  padding: 3rem;
  background: rgb(0 0 0 / 0.6);
  backdrop-filter: blur(6px);
}
.layer-panel {
  max-width: 100%;
  max-height: 100%;
  min-width: 28rem;
  overflow-y: auto;
  padding: 2.5rem;
  border: 2px solid var(--color-base-700);
  border-radius: 1.5rem;
  background: var(--color-base-900);
  box-shadow: 0 30px 80px rgb(0 0 0 / 0.6);
}
.bp-layer-enter-active,
.bp-layer-leave-active {
  transition: opacity 0.18s ease;
}
.bp-layer-enter-active .layer-panel,
.bp-layer-leave-active .layer-panel {
  transition: transform 0.22s cubic-bezier(0.2, 0.8, 0.2, 1);
}
.bp-layer-enter-from,
.bp-layer-leave-to {
  opacity: 0;
}
.bp-layer-enter-from .layer-panel {
  transform: scale(0.96) translateY(1rem);
}
</style>
