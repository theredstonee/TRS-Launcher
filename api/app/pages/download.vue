<script setup lang="ts">
const { m, fill, date } = useLang()
const { data } = await useRelease()
const release = computed(() => data.value?.release ?? null)
useHead({ title: () => m.value.nav.download })

const windows = computed(() => assetFor(release.value, 'windows'))
const linux = computed(() =>
  (['appimage', 'deb', 'rpm'] as const).map((p) => ({
    platform: p,
    label: p === 'appimage' ? m.value.download.appimage : p === 'deb' ? m.value.download.deb : m.value.download.rpm,
    asset: assetFor(release.value, p),
  })),
)

function mb(bytes: number): string {
  return fill(m.value.download.size, { size: (bytes / 1024 / 1024).toFixed(0) })
}

/** Das AUR-Paket baut auf der AppImage/.deb auf – erst anzeigen, wenn es Linux-Dateien gibt. */
const hasLinux = computed(() => linux.value.some((l) => l.asset))
const AUR = 'yay -S trs-launcher-bin'
const copied = ref(false)
async function copyAur() {
  try {
    await navigator.clipboard.writeText(AUR)
    copied.value = true
    setTimeout(() => (copied.value = false), 1600)
  } catch {
    // Zwischenablage gesperrt – der Befehl steht ja da.
  }
}
</script>

<template>
  <div class="mx-auto max-w-6xl px-4 pt-14 sm:px-6">
    <header class="max-w-2xl">
      <h1 class="display text-5xl leading-tight text-base-50">{{ m.download.title }}</h1>
      <p class="mt-3 text-lg text-base-400">{{ m.download.lead }}</p>
      <p v-if="release" class="mt-4 flex flex-wrap items-center gap-2 text-sm">
        <span class="badge bg-redstone-900 px-2.5 py-1 text-xs text-redstone-300">{{ fill(m.download.version, { version: release.version }) }}</span>
        <span v-if="release.publishedAt" class="text-base-400">{{ fill(m.download.published, { date: date(release.publishedAt) }) }}</span>
        <NuxtLink :to="`/blog/${release.version}`" class="text-redstone-300 hover:underline">{{ m.blog.read }}</NuxtLink>
      </p>
      <p v-else class="mt-4 text-sm text-lamp-300">{{ m.download.unavailable }}</p>
    </header>

    <div class="mt-10 grid gap-5 lg:grid-cols-2">
      <!-- Windows -->
      <section id="windows" class="card flex flex-col p-6 sm:p-8" aria-labelledby="win-title">
        <div class="flex items-center gap-3">
          <span class="os-icon"><SiteIcon name="windows" class="size-6" /></span>
          <h2 id="win-title" class="heading text-2xl">{{ m.download.windows }}</h2>
        </div>
        <p class="mt-4 text-base-400">{{ m.download.windowsText }}</p>
        <div class="mt-6 mb-6">
          <a v-if="windows" :href="windows.url" class="btn btn-primary pixel-corners h-12 px-6 text-base" style="--notch: 3px">
            <SiteIcon name="download" class="size-5" />{{ m.download.windowsButton }}
            <span class="text-xs font-normal opacity-80">{{ mb(windows.size) }}</span>
          </a>
          <a v-else :href="RELEASES_URL" class="btn btn-ghost h-12 px-6" rel="noopener">GitHub Releases</a>
        </div>
        <div class="note">
          <h3 class="text-sm font-semibold text-lamp-300">{{ m.download.smartTitle }}</h3>
          <p class="mt-1 text-sm text-base-400">{{ m.download.smartText }}</p>
        </div>
      </section>

      <!-- Linux -->
      <section id="linux" class="card flex flex-col p-6 sm:p-8" aria-labelledby="linux-title">
        <div class="flex items-center gap-3">
          <span class="os-icon"><SiteIcon name="linux" class="size-6" /></span>
          <h2 id="linux-title" class="heading text-2xl">{{ m.download.linux }}</h2>
        </div>
        <p class="mt-4 text-base-400">{{ m.download.linuxText }}</p>

        <ul class="mt-6 divide-y divide-base-800 border-y border-base-800">
          <li v-for="l in linux" :key="l.platform" class="flex flex-wrap items-center gap-3 py-3">
            <div class="min-w-0 flex-1">
              <p class="font-medium text-base-50">{{ l.label }}</p>
              <p v-if="l.platform === 'appimage'" class="text-xs text-base-400">{{ m.download.appimageText }}</p>
            </div>
            <a v-if="l.asset" :href="l.asset.url" class="btn btn-ghost">
              <SiteIcon name="download" class="size-4" />{{ mb(l.asset.size) }}
            </a>
            <span v-else class="text-xs text-base-600">{{ m.download.notYet }}</span>
          </li>
          <li class="py-3">
            <p class="font-medium text-base-50">{{ m.download.aur }}</p>
            <p v-if="!hasLinux" class="mt-1 text-xs text-base-600">{{ m.download.notYet }}</p>
            <div v-else class="mt-2 flex items-center gap-2">
              <code class="cmd flex-1">{{ AUR }}</code>
              <button type="button" class="btn-icon" :aria-label="copied ? m.common.copied : m.common.copy" @click="copyAur">
                <SiteIcon :name="copied ? 'check' : 'copy'" class="size-4" />
              </button>
            </div>
          </li>
          <li class="flex flex-wrap items-center gap-3 py-3">
            <p class="flex-1 font-medium text-base-50">{{ m.download.flatpak }}</p>
            <span class="text-xs text-base-400">{{ m.download.flatpakSoon }}</span>
          </li>
        </ul>
      </section>
    </div>

    <div class="mt-10 grid gap-8 md:grid-cols-[1fr_auto] md:items-start">
      <section aria-labelledby="req-title">
        <h2 id="req-title" class="heading text-2xl">{{ m.download.requirementsTitle }}</h2>
        <ul class="mt-4 grid gap-2 sm:grid-cols-2">
          <li v-for="r in m.download.requirements" :key="r" class="flex items-start gap-2.5 text-base-200">
            <span class="mt-2 size-1.5 shrink-0 bg-redstone-400" />{{ r }}
          </li>
        </ul>
      </section>
      <a :href="release?.pageUrl ?? RELEASES_URL" class="btn btn-ghost" rel="noopener" target="_blank">
        <SiteIcon name="github" class="size-4" />{{ m.download.checksums }}
        <SiteIcon name="external" class="size-3.5 opacity-60" />
      </a>
    </div>
  </div>
</template>

<style scoped>
.os-icon {
  display: grid;
  place-items: center;
  width: 3rem;
  height: 3rem;
  color: var(--color-base-50);
  background: var(--color-base-800);
  box-shadow: inset 0 -3px 0 var(--color-redstone-600);
}
.note {
  padding-top: 1.5rem;
  margin-top: auto;
  border-top: 1px dashed var(--color-base-700);
}
.cmd {
  font-family: var(--font-mono);
  font-size: 0.8125rem;
  padding: 0.5rem 0.75rem;
  border-radius: 0.375rem;
  background: var(--color-base-950);
  color: var(--color-lamp-300);
  border: 1px solid var(--color-base-800);
  overflow-x: auto;
  white-space: nowrap;
}
</style>
