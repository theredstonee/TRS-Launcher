import { joinURL } from 'ufo'

// Docus verlinkt `/favicon.ico` ohne Basis-Pfad (die Website hat keins). Stattdessen das TRS-Logo aus /docs/.
export default defineNuxtPlugin(() => {
  const head = injectHead()
  const icon = joinURL(useRuntimeConfig().app.baseURL, 'icon.png')
  head.hooks.hook('tags:resolve', (ctx) => {
    ctx.tags = ctx.tags.filter((tag) => !(tag.tag === 'link' && tag.props.rel === 'icon' && tag.props.href === '/favicon.ico'))
  })
  useHead({
    link: [
      { rel: 'icon', type: 'image/png', href: icon },
      { rel: 'apple-touch-icon', href: icon },
    ],
  })
})
