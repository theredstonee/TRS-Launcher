<script setup lang="ts">
import type { ClipSettings, FfmpegStatus } from '~/types'

// Einstellungen für Clips & Aufnahme. Das Formular gehört dem Einstellungs-
// Dialog (speichert selbst); hier nur Bedienelemente, FFmpeg-Download und
// Speicherort.
const model = defineModel<ClipSettings>({ required: true })

const toasts = useToasts()
const tasks = useTasksStore()
const clipsStore = useClipsStore()
const ffmpeg = ref<FfmpegStatus | null>(null)

async function refreshFfmpeg() {
  try {
    ffmpeg.value = await backend.ffmpegStatus()
  } catch {
    ffmpeg.value = null
  }
}
onMounted(refreshFfmpeg)
// Lädt der Kern FFmpeg selbst (Spielstart mit Clips an), danach neu prüfen.
watch(() => clipsStore.ffmpegDownloading, (now, before) => before && !now && refreshFfmpeg())

const download = computed(() => tasks.active.find((a) => a.kind === 'ffmpeg') ?? null)

function installFfmpeg() {
  void tasks
    .run(
      {
        key: taskKey('ffmpeg'),
        kind: 'ffmpeg',
        title: t('settings.clips.ffmpegTaskTitle'),
        stage: t('settings.clips.ffmpegTaskStage'),
        cancellable: true,
        pausable: true,
        doneText: t('settings.clips.ffmpegTaskDone'),
      },
      async (ctx) => {
        await backend.installFfmpeg((p) => ctx.progress(p), ctx.taskId)
      },
    )
    .then(refreshFfmpeg)
}

// Einschalten lädt FFmpeg gleich mit (einmalig, ~110 MB).
watch(
  () => model.value.enabled,
  (on) => {
    if (on && ffmpeg.value && !ffmpeg.value.installed && !download.value) installFfmpeg()
  },
)

async function pickFolder() {
  try {
    const folder = await backend.pickClipsFolder()
    if (folder) model.value.folder = folder
  } catch (e) {
    toasts.error(e)
  }
}

function openFolder() {
  backend.openClipsFolder().catch((e) => toasts.error(e))
}

const resolutions = ['native', '1080p', '720p'] as const
const qualities = ['low', 'medium', 'high'] as const
const encoders = ['auto', 'nvenc', 'amf', 'qsv', 'x264'] as const
</script>

