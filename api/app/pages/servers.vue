<script setup lang="ts">
import { breadcrumbLd } from '#shared/seo'
import { docsPageUrl } from '#shared/docs'
import { SERVER_RULES_CONFIG_EXAMPLE, type ServerPlatformId } from '~/utils/servers-i18n'

// „Für Server“: TRS Server Rules (Plugin/Server-Mod) – was es kann, Plattformen, Download aus dem Release-Kanal
// `server-plugin`, Beispiel-Config und der offen dokumentierte Team-Bypass (`serverrules.bypass`).
const { m } = useLang()
const { s, lang } = useServersText()
const lp = useLocalePath()
const siteUrl = useSiteUrl()

usePageSeo(() => ({
  path: '/servers',
  title: s.value.seo.title,
  description: s.value.seo.description,
  jsonLd: [
    breadcrumbLd(siteUrl, lang.value, [
      { name: m.value.nav.home, path: '/' },
      { name: m.value.nav.servers, path: '/servers' },
    ]),
  ],
}))

const docsLink = computed(() => docsPageUrl(lang.value, '/developers/server-rules'))
/** Anker des Abschnitts zum Team-Bypass in der Doku (Überschrift je Sprache). */
const BYPASS_ANCHOR = { en: 'trs-team-bypass', de: 'trs-team-bypass', es: 'bypass-del-equipo-trs' } as const
const modrinth = SERVER_PLUGIN_MODRINTH_URL
const platformIcon: Record<ServerPlatformId, string> = { bukkit: 'server', velocity: 'swap', bungee: 'swap', sponge: 'server', mods: 'blocks' }

const copied = ref(false)
async function copyConfig() {
  try {
    await navigator.clipboard.writeText(SERVER_RULES_CONFIG_EXAMPLE)
    copied.value = true
    setTimeout(() => (copied.value = false), 1600)
  } catch {
    // Zwischenablage gesperrt – die Config steht ja da.
  }
}
</script>

