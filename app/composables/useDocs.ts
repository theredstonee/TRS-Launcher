import type { DocsPage } from '~/utils/docs'

/**
 * Öffnet eine Seite der Dokumentation (Website, `/docs/<sprache>/…`) im Browser – in der Launcher-Sprache,
 * wie jeder externe Link über den Kern. Ohne Seite: Startseite der Docs.
 */
export function useDocs() {
  const toasts = useToasts()
  return (page?: DocsPage) => {
    backend.openExternalUrl(docsUrl(page)).catch((e) => toasts.error(e))
  }
}
