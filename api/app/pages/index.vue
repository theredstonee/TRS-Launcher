<script setup lang="ts">
import { organizationLd, websiteLd } from '#shared/seo'

const { lang, m, fill } = useLang()
const lp = useLocalePath()
const siteUrl = useSiteUrl()
const os = useVisitorOs()
const { data: releaseData } = await useRelease()
const { data: blogData } = await useBlog()
const { data: capeData } = await useCapes()

const release = computed(() => releaseData.value?.release ?? null)
const posts = computed(() => (blogData.value?.posts ?? []).slice(0, 3))

usePageSeo(() => ({
  path: '/',
  title: m.value.seo.home.title,
  description: m.value.seo.home.description,
  image: { url: '/og.png', width: 1260, height: 660, alt: m.value.seo.ogAlt },
  jsonLd: [
    organizationLd(siteUrl),
    websiteLd(siteUrl, m.value.seo.home.description),
    launcherLd(siteUrl, lang.value, m.value, release.value, blogData.value?.posts?.[0]),
  ],
}))
// Bilder der Update-Karten kommen von GitHub.
useHead({ link: [{ rel: 'preconnect', href: 'https://raw.githubusercontent.com' }] })
const capes = computed(() => capeData.value?.capes ?? [])
const showcase = computed(() => capes.value.slice(0, 8))
const preview = ref<SiteCape | null>(null)
watchEffect(() => {
  if (!preview.value && capes.value.length) preview.value = capes.value.find((c) => c.frames > 1) ?? capes.value[0]!
})

/** Großer Knopf: passende Datei fürs System, sonst die Download-Seite. */
const primary = computed(() => {
  const r = release.value
  if (os.value === 'windows') {
    const a = assetFor(r, 'windows')
    if (a) return { href: a.url, label: fill(m.value.home.download, { os: 'Windows' }), icon: 'windows', external: true }
  }
  if (os.value === 'linux') return { href: lp('/download#linux'), label: fill(m.value.home.download, { os: 'Linux' }), icon: 'linux', external: false }
  return { href: lp('/download'), label: m.value.home.downloadGeneric, icon: 'download', external: false }
})

// Die Hauptleitung der Szene endet am Download-Knopf; beim Zeigen darauf leuchtet die Lampe.
const lamp = shallowRef<HTMLElement | null>(null)
const powered = ref(false)
</script>