<template>
  <div class="mx-auto max-w-6xl px-4 pt-14 sm:px-6">
    <header class="max-w-3xl">
      <p class="text-xs font-semibold tracking-[0.2em] text-lamp-300 uppercase">{{ s.kicker }}</p>
      <h1 class="display mt-3 text-5xl leading-tight text-base-50">{{ s.title }}</h1>
      <p class="mt-4 text-lg leading-relaxed text-base-400">{{ s.lead }}</p>
      <div class="mt-7 flex flex-wrap gap-3">
        <a :href="SERVER_PLUGIN_RELEASE_PAGE" class="btn btn-primary pixel-corners h-12 px-5 text-base" style="--notch: 3px" rel="noopener" target="_blank">
          <SiteIcon name="download" class="size-5" />{{ s.download }}
        </a>
        <a v-if="modrinth" :href="modrinth" class="btn btn-ghost h-12 px-5" rel="noopener" target="_blank">
          <SiteIcon name="external" class="size-4" />{{ s.modrinth }}
        </a>
        <a :href="docsLink" class="btn btn-ghost h-12 px-5">
          <SiteIcon name="docs" class="size-4" />{{ s.docs }}
        </a>
      </div>
      <p class="mt-3 text-sm text-base-400">{{ s.releaseNote }}</p>
    </header>

    <!-- Was es kann -->
    <section class="mt-16" aria-labelledby="what-title">
      <h2 id="what-title" class="heading text-3xl">{{ s.features.title }}</h2>
      <ul class="mt-6 grid gap-4 sm:grid-cols-2 lg:grid-cols-3" data-fx="stagger">
        <li v-for="f in s.features.items" :key="f.title" class="card p-5">
          <span class="icon-tile"><SiteIcon :name="f.icon" class="size-5" /></span>
          <h3 class="mt-4 font-semibold text-base-50">{{ f.title }}</h3>
          <p class="mt-1.5 text-sm leading-relaxed text-base-400">{{ f.text }}</p>
        </li>
      </ul>
    </section>

    <!-- Plattformen -->
    <section id="platforms" class="mt-16 scroll-mt-24" aria-labelledby="platforms-title">
      <h2 id="platforms-title" class="heading text-3xl">{{ s.platforms.title }}</h2>
      <p class="mt-2 max-w-2xl text-base-400">{{ s.platforms.lead }}</p>
      <ul class="mt-6 divide-y divide-base-800 border-y border-base-800">
        <li v-for="p in s.platforms.items" :key="p.id" class="grid gap-3 py-4 md:grid-cols-[minmax(0,1.2fr)_minmax(0,1fr)_auto] md:items-center">
          <div class="flex min-w-0 items-start gap-3">
            <span class="icon-tile shrink-0"><SiteIcon :name="platformIcon[p.id]" class="size-5" /></span>
            <div class="min-w-0">
              <p class="font-semibold text-base-50">{{ p.name }}</p>
              <p class="text-sm text-base-400">{{ p.versions }}</p>
            </div>
          </div>
          <dl class="grid min-w-0 gap-1 text-xs">
            <div class="flex min-w-0 gap-2"><dt class="w-14 shrink-0 text-base-600">{{ s.platforms.file }}</dt><dd class="mono min-w-0 break-all text-base-200">{{ p.file }}</dd></div>
            <div class="flex min-w-0 gap-2"><dt class="w-14 shrink-0 text-base-600">{{ s.platforms.config }}</dt><dd class="mono min-w-0 break-all text-base-200">{{ p.config }}</dd></div>
          </dl>
          <a :href="SERVER_PLUGIN_RELEASE_PAGE" class="btn btn-ghost justify-self-start" rel="noopener" target="_blank">
            <SiteIcon name="download" class="size-4" />Jar
          </a>
        </li>
      </ul>
      <p class="mt-3 text-sm text-base-400">{{ s.platforms.proxy }}</p>
    </section>

    <!-- Konfiguration + So funktioniert es -->
    <div class="mt-16 grid gap-10 lg:grid-cols-[minmax(0,1.15fr)_minmax(0,1fr)]">
      <section id="config" class="min-w-0 scroll-mt-24" aria-labelledby="config-title">
        <div class="flex items-end justify-between gap-3">
          <h2 id="config-title" class="heading text-3xl">{{ s.config.title }}</h2>
          <button type="button" class="btn-icon" :aria-label="copied ? m.common.copied : m.common.copy" @click="copyConfig">
            <SiteIcon :name="copied ? 'check' : 'copy'" class="size-4" />
          </button>
        </div>
        <p class="mt-2 text-base-400">{{ s.config.lead }}</p>
        <pre class="yaml mt-4"><code>{{ SERVER_RULES_CONFIG_EXAMPLE }}</code></pre>
        <h3 class="mt-6 font-semibold text-base-50">{{ s.config.commandsTitle }}</h3>
        <ul class="mt-2 grid gap-2">
          <li v-for="c in s.config.commands" :key="c.cmd" class="text-sm text-base-400">
            <code class="mono text-lamp-300">{{ c.cmd }}</code> – {{ c.text }}
          </li>
        </ul>
        <p class="mt-2 text-xs text-base-600">{{ s.config.permission }}</p>
      </section>

      <section class="min-w-0" aria-labelledby="how-title">
        <h2 id="how-title" class="heading text-3xl">{{ s.how.title }}</h2>
        <ol class="mt-5 grid gap-4">
          <li v-for="(step, i) in s.how.steps" :key="step" class="flex gap-4">
            <span class="step-no" aria-hidden="true">{{ i + 1 }}</span>
            <p class="pt-1 text-sm leading-relaxed text-base-300">{{ step }}</p>
          </li>
        </ol>
        <p class="note mt-6 text-sm text-base-400">
          <SiteIcon name="shield" class="mt-0.5 size-4 shrink-0 text-lamp-300" />
          <span>{{ s.how.honest }}</span>
        </p>
      </section>
    </div>

    <!-- Team-Bypass: bewusst offen erklärt -->
    <section id="team-bypass" class="team card mt-16 scroll-mt-24 p-6 sm:p-8" aria-labelledby="team-title" data-fx="rise">
      <div class="flex items-center gap-3">
        <span class="icon-tile shrink-0"><SiteIcon name="unlock" class="size-5" /></span>
        <h2 id="team-title" class="heading text-2xl">{{ s.team.title }}</h2>
      </div>
      <p class="mt-4 max-w-3xl leading-relaxed text-base-300">{{ s.team.text }}</p>
      <ul class="mt-4 flex max-w-3xl flex-col gap-2.5">
        <li v-for="p in s.team.points" :key="p" class="flex gap-3 text-sm leading-relaxed text-base-400">
          <span class="dot" aria-hidden="true" />
          <span>{{ p }}</span>
        </li>
      </ul>
      <a :href="`${docsLink}#${BYPASS_ANCHOR[lang]}`" class="btn btn-ghost mt-6">
        <SiteIcon name="docs" class="size-4" />{{ s.docs }}
      </a>
    </section>

    <section class="cta card mt-10 flex flex-wrap items-center justify-between gap-6 p-6 sm:p-8" aria-labelledby="cta-title">
      <div>
        <h2 id="cta-title" class="heading text-2xl">{{ s.cta.title }}</h2>
        <p class="mt-2 text-base-400">{{ s.cta.text }}</p>
      </div>
      <div class="flex flex-wrap gap-3">
        <a :href="DISCORD_URL" class="btn btn-primary h-12 px-5" rel="noopener" target="_blank"><SiteIcon name="discord" class="size-4" />Discord</a>
        <NuxtLink :to="lp('/issues')" class="btn btn-ghost h-12 px-5"><SiteIcon name="bug" class="size-4" />Issues</NuxtLink>
      </div>
    </section>
  </div>
