<script setup lang="ts">
import type { Settings } from '~/types'

const store = useSettingsStore()
const onboarding = useOnboardingStore()

const form = ref<Settings | null>(null)
const dataDir = ref<string | null>(null)
const status = ref<{ ok: boolean; text: string } | null>(null)
const saving = ref(false)

onMounted(async () => {
  try {
    form.value = structuredClone(toRaw(await store.load()))
    dataDir.value = (await backend.appInfo()).dataDir
  } catch (e) {
    status.value = { ok: false, text: errorMessage(e) }
  }
})

const dirty = computed(() => JSON.stringify(form.value) !== JSON.stringify(store.current))

// Leeres Feld = automatische Java-Auswahl.
const javaPath = computed({
  get: () => form.value?.javaPath ?? '',
  set: (v: string) => {
    if (form.value) form.value.javaPath = v.trim() || null
  },
})

function openDataDir() {
  backend.openDataDir().catch(() => {})
}

async function save() {
  if (!form.value) return
  status.value = null
  const parsed = settingsSchema.safeParse(form.value)
  if (!parsed.success) {
    status.value = { ok: false, text: firstIssue(parsed.error) }
    return
  }
  saving.value = true
  try {
    form.value = structuredClone(toRaw(await store.save(parsed.data)))
    status.value = { ok: true, text: 'Gespeichert' }
  } catch (e) {
    status.value = { ok: false, text: errorMessage(e) }
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <div class="mx-auto max-w-2xl p-6">
    <PageHeader title="Einstellungen" subtitle="Standardwerte für alle Instanzen.">
      <span v-if="status" class="text-sm" :class="status.ok ? 'text-ok' : 'text-redstone-300'" role="status">
        {{ status.text }}
      </span>
      <button class="btn btn-primary" :disabled="!form || !dirty || saving" @click="save">
        {{ saving ? 'Speichere …' : 'Speichern' }}
      </button>
    </PageHeader>

    <form v-if="form" class="space-y-5" @submit.prevent="save">
      <section class="card p-5">
        <h2 class="mb-4 font-medium">Java &amp; Arbeitsspeicher</h2>

        <div class="mb-1.5 flex items-baseline justify-between">
          <label class="label mb-0" for="s-mem">Maximaler Arbeitsspeicher</label>
          <span class="font-mono text-sm text-base-50">{{ formatMemory(form.maxMemoryMb) }}</span>
        </div>
        <input id="s-mem" v-model.number="form.maxMemoryMb" type="range" min="1024" max="32768" step="512" class="w-full accent-redstone-500" />

        <div class="mt-4 grid grid-cols-2 gap-4">
          <div>
            <label class="label" for="s-min">Minimum (MB)</label>
            <input id="s-min" v-model.number="form.minMemoryMb" type="number" min="128" step="128" class="field font-mono" />
          </div>
          <div>
            <label class="label" for="s-dl">Parallele Downloads</label>
            <input id="s-dl" v-model.number="form.concurrentDownloads" type="number" min="1" max="64" class="field font-mono" />
          </div>
        </div>

        <div class="mt-4">
          <label class="label" for="s-java">Java-Pfad</label>
          <input id="s-java" v-model="javaPath" class="field font-mono" maxlength="1024" placeholder="Automatisch (empfohlen)" spellcheck="false" />
        </div>

        <div class="mt-4">
          <label class="label" for="s-jvm">Zusätzliche JVM-Argumente</label>
          <input id="s-jvm" v-model="form.jvmArgs" class="field font-mono" maxlength="4096" placeholder="-XX:+UseG1GC" spellcheck="false" />
        </div>
      </section>

      <section class="card p-5">
        <h2 class="mb-4 font-medium">Spiel</h2>
        <div class="grid grid-cols-2 gap-4">
          <div>
            <label class="label" for="s-w">Fensterbreite</label>
            <input id="s-w" v-model.number="form.resolution.width" type="number" min="320" class="field font-mono" />
          </div>
          <div>
            <label class="label" for="s-h">Fensterhöhe</label>
            <input id="s-h" v-model.number="form.resolution.height" type="number" min="240" class="field font-mono" />
          </div>
        </div>
        <label class="mt-4 flex items-center gap-2.5 text-sm text-base-200">
          <input v-model="form.closeOnLaunch" type="checkbox" class="accent-redstone-500" />
          Launcher beim Spielstart minimieren
        </label>
        <label class="mt-2.5 flex items-center gap-2.5 text-sm text-base-200">
          <input v-model="form.showSnapshots" type="checkbox" class="accent-redstone-500" />
          Snapshots und alte Versionen standardmäßig anzeigen
        </label>
      </section>

      <section v-if="dataDir" class="card flex items-center justify-between gap-4 p-5">
        <div class="min-w-0">
          <h2 class="font-medium">Datenverzeichnis</h2>
          <p class="mt-0.5 truncate font-mono text-xs text-base-400" :title="dataDir">{{ dataDir }}</p>
        </div>
        <button type="button" class="btn btn-ghost shrink-0" @click="openDataDir">Öffnen</button>
      </section>
    </form>

    <div class="mt-6 text-center">
      <button type="button" class="text-xs text-base-400 underline-offset-2 hover:text-base-50 hover:underline" @click="onboarding.restart()">
        Einrichtung erneut starten
      </button>
    </div>
  </div>
</template>