<template>
  <div>
    <section class="hero relative isolate overflow-hidden" :class="{ 'hero-on': powered }">
      <ClientOnly>
        <RedstoneScene :mode="powered ? 'running' : 'idle'" :anchor="lamp">
          <div class="scrim" />
        </RedstoneScene>
      </ClientOnly>

      <div class="relative mx-auto flex h-full max-w-6xl flex-col justify-end gap-8 px-4 pt-16 pb-12 sm:px-6">
        <!-- Die Zeile darüber gehört zur Überschrift (sichtbar, gleiche Optik wie vorher) – so steht „Minecraft-Launcher“ in der h1. -->
        <h1>
          <span class="block text-xs font-semibold tracking-[0.2em] text-lamp-300 uppercase">{{ m.home.kicker }}</span>
          <span class="hero-title display mt-3 block max-w-3xl text-5xl leading-[1.02] text-balance text-base-50 sm:text-6xl lg:text-7xl">
            {{ m.home.title }}
          </span>
        </h1>
        <div class="flex flex-wrap items-end justify-between gap-x-10 gap-y-6">
          <p class="max-w-xl text-base leading-relaxed text-base-200 sm:text-lg">{{ m.home.lead }}</p>
          <div class="flex w-full flex-col gap-2 sm:w-auto">
            <div ref="lamp" class="flex flex-wrap gap-2">
              <a
                v-if="primary.external"
                :href="primary.href"
                class="btn btn-primary cta pixel-corners"
                style="--notch: 3px"
                @mouseenter="powered = true"
                @mouseleave="powered = false"
                @focus="powered = true"
                @blur="powered = false"
              >
                <SiteIcon :name="primary.icon" class="size-5" />{{ primary.label }}
              </a>
              <NuxtLink
                v-else
                :to="primary.href"
                class="btn btn-primary cta pixel-corners"
                style="--notch: 3px"
                @mouseenter="powered = true"
                @mouseleave="powered = false"
                @focus="powered = true"
                @blur="powered = false"
              >
                <SiteIcon :name="primary.icon" class="size-5" />{{ primary.label }}
              </NuxtLink>
              <NuxtLink :to="lp('/download')" class="btn btn-ghost h-14 px-5 text-base">{{ m.home.allDownloads }}</NuxtLink>
            </div>
            <p class="text-xs text-base-400">
              <span v-if="release">{{ fill(m.home.latest, { version: release.version }) }} · </span>{{ m.home.free }}
            </p>
          </div>
        </div>
      </div>
    </section>

    <!-- Große Bild-Kacheln: echter Screenshot, kleine Zeile, riesiges Wort -->
    <section class="relative z-10 mx-auto -mt-6 max-w-6xl px-4 sm:px-6" aria-label="Highlights">
      <ul class="tiles">
        <li v-for="tile in m.home.tiles" :key="tile.title">
          <NuxtLink :to="lp(tile.to)" class="tile">
            <img :src="`/shots/${tile.img}`" alt="" loading="lazy" class="tile-img" />
            <span class="tile-text">
              <span class="tile-kicker">{{ tile.kicker }}</span>
              <span class="tile-title">{{ tile.title }}</span>
            </span>
          </NuxtLink>
        </li>
      </ul>
    </section>

    <!-- Funktionen -->
    <section id="features" class="mx-auto max-w-6xl px-4 pt-16 sm:px-6" aria-labelledby="features-title">
      <h2 id="features-title" class="heading text-3xl">{{ m.home.featuresTitle }}</h2>
      <ul class="mt-8 grid gap-x-8 gap-y-9 sm:grid-cols-2 lg:grid-cols-4">
        <li v-for="f in m.home.features" :key="f.title" class="feature">
          <span class="feature-icon"><SiteIcon :name="f.icon" class="size-5" /></span>
          <h3 class="mt-4 font-semibold text-base-50">{{ f.title }}</h3>
          <p class="mt-1.5 text-sm leading-relaxed text-base-400">{{ f.text }}</p>
        </li>
      </ul>
      <NuxtLink :to="lp('/features')" class="btn btn-ghost mt-8">
        {{ m.home.featuresCta }} <SiteIcon name="arrow" class="size-4" />
      </NuxtLink>
    </section>

    <!-- Vergleich -->
    <section class="mx-auto max-w-6xl px-4 pt-20 sm:px-6" aria-labelledby="compare-title">
      <h2 id="compare-title" class="heading text-3xl">{{ m.home.compare.title }}</h2>
      <p class="mt-3 max-w-2xl text-base-400">{{ m.home.compare.lead }}</p>
      <div class="compare-wrap mt-8">
        <table class="compare">
          <thead>
            <tr>
              <th scope="col"><span class="sr-only">Feature</span></th>
              <th v-for="(c, i) in m.home.compare.columns" :key="c" scope="col" :class="{ 'col-trs': i === 0 }">{{ c }}</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="row in m.home.compare.rows" :key="row.label">
              <th scope="row">{{ row.label }}</th>
              <td v-for="(v, i) in row.values" :key="i" :class="{ 'col-trs': i === 0 }">
                <span class="mark" :class="`mark-${v}`">
                  <SiteIcon v-if="v === 'yes'" name="check" class="size-4" />
                  <span v-else-if="v === 'partial'" class="mark-dot" />
                  <SiteIcon v-else name="close" class="size-3.5" />
                  <span class="sr-only">{{ v === 'yes' ? m.home.compare.yes : v === 'partial' ? m.home.compare.partial : m.home.compare.no }}</span>
                </span>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
      <p class="mt-3 flex flex-wrap gap-x-5 gap-y-1 text-xs text-base-600">
        <span class="inline-flex items-center gap-1.5"><span class="mark mark-yes small"><SiteIcon name="check" class="size-3" /></span>{{ m.home.compare.yes }}</span>
        <span class="inline-flex items-center gap-1.5"><span class="mark mark-partial small"><span class="mark-dot" /></span>{{ m.home.compare.partial }}</span>
        <span class="inline-flex items-center gap-1.5"><span class="mark mark-no small"><SiteIcon name="close" class="size-2.5" /></span>{{ m.home.compare.no }}</span>
        <span>{{ m.home.compare.note }}</span>
      </p>
    </section>

    <!-- Umhänge -->
    <section v-if="capes.length" class="mx-auto max-w-6xl px-4 pt-20 sm:px-6" aria-labelledby="capes-title">
      <div class="capes-band card overflow-hidden">
        <div class="grid items-center gap-6 md:grid-cols-[1fr_20rem]">
          <div class="p-6 sm:p-8">
            <h2 id="capes-title" class="heading text-3xl">{{ m.home.capesTitle }}</h2>
            <p class="mt-3 max-w-lg text-base-400">{{ m.home.capesText }}</p>
            <ul class="mt-6 flex flex-wrap gap-3">
              <li v-for="c in showcase" :key="c.id">
                <button
                  type="button"
                  class="cape-pick"
                  :class="{ 'cape-pick-on': preview?.id === c.id }"
                  :title="c.name"
                  :aria-label="c.name"
                  :aria-pressed="preview?.id === c.id"
                  @click="preview = c"
                >
                  <ClientOnly>
                    <CapeThumb :texture="c.url" :scale="c.scale" :frames="c.frames" :frame-time-ms="c.frameTimeMs" :width="30" />
                  </ClientOnly>
                </button>
              </li>
            </ul>
            <NuxtLink :to="lp('/capes')" class="btn btn-ghost mt-6">
              {{ m.home.capesCta }} <SiteIcon name="arrow" class="size-4" />
            </NuxtLink>
          </div>
          <div class="viewer-stage">
            <ClientOnly><CapeViewer :cape="preview" :height="340" /></ClientOnly>
            <p v-if="preview" class="absolute inset-x-0 bottom-3 text-center text-sm font-medium text-base-200">{{ preview.name }}</p>
          </div>
        </div>
      </div>
    </section>

    <!-- Neueste Updates -->
    <section v-if="posts.length" class="mx-auto max-w-6xl px-4 pt-20 sm:px-6" aria-labelledby="blog-title">
      <div class="flex items-end justify-between gap-4">
        <h2 id="blog-title" class="heading text-3xl">{{ m.home.blogTitle }}</h2>
        <NuxtLink :to="lp('/blog')" class="text-sm text-base-400 hover:text-base-50">{{ m.home.blogCta }}</NuxtLink>
      </div>
      <div class="mt-8 grid gap-5" :class="{ 'md:grid-cols-2': posts.length === 2, 'md:grid-cols-3': posts.length > 2 }">
        <BlogCard v-for="p in posts" :key="p.version" :post="p" :featured="posts.length === 1" />
      </div>
    </section>
  </div>