</template>

<style scoped>
.icon-tile {
  display: grid;
  place-items: center;
  width: 2.5rem;
  height: 2.5rem;
  color: var(--color-base-50);
  background: var(--color-base-800);
  box-shadow: inset 0 -3px 0 var(--color-redstone-600);
}
.mono {
  font-family: var(--font-mono);
}
.yaml {
  font-family: var(--font-mono);
  font-size: 0.8rem;
  line-height: 1.55;
  padding: 1rem 1.1rem;
  border-radius: 0.6rem;
  background: var(--color-base-950);
  color: var(--color-base-200);
  border: 1px solid var(--color-base-800);
  overflow-x: auto;
  white-space: pre;
}
.step-no {
  display: grid;
  flex-shrink: 0;
  place-items: center;
  width: 2rem;
  height: 2rem;
  font-weight: 700;
  color: var(--color-redstone-300);
  background: color-mix(in srgb, var(--color-redstone-900) 60%, var(--color-base-900));
  box-shadow: inset 0 0 0 1px var(--color-redstone-600);
}
.note {
  display: flex;
  gap: 0.6rem;
  padding-top: 1.25rem;
  border-top: 1px dashed var(--color-base-700);
}
.dot {
  flex-shrink: 0;
  width: 0.45rem;
  height: 0.45rem;
  margin-top: 0.5rem;
  background: var(--color-lamp-400);
  box-shadow: 0 0 8px -1px var(--color-lamp-400);
}
.team {
  border-color: color-mix(in srgb, var(--color-lamp-400) 35%, var(--color-base-800));
  background:
    radial-gradient(70% 120% at 100% 0%, color-mix(in srgb, var(--color-lamp-900) 35%, transparent), transparent 70%),
    var(--color-base-900);
}
.cta {
  background:
    radial-gradient(80% 140% at 90% 50%, color-mix(in srgb, var(--color-redstone-900) 55%, transparent), transparent 70%),
    var(--color-base-900);
}
</style>
