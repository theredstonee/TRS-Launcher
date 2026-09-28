<script setup lang="ts">
// Blog-Editor (§30.3): Markdown mit Live-Vorschau im Website-Stil, Texte je Sprache (EN Pflicht, DE/ES optional mit
// Rückfall auf Englisch), Titelbild, Bilder per Ziehen/Einfügen (Upload + neu kodiert), Adresse, Autor, Entwurf /
// sofort veröffentlichen / planen. `rev` schützt vor dem Überschreiben fremder Änderungen (409 stale).
// Die Vorschau nutzt denselben Markdown-Weg wie die Website (kein rohes HTML, Bilder nur aus dem Blog-Speicher).
const route = useRoute()
const router = useRouter()
const { a, when } = useAdminText()
const { b, fill, date, lang } = useBlogText()
const { api, can, account } = useAdmin()

const id = computed(() => String(route.params.id ?? ''))
const isNew = computed(() => id.value === 'new')
const canWrite = computed(() => can('blog.write'))
const canPublish = computed(() => can('blog.publish'))

interface Draft {
  slug: string
  author: string | null
  coverId: string | null
  texts: Record<BlogLang, BlogText>
}
const emptyText = (): BlogText => ({ title: '', summary: '', body: '' })
const post = ref<AdminBlogPost | null>(null)
const authors = ref<NewsAuthor[]>([])
const draft = ref<Draft>({ slug: '', author: null, coverId: null, texts: { en: emptyText(), de: emptyText(), es: emptyText() } })
const saved = ref('')
const slugTouched = ref(false)
const editLang = ref<BlogLang>('en')
const loading = ref(true)
const busy = ref(false)
const error = ref('')
const notice = ref('')
const uploading = ref(0)
const scheduleAt = ref('')
const confirm = ref<'delete' | 'unpublish' | null>(null)
const body = shallowRef<HTMLTextAreaElement | null>(null)
const fileInput = shallowRef<HTMLInputElement | null>(null)

const snapshot = () => JSON.stringify(draft.value)
const dirty = computed(() => snapshot() !== saved.value)
const state = computed<BlogState>(() => post.value?.state ?? 'draft')
/** Veröffentlichte/geplante Beiträge ändern nur Publisher (wie der Server). */
const editable = computed(() => canWrite.value && (state.value === 'draft' || canPublish.value))

function fromPost(p: AdminBlogPost) {
  post.value = p
  draft.value = {
    slug: p.slug,
    author: p.author?.uuid ?? null,
    coverId: p.cover?.id ?? null,
    texts: {
      en: { ...emptyText(), ...p.texts.en },
      de: { ...emptyText(), ...p.texts.de },
      es: { ...emptyText(), ...p.texts.es },
    },
  }
  saved.value = snapshot()
  slugTouched.value = true
  if (p.publishAt && p.state === 'scheduled') scheduleAt.value = toLocalInput(p.publishAt)
}

async function load() {
  loading.value = true
  error.value = ''
  try {
    const [au] = await Promise.all([
      api<{ authors: NewsAuthor[] }>('/v1/admin/blog/authors'),
      isNew.value ? Promise.resolve(null) : api<{ post: AdminBlogPost }>(`/v1/admin/blog/${id.value}`).then((r) => fromPost(r.post)),
    ])
    authors.value = au.authors
    if (isNew.value) {
      post.value = null
      draft.value = { slug: '', author: account.value?.uuid && au.authors.some((x) => x.uuid === account.value!.uuid) ? account.value.uuid : null, coverId: null, texts: { en: emptyText(), de: emptyText(), es: emptyText() } }
      saved.value = snapshot()
      slugTouched.value = false
    }
  } catch (e) {
    error.value = errText(e)
  } finally {
    loading.value = false
  }
}
onMounted(load)
watch(id, (now, before) => {
  // Nach dem ersten Speichern wechselt die Adresse von /new auf die ID – dann nichts neu laden.
  if (before === 'new' && post.value?.id === now) return
  void load()
})

