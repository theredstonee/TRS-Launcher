<script setup lang="ts">
// Markdown-Eingabe für Issues und Kommentare (§28): Schreiben/Vorschau, Zeichenzähler und Bilder (Auswahl, Ablegen,
// Einfügen mit Strg+V). Bilder gehen sofort an `POST /v1/issues/uploads` (neu kodiert, 1 h gültig) – das Formular
// schickt danach nur noch die IDs mit. Vorschau über denselben sicheren Renderer wie die Anzeige.
import type { UploadView } from '#shared/issues'
import { renderUserMarkdown } from '~/utils/markdown'

const props = withDefaults(defineProps<{
  id: string
  placeholder?: string
  max: number
  maxImages: number
  rows?: number
  label?: string
  disabled?: boolean
}>(), { placeholder: '', rows: 6, label: '', disabled: false })
const text = defineModel<string>({ required: true })
const images = defineModel<UploadView[]>('images', { default: () => [] })

const { it, fill, errorText } = useIssueText()
const { account } = useAccount()
const tab = ref<'write' | 'preview'>('write')
const uploading = ref(0)
const error = ref('')
const dragging = ref(false)
const fileInput = shallowRef<HTMLInputElement | null>(null)

const length = computed(() => [...text.value].length)
const preview = computed(() => (text.value.trim() ? renderUserMarkdown(text.value) : ''))
const TYPES = ['image/png', 'image/jpeg', 'image/webp']

async function upload(files: File[]) {
  error.value = ''
  const room = props.maxImages - images.value.length - uploading.value
  const list = files.filter((f) => TYPES.includes(f.type))
  if (list.length < files.length) error.value = it.value.errors.unsupported_media_type!
  if (list.length > room) error.value = fill(it.value.editor.tooMany, { n: props.maxImages })
  for (const f of list.slice(0, Math.max(0, room))) {
    if (f.size > 8 * 1024 * 1024) {
      error.value = it.value.errors.payload_too_large!
      continue
    }
    uploading.value++
    try {
      // Rohes Bild (kein JSON) – deshalb fetch statt $fetch; Fehler im selben Format wie bei $fetch (e.data.error).
      const res = await fetch('/v1/issues/uploads', {
        method: 'POST',
        body: f,
        headers: { 'Content-Type': f.type, ...(account.value ? { 'X-CSRF-Token': account.value.csrf } : {}) },
        credentials: 'same-origin',
      })
      const json = await res.json().catch(() => null) as { upload?: UploadView } | null
      if (!res.ok || !json?.upload) throw Object.assign(new Error(`HTTP ${res.status}`), { data: json })
      images.value = [...images.value, json.upload]
    } catch (e) {
      error.value = errorText(e)
    } finally {
      uploading.value--
    }
  }
}

function onPick(e: Event) {
  const input = e.target as HTMLInputElement
  void upload([...(input.files ?? [])])
  input.value = ''
}
function onDrop(e: DragEvent) {
  dragging.value = false
  if (props.disabled) return
  const files = [...(e.dataTransfer?.files ?? [])]
  if (files.length) void upload(files)
}
function onPaste(e: ClipboardEvent) {
  if (props.disabled) return
  const files = [...(e.clipboardData?.files ?? [])].filter((f) => f.type.startsWith('image/'))
  if (files.length) {
    e.preventDefault()
    void upload(files)
  }
}
function remove(id: string) {
  images.value = images.value.filter((x) => x.id !== id)
}
defineExpose({ busy: computed(() => uploading.value > 0) })
</script>