<template>
  <div>
    <h3 class="section-heading">{{ t('settings.clips.title') }}</h3>
    <p class="mb-3 text-xs text-base-400">{{ t('settings.clips.intro') }}</p>

    <SettingRow :title="t('settings.clips.enabledTitle')" :description="t('settings.clips.enabledDescription')">
      <ToggleSwitch v-model="model.enabled" :label="t('settings.clips.enabledTitle')" />
    </SettingRow>

    <!-- FFmpeg -->
    <SettingRow :title="t('settings.clips.ffmpegTitle')" stacked>
      <template #description>
        <p class="mt-0.5 text-xs" :class="ffmpeg?.installed ? 'text-ok' : 'text-base-400'">
          {{
            ffmpeg?.installed
              ? t('settings.clips.ffmpegReady', { version: ffmpeg.version })
              : t('settings.clips.ffmpegMissing', { size: formatBytes(ffmpeg?.downloadBytes ?? 0) })
          }}
        </p>
      </template>
      <div class="flex items-center gap-3">
        <button v-if="!ffmpeg?.installed" class="btn btn-ghost" :disabled="!!download" @click="installFfmpeg">
          {{ download ? t('settings.clips.ffmpegDownloading', { percent: Math.floor(download.percent ?? 0) }) : t('settings.clips.ffmpegDownload') }}
        </button>
      </div>
      <RedstoneWire v-if="download" class="mt-2" :percent="download.percent ?? 0" :segments="40" />
    </SettingRow>

    <h3 class="section-heading mt-6">{{ t('settings.clips.captureTitle') }}</h3>
    <SettingRow :title="t('settings.clips.bufferTitle')" :description="t('settings.clips.bufferDescription')" stacked>
      <div class="flex items-center gap-4">
        <input
          v-model.number="model.bufferSeconds"
          type="range"
          min="15"
          max="120"
          step="5"
          class="flex-1 accent-redstone-500"
          :aria-label="t('settings.clips.bufferTitle')"
        />
        <span class="w-16 text-right font-mono text-sm text-base-50">{{ t('settings.clips.seconds', { n: model.bufferSeconds }) }}</span>
      </div>
    </SettingRow>
    <SettingRow :title="t('settings.clips.resolutionTitle')" :description="t('settings.clips.resolutionDescription')">
      <select v-model="model.resolution" class="field w-44 py-1.5" :aria-label="t('settings.clips.resolutionTitle')">
        <option v-for="r in resolutions" :key="r" :value="r">{{ tKey(`settings.clips.resolution.${r}`) }}</option>
      </select>
    </SettingRow>
    <SettingRow :title="t('settings.clips.fpsTitle')">
      <select v-model.number="model.fps" class="field w-44 py-1.5" :aria-label="t('settings.clips.fpsTitle')">
        <option :value="30">30 FPS</option>
        <option :value="60">60 FPS</option>
      </select>
    </SettingRow>
    <SettingRow :title="t('settings.clips.qualityTitle')" :description="t('settings.clips.qualityDescription')">
      <select v-model="model.quality" class="field w-44 py-1.5" :aria-label="t('settings.clips.qualityTitle')">
        <option v-for="q in qualities" :key="q" :value="q">{{ tKey(`settings.clips.quality.${q}`) }}</option>
      </select>
    </SettingRow>
    <SettingRow :title="t('settings.clips.encoderTitle')" :description="t('settings.clips.encoderDescription')">
      <select v-model="model.encoder" class="field w-44 py-1.5" :aria-label="t('settings.clips.encoderTitle')">
        <option v-for="e in encoders" :key="e" :value="e">{{ e === 'auto' ? t('common.labels.automatic') : tKey(`clips.encoders.${e}`) }}</option>
      </select>
    </SettingRow>

    <h3 class="section-heading mt-6">{{ t('settings.clips.audioTitle') }}</h3>
    <SettingRow :title="t('settings.clips.systemAudioTitle')" :description="t('settings.clips.systemAudioDescription')">
      <ToggleSwitch v-model="model.systemAudio" :label="t('settings.clips.systemAudioTitle')" />
    </SettingRow>
    <SettingRow :title="t('settings.clips.microphoneTitle')" :description="t('settings.clips.microphoneDescription')">
      <ToggleSwitch v-model="model.microphone" :label="t('settings.clips.microphoneTitle')" />
    </SettingRow>

    <h3 class="section-heading mt-6">{{ t('settings.clips.storageTitle') }}</h3>
    <SettingRow :title="t('settings.clips.folderTitle')" stacked>
      <template #description>
        <p class="mt-0.5 truncate font-mono text-xs text-base-400" :title="model.folder ?? ''">
          {{ model.folder ?? t('settings.clips.folderDefault') }}
        </p>
      </template>
      <div class="flex flex-wrap gap-2">
        <button class="btn btn-ghost" @click="pickFolder">{{ t('common.actions.browse') }}</button>
        <button v-if="model.folder" class="btn btn-ghost" @click="model.folder = null">{{ t('settings.clips.folderReset') }}</button>
        <button class="btn btn-ghost" @click="openFolder">{{ t('common.actions.openFolder') }}</button>
      </div>
    </SettingRow>
    <SettingRow :title="t('settings.clips.limitTitle')" :description="t('settings.clips.limitDescription')">
      <div class="flex items-center gap-2">
        <input
          v-model.number="model.maxStorageGb"
          type="number"
          min="1"
          max="2000"
          class="field w-24 font-mono"
          :aria-label="t('settings.clips.limitTitle')"
        />
        <span class="text-xs text-base-400">GB</span>
      </div>
    </SettingRow>

    <section class="mt-6 rounded-xl border border-base-800 bg-base-850 p-4 text-xs text-base-400">
      <p class="mb-1 font-medium text-base-200">{{ t('settings.clips.keysTitle') }}</p>
      <p>{{ t('settings.clips.keysText') }}</p>
      <p class="mt-2">{{ t('settings.clips.privacy') }}</p>
    </section>
  </div>
</template>

<style scoped>
@reference "~/assets/css/main.css";

.section-heading {
  @apply mb-2 text-base font-semibold text-base-50;
}
</style>
