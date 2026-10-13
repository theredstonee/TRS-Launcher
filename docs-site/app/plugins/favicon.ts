import { joinURL } from 'ufo'

// Docus verlinkt `/favicon.ico` (gibt es nicht). Stattdessen das TRS-Logo aus public/icon.png.
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
