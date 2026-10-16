<script setup lang="ts">
import type { TrsCape } from '~/utils/trs'
import { FRAME_TIME_DEFAULT } from '~/utils/capeImage'
import { reduceTimeline, type CapeDoc, type CapeImportResult } from '~/utils/capeEditor'

// Umhang-Werkstatt: eigener Umhang in drei Schritten – 1. Quelle (Bilder, GIF/APNG,
// Sprite-Sheet, Video, TRS-Studio) zuschneiden, 2. im Editor bearbeiten (Zeitleiste,
// Pixel-Werkzeuge, Live-3D-Vorschau), 3. Auflösung wählen und hochladen (bis 1024×512
// je Frame, bis 32 Frames, bis 20 MB). Am Handy geht es vom Import direkt zum Hochladen.
// `fanCape`: aus dem Creator-Dashboard – „Hochladen“ reicht den Fan-Umhang ein (§35.3).
const props = defineProps<{ fanCape?: boolean }>()
const emit = defineEmits<{ close: []; uploaded: [cape: TrsCape]; fanUploaded: [] }>()

type Step = 'import' | 'edit' | 'upload'
const step = ref<Step>('import')
const doc = shallowRef<CapeDoc | null>(null)
const frameTime = ref(FRAME_TIME_DEFAULT)
const name = ref('')
const sourceFrames = ref(0)

/** Der Editor braucht Maus und Platz – am Handy nur Import + Hochladen. */
const editorAvailable = computed(() => !mobileUi.value)
const steps = computed<Step[]>(() => (editorAvailable.value ? ['import', 'edit', 'upload'] : ['import', 'upload']))

const importer = ref<{ finish: () => Promise<void>; ready: boolean } | null>(null)
const exporter = ref<{
  upload: () => Promise<void>
  canUpload: boolean
  uploading: boolean
  uploadFan: () => Promise<void>
  canUploadFan: boolean
  fanMode: 'standard' | 'hd' | null
} | null>(null)

function imported(result: CapeImportResult) {
  doc.value = result.doc
  frameTime.value = result.frameTime
  sourceFrames.value = result.sourceFrames
  if (!name.value.trim()) name.value = result.name
  step.value = editorAvailable.value ? 'edit' : 'upload'
}

/** Schritt wechseln: zurück immer, vor nur mit Frames. */
function go(target: Step) {
  if (target !== 'import' && !doc.value) return
  step.value = target
}

function reduce(keep: number) {
  if (doc.value) doc.value = { ...doc.value, frames: reduceTimeline(doc.value.frames, keep) }
}

const stepIndex = computed(() => steps.value.indexOf(step.value))
const back = computed<Step | null>(() => steps.value[stepIndex.value - 1] ?? null)
</script>

<template>
  <BaseDialog :title="props.fanCape ? t('creator.cape.workshopTitle') : t('capes.editor.title')" :huge="step !== 'edit'" :giant="step === 'edit'" @close="emit('close')">
    <!-- Schritte -->
    <ol class="mb-4 flex flex-wrap items-center gap-1.5 text-xs" :aria-label="t('capes.studio.steps.label')">
      <li v-for="(s, i) in steps" :key="s" class="flex items-center gap-1.5">
        <button
          type="button"
          class="flex items-center gap-2 rounded-full px-3 py-1 transition-colors"
          :class="step === s ? 'bg-redstone-600 text-white' : doc || s === 'import' ? 'bg-base-800 text-base-200 hover:bg-base-700' : 'bg-base-900 text-base-600'"
          :disabled="s !== 'import' && !doc"
          :aria-current="step === s ? 'step' : undefined"
          @click="go(s)"
        >
          <span class="grid size-4 place-items-center rounded-full bg-black/25 text-[10px] font-bold">{{ i + 1 }}</span>
          {{ t(`capes.studio.steps.${s}`) }}
        </button>
        <span v-if="i < steps.length - 1" class="h-px w-5 bg-base-700" aria-hidden="true" />
      </li>
    </ol>

    <KeepAlive>
      <CapeImport v-if="step === 'import'" ref="importer" @done="imported" />
    </KeepAlive>
    <KeepAlive>
      <CapeEditor v-if="step === 'edit' && doc" v-model:doc="doc" v-model:frame-time="frameTime" />
    </KeepAlive>
    <CapeExport
      v-if="step === 'upload' && doc"
      ref="exporter"
      v-model:frame-time="frameTime"
      v-model:name="name"
      :doc="doc"
      :source-frames="sourceFrames"
      :fan-cape="props.fanCape"
      @reduce="reduce"
      @uploaded="emit('uploaded', $event)"
      @fan-uploaded="emit('fanUploaded')"
    />

    <template #actions>
      <button class="btn btn-ghost" @click="emit('close')">{{ t('common.actions.cancel') }}</button>
      <button v-if="back && step !== 'import'" class="btn btn-ghost" @click="go(back)">{{ t('capes.studio.steps.back') }}</button>
      <button v-if="step === 'import'" class="btn btn-primary" :disabled="!importer?.ready" data-testid="cape-studio-next" @click="importer?.finish()">
        {{ editorAvailable ? t('capes.studio.steps.toEditor') : t('capes.studio.steps.toUpload') }}
      </button>
      <button v-else-if="step === 'edit'" class="btn btn-primary" data-testid="cape-studio-next" @click="go('upload')">{{ t('capes.studio.steps.toUpload') }}</button>
      <button v-else-if="props.fanCape" class="btn btn-primary" :disabled="!exporter?.canUploadFan" data-testid="cape-editor-upload-fan" @click="exporter?.uploadFan()">
        {{ exporter?.uploading ? t('capes.editor.uploading') : t('creator.cape.submitFan') }}
      </button>
      <template v-else>
        <!-- Creator ab Partner: denselben Umhang auch als Fan-Umhang einreichen. -->
        <button v-if="exporter?.fanMode" class="btn btn-ghost" :disabled="!exporter?.canUploadFan" data-testid="cape-editor-upload-fan" @click="exporter?.uploadFan()">
          {{ t('creator.cape.submitFan') }}
        </button>
        <button class="btn btn-primary" :disabled="!exporter?.canUpload" data-testid="cape-editor-upload" @click="exporter?.upload()">
          {{ exporter?.uploading ? t('capes.editor.uploading') : t('capes.editor.upload') }}
        </button>
      </template>
    </template>
  </BaseDialog>
</template>
