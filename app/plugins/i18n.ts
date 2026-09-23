// Übersetzungen vor dem ersten Rendern bereitstellen – mit der zuletzt
// benutzten Sprache (Merker), bis die Einstellungen geladen sind.
export default defineNuxtPlugin(async (nuxtApp) => {
  nuxtApp.vueApp.use(i18n)
  await setLocale(readLocaleHint() ?? 'en')
})
