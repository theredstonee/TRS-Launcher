<script setup lang="ts">
import { breadcrumbLd } from '#shared/seo'

const { lang, m } = useLang()
const { data, error } = await useCapes()
const capes = computed(() => data.value?.capes ?? [])
const selected = ref<SiteCape | null>(null)
watchEffect(() => {
  if (!selected.value && capes.value.length) selected.value = capes.value[0]!
})
const siteUrl = useSiteUrl()
usePageSeo(() => ({
  path: '/capes',
  title: m.value.seo.capes.title,
  description: m.value.seo.capes.description,
  image: { url: '/shots/cape-physics.png', width: 854, height: 480, alt: m.value.seo.capes.title },
  jsonLd: [
    breadcrumbLd(siteUrl, lang.value, [
      { name: m.value.nav.home, path: '/' },
      { name: m.value.capes.title, path: '/capes' },
    ]),
  ],
}))

const unlockLabel = (c: SiteCape) => (c.unlock === 'free' ? m.value.capes.free : c.unlock === 'code' ? m.value.capes.code : m.value.capes.admin)
const unlockClass = (c: SiteCape) =>
  c.unlock === 'free' ? 'bg-ok/15 text-ok' : c.unlock === 'code' ? 'bg-lamp-900 text-lamp-300' : 'bg-redstone-900 text-redstone-300'
</script>

<template>
  <div class="mx-auto max-w-6xl px-4 pt-14 sm:px-6">
    <header class="max-w-2xl">
      <h1 class="display text-5xl leading-tight text-base-50">{{ m.capes.title }}</h1>
      <p class="mt-3 text-lg text-base-400">{{ m.capes.lead }}</p>
    </header>

    <p v-if="error" class="mt-10 text-lamp-300">{{ m.common.error }}</p>
    <p v-else-if="!capes.length" class="mt-10 text-base-400">{{ m.capes.empty }}</p>
    <div v-else class="mt-10 grid gap-6 lg:grid-cols-[1fr_22rem]">
      <ul class="grid grid-cols-2 gap-3 sm:grid-cols-3 md:grid-cols-4">
        <li v-for="c in capes" :key="c.id">
          <button
            type="button"
            class="cape-card card"
            :class="{ 'cape-card-on': selected?.id === c.id }"
            :aria-pressed="selected?.id === c.id"
            @click="selected = c"
          >
            <span class="grid h-32 place-items-center">
              <ClientOnly>
                <CapeThumb :texture="c.url" :scale="c.scale" :frames="c.frames" :frame-time-ms="c.frameTimeMs" :width="60" />
                <template #fallback><span class="block h-24 w-15 rounded-sm bg-base-800" /></template>
              </ClientOnly>
            </span>
            <span class="block truncate text-sm font-semibold text-base-50">{{ c.name }}</span>
            <span class="mt-1.5 flex flex-wrap justify-center gap-1">
              <span class="badge" :class="unlockClass(c)">{{ unlockLabel(c) }}</span>
              <span v-if="c.scale > 1" class="badge bg-base-800 text-base-200">HD</span>
              <span v-if="c.frames > 1" class="badge bg-lamp-900 text-lamp-300">{{ m.capes.animated }}</span>
            </span>
          </button>
        </li>
      </ul>

      <aside class="lg:sticky lg:top-24 lg:self-start">
        <div class="card overflow-hidden">
          <div class="stage relative">
            <ClientOnly><CapeViewer :cape="selected" :height="400" /></ClientOnly>
            <p class="absolute top-3 left-4 text-xs tracking-[0.18em] text-base-400 uppercase">{{ m.capes.preview }}</p>
            <p class="absolute right-4 bottom-3 text-xs text-base-600">{{ m.capes.dragHint }}</p>
          </div>
          <div v-if="selected" class="border-t border-base-800 p-5">
            <p class="display text-2xl text-base-50">{{ selected.name }}</p>
            <span class="badge mt-2" :class="unlockClass(selected)">{{ unlockLabel(selected) }}</span>
          </div>
        </div>
        <div class="mt-5 p-1">
          <h2 class="font-semibold text-base-50">{{ m.capes.howTitle }}</h2>
          <p class="mt-1.5 text-sm text-base-400">{{ m.capes.howText }}</p>
        </div>
      </aside>
    </div>
  </div>
</template>

<style scoped>
.cape-card {
  display: block;
  width: 100%;
  padding: 0.75rem 0.75rem 1rem;
  text-align: center;
  transition: transform 0.15s, border-color 0.15s, box-shadow 0.15s;
}
.cape-card:hover {
  transform: translateY(-2px);
  border-color: var(--color-base-700);
}
.cape-card-on {
  border-color: var(--color-lamp-400);
  box-shadow: 0 0 22px -8px var(--color-lamp-400);
}
.stage {
  background-color: var(--color-base-950);
  background-image: var(--deepslate);
  background-size: 48px 48px;
}
</style>
