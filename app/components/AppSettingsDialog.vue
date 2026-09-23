<script setup lang="ts">
import type { Accent, Settings, StorageStats, Theme } from '~/types'
import type { ShellSection } from '~/components/SettingsShell.vue'

// Globale Einstellungen im Stil der Modrinth App. Alles speichert
// automatisch; Darstellung wirkt sofort als Vorschau.
const store = useSettingsStore()
const accounts = useAccountsStore()
const onboarding = useOnboardingStore()
const toasts = useToasts()

const sections: ShellSection[] = appSettingsSections
const active = computed({
  get: () => (sections.some((s) => s.key === store.dialog) ? store.dialog! : 'appearance'),
  set: (v: string) => (store.dialog = v),
})

const form = ref<Settings | null>(null)
const info = ref<{ version: string; os: string; dataDir: string } | null>(null)
const status = ref<{ ok: boolean; text: string } | null>(null)
let lastSaved = ''
let timer: ReturnType<typeof setTimeout> | undefined

onMounted(async () => {
  try {
    form.value = structuredClone(toRaw(store.current ?? (await store.load())))
    lastSaved = JSON.stringify(form.value)
    info.value = await backend.appInfo()
  } catch (e) {
    status.value = { ok: false, text: errorMessage(e) }
  }
  if (!accounts.loaded) accounts.load().catch(() => {})
})

// Darstellung sofort anwenden, gespeichert wird gleich danach.
watch(() => form.value?.ui, (ui) => ui && applyAppearance(ui), { deep: true })

watch(
  () => JSON.stringify(form.value),
  (json) => {
    clearTimeout(timer)
    if (!form.value || json === lastSaved) return
    timer = setTimeout(save, 500)
  },
)

async function save() {
  timer = undefined
  if (!form.value) return
  const parsed = settingsSchema.safeParse(form.value)
  if (!parsed.success) {
    status.value = { ok: false, text: firstIssue(parsed.error) }
    return
  }
  const json = JSON.stringify(form.value)
  status.value = { ok: true, text: 'Speichere …' }
  try {
    await store.save(parsed.data as Settings)
    lastSaved = json
    status.value = { ok: true, text: 'Gespeichert' }
  } catch (e) {
    status.value = { ok: false, text: errorMessage(e) }
  }
}

async function close() {
  if (timer) {
    clearTimeout(timer)
    await save()
  }
  // Nicht gespeicherte Vorschau zurücknehmen.
  applyAppearance(store.current?.ui)
  store.dialog = null
}
onBeforeUnmount(() => clearTimeout(timer))

// --- Aussehen -----------------------------------------------------------------
const themes: { key: Theme; label: string; bg: string; panel: string; line: string }[] = [
  { key: 'dark', label: 'Dunkel', bg: '#111116', panel: '#1d1d26', line: '#333343' },
  { key: 'oled', label: 'OLED', bg: '#000000', panel: '#0c0c10', line: '#24242e' },
  { key: 'light', label: 'Hell', bg: '#eeeef3', panel: '#ffffff', line: '#d4d4df' },
  { key: 'system', label: 'System', bg: 'linear-gradient(135deg,#111116 50%,#eeeef3 50%)', panel: '#1d1d26', line: '#333343' },
]
const accents: { key: Accent; label: string; color: string }[] = [
  { key: 'redstone', label: 'Redstone', color: '#e0281e' },
  { key: 'lamp', label: 'Lampe', color: '#e0900c' },
  { key: 'emerald', label: 'Smaragd', color: '#17a34a' },
  { key: 'lapis', label: 'Lapis', color: '#3563e9' },
  { key: 'amethyst', label: 'Amethyst', color: '#9b4ddf' },
]

// --- Sprache ------------------------------------------------------------------
const languageQuery = ref('')
const languages = [
  { code: 'de', label: 'Deutsch', native: 'Deutsch', ready: true },
  { code: 'en', label: 'Englisch', native: 'English', ready: false },
]
const visibleLanguages = computed(() => {
  const q = languageQuery.value.trim().toLowerCase()
  return languages.filter((l) => !q || `${l.label} ${l.native}`.toLowerCase().includes(q))
})

