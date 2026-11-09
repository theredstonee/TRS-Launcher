import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import { describe, expect, it } from 'vitest'
import { createJob, submitApplication, type JobInput } from '../server/lib/applications'
import {
  applicationMessages,
  readApplicationFile,
  rotateApplicationKeys,
  sendApplicationMessage,
  uploadApplicationFile,
} from '../server/lib/application-chat'
import { one } from '../server/lib/db'
import { ChatCipher } from '../server/lib/crypto'
import { ownerStaff, setMemberRoles, teamOf } from '../server/lib/team'
import { ADMIN, login, makeEnv } from './helpers'
import { players } from './chathelpers'

const OWNER = ownerStaff(ADMIN)
const JOB: JobInput = {
  status: 'open', roleId: null, form: [],
  texts: { en: { title: 'Test position' }, de: { title: 'Teststelle' } },
}
async function setup(mail = false, chatKeys?: string) {
  const env = makeEnv({ env: { ...(mail ? { SMTP_HOST: 'smtp.example.test' } : {}), ...(chatKeys ? { CHAT_KEYS: chatKeys } : {}) } })
  await login(env, 'Owner', ADMIN)
  const [admin, applicant, other] = await players(env, 'Admin', 'Applicant', 'Other')
  setMemberRoles(env.ctx, OWNER, admin!.uuid, ['admin'])
  const staff = teamOf(env.ctx, admin!.uuid)!
  const job = createJob(env.ctx, OWNER, JOB)
  const application = submitApplication(env.ctx, { uuid: applicant!.uuid, name: applicant!.name }, job.id, {
    email: 'applicant@example.test', discord: 'applicant.name', ageGroup: '18+', answers: {},
  })
  return { env, applicant: { uuid: applicant!.uuid, name: applicant!.name }, staff, other: other!.uuid, application }
}

describe('application chat', () => {
  it('keeps internal notes separate and permits only the applicant or authorized reviewers', async () => {
    const { env, applicant, staff, other, application } = await setup()
    sendApplicationMessage(env.ctx, applicant.uuid, application.id, { text: 'Hello team' })
    expect(applicationMessages(env.ctx, staff.uuid, application.id).map((message) => message.text)).toEqual(['Hello team'])
    expect(() => applicationMessages(env.ctx, other, application.id)).toThrow()
  })

  it('sends a link-only email for staff replies, but not for applicant messages', async () => {
    const { env, applicant, staff, application } = await setup(true)
    sendApplicationMessage(env.ctx, applicant.uuid, application.id, { text: 'Question' })
    expect(one<{ n: number }>(env.ctx.db, "SELECT COUNT(*) AS n FROM mail_outbox WHERE kind = 'confirm'")?.n).toBe(0)
    sendApplicationMessage(env.ctx, staff.uuid, application.id, { text: 'Private message contents' })
    const mail = one<{ subject: string, text: string }>(env.ctx.db, "SELECT subject, text FROM mail_outbox WHERE kind = 'confirm' ORDER BY id DESC LIMIT 1")!
    expect(mail.subject).toContain('message')
    expect(mail.text).not.toContain('Private message contents')
    expect(mail.text).toContain('/applications#')
  })

  it('stores attachments encrypted and marks them unscanned, but allows authenticated download', async () => {
    const { env, applicant, staff, application } = await setup()
    const file = uploadApplicationFile(env.ctx, applicant.uuid, application.id, 'portfolio.pdf', Buffer.from('%PDF-test'))
    const message = sendApplicationMessage(env.ctx, applicant.uuid, application.id, { text: 'My portfolio', fileIds: [file.id] })
    expect(file.status).toBe('unscanned')
    expect(applicationMessages(env.ctx, staff.uuid, application.id)[0]?.files[0]?.status).toBe('unscanned')
    expect(readApplicationFile(env.ctx, staff.uuid, application.id, file.id).data.toString()).toBe('%PDF-test')
    expect(applicationMessages(env.ctx, applicant.uuid, application.id)[0]?.id).toBe(message.id)
    expect(() => uploadApplicationFile(env.ctx, applicant.uuid, application.id, 'large.bin', Buffer.alloc(30 * 1024 * 1024 + 1))).toThrow()
  })

  it('rotates encrypted messages and files to the active chat key', async () => {
    const oldKey = Buffer.alloc(32, 1)
    const newKey = Buffer.alloc(32, 2)
    const { env, applicant, application } = await setup(false, `old:${oldKey.toString('base64')}`)
    const file = uploadApplicationFile(env.ctx, applicant.uuid, application.id, 'notes.txt', Buffer.from('rotation test'))
    sendApplicationMessage(env.ctx, applicant.uuid, application.id, { text: 'Keep this', fileIds: [file.id] })
    env.ctx.cipher = new ChatCipher([{ id: 'new', key: newKey }, { id: 'old', key: oldKey }])

    expect(rotateApplicationKeys(env.ctx, 10)).toBe(2)
    expect(applicationMessages(env.ctx, applicant.uuid, application.id)[0]?.text).toBe('Keep this')
    const encrypted = readFileSync(join(env.ctx.applicationFileDir, file.id.slice(1, 3), `${file.id}.bin`))
    expect(env.ctx.cipher.decrypt(encrypted, `appfile:${file.id}`).toString()).toBe('rotation test')
    expect(one<{ key_id: string }>(env.ctx.db, 'SELECT key_id FROM team_application_files WHERE id = ?', file.id)?.key_id).toBe('new')
  })
})
