<script setup lang="ts">
const { lang, setLang } = useLang()
const open = ref(false)
const root = shallowRef<HTMLElement | null>(null)
const current = computed(() => LANGS.find((l) => l.code === lang.value) ?? LANGS[0]!)

const route = useRoute()
const router = useRouter()

function pick(code: Lang) {
  setLang(code)
  open.value = false
  // Steht die Sprache in der Adresse (?lang=), dort mitziehen – sonst zeigt ein Neuladen die alte Sprache.
  if (route.query.lang !== undefined) {
    const query = { ...route.query }
    if (code === 'en') delete query.lang
    else query.lang = code
    void router.replace({ query, hash: route.hash })
  }
}

function onDocClick(e: MouseEvent) {
  if (root.value && !root.value.contains(e.target as Node)) open.value = false
}
onMounted(() => document.addEventListener('click', onDocClick))
onBeforeUnmount(() => document.removeEventListener('click', onDocClick))
</script>

<template>
  <div ref="root" class="relative">
    <button
      type="button"
      class="btn-icon w-auto gap-2 px-2.5"
      :aria-expanded="open"
      aria-haspopup="listbox"
      :aria-label="current.name"
      @click="open = !open"
      @keydown.escape="open = false"
    >
      <img :src="current.flag" alt="" class="h-3.5 w-5 rounded-[2px] object-cover" />
      <span class="text-xs font-semibold uppercase">{{ current.code }}</span>
    </button>
    <ul v-if="open" class="menu right-0 mt-2" role="listbox">
      <li v-for="l in LANGS" :key="l.code">
        <button type="button" class="menu-item" role="option" :aria-selected="l.code === lang" @click="pick(l.code)">
          <img :src="l.flag" alt="" class="h-3.5 w-5 rounded-[2px] object-cover" />
          <span class="flex-1">{{ l.name }}</span>
          <SiteIcon v-if="l.code === lang" name="check" class="size-4 text-redstone-300" />
        </button>
      </li>
    </ul>
  </div>
</template>
