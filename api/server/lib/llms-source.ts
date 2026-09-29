import { WEBSITE_PRIVACY } from '../../app/content/website-privacy'
import { circuitTexts } from '../../app/utils/circuit-i18n'
import { issueTexts } from '../../app/utils/issue-i18n'
import { messages } from '../../app/utils/messages'
import { teamTexts } from '../../app/utils/team-i18n'
import { landingTexts } from '../../shared/landing'
import type { LlmsSource } from './llms'
import { blogPosts } from './site'

/** Quelle für /llms.txt und /llms-full.txt: die englischen Texte der Seiten plus die neuesten Versionen. */
export async function llmsSource(siteUrl: string): Promise<LlmsSource> {
  const posts = await blogPosts().catch(() => [])
  return {
    siteUrl,
    m: messages.en,
    landing: landingTexts.en,
    pages: {
      issues: issueTexts.en.seo.list.description,
      roadmap: issueTexts.en.seo.roadmap.description,
      circuits: circuitTexts.en.seo.list.description,
      team: teamTexts.en.seo.team.description,
    },
    privacy: WEBSITE_PRIVACY.en.intro,
    releases: posts.slice(0, 6).map((p) => ({ version: p.version, name: p.title?.en ?? null, date: p.date, headlines: p.headlines.en })),
  }
}
