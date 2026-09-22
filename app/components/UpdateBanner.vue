<script setup lang="ts">
import { isTauri } from '@tauri-apps/api/core'
import { relaunch } from '@tauri-apps/plugin-process'
import { error as logError } from '@tauri-apps/plugin-log'
import { check, type Update } from '@tauri-apps/plugin-updater'

// Sucht beim Start nach einer neuen Launcher-Version (signierte Updates aus
// den GitHub-Releases) und bietet sie dezent über der Seite an.
const update = ref<Update | null>(null)
const percent = ref<number | null>(null)
const failed = ref(false)
const failReason = ref('')
const attempts = ref(0)

onMounted(async () => {
  if (!isTauri() || import.meta.dev) return
  try {
    update.value = await check()
  } catch {
    // Offline oder noch kein Release veröffentlicht – kein Grund für eine Meldung.
  }
})

async function install() {
  if (!update.value) return
  failed.value = false
  failReason.value = ''
  attempts.value++
  percent.value = 0
  let total = 0
  let done = 0
  try {
    await update.value.downloadAndInstall((event) => {
      if (event.event === 'Started') total = event.data.contentLength ?? 0
      if (event.event === 'Progress') {
        done += event.data.chunkLength
        if (total > 0) percent.value = Math.min(100, Math.floor((done / total) * 100))
      }
    })
    await relaunch()
  } catch (e) {
    // Grund ins Log und anzeigen – sonst lässt sich ein Fehlschlag nicht nachvollziehen.
    failReason.value = errorMessage(e)
    logError(`Update auf ${update.value.version} fehlgeschlagen: ${failReason.value}`).catch(() => {})
    failed.value = true
    percent.value = null
  }
}

function openRelease() {
  if (update.value) backend.openExternalUrl(`https://github.com/theredstonee/TRS-Launcher/releases/tag/v${update.value.version}`).catch(() => {})
}
</script>

<template>
  <div v-if="update" class="flex items-center gap-3 border-b border-lamp-400/30 bg-lamp-900 px-4 py-2 text-sm text-lamp-300">
    <span class="size-2 animate-lamp rounded-full bg-lamp-400" />
    <p class="min-w-0 flex-1 truncate">
      <template v-if="failed">
        Update fehlgeschlagen<span v-if="failReason" class="text-lamp-300/80">: {{ failReason }}</span>
      </template>
      <template v-else-if="percent !== null">Update wird geladen … <span class="display tabular-nums">{{ percent }} %</span></template>
      <template v-else>TRS Launcher {{ update.version }} ist verfügbar.</template>
    </p>
    <RedstoneWire v-if="percent !== null" :percent="percent" :segments="16" class="w-40" />
    <template v-else>
      <button v-if="failed && attempts > 1" class="text-xs text-lamp-300 underline hover:text-lamp-200" @click="openRelease">Installer herunterladen</button>
      <button class="btn bg-lamp-400 px-3 py-1 text-xs text-base-950 hover:bg-lamp-300" @click="install">
        {{ failed ? 'Erneut versuchen' : 'Jetzt aktualisieren' }}
      </button>
    </template>
  </div>
</template>