// Adresse aus dem englischen Titel, solange niemand sie von Hand geändert hat.
watch(() => draft.value.texts.en.title, (title) => {
  if (!slugTouched.value) draft.value.slug = slugify(title)
})
function slugify(title: string): string {
  return title.normalize('NFKD').replace(/ß/g, 'ss').replace(/[̀-ͯ]/g, '').toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-+|-+$/g, '').slice(0, 60).replace(/-+$/g, '')
}

function errText(e: unknown): string {
  const code = apiCode(e)
  const lang = (e as { data?: { error?: { lang?: string } } }).data?.error?.lang
  const known = b.value.adm.errors[code]
  return known ? fill(known, { lang: (lang ?? '').toUpperCase() }) : fill(a.value.common.failed, { error: apiMessage(e) })
}

// --- Speichern / Veröffentlichen ---------------------------------------------------------------------------
function payloadTexts() {
  const out: Record<string, BlogText> = {}
  for (const l of BLOG_LANGS) out[l] = { ...draft.value.texts[l] }
  return out
}

/** Speichert (neu: legt den Entwurf an). `true` = gespeichert. */
async function save(): Promise<boolean> {
  if (busy.value && !uploading.value) return false
  error.value = ''
  notice.value = ''
  busy.value = true
  try {
    const slug = draft.value.slug.trim() || undefined
    if (!post.value) {
      const r = await api<{ post: AdminBlogPost }>('/v1/admin/blog', {
        method: 'POST',
        body: { ...(slug && /^(?=[a-z0-9-]*[a-z])[a-z0-9]+(?:-[a-z0-9]+)*$/.test(slug) && slug.length >= 3 ? { slug } : {}), texts: payloadTexts(), author: draft.value.author },
      })
      fromPost(r.post)
      await router.replace(`/admin/blog/${r.post.id}`)
    } else {
      const r = await api<{ post: AdminBlogPost }>(`/v1/admin/blog/${post.value.id}`, {
        method: 'PATCH',
        body: { rev: post.value.rev, slug, texts: payloadTexts(), coverId: draft.value.coverId, author: draft.value.author },
      })
      fromPost(r.post)
    }
    notice.value = b.value.adm.saved
    return true
  } catch (e) {
    error.value = errText(e)
    return false
  } finally {
    busy.value = false
  }
}

