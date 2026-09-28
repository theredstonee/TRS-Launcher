import { defineEventHandler } from 'h3'
import { touchAchievements } from '../../../lib/achievements'
import { useCtx } from '../../../lib/context'
import { created, readJson } from '../../../lib/http'
import { createIssueBody, requireIssueWriter } from '../../../lib/issue-http'
import { createIssue } from '../../../lib/issues'

/** Neues Issue (§28.3) – Website-Sitzung oder Bearer-Token (z. B. „Bug melden“ im TRS Client). */
export default defineEventHandler(async (event) => {
  const viewer = requireIssueWriter(event, 'issueCreateUser')
  const body = await readJson(event, createIssueBody, 64 * 1024)
  const ctx = useCtx()
  const issue = createIssue(ctx, viewer, body)
  touchAchievements(ctx, viewer.uuid, ['issues_opened', 'bug_from_game_flag'])
  return created(event, { issue })
})
