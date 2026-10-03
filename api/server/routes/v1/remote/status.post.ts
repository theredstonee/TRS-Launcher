import { defineEventHandler } from 'h3'
import { z } from 'zod'
import { useCtx } from '../../../lib/context'
import { limit, noContent, readJson } from '../../../lib/http'
import { RULES } from '../../../lib/ratelimit'
import { MAX_STATUS_INSTANCES, cleanStatus, putStatus } from '../../../lib/remote'
import { requireDevice } from '../../../lib/remote-http'

const text = (max: number) => z.string().max(max)
const remoteStatusBody = z.strictObject({
  online: z.boolean(),
  allow: z.strictObject({ launch: z.boolean(), install: z.boolean() }),
  instances: z.array(z.strictObject({
    id: text(64),
    name: text(200),
    version: text(64),
    loader: text(32),
    iconHash: text(64).nullable().optional(),
    running: z.boolean(),
  })).max(MAX_STATUS_INSTANCES),
  tasks: z.array(z.strictObject({
    title: text(200),
    progress: z.number().min(0).max(1).nullable(),
    instanceId: text(64).nullable().optional(),
  })).max(10).optional(),
})

/**
 * PC: eigenen Stand melden (§34.4) – entprellt bei Änderungen und spätestens alle 60 s. Gekoppelte Handys bekommen
 * `remote_status`. `online: false` = Fernbedienung ausgeschaltet bzw. Launcher wird beendet.
 */
export default defineEventHandler(async (event) => {
  const { device } = requireDevice(event, 'read', 'desktop')
  limit(`remoteStatus:${device.id}`, RULES.remoteStatusDesktop)
  const input = await readJson(event, remoteStatusBody, 64 * 1024)
  putStatus(useCtx(), device, cleanStatus(input))
  return noContent(event)
})
