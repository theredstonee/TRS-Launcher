import { useCtx } from '../lib/context'
import { llmsHandler } from '../lib/llms-http'
import { llmsSource } from '../lib/llms-source'

/** Website: der ganze englische Inhalt als ein Text für Sprachmodelle, siehe server/lib/llms.ts. */
export default llmsHandler('full', () => llmsSource(useCtx().config.siteUrl))
