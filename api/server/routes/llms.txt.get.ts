import { useCtx } from '../lib/context'
import { llmsHandler } from '../lib/llms-http'
import { llmsSource } from '../lib/llms-source'

/** Website: kurze Übersicht für Sprachmodelle (llmstxt.org), siehe server/lib/llms.ts. */
export default llmsHandler('short', () => llmsSource(useCtx().config.siteUrl))
