// Aussehen und Verhalten der Doku (Docus + Nuxt UI). Farben: siehe app.css (Paletten redstone, lamp, deepslate).
export default defineAppConfig({
  docus: {
    locale: 'en',
    // Standard dunkel wie die Website; Hell-Modus bleibt über den Umschalter erreichbar.
    colorMode: '',
  },
  seo: {
    titleTemplate: '%s · TRS Launcher Docs',
    title: 'TRS Launcher Docs',
    description: 'Documentation for TRS Launcher, the free redstone Minecraft launcher by TheRedstonee, and the TRS Client mod.',
  },
  header: {
    title: 'TRS Launcher Docs',
  },
  // Keine „Edit this page“-/„Report an issue“-Links und kein GitHub-Knopf von Docus (eigene Links im Kopf).
  github: false,
  socials: {},
  search: {
    // Suche über die Abschnitte der Seiten (Nuxt Content, clientseitig aus der statischen Datenbank).
    fts: false,
  },
  toc: {
    bottom: {
      links: [],
    },
  },
  assistant: {
    floatingInput: false,
    explainWithAi: false,
  },
  ui: {
    colors: {
      primary: 'redstone',
      secondary: 'lamp',
      neutral: 'deepslate',
    },
  },
})