</template>

<style scoped>
.hero {
  min-height: 34rem;
  height: min(78vh, 44rem);
}
.scrim {
  position: absolute;
  inset: 0;
  pointer-events: none;
  background:
    linear-gradient(90deg, color-mix(in srgb, var(--color-base-950) 88%, transparent) 0%, color-mix(in srgb, var(--color-base-950) 60%, transparent) 35%, color-mix(in srgb, var(--color-base-950) 18%, transparent) 70%, transparent 90%),
    linear-gradient(0deg, var(--color-base-950) 0%, color-mix(in srgb, var(--color-base-950) 45%, transparent) 25%, transparent 55%),
    radial-gradient(140% 120% at 70% 40%, transparent 55%, color-mix(in srgb, var(--color-base-950) 70%, transparent) 100%);
}
.hero-title {
  text-shadow: 0 3px 0 color-mix(in srgb, var(--color-base-950) 80%, transparent);
}
.cta {
  height: 3.5rem;
  padding-inline: 1.5rem;
  font-size: 1rem;
  font-weight: 600;
}
.hero-on .cta {
  background: var(--color-lamp-400);
  color: var(--color-base-950);
  box-shadow:
    inset 0 1px 0 rgb(255 255 255 / 0.35),
    0 0 28px -4px var(--color-lamp-400);
}

.feature-icon {
  display: grid;
  place-items: center;
  width: 2.75rem;
  height: 2.75rem;
  color: var(--color-redstone-300);
  background: var(--color-base-900);
  box-shadow:
    inset 0 0 0 1px var(--color-base-800),
    inset 0 -3px 0 color-mix(in srgb, var(--color-redstone-600) 60%, transparent);
}
.feature:hover .feature-icon {
  color: var(--color-lamp-300);
  box-shadow:
    inset 0 0 0 1px var(--color-base-700),
    inset 0 -3px 0 var(--color-lamp-400),
    0 0 18px -6px var(--color-lamp-400);
}