async function publish(at: string | null) {
  if (dirty.value || !post.value) {
    if (!(await save())) return
  }
  busy.value = true
  error.value = ''
  try {
    const r = await api<{ post: AdminBlogPost }>(`/v1/admin/blog/${post.value!.id}/publish`, { method: 'POST', body: { rev: post.value!.rev, at } })
    fromPost(r.post)
    notice.value = b.value.adm.states[r.post.state] ?? ''
  } catch (e) {
    error.value = errText(e)
  } finally {
    busy.value = false
  }
}
function schedule() {
  const t = new Date(scheduleAt.value)
  if (Number.isNaN(t.getTime())) return
  void publish(t.toISOString())
}
function toLocalInput(iso: string): string {
  const d = new Date(iso)
  const p = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}T${p(d.getHours())}:${p(d.getMinutes())}`
}
const minSchedule = computed(() => toLocalInput(new Date(Date.now() + 5 * 60_000).toISOString()))

async function unpublish() {
  if (!post.value) return
  busy.value = true
  try {
    fromPost((await api<{ post: AdminBlogPost }>(`/v1/admin/blog/${post.value.id}/unpublish`, { method: 'POST', body: { rev: post.value.rev } })).post)
    confirm.value = null
  } catch (e) {
    error.value = errText(e)
  } finally {
    busy.value = false
  }
}

async function remove() {
  if (!post.value) return
  busy.value = true
  try {
    await api(`/v1/admin/blog/${post.value.id}`, { method: 'DELETE' })
    saved.value = snapshot()
    confirm.value = null
    await router.push('/admin/blog')
  } catch (e) {
    error.value = errText(e)
  } finally {
    busy.value = false
  }
}

// --- Bilder --------------------------------------------------------------------------------------------------
const IMAGE_TYPES = ['image/png', 'image/jpeg', 'image/webp']

async function uploadFiles(files: File[], insertAt: number | null) {
  const list = files.filter((f) => IMAGE_TYPES.includes(f.type)).slice(0, 10)
  if (!list.length || !editable.value) return
  // Bilder gehören zu einem Beitrag – ein neuer wird dafür zuerst als Entwurf gespeichert.
  if (!post.value && !(await save())) return
  uploading.value += list.length
  const inserted: string[] = []
  for (const f of list) {
    try {
      const r = await apiFetch<{ media: BlogMediaView }>(`/v1/admin/blog/${post.value!.id}/media`, {
        method: 'POST',
        body: f,
        headers: { 'Content-Type': f.type, 'X-CSRF-Token': account.value?.csrf ?? '' },
        credentials: 'same-origin',
      })
      post.value!.media.push(r.media)
      const alt = f.name.replace(/\.[a-z0-9]+$/i, '').replace(/[[\]()\n]/g, ' ').slice(0, 80) || 'image'
      inserted.push(`![${alt}](${r.media.url})`)
    } catch (e) {
      error.value = errText(e)
    } finally {
      uploading.value--
    }
  }
  if (inserted.length && insertAt !== null) insertText(`\n${inserted.join('\n\n')}\n`, insertAt)
}

function onDrop(e: DragEvent) {
  const files = [...(e.dataTransfer?.files ?? [])]
  if (!files.some((f) => IMAGE_TYPES.includes(f.type))) return
  e.preventDefault()
  void uploadFiles(files, body.value?.selectionStart ?? null)
}
function onPaste(e: ClipboardEvent) {
  const files = [...(e.clipboardData?.files ?? [])]
  if (!files.some((f) => IMAGE_TYPES.includes(f.type))) return
  e.preventDefault()
  void uploadFiles(files, body.value?.selectionStart ?? null)
}
function onPick(e: Event) {
  const input = e.target as HTMLInputElement
  const files = [...(input.files ?? [])]
  input.value = ''
  void uploadFiles(files, body.value?.selectionStart ?? draft.value.texts[editLang.value].body.length)
}

function insertMedia(m: BlogMediaView) {
  insertText(`\n![](${m.url})\n`, body.value?.selectionStart ?? draft.value.texts[editLang.value].body.length)
}
async function deleteMedia(m: BlogMediaView) {
  if (!post.value) return
  try {
    await api(`/v1/admin/blog/${post.value.id}/media/${m.id}`, { method: 'DELETE' })
    const wasCover = draft.value.coverId === m.id
    // Der Server hat ggf. das Titelbild entfernt (rev +1) – frisch laden, eigene Texte behalten.
    const keep = draft.value
    fromPost((await api<{ post: AdminBlogPost }>(`/v1/admin/blog/${post.value.id}`)).post)
    draft.value = { ...keep, coverId: wasCover ? null : keep.coverId }
  } catch (e) {
    error.value = errText(e)
  }
}

// --- Markdown-Werkzeuge ----------------------------------------------------------------------------------------
function insertText(text: string, at: number) {
  const t = draft.value.texts[editLang.value]
  t.body = t.body.slice(0, at) + text + t.body.slice(at)
  void nextTick(() => {
    const el = body.value
    if (!el) return
    el.focus()
    el.setSelectionRange(at + text.length, at + text.length)
  })
}
function wrap(before: string, after = before, placeholder = '') {
  const el = body.value
  const t = draft.value.texts[editLang.value]
  const s = el?.selectionStart ?? t.body.length
  const e = el?.selectionEnd ?? s
  const sel = t.body.slice(s, e) || placeholder
  t.body = t.body.slice(0, s) + before + sel + after + t.body.slice(e)
  void nextTick(() => {
    el?.focus()
    el?.setSelectionRange(s + before.length, s + before.length + sel.length)
  })
}
function prefixLines(prefix: string) {
  const el = body.value
  const t = draft.value.texts[editLang.value]
  const s = el?.selectionStart ?? 0
  const e = el?.selectionEnd ?? s
  const start = t.body.lastIndexOf('\n', s - 1) + 1
  const block = t.body.slice(start, e)
  const changed = block.split('\n').map((line) => (line.startsWith(prefix) ? line : prefix + line)).join('\n')
  t.body = t.body.slice(0, start) + changed + t.body.slice(e)
  void nextTick(() => el?.focus())
}
function link() {
  const url = window.prompt(b.value.adm.linkPrompt, 'https://')
  if (!url || !/^https?:\/\/\S+$/i.test(url.trim())) return
  wrap('[', `](${url.trim()})`, b.value.adm.toolbar.link)
}
function onKey(e: KeyboardEvent) {
  if (!(e.ctrlKey || e.metaKey)) return
  if (e.key === 'b') {
    e.preventDefault()
    wrap('**')
  } else if (e.key === 'i') {
    e.preventDefault()
    wrap('*')
  } else if (e.key === 's') {
    e.preventDefault()
    void save()
  }
}

// --- Vorschau ------------------------------------------------------------------------------------------------
const previewLang = computed<BlogLang>(() => (draft.value.texts[editLang.value].title || draft.value.texts[editLang.value].body.trim() ? editLang.value : 'en'))
const previewText = computed(() => draft.value.texts[previewLang.value])
const previewHtml = computed(() => renderMarkdown(previewText.value.body, { blogImages: true }))
const cover = computed(() => post.value?.media.find((m) => m.id === draft.value.coverId) ?? null)
const previewAuthor = computed(() => authors.value.find((x) => x.uuid === draft.value.author) ?? null)
const langDone = (l: BlogLang) => !!draft.value.texts[l].title.trim() && !!draft.value.texts[l].body.trim()

// --- Verlassen mit ungespeicherten Änderungen -------------------------------------------------------------------
onBeforeRouteLeave(() => {
  if (dirty.value && !window.confirm(b.value.adm.leave)) return false
})
function beforeUnload(e: BeforeUnloadEvent) {
  if (!dirty.value) return
  e.preventDefault()
}
onMounted(() => window.addEventListener('beforeunload', beforeUnload))
onBeforeUnmount(() => window.removeEventListener('beforeunload', beforeUnload))

const stateTone = (s: BlogState) => (s === 'published' ? 'tone-ok' : s === 'scheduled' ? 'tone-info' : 'tone-muted')
const titleShown = computed(() => draft.value.texts[lang.value as BlogLang]?.title || draft.value.texts.en.title || b.value.adm.untitled)
</script>

<template>
  <div class="adm-page editor-page">
    <NuxtLink to="/admin/blog" class="inline-flex items-center gap-1.5 text-sm text-base-400 hover:text-base-50"><SiteIcon name="back" class="size-4" />{{ b.adm.backToList }}</NuxtLink>

    <div v-if="loading" class="skeleton mt-6 h-96 rounded-xl" />
    <template v-else>
      <!-- Kopf: Titel, Zustand, Aktionen -->
      <header class="mt-3 flex flex-wrap items-center gap-3">
        <div class="min-w-0 flex-1">
          <h1 class="adm-title truncate">{{ isNew && !post ? b.adm.new : titleShown }}</h1>
          <p class="mt-1 flex flex-wrap items-center gap-2 text-xs text-base-400">
            <span class="tone" :class="stateTone(state)">{{ b.adm.states[state] }}</span>
            <span v-if="state === 'scheduled' && post?.publishAt">{{ fill(b.adm.scheduledFor, { date: when(post.publishAt) }) }}</span>
            <span v-else-if="state === 'published' && post?.publishAt">{{ fill(b.adm.publishedAt, { date: when(post.publishAt) }) }}</span>
            <span v-if="post">{{ fill(b.adm.updated, { date: when(post.updatedAt), name: post.updatedBy.name ?? '—' }) }}</span>
            <span v-if="dirty" class="text-lamp-300">● {{ b.adm.unsaved }}</span>
            <span v-else-if="notice" class="text-ok">{{ notice }}</span>
          </p>
        </div>
        <div class="flex flex-wrap gap-2">
          <a v-if="post && state === 'published'" :href="post.path" target="_blank" rel="noopener" class="btn btn-ghost"><SiteIcon name="external" class="size-4" />{{ b.adm.view }}</a>
          <button v-if="editable" type="button" class="btn btn-ghost" :disabled="busy || (!dirty && !!post)" data-testid="blog-save" @click="save">
            <SiteIcon name="check" class="size-4" />{{ busy ? b.adm.saving : state === 'draft' ? b.adm.save : b.adm.saveChanges }}
          </button>
          <button v-if="canPublish && state !== 'published'" type="button" class="btn btn-primary" :disabled="busy" data-testid="blog-publish" @click="publish(null)">
            <SiteIcon name="globe" class="size-4" />{{ b.adm.publish }}
          </button>
        </div>
      </header>

      <p v-if="error" role="alert" class="mt-4 rounded-md border border-redstone-600/60 bg-redstone-900/40 px-3 py-2 text-sm text-base-200">{{ error }}</p>
      <p v-if="!editable" class="mt-4 flex items-center gap-2 text-sm text-lamp-300"><SiteIcon name="key" class="size-4" />{{ b.adm.needPublish }}</p>

      <div class="editor-grid mt-5">
        <!-- Bearbeiten -->
        <section class="min-w-0 space-y-4">
          <div class="card p-4">
            <div class="flex flex-wrap items-center justify-between gap-2">
              <div class="adm-seg" role="group" :aria-label="b.adm.langTabs">
                <button
                  v-for="l in BLOG_LANGS"
                  :key="l"
                  type="button"
                  :aria-pressed="editLang === l"
                  :data-testid="`blog-lang-${l}`"
                  @click="editLang = l"
                >
                  <span class="font-mono uppercase">{{ l }}</span>
                  <span class="dot" :class="langDone(l) ? 'dot-ok' : l === 'en' ? 'dot-need' : 'dot-empty'" :title="langDone(l) ? '' : b.adm.langMissing" />
                </button>
              </div>
              <p class="text-xs text-base-400">{{ b.adm.langFallback }}</p>
            </div>

            <label class="label mt-4" :for="`t-${editLang}`">{{ b.adm.fields.title }}</label>
            <input :id="`t-${editLang}`" v-model="draft.texts[editLang].title" class="field text-lg" maxlength="120" :disabled="!editable" data-testid="blog-title" />

            <label class="label mt-3" :for="`s-${editLang}`">{{ b.adm.fields.summary }}</label>
            <textarea :id="`s-${editLang}`" v-model="draft.texts[editLang].summary" class="field" rows="2" maxlength="300" :disabled="!editable" />
            <p class="mt-1 flex justify-between gap-2 text-[11px] text-base-400"><span>{{ b.adm.fields.summaryHint }}</span><span class="tabular-nums">{{ draft.texts[editLang].summary.length }}/300</span></p>

            <div class="mt-3 flex flex-wrap items-center justify-between gap-2">
              <label class="label !mb-0" :for="`b-${editLang}`">{{ b.adm.fields.body }}</label>
              <div class="toolbar" role="toolbar" :aria-label="b.adm.fields.body">
                <button type="button" :title="`${b.adm.toolbar.bold} (Ctrl+B)`" :disabled="!editable" @click="wrap('**')"><b>B</b></button>
                <button type="button" :title="`${b.adm.toolbar.italic} (Ctrl+I)`" :disabled="!editable" @click="wrap('*')"><i>I</i></button>
                <button type="button" :title="b.adm.toolbar.heading" :disabled="!editable" @click="prefixLines('## ')">H2</button>
                <button type="button" :title="b.adm.toolbar.link" :disabled="!editable" @click="link"><SiteIcon name="link" class="size-4" /></button>
                <button type="button" :title="b.adm.toolbar.list" :disabled="!editable" @click="prefixLines('- ')"><SiteIcon name="list" class="size-4" /></button>
                <button type="button" :title="b.adm.toolbar.quote" :disabled="!editable" @click="prefixLines('> ')">❝</button>
                <button type="button" :title="b.adm.toolbar.code" :disabled="!editable" @click="wrap('`')"><span class="font-mono text-xs">&lt;/&gt;</span></button>
                <button type="button" :title="b.adm.toolbar.image" :disabled="!editable" @click="fileInput?.click()"><SiteIcon name="import" class="size-4" /></button>
              </div>
            </div>
            <textarea
              :id="`b-${editLang}`"
              ref="body"
              v-model="draft.texts[editLang].body"
              class="field body-field mt-2"
              rows="18"
              spellcheck="true"
              :lang="editLang"
              :disabled="!editable"
              data-testid="blog-body"
              @drop="onDrop"
              @dragover.prevent
              @paste="onPaste"
              @keydown="onKey"
            />
            <p class="mt-1 text-[11px] text-base-400">
              <template v-if="uploading">{{ fill(b.adm.uploading, { n: uploading }) }}</template>
              <template v-else>{{ b.adm.dropHint }}</template>
            </p>
            <input ref="fileInput" type="file" accept="image/png,image/jpeg,image/webp" multiple class="hidden" @change="onPick" />
          </div>

          <!-- Einstellungen -->
          <div class="card grid gap-4 p-4 sm:grid-cols-2">
            <div>
              <label class="label" for="blog-slug">{{ b.adm.fields.slug }}</label>
              <div class="flex items-center gap-1">
                <span class="font-mono text-sm text-base-400">/blog/</span>
                <input id="blog-slug" v-model="draft.slug" class="field font-mono text-sm" maxlength="80" :disabled="!editable" @input="slugTouched = true" />
              </div>
              <p class="mt-1 text-[11px] text-base-400">{{ b.adm.fields.slugHint }}</p>
            </div>
            <div>
              <label class="label" for="blog-author">{{ b.adm.fields.author }}</label>
              <select id="blog-author" v-model="draft.author" class="field" :disabled="!editable">
                <option :value="null">{{ b.adm.fields.teamAuthor }}</option>
                <option v-for="x in authors" :key="x.uuid" :value="x.uuid">{{ x.name }}</option>
              </select>
            </div>
            <div class="sm:col-span-2">
              <p class="label">{{ b.adm.fields.cover }}</p>
              <div class="flex flex-wrap items-center gap-3">
                <div class="cover-thumb">
                  <img v-if="cover" :src="cover.thumbUrl" alt="" class="size-full object-cover" />
                  <SiteIcon v-else name="book" class="size-6 text-base-400" />
                </div>
                <p v-if="!cover" class="flex-1 text-xs text-base-400">{{ b.adm.fields.noCover }}</p>
                <button v-else type="button" class="btn btn-ghost px-2.5 py-1 text-xs" :disabled="!editable" @click="draft.coverId = null">{{ b.adm.removeCover }}</button>
              </div>
            </div>
            <div class="sm:col-span-2">
              <div class="flex items-center justify-between gap-2">
                <p class="label !mb-0">{{ b.adm.images }}</p>
                <button type="button" class="btn btn-ghost px-2.5 py-1 text-xs" :disabled="!editable || uploading > 0" @click="fileInput?.click()"><SiteIcon name="plus" class="size-3.5" />{{ b.adm.upload }}</button>
              </div>
              <p v-if="!post?.media.length" class="mt-2 text-xs text-base-400">{{ b.adm.noImages }}</p>
              <ul v-else class="media-grid mt-2">
                <li v-for="m in post.media" :key="m.id" class="media" :class="{ 'is-cover': draft.coverId === m.id }">
                  <img :src="m.thumbUrl" alt="" loading="lazy" class="size-full object-cover" />
                  <div class="media-actions">
                    <button type="button" :title="b.adm.insert" :disabled="!editable" @click="insertMedia(m)"><SiteIcon name="plus" class="size-3.5" /></button>
                    <button type="button" :title="b.adm.setCover" :disabled="!editable" @click="draft.coverId = m.id"><SiteIcon name="crown" class="size-3.5" /></button>
                    <button type="button" :title="b.adm.deleteImage" :disabled="!editable" @click="deleteMedia(m)"><SiteIcon name="trash" class="size-3.5" /></button>
                  </div>
                </li>
              </ul>
            </div>
          </div>

          <!-- Veröffentlichen -->
          <div v-if="canPublish" class="card flex flex-wrap items-end gap-3 p-4">
            <div class="min-w-0 flex-1">
              <label class="label" for="blog-at">{{ b.adm.scheduleAt }}</label>
              <input id="blog-at" v-model="scheduleAt" type="datetime-local" class="field" :min="minSchedule" />
              <p class="mt-1 text-[11px] text-base-400">{{ b.adm.scheduleHint }}</p>
            </div>
            <button type="button" class="btn btn-ghost" :disabled="busy || !scheduleAt" data-testid="blog-schedule" @click="schedule"><SiteIcon name="clock" class="size-4" />{{ b.adm.schedule }}</button>
            <button v-if="state !== 'draft'" type="button" class="btn btn-ghost" :disabled="busy" @click="confirm = 'unpublish'"><SiteIcon name="back" class="size-4" />{{ b.adm.unpublish }}</button>
            <button v-if="post" type="button" class="btn btn-danger" :disabled="busy" @click="confirm = 'delete'"><SiteIcon name="trash" class="size-4" />{{ b.adm.delete }}</button>
          </div>
          <div v-else-if="post && state === 'draft' && canWrite" class="flex justify-end">
            <button type="button" class="btn btn-danger" :disabled="busy" @click="confirm = 'delete'"><SiteIcon name="trash" class="size-4" />{{ b.adm.delete }}</button>
          </div>
        </section>

        <!-- Vorschau im Website-Stil -->
        <aside class="preview-col" :aria-label="b.adm.preview">
          <p class="label">{{ b.adm.preview }} · <span class="font-mono uppercase">{{ previewLang }}</span></p>
          <article class="preview card overflow-hidden" data-testid="blog-preview">
            <div class="preview-banner relative">
              <template v-if="cover">
                <img :src="cover.url" alt="" class="absolute inset-0 size-full object-cover" />
                <div class="preview-shade absolute inset-0" />
                <div class="absolute inset-x-5 bottom-4">
                  <p class="kicker">{{ b.kind.news }} · {{ date(post?.publishAt ?? new Date().toISOString()) }}</p>
                  <h2 class="display mt-1 text-3xl leading-tight preview-title">{{ previewText.title || b.adm.untitled }}</h2>
                </div>
              </template>
              <UpdateBanner v-else :kicker="`${b.kind.news} · ${date(post?.publishAt ?? new Date().toISOString())}`" :title="previewText.title || b.adm.untitled" accent="#ffb84d" size="md" tag="h2" />
            </div>
            <div class="p-5">
              <p class="flex items-center gap-2 border-b border-base-800 pb-3 text-xs text-base-400">
                <template v-if="previewAuthor">
                  <PlayerHead :uuid="previewAuthor.uuid" :name="previewAuthor.name" :skin="previewAuthor.skin" :size="20" :fetch="false" />
                  <span class="text-base-200">{{ previewAuthor.name }}</span>
                </template>
                <span v-else class="text-base-200">{{ b.team }}</span>
              </p>
              <!-- eslint-disable-next-line vue/no-v-html -- gleicher gesäuberter Markdown-Weg wie auf der Website (utils/markdown.ts) -->
              <div v-if="previewText.body.trim()" class="prose-md preview-text mt-4" v-html="previewHtml" />
              <p v-else class="mt-4 text-sm text-base-400">{{ b.adm.previewEmpty }}</p>
            </div>
          </article>
        </aside>
      </div>
    </template>

    <AdminConfirm
      v-if="confirm"
      :title="confirm === 'delete' ? b.adm.delete : b.adm.unpublish"
      :text="fill(confirm === 'delete' ? b.adm.confirmDelete : b.adm.confirmUnpublish, { title: titleShown })"
      :confirm-label="confirm === 'delete' ? b.adm.delete : b.adm.unpublish"
      :danger="confirm === 'delete'"
      :busy="busy"
      @confirm="confirm === 'delete' ? remove() : unpublish()"
      @cancel="confirm = null"
    />
  </div>