// --- Profil -------------------------------------------------------------------
async function activateAccount(id: string) {
  try {
    await accounts.setActive(id)
  } catch (e) {
    toasts.error(e)
  }
}

// --- Java ---------------------------------------------------------------------
const javaSlots = [
  { major: 8, key: 'java8', hint: 'Minecraft bis 1.16' },
  { major: 17, key: 'java17', hint: 'Minecraft 1.17 bis 1.20.4' },
  { major: 21, key: 'java21', hint: 'Minecraft 1.20.5 bis 1.21.x' },
  { major: 25, key: 'java25', hint: 'Minecraft 26.x' },
] as const
function javaModel(key: (typeof javaSlots)[number]['key']) {
  return computed({
    get: () => form.value?.java[key] ?? '',
    set: (v: string) => {
      if (form.value) form.value.java[key] = v.trim() || null
    },
  })
}
const javaModels = Object.fromEntries(javaSlots.map((s) => [s.key, javaModel(s.key)])) as Record<(typeof javaSlots)[number]['key'], WritableComputedRef<string>>
const globalJava = computed({
  get: () => form.value?.javaPath ?? '',
  set: (v: string) => {
    if (form.value) form.value.javaPath = v.trim() || null
  },
})
const installing = ref<{ major: number; percent: number } | null>(null)
async function installJava(slot: (typeof javaSlots)[number]) {
  installing.value = { major: slot.major, percent: 0 }
  try {
    const path = await backend.installJava(slot.major, (p) => installing.value && (installing.value.percent = p))
    javaModels[slot.key].value = path
    toasts.ok(`Java ${slot.major} installiert`)
  } catch (e) {
    toasts.error(e)
  } finally {
    installing.value = null
  }
}

// --- Speicher -----------------------------------------------------------------
const stats = ref<StorageStats | null>(null)
const statsBusy = ref(false)
const verifying = ref(false)
const confirmClean = ref(false)
const cleaning = ref(false)
async function loadStats() {
  statsBusy.value = true
  try {
    stats.value = await backend.storageStats()
  } catch (e) {
    toasts.error(e)
  } finally {
    statsBusy.value = false
  }
}
watch(active, (a) => a === 'storage' && !stats.value && loadStats(), { immediate: true })
const bars = computed(() => {
  const s = stats.value
  if (!s) return []
  const shared = s.libraries + s.assets + s.versions - s.unused + s.java + s.shared
  const parts = [
    { label: 'Instanzen', bytes: s.instances, color: 'bg-redstone-500' },
    { label: 'Geteilte Spieldateien', bytes: shared, color: 'bg-lamp-400' },
    { label: 'Nicht mehr benötigt', bytes: s.unused, color: 'bg-base-600' },
  ]
  const total = parts.reduce((sum, p) => sum + p.bytes, 0) || 1
  return parts.map((p) => ({ ...p, share: (p.bytes / total) * 100 }))
})
async function verify() {
  verifying.value = true
  try {
    const r = await backend.verifyStorage()
    toasts.ok(r.removed ? `${r.checked} Dateien geprüft, ${r.removed} beschädigte entfernt – sie werden beim nächsten Start neu geladen.` : `${r.checked} Dateien geprüft – alles in Ordnung.`)
  } catch (e) {
    toasts.error(e)
  } finally {
    verifying.value = false
  }
}
async function clean() {
  confirmClean.value = false
  cleaning.value = true
  try {
    const freed = await backend.cleanUnusedStorage()
    toasts.ok(`${formatBytes(freed)} freigegeben`)
    await loadStats()
  } catch (e) {
    toasts.error(e)
  } finally {
    cleaning.value = false
  }
}
function openDataDir() {
  backend.openDataDir().catch(() => {})
}

// --- Netzwerk -----------------------------------------------------------------
const firewall = ref<{ total: number; missing: number } | null>(null)
const firewallBusy = ref(false)
watch(active, (a) => a === 'network' && !firewall.value && backend.firewallStatus().then((s) => (firewall.value = s)).catch(() => {}), {
  immediate: true,
})
async function allowFirewall() {
  firewallBusy.value = true
  try {
    const n = await backend.firewallAllowAll()
    toasts.ok(n ? `Netzwerkzugriff für ${n} Java-Programme erlaubt` : 'Noch keine Java-Version installiert')
    firewall.value = await backend.firewallStatus()
  } catch (e) {
    if (!isCancelled(e)) toasts.error(e)
  } finally {
    firewallBusy.value = false
  }
}
</script>

