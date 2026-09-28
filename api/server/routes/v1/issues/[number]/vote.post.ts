import { defineEventHandler } from 'h3'
import { touchAchievements, touchIssueAuthor } from '../../../../lib/achievements'
import { useCtx } from '../../../../lib/context'
import { readJson } from '../../../../lib/http'
import { issueNumberParam, requireIssueWriter, voteBody } from '../../../../lib/issue-http'
import { voteIssue } from '../../../../lib/issues'

/** Hoch (1), runter (-1) oder Stimme zurücknehmen (0) – je Konto eine Stimme. */
export default defineEventHandler(async (event) => {
  const viewer = requireIssueWriter(event, 'issueVoteUser')
  const number = issueNumberParam(event)
  const body = await readJson(event, voteBody)
  const ctx = useCtx()
  const result = voteIssue(ctx, viewer, number, body.vote)
  touchAchievements(ctx, viewer.uuid, ['votes_cast'])
  touchIssueAuthor(ctx, number)
  return result
})