</template>

<style scoped>
.editor-page {
  max-width: 96rem;
}
.editor-grid {
  display: grid;
  gap: 1.25rem;
}
@media (min-width: 1280px) {
  .editor-grid {
    grid-template-columns: minmax(0, 1fr) minmax(0, 1fr);
    align-items: start;
  }
  .preview-col {
    position: sticky;
    top: 5rem;
    max-height: calc(100dvh - 6rem);
    overflow-y: auto;
  }
}
.body-field {
  font-family: ui-monospace, SFMono-Regular, Consolas, monospace;
  font-size: 0.875rem;
  line-height: 1.6;
  resize: vertical;
  min-height: 18rem;
}
.toolbar {
  display: inline-flex;
  flex-wrap: wrap;
  gap: 0.125rem;
  padding: 0.125rem;
  border-radius: 0.5rem;
  border: 1px solid var(--color-base-800);
  background: var(--color-base-950);
}
.toolbar button {
  display: grid;
  place-items: center;
  min-width: 2rem;
  height: 2rem;
  padding-inline: 0.375rem;
  border-radius: 0.375rem;
  font-size: 0.8125rem;
  color: var(--color-base-200);
}
.toolbar button:not(:disabled):hover {
  background: var(--color-base-800);
  color: var(--color-base-50);
}
.toolbar button:disabled {
  opacity: 0.4;
}
.dot {
  display: inline-block;
  width: 0.4rem;
  height: 0.4rem;
  margin-left: 0.35rem;
  border-radius: 9999px;
}
.dot-ok {
  background: var(--color-ok);
}
.dot-need {
  background: var(--color-redstone-400);
}
.dot-empty {
  background: var(--color-base-600);
}
.cover-thumb {
  display: grid;
  place-items: center;
  width: 9rem;
  aspect-ratio: 16 / 9;
  overflow: hidden;
  border-radius: 0.5rem;
  border: 1px solid var(--color-base-700);
  background: var(--color-base-950);
}
.media-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(7.5rem, 1fr));
  gap: 0.5rem;
}
.media {
  position: relative;
  aspect-ratio: 16 / 10;
  overflow: hidden;
  border-radius: 0.5rem;
  border: 1px solid var(--color-base-700);
  background: var(--color-base-950);
}
.media.is-cover {
  border-color: var(--color-lamp-400);
  box-shadow: 0 0 0 1px var(--color-lamp-400);
}
.media-actions {
  position: absolute;
  inset-inline: 0.25rem;
  bottom: 0.25rem;
  display: flex;
  justify-content: flex-end;
  gap: 0.25rem;
}
.media-actions button {
  display: grid;
  place-items: center;
  width: 1.75rem;
  height: 1.75rem;
  border-radius: 0.375rem;
  background: rgb(12 11 14 / 0.8);
  color: #f3f3f8;
}
.media-actions button:not(:disabled):hover {
  background: var(--color-redstone-600);
}
.preview-banner {
  height: 13rem;
  overflow: hidden;
  background: var(--color-base-950);
}
.preview-shade {
  background: linear-gradient(to top, rgb(12 11 14 / 0.95), rgb(12 11 14 / 0.3) 60%, transparent);
}
.preview-title {
  color: #f3f3f8;
}
.kicker {
  font-size: 0.75rem;
  letter-spacing: 0.1em;
  text-transform: uppercase;
  color: var(--color-lamp-300);
}
.preview-text {
  font-size: 1rem;
  line-height: 1.7;
}
.preview-text :deep(img) {
  display: block;
  max-width: 100%;
  height: auto;
  margin-block: 1rem;
  border-radius: 0.5rem;
  border: 1px solid var(--color-base-800);
}
</style>
