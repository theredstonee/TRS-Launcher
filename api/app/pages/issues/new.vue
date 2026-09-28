<script setup lang="ts">
// Neues Issue (§28): Art, Bereich, Titel, Beschreibung (Markdown mit Vorschau) und Screenshots. Nur angemeldet
// (Website-Sitzung). Nur im Browser gerendert, nie indexiert (nuxt.config routeRules).
import { ISSUE_AREAS, ISSUE_LIMITS, ISSUE_TYPES, type IssueArea, type IssueDetail, type IssueType, type UploadView } from '#shared/issues'

const { it, fill, errorText } = useIssueText()
const lp = useLocalePath()
const route = useRoute()
const router = useRouter()
const { account, load, api, loginUrl } = useAccount()
const loaded = ref(false)
onMounted(async () => {
  await load()
  loaded.value = true
})

const pick = <T extends string>(v: unknown, allowed: readonly T[], fallback: T): T => (typeof v === 'string' && (allowed as readonly string[]).includes(v) ? (v as T) : fallback)
const type = ref<IssueType>(pick(route.query.type, ISSUE_TYPES, 'bug'))
const area = ref<IssueArea>(pick(route.query.area, ISSUE_AREAS, 'launcher'))
const title = ref('')
const description = ref('')
const images = ref<UploadView[]>([])
const busy = ref(false)
const error = ref('')
const editor = shallowRef<{ busy: boolean } | null>(null)

const titleLen = computed(() => [...title.value.trim()].length)
const descLen = computed(() => [...description.value.trim()].length)
const valid = computed(() => titleLen.value >= ISSUE_LIMITS.titleMin && titleLen.value <= ISSUE_LIMITS.titleMax
  && descLen.value >= ISSUE_LIMITS.descriptionMinWeb && descLen.value <= ISSUE_LIMITS.descriptionMax)

async function submit() {
  if (busy.value) return
  if (descLen.value < ISSUE_LIMITS.descriptionMinWeb) {
    error.value = fill(it.value.new.minDescription, { n: ISSUE_LIMITS.descriptionMinWeb })
    return
  }
  busy.value = true
  error.value = ''
  try {
    const r = await api<{ issue: IssueDetail }>('/v1/issues', {
      method: 'POST',
      body: { type: type.value, area: area.value, title: title.value, description: description.value, attachments: images.value.map((x) => x.id) },
    })
    await router.push(lp(`/issues/${r.issue.number}`))
  } catch (e) {
    error.value = errorText(e)
  } finally {
    busy.value = false
  }
}

useHead({ title: () => it.value.seo.new, meta: [{ name: 'robots', content: 'noindex, nofollow' }] })
</script>

