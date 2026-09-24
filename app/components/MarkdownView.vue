<script setup lang="ts">
// Zeigt Markdown von Modrinth bzw. (mit `html`) HTML von CurseForge an.
// `v-html` bekommt ausschließlich die Ausgabe von `renderMarkdown`/`renderHtml`
// (DOMPurify mit fester Whitelist, siehe utils/markdown.ts).
// Links navigieren nie im Launcher-Fenster, sondern öffnen – nur HTTPS – den Browser.
const props = defineProps<{ source: string | null | undefined; html?: boolean }>()

const rendered = computed(() => (props.html ? renderHtml(props.source) : renderMarkdown(props.source)))
const toasts = useToasts()

function onClick(event: MouseEvent) {
  const link = (event.target as HTMLElement | null)?.closest('a')
  if (!link) return
  event.preventDefault()
  const href = link.getAttribute('href')
  if (href?.startsWith('#')) {
    // Anker innerhalb der Beschreibung.
    const target = href.length > 1 ? (event.currentTarget as HTMLElement).querySelector(`[name="${CSS.escape(href.slice(1))}"]`) : null
    target?.scrollIntoView({ behavior: 'smooth' })
    return
  }
  if (isSafeLink(href)) backend.openExternalUrl(href).catch((e) => toasts.error(e))
}
</script>

<template>
  <!-- eslint-disable-next-line vue/no-v-html -- nur bereinigtes HTML aus renderMarkdown/renderHtml -->
  <div class="prose-md" @click="onClick" v-html="rendered" />
</template>