<template>
  <SettingsShell v-model="active" title="Einstellungen" :sections="sections" :status="status" @close="close">
    <template #nav-footer>
      <p v-if="info">TRS Launcher v{{ info.version }}</p>
      <p v-if="info">{{ info.os }}</p>
    </template>

    <div v-if="!form" class="space-y-3">
      <div v-for="i in 4" :key="i" class="skeleton h-14" />
    </div>

    <!-- Aussehen --------------------------------------------------------------- -->
    <div v-else-if="active === 'appearance'">
      <h3 class="section-heading">Farbschema</h3>
      <div class="grid grid-cols-4 gap-3 border-b border-base-800 pb-5">
        <button
          v-for="t in themes"
          :key="t.key"
          class="overflow-hidden rounded-xl border-2 text-left transition-colors"
          :class="form.ui.theme === t.key ? 'border-redstone-500' : 'border-base-800 hover:border-base-700'"
          :aria-pressed="form.ui.theme === t.key"
          @click="form.ui.theme = t.key"
        >
          <div class="relative h-20 p-2" :style="{ background: t.bg }">
            <div class="absolute inset-y-2 left-2 w-5 rounded" :style="{ background: t.panel, border: `1px solid ${t.line}` }" />
            <div class="absolute top-2 right-2 left-9 h-3 rounded" :style="{ background: t.panel, border: `1px solid ${t.line}` }" />
            <div class="absolute right-2 bottom-2 left-9 h-8 rounded" :style="{ background: t.panel, border: `1px solid ${t.line}` }" />
            <div class="absolute bottom-4 left-11 h-2 w-8 rounded-full bg-redstone-500" />
          </div>
          <div class="flex items-center gap-2 bg-base-850 px-3 py-2 text-sm font-medium">
            <span class="grid size-4 place-items-center rounded-full border-2" :class="form.ui.theme === t.key ? 'border-redstone-500' : 'border-base-600'">
              <span v-if="form.ui.theme === t.key" class="size-2 rounded-full bg-redstone-500" />
            </span>
            {{ t.label }}
          </div>
        </button>
      </div>

      <SettingRow title="Akzentfarbe" description="Farbe für Knöpfe, aktive Punkte und die Redstone-Leitung.">
        <div class="flex gap-2">
          <button
            v-for="a in accents"
            :key="a.key"
            class="size-7 rounded-full ring-offset-2 ring-offset-base-900 transition-transform hover:scale-110"
            :class="form.ui.accent === a.key ? 'ring-2 ring-base-50' : ''"
            :style="{ background: a.color }"
            :title="a.label"
            :aria-label="`Akzentfarbe ${a.label}`"
            :aria-pressed="form.ui.accent === a.key"
            @click="form.ui.accent = a.key"
          />
        </div>
      </SettingRow>
      <SettingRow title="Erweitertes Rendering" description="Unschärfe-Effekte hinter Dialogen und Leisten. Aus spart auf schwachen Rechnern etwas Leistung.">
        <ToggleSwitch v-model="form.ui.advancedRendering" label="Erweitertes Rendering" />
      </SettingRow>
      <SettingRow title="Animierter Hintergrund" description="Eine ruhige Redstone-Schaltung hinter allen Seiten. Aus zeigt nur das Deepslate-Muster.">
        <ToggleSwitch v-model="form.ui.animatedBackground" label="Animierter Hintergrund" />
      </SettingRow>
    </div>

    <!-- Funktionen ------------------------------------------------------------- -->
    <div v-else-if="active === 'features'">
      <h3 class="section-heading">Instanz-Seite</h3>
      <SettingRow title="Verlauf-Tab" description="Starts, Abstürze, installierte Mods und Versionswechsel je Instanz.">
        <ToggleSwitch v-model="form.ui.historyTab" label="Verlauf-Tab" />
      </SettingRow>
      <SettingRow title="Screenshots-Tab" description="Galerie der Screenshots einer Instanz.">
        <ToggleSwitch v-model="form.ui.screenshotsTab" label="Screenshots-Tab" />
      </SettingRow>
      <SettingRow title="Welten-Tab" description="Liste der Welten einer Instanz.">
        <ToggleSwitch v-model="form.ui.worldsTab" label="Welten-Tab" />
      </SettingRow>
      <h3 class="section-heading mt-6">Linke Leiste</h3>
      <SettingRow title="Schnell-Instanzen" description="Die zuletzt gespielten Instanzen mit Bild unter der Navigation.">
        <ToggleSwitch v-model="form.ui.sidebarRecent" label="Schnell-Instanzen" />
      </SettingRow>
      <SettingRow title="Account-Kachel" description="Aktiver Account mit Skin-Kopf unten in der Leiste.">
        <ToggleSwitch v-model="form.ui.sidebarAccount" label="Account-Kachel" />
      </SettingRow>
    </div>

    <!-- Verhalten -------------------------------------------------------------- -->
    <div v-else-if="active === 'behavior'">
      <h3 class="section-heading">Verhalten</h3>
      <SettingRow title="Launcher beim Spielstart minimieren" description="Das Fenster geht beim Start aus dem Weg; das Spiel läuft weiter, auch wenn der Launcher geschlossen wird.">
        <ToggleSwitch v-model="form.closeOnLaunch" label="Launcher beim Spielstart minimieren" />
      </SettingRow>
      <SettingRow title="Kompakte Bibliothek" description="Kleinere Kacheln – mehr Instanzen auf einen Blick.">
        <ToggleSwitch v-model="form.ui.compactLibrary" label="Kompakte Bibliothek" />
      </SettingRow>
      <SettingRow title="Spielzeit anzeigen" description="Gesamte Spielzeit auf Kacheln und Instanzseiten.">
        <ToggleSwitch v-model="form.ui.showPlayTime" label="Spielzeit anzeigen" />
      </SettingRow>
      <SettingRow title="Snapshots anzeigen" description="Snapshots und alte Versionen standardmäßig in der Versionsauswahl.">
        <ToggleSwitch v-model="form.showSnapshots" label="Snapshots anzeigen" />
      </SettingRow>
      <SettingRow title="Einrichtung erneut starten" description="Führt noch einmal durch Anmeldung und erste Instanz.">
        <button class="btn btn-ghost" @click="close(); onboarding.restart()">Starten</button>
      </SettingRow>
    </div>

    <!-- Sprache -------------------------------------------------------------- -->
    <div v-else-if="active === 'language'">
      <h3 class="section-heading">Sprache</h3>
      <input v-model="languageQuery" class="field mb-3" maxlength="40" placeholder="Sprache suchen …" aria-label="Sprache suchen" />
      <ul class="space-y-1.5">
        <li v-for="l in visibleLanguages" :key="l.code">
          <button
            class="flex w-full items-center gap-3 rounded-lg border px-3 py-2.5 text-left transition-colors disabled:cursor-not-allowed disabled:opacity-50"
            :class="form.ui.language === l.code ? 'border-redstone-500 bg-redstone-900/30' : 'border-base-800'"
            :disabled="!l.ready"
            @click="l.ready && (form.ui.language = 'de')"
          >
            <span class="grid size-4 place-items-center rounded-full border-2" :class="form.ui.language === l.code ? 'border-redstone-500' : 'border-base-600'">
              <span v-if="form.ui.language === l.code" class="size-2 rounded-full bg-redstone-500" />
            </span>
            <span class="flex-1 text-sm font-medium">{{ l.native }}</span>
            <span class="text-xs text-base-400">{{ l.ready ? l.label : 'bald' }}</span>
          </button>
        </li>
      </ul>
    </div>

    <!-- Profil ---------------------------------------------------------------- -->
    <div v-else-if="active === 'profile'">
      <h3 class="section-heading">Minecraft-Accounts</h3>
      <ul v-if="accounts.items.length" class="divide-y divide-base-800 rounded-xl border border-base-800">
        <li v-for="a in accounts.items" :key="a.id" class="flex items-center gap-3 px-4 py-3">
          <SkinHead :skin-url="a.skinUrl" :name="a.name" :size="36" />
          <div class="min-w-0 flex-1">
            <p class="truncate text-sm font-semibold">{{ a.name }}</p>
            <p class="text-xs text-base-400">Hinzugefügt {{ formatRelative(a.addedAt) }}</p>
          </div>
          <span v-if="a.active" class="badge bg-ok/10 text-ok">Aktiv</span>
          <button v-else class="btn btn-ghost py-1.5 text-xs" @click="activateAccount(a.id)">Verwenden</button>
        </li>
      </ul>
      <p v-else class="text-sm text-base-400">Noch kein Account angemeldet.</p>
      <NuxtLink to="/accounts" class="btn btn-primary mt-4" @click="close">Accounts verwalten</NuxtLink>
    </div>

    <!-- Datenschutz -------------------------------------------------------------- -->
    <div v-else-if="active === 'privacy'">
      <h3 class="section-heading">Datenschutz</h3>
      <SettingRow title="Logs teilen erlauben" description="„Log teilen“ lädt den Spiel-Log auf mclo.gs hoch – Zugangsdaten und Benutzername werden vorher geschwärzt. Aus = der Knopf funktioniert nicht.">
        <ToggleSwitch v-model="form.allowLogUpload" label="Logs teilen erlauben" />
      </SettingRow>
      <SettingRow title="Telemetrie" description="Der TRS Launcher sammelt keine Nutzungs- oder Absturzdaten. Es gibt nichts einzuschalten.">
        <ToggleSwitch :model-value="false" label="Telemetrie" disabled />
      </SettingRow>
    </div>

    <!-- Standard-Einstellungen ------------------------------------------------------ -->
    <div v-else-if="active === 'defaults'">
      <h3 class="section-heading">Standard für alle Instanzen</h3>
      <p class="mb-2 text-xs text-base-400">Instanzen können einzelne Werte in ihren eigenen Einstellungen überschreiben.</p>
      <SettingRow title="Arbeitsspeicher" description="Wie viel RAM das Spiel höchstens nutzen darf." stacked>
        <div class="flex items-center gap-4">
          <input v-model.number="form.maxMemoryMb" type="range" min="1024" max="32768" step="512" class="flex-1 accent-redstone-500" aria-label="Maximaler Arbeitsspeicher" />
          <div class="flex items-center gap-1.5">
            <input v-model.number="form.maxMemoryMb" type="number" min="512" max="131072" step="256" class="field w-28 font-mono" aria-label="Maximaler Arbeitsspeicher in MB" />
            <span class="text-xs text-base-400">MB</span>
          </div>
        </div>
        <p class="mt-1 text-xs text-base-600">{{ formatMemory(form.maxMemoryMb) }}</p>
      </SettingRow>
      <SettingRow title="Minimaler Arbeitsspeicher" description="Startwert für Java (-Xms), in MB.">
        <input v-model.number="form.minMemoryMb" type="number" min="128" step="128" class="field w-28 font-mono" aria-label="Minimaler Arbeitsspeicher" />
      </SettingRow>
      <SettingRow title="JVM-Argumente" description="Zusätzliche Argumente für Java." stacked>
        <div class="flex gap-2">
          <input v-model="form.jvmArgs" class="field font-mono text-xs" maxlength="4096" placeholder="Keine" spellcheck="false" aria-label="JVM-Argumente" />
          <button class="btn btn-ghost shrink-0 py-1.5 text-xs" @click="form.jvmArgs = optimizedJvmArgs">Optimierte Argumente</button>
        </div>
      </SettingRow>
      <SettingRow title="Umgebungsvariablen" description="Gelten für das Spiel und die Start-Hooks." stacked>
        <EnvEditor v-model="form.env" />
      </SettingRow>
      <SettingRow title="Vollbild" description="Spiele im Vollbild starten.">
        <ToggleSwitch v-model="form.fullscreen" label="Vollbild" />
      </SettingRow>
      <SettingRow title="Fenstergröße" description="Breite × Höhe des Spielfensters.">
        <input v-model.number="form.resolution.width" type="number" min="320" class="field w-24 font-mono" aria-label="Fensterbreite" />
        <span class="text-base-600">×</span>
        <input v-model.number="form.resolution.height" type="number" min="240" class="field w-24 font-mono" aria-label="Fensterhöhe" />
      </SettingRow>
      <SettingRow title="Leistungsstarke Grafikkarte" description="Für Laptops mit zwei Grafikchips: Windows gibt dem Spiel die dedizierte GPU.">
        <ToggleSwitch v-model="form.preferDedicatedGpu" label="Leistungsstarke Grafikkarte" />
      </SettingRow>

      <h3 class="section-heading mt-6">Start-Hooks</h3>
      <SettingRow title="Vor dem Start" description="Läuft über cmd /C vor jedem Start; schlägt er fehl, startet das Spiel nicht." stacked>
        <input v-model="form.hooks.preLaunch" class="field font-mono text-xs" maxlength="1024" placeholder="Kein Befehl" spellcheck="false" aria-label="Befehl vor dem Start" />
      </SettingRow>
      <SettingRow title="Wrapper" description="Wird vor die Java-Kommandozeile gesetzt." stacked>
        <input v-model="form.hooks.wrapper" class="field font-mono text-xs" maxlength="1024" placeholder="Kein Wrapper" spellcheck="false" aria-label="Wrapper-Befehl" />
      </SettingRow>
      <SettingRow title="Nach dem Beenden" description="Läuft, nachdem das Spiel beendet wurde." stacked>
        <input v-model="form.hooks.postExit" class="field font-mono text-xs" maxlength="1024" placeholder="Kein Befehl" spellcheck="false" aria-label="Befehl nach dem Beenden" />
      </SettingRow>

      <h3 class="section-heading mt-6">Synchronisierung</h3>
      <p class="mb-1 text-xs leading-relaxed text-base-400">
        Eingeschaltetes wird vor dem Start aus einem gemeinsamen Speicher geholt und nach dem Beenden zurückgeschrieben – so hat jede
        Instanz dieselben Einstellungen. Die erste Instanz, die eine Datei hat, liefert den Ausgangsstand; überschriebene Dateien werden gesichert.
      </p>
      <SettingRow v-for="item in syncItemList" :key="item.key" :title="item.label" :description="item.description">
        <ToggleSwitch v-model="form.sync[item.key]" :label="`${item.label} synchronisieren`" />
      </SettingRow>
    </div>

    <!-- Java-Installationen ------------------------------------------------------------ -->
    <div v-else-if="active === 'java'">
      <h3 class="section-heading">Java-Installationen</h3>
      <p class="mb-3 text-xs text-base-400">Leer = der Launcher installiert die passende Java-Version von Mojang automatisch.</p>
      <div class="space-y-3">
        <section v-for="slot in javaSlots" :key="slot.major" class="rounded-xl border border-base-800 bg-base-850 p-4">
          <div class="mb-2 flex items-center justify-between">
            <div>
              <h4 class="text-sm font-semibold">Java {{ slot.major }}</h4>
              <p class="text-xs text-base-400">{{ slot.hint }}</p>
            </div>
            <button class="btn btn-ghost py-1.5 text-xs" :disabled="!!installing" @click="installJava(slot)">
              {{ installing?.major === slot.major ? `Installiere … ${Math.floor(installing.percent)} %` : 'Empfohlene installieren' }}
            </button>
          </div>
          <RedstoneWire v-if="installing?.major === slot.major" class="mb-2" :percent="installing.percent" :segments="40" />
          <JavaPathField v-model="javaModels[slot.key].value" :expected-major="slot.major" :placeholder="`Automatisch (Java ${slot.major})`" />
        </section>
        <section class="rounded-xl border border-base-800 p-4">
          <h4 class="text-sm font-semibold">Für alle Versionen</h4>
          <p class="mb-2 text-xs text-base-400">Nur nötig, wenn ein einziges Java für alles gelten soll – die Einträge oben haben Vorrang.</p>
          <JavaPathField v-model="globalJava" placeholder="Nicht gesetzt" />
        </section>
      </div>
    </div>

    <!-- Speicherverwaltung --------------------------------------------------------------- -->
    <div v-else-if="active === 'storage'">
      <h3 class="section-heading">Speicherverwaltung</h3>
      <div v-if="!stats" class="skeleton h-24" />
      <div v-else class="rounded-xl border border-base-800 bg-base-850 p-4">
        <div class="flex h-3 overflow-hidden rounded-full bg-base-800">
          <div v-for="b in bars" :key="b.label" :class="b.color" :style="{ width: `${b.share}%` }" />
        </div>
        <ul class="mt-3 grid grid-cols-3 gap-3 text-xs">
          <li v-for="b in bars" :key="b.label" class="flex items-start gap-2">
            <span class="mt-1 size-2.5 shrink-0 rounded-full" :class="b.color" />
            <span><span class="block text-base-400">{{ b.label }}</span><span class="font-mono text-sm text-base-50">{{ formatBytes(b.bytes) }}</span></span>
          </li>
        </ul>
      </div>
      <SettingRow title="Prüfen & reparieren" description="Prüft die geteilten Spieldateien per Prüfsumme. Beschädigte werden entfernt und beim nächsten Start neu geladen.">
        <button class="btn btn-ghost" :disabled="verifying" @click="verify">{{ verifying ? 'Prüfe …' : 'Prüfen & reparieren' }}</button>
      </SettingRow>
      <SettingRow title="Unbenutztes löschen" :description="stats ? `${stats.unusedVersions} Spielversionen braucht keine Instanz mehr (${formatBytes(stats.unused)}). Sie werden bei Bedarf neu geladen.` : 'Spielversionen, die keine Instanz mehr braucht.'" danger>
        <button class="btn btn-danger" :disabled="cleaning || !stats?.unusedVersions" @click="confirmClean = true">{{ cleaning ? 'Lösche …' : 'Unbenutztes löschen' }}</button>
      </SettingRow>
      <SettingRow title="App-Ordner" stacked>
        <template #description>
          <p class="mt-0.5 truncate font-mono text-xs text-base-400" :title="info?.dataDir">{{ info?.dataDir }}</p>
        </template>
        <button class="btn btn-ghost" @click="openDataDir">Ordner öffnen</button>
      </SettingRow>
    </div>

    <!-- Netzwerk --------------------------------------------------------------------- -->
    <div v-else-if="active === 'network'">
      <h3 class="section-heading">Netzwerk</h3>
      <SettingRow
        title="Java-Versionen automatisch freigeben"
        description="Windows fragt sonst bei jeder Java-Version einzeln, ob Minecraft ins Netzwerk darf (z. B. für LAN-Welten). Neue Java-Versionen werden vor dem Start mit einer Admin-Abfrage freigegeben."
      >
        <ToggleSwitch v-model="form.autoFirewall" label="Java-Versionen automatisch freigeben" />
      </SettingRow>
      <SettingRow title="Firewall-Freigabe" description="Trägt die Freigabe für alle Java-Versionen des Launchers auf einmal ein – eine einzige Admin-Abfrage.">
        <template #description>
          <p v-if="firewall" class="mt-1 text-xs" :class="firewall.missing ? 'text-warn' : 'text-ok'">
            {{ firewall.total === 0 ? 'Noch keine Java-Version installiert' : firewall.missing ? `${firewall.missing} von ${firewall.total} noch nicht freigegeben` : 'Alle freigegeben' }}
          </p>
        </template>
        <button class="btn btn-ghost" :disabled="firewallBusy || firewall?.total === 0" @click="allowFirewall">
          {{ firewallBusy ? 'Warte auf Windows …' : 'Jetzt für alle erlauben' }}
        </button>
      </SettingRow>
      <SettingRow title="Parallele Downloads" description="Wie viele Dateien gleichzeitig geladen werden. Bei langsamer Leitung weniger.">
        <input v-model.number="form.concurrentDownloads" type="number" min="1" max="64" class="field w-24 font-mono" aria-label="Parallele Downloads" />
      </SettingRow>
    </div>
  </SettingsShell>

  <BaseDialog v-if="confirmClean" title="Unbenutztes löschen?" @close="confirmClean = false">
    <p class="text-sm text-base-200">
      {{ stats?.unusedVersions }} Spielversionen ({{ formatBytes(stats?.unused ?? 0) }}) werden gelöscht. Instanzen, Welten und Mods bleiben unberührt.
    </p>
    <template #actions>
      <button class="btn btn-ghost" @click="confirmClean = false">Abbrechen</button>
      <button class="btn btn-danger" @click="clean">Löschen</button>
    </template>
  </BaseDialog>
</template>

<style scoped>
@reference "~/assets/css/main.css";

.section-heading {
  @apply mb-2 text-base font-semibold text-base-50;
}
</style>