<template>
  <div class="editor" :class="{ dragging }" @dragover.prevent="dragging = !disabled" @dragleave="dragging = false" @drop.prevent="onDrop">
    <div class="flex items-center justify-between gap-2 border-b border-base-800 px-2 pt-1.5">
      <div class="flex gap-1" role="tablist">
        <button type="button" role="tab" class="tab-btn" :aria-selected="tab === 'write'" @click="tab = 'write'">{{ it.editor.write }}</button>
        <button type="button" role="tab" class="tab-btn" :aria-selected="tab === 'preview'" @click="tab = 'preview'">{{ it.editor.preview }}</button>
      </div>
      <span class="pb-1 text-xs tabular-nums" :class="length > max ? 'text-redstone-300' : 'text-base-400'">{{ fill(it.editor.chars, { n: length, max }) }}</span>
    </div>
    <textarea
      v-show="tab === 'write'"
      :id="id"
      v-model="text"
      class="block w-full resize-y bg-transparent px-3 py-2.5 text-sm text-base-50 outline-none placeholder:text-base-400"
      :rows="rows"
      :placeholder="placeholder"
      :aria-label="label || undefined"
      :disabled="disabled"
      @paste="onPaste"
    />
    <template v-if="tab === 'preview'">
      <!-- eslint-disable-next-line vue/no-v-html -- renderUserMarkdown lässt kein HTML durch (tests/usermarkdown.test.ts) -->
      <div v-if="preview" class="prose-md min-h-24 px-3 py-2.5 text-sm" v-html="preview" />
      <p v-else class="min-h-24 px-3 py-2.5 text-sm text-base-400">{{ it.editor.empty }}</p>
    </template>

    <div class="flex flex-wrap items-center gap-2 border-t border-base-800 px-3 py-2">
      <ul v-if="images.length" class="flex flex-wrap gap-2">
        <li v-for="img in images" :key="img.id" class="thumb">
          <img :src="img.thumbUrl" :alt="it.editor.images" loading="lazy" />
          <button type="button" class="remove" :aria-label="it.editor.remove" :title="it.editor.remove" @click="remove(img.id)">
            <SiteIcon name="close" class="size-3.5" />
          </button>
        </li>
      </ul>
      <span v-if="uploading" class="text-xs text-lamp-300" role="status">{{ it.editor.uploading }}</span>
      <button
        v-if="images.length + uploading < maxImages"
        type="button"
        class="btn btn-ghost px-2.5 py-1.5 text-xs"
        :disabled="disabled"
        @click="fileInput?.click()"
      >
        <SiteIcon name="image" class="size-4" />{{ it.editor.addImages }}
      </button>
      <input ref="fileInput" type="file" class="hidden" accept="image/png,image/jpeg,image/webp" multiple @change="onPick" />
      <p class="w-full text-[11px] leading-4 text-base-400">{{ fill(it.editor.dropHint, { n: maxImages }) }} {{ it.editor.markdown }}</p>
      <p v-if="error" role="alert" class="w-full text-xs text-redstone-300">{{ error }}</p>
    </div>
  </div>
</template>

<style scoped>
.editor {
  border: 1px solid var(--color-base-700);
  border-radius: 0.6rem;
  background: var(--color-base-900);
  transition: border-color 0.15s, box-shadow 0.15s;
}
.editor:focus-within {
  border-color: var(--color-redstone-500);
}
.editor.dragging {
  border-color: var(--color-lamp-400);
  box-shadow: 0 0 0 3px color-mix(in srgb, var(--color-lamp-400) 20%, transparent);
}
.tab-btn {
  padding: 0.35rem 0.7rem;
  font-size: 0.8rem;
  color: var(--color-base-400);
  border-bottom: 2px solid transparent;
}
.tab-btn[aria-selected='true'] {
  color: var(--color-base-50);
  border-bottom-color: var(--color-redstone-500);
}
.thumb {
  position: relative;
  width: 4.5rem;
  height: 3.25rem;
  overflow: hidden;
  border-radius: 0.4rem;
  border: 1px solid var(--color-base-700);
  background: var(--color-base-950);
}
.thumb img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}
.remove {
  position: absolute;
  top: 2px;
  right: 2px;
  display: grid;
  place-items: center;
  width: 1.25rem;
  height: 1.25rem;
  border-radius: 999px;
  background: rgb(0 0 0 / 0.7);
  color: white;
}
</style>
