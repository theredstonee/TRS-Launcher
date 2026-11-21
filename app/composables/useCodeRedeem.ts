import { switchState } from '~/utils/creator'
import { partnerRedeemText, type RedeemOutcome } from '~/utils/partner'

/**
 * Gemeinsames Code-Feld (API §44.2) für die Garderobe und die Creator-Seite: löst ein,
 * lädt die betroffenen Stände neu und liefert den Text für die Meldung.
 */
export function useCodeRedeem() {
  const creator = useCreatorStore()
  const partner = usePartnerStore()
  const trs = useTrsStore()

  async function redeem(code: string): Promise<{ outcome: RedeemOutcome; text: string }> {
    const creatorBefore = creator.me?.support.creator?.uuid ?? null
    const outcome = await backend.trs.redeemAny(code)
    let text: string
    if (outcome.kind === 'creator') {
      await creator.load()
      text = creatorText(outcome.result, creatorBefore)
    } else if (outcome.kind === 'partner') {
      await partner.load()
      text = partnerRedeemText(outcome.result, partner.me?.support.partner?.slug ?? null)
    } else {
      text = outcome.result.alreadyOwned ? t('capes.toasts.alreadyOwned', { name: outcome.result.name }) : t('capes.toasts.unlocked', { name: outcome.result.name })
    }
    if (outcome.kind !== 'creator' || outcome.result.fanCapeGranted || outcome.result.supportChanged) trs.capesRevision++
    return { outcome, text }
  }

  return { redeem }
}

function creatorText(r: Extract<RedeemOutcome, { kind: 'creator' }>['result'], before: string | null): string {
  const name = r.creator.name
  const cape = r.creator.fanCape?.name ?? ''
  if (r.supportChanged) return r.fanCapeGranted ? t('creator.fan.redeemed.nowWithCape', { name, cape }) : t('creator.fan.redeemed.now', { name })
  const left = switchState({ startedAt: null, lastCreatorUuid: before, switchAvailableAt: r.switchAvailableAt }).daysLeft
  if (r.fanCapeGranted) return t('creator.fan.redeemed.capeOnly', { name, cape, days: left }, left)
  if (before === r.creator.uuid) return t('creator.fan.redeemed.already', { name })
  return t('creator.fan.redeemed.locked', { days: left }, left)
}