.tiles {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  overflow: hidden;
  border-radius: 1rem;
  border: 1px solid var(--color-base-800);
  box-shadow: 0 30px 60px -30px rgb(0 0 0 / 0.8);
}
@media (min-width: 900px) {
  .tiles {
    grid-template-columns: repeat(4, minmax(0, 1fr));
  }
}
.tile {
  position: relative;
  display: grid;
  place-items: center;
  height: 15rem;
  overflow: hidden;
  isolation: isolate;
  text-align: center;
}
.tile-img {
  position: absolute;
  inset: 0;
  z-index: -2;
  width: 100%;
  height: 100%;
  object-fit: cover;
  filter: blur(3px) saturate(1.1);
  scale: 1.08;
  transition: filter 0.35s ease, scale 0.35s ease;
}
.tile::after {
  content: '';
  position: absolute;
  inset: 0;
  z-index: -1;
  background: linear-gradient(to top, rgb(12 11 14 / 0.85), rgb(12 11 14 / 0.35));
  transition: background 0.35s ease;
}
.tile:hover .tile-img,
.tile:focus-visible .tile-img {
  filter: blur(0) saturate(1.15);
  scale: 1.02;
}
.tile:hover::after {
  background: linear-gradient(to top, rgb(12 11 14 / 0.8), rgb(12 11 14 / 0.15));
}
.tile-text {
  display: flex;
  flex-direction: column;
  gap: 0.4rem;
  padding: 1rem;
}
.tile-kicker {
  font-size: 0.7rem;
  font-weight: 700;
  font-style: italic;
  letter-spacing: 0.2em;
  text-transform: uppercase;
  color: var(--color-lamp-300);
}
.tile-title {
  font-family: var(--font-display);
  font-size: clamp(1.9rem, 3vw, 2.6rem);
  line-height: 0.95;
  text-transform: uppercase;
  color: var(--color-base-50);
  text-shadow: 0 3px 0 rgb(12 11 14 / 0.7);
}

.compare-wrap {
  overflow-x: auto;
  border-radius: 0.75rem;
  border: 1px solid var(--color-base-800);
  background: var(--color-base-900);
}
.compare {
  width: 100%;
  min-width: 40rem;
  border-collapse: collapse;
  font-size: 0.9rem;
}
.compare th,
.compare td {
  padding: 0.85rem 1rem;
  border-bottom: 1px solid var(--color-base-800);
}
.compare tbody tr:last-child th,
.compare tbody tr:last-child td {
  border-bottom: 0;
}
.compare thead th {
  font-size: 0.8rem;
  font-weight: 600;
  color: var(--color-base-400);
  text-align: center;
}
.compare tbody th {
  text-align: left;
  font-weight: 500;
  color: var(--color-base-200);
}
.compare td {
  text-align: center;
}
.compare .col-trs {
  background: color-mix(in srgb, var(--color-redstone-500) 9%, transparent);
  box-shadow: inset 1px 0 0 color-mix(in srgb, var(--color-redstone-500) 40%, transparent), inset -1px 0 0 color-mix(in srgb, var(--color-redstone-500) 40%, transparent);
}
.compare thead .col-trs {
  font-family: var(--font-display);
  font-size: 1rem;
  color: var(--color-base-50);
}
.mark {
  display: inline-grid;
  place-items: center;
  width: 1.6rem;
  height: 1.6rem;
}
.mark.small {
  width: 1.1rem;
  height: 1.1rem;
}
.mark-yes {
  color: #0d0d10;
  background: var(--color-ok);
}
.mark-no {
  color: var(--color-base-400);
  background: var(--color-base-800);
}
.mark-partial {
  background: var(--color-lamp-900);
}
.mark-dot {
  width: 40%;
  height: 40%;
  background: var(--color-lamp-400);
}

.capes-band {
  background:
    radial-gradient(80% 120% at 85% 50%, color-mix(in srgb, var(--color-redstone-900) 55%, transparent), transparent 70%),
    var(--color-base-900);
}
.viewer-stage {
  position: relative;
  height: 100%;
  min-height: 340px;
  background-image: var(--deepslate);
  background-size: 48px 48px;
}
.cape-pick {
  display: grid;
  place-items: center;
  padding: 0.4rem;
  border-radius: 0.5rem;
  background: var(--color-base-850);
  box-shadow: inset 0 0 0 1px var(--color-base-700);
  transition: transform 0.15s, box-shadow 0.15s;
}
.cape-pick:hover {
  transform: translateY(-2px);
}
.cape-pick-on {
  box-shadow:
    inset 0 0 0 2px var(--color-lamp-400),
    0 0 16px -6px var(--color-lamp-400);
}
</style>