<template>
  <div class="mx-auto max-w-5xl px-4 pt-8 pb-6 sm:px-6">
    <NuxtLink :to="lp('/issues')" class="inline-flex items-center gap-1.5 text-sm text-base-400 hover:text-base-100">
      <SiteIcon name="back" class="size-4" />{{ it.detail.back }}
    </NuxtLink>
    <h1 class="display mt-4 text-4xl leading-tight text-base-50">{{ it.new.title }}</h1>
    <p class="mt-2 max-w-2xl text-base-400">{{ it.new.lead }}</p>

    <p v-if="!loaded" class="mt-10 text-base-400">{{ it.common.loading }}</p>
    <section v-else-if="!account" class="card mt-8 max-w-xl p-6">
      <p class="text-base-200">{{ it.new.signInLead }}</p>
      <a :href="loginUrl()" class="btn btn-primary mt-4"><SiteIcon name="microsoft" class="size-4" />{{ it.common.signIn }}</a>
    </section>

    <div v-else class="layout mt-8">
      <form class="space-y-6" data-testid="new-issue-form" @submit.prevent="submit">
        <fieldset>
          <legend class="label">{{ it.new.type }}</legend>
          <div class="grid gap-2 sm:grid-cols-2">
            <label v-for="t in ISSUE_TYPES" :key="t" class="choice" :data-t="t" :class="{ on: type === t }">
              <input v-model="type" type="radio" name="type" :value="t" class="sr-only" />
              <SiteIcon :name="t === 'bug' ? 'bug' : 'bolt'" class="size-5 shrink-0" />
              <span>
                <strong class="block text-base-50">{{ it.types[t] }}</strong>
                <span class="text-xs text-base-400">{{ it.new.typeHint[t] }}</span>
              </span>
            </label>
          </div>
        </fieldset>

        <fieldset>
          <legend class="label">{{ it.new.area }}</legend>
          <div class="flex flex-wrap gap-2">
            <label v-for="a in ISSUE_AREAS" :key="a" class="seg-choice" :class="{ on: area === a }">
              <input v-model="area" type="radio" name="area" :value="a" class="sr-only" />
              <SiteIcon :name="a === 'launcher' ? 'download' : a === 'client' ? 'client' : 'globe'" class="size-4" />{{ it.areas[a] }}
            </label>
          </div>
          <p v-if="area === 'client' && type === 'bug'" class="mt-2 flex items-start gap-2 text-xs text-lamp-300">
            <SiteIcon name="client" class="mt-0.5 size-3.5 shrink-0" />{{ it.new.clientHint }}
          </p>
        </fieldset>

        <div>
          <label for="issue-title" class="label">{{ it.new.titleLabel }}</label>
          <input id="issue-title" v-model="title" class="field" :maxlength="ISSUE_LIMITS.titleMax" :placeholder="it.new.titlePlaceholder[type]" required />
          <p class="mt-1 text-right text-[11px] text-base-400 tabular-nums">{{ fill(it.editor.chars, { n: titleLen, max: ISSUE_LIMITS.titleMax }) }}</p>
        </div>

        <div>
          <label for="issue-body" class="label">{{ it.new.description }}</label>
          <IssueEditor
            id="issue-body"
            ref="editor"
            v-model="description"
            v-model:images="images"
            :placeholder="it.new.placeholder[type]"
            :max="ISSUE_LIMITS.descriptionMax"
            :max-images="ISSUE_LIMITS.uploadsPerIssue"
            :rows="12"
          />
        </div>

        <p v-if="error" role="alert" class="text-sm text-redstone-300">{{ error }}</p>
        <div class="flex justify-end gap-2">
          <NuxtLink :to="lp('/issues')" class="btn btn-ghost">{{ it.common.cancel }}</NuxtLink>
          <button type="submit" class="btn btn-primary" :disabled="busy || !valid || !!editor?.busy" data-testid="submit-issue">
            <SiteIcon name="plus" class="size-4" />{{ it.new.submit }}
          </button>
        </div>
      </form>

      <aside class="card h-fit p-5 text-sm">
        <h2 class="font-semibold text-base-50">{{ it.new.guidelinesTitle }}</h2>
        <ul class="mt-3 space-y-2 text-base-300">
          <li v-for="(g, i) in it.new.guidelines" :key="i" class="flex gap-2"><SiteIcon name="check" class="mt-0.5 size-4 shrink-0 text-ok" />{{ g }}</li>
        </ul>
      </aside>
    </div>
  </div>
</template>

<style scoped>
.layout {
  display: grid;
  gap: 1.5rem;
}
@media (min-width: 900px) {
  .layout {
    grid-template-columns: minmax(0, 1fr) 17rem;
  }
}
.choice {
  display: flex;
  align-items: center;
  gap: 0.75rem;
  padding: 0.8rem 0.9rem;
  border-radius: 0.6rem;
  border: 1px solid var(--color-base-700);
  background: var(--color-base-900);
  color: var(--color-base-400);
  cursor: pointer;
  transition: border-color 0.12s, background-color 0.12s;
}
.choice:hover {
  border-color: var(--color-base-600);
}
.choice.on {
  border-color: var(--color-redstone-500);
  background: color-mix(in srgb, var(--color-redstone-500) 10%, var(--color-base-900));
  color: var(--color-redstone-300);
}
.choice.on[data-t='feature'] {
  border-color: var(--color-lamp-400);
  background: color-mix(in srgb, var(--color-lamp-400) 10%, var(--color-base-900));
  color: var(--color-lamp-300);
}
.choice:focus-within,
.seg-choice:focus-within {
  outline: 2px solid var(--color-redstone-400);
  outline-offset: 2px;
}
.seg-choice {
  display: inline-flex;
  align-items: center;
  gap: 0.45rem;
  padding: 0.5rem 0.85rem;
  border-radius: 999px;
  background: var(--color-base-800);
  font-size: 0.875rem;
  color: var(--color-base-200);
  cursor: pointer;
}
.seg-choice.on {
  background: var(--color-base-50);
  color: var(--color-base-950);
}
</style>
