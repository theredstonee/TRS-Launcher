// Skin-Links anderer Spieler (Freunde, Admin-Suche): ein Aufruf je UUID und Sitzung,
// auch wenn Listen neu gezeichnet werden. Der Kern hält zusätzlich 30 Minuten vor.
const pending = new Map<string, Promise<string | null>>()

export function playerSkinUrl(uuid: string): Promise<string | null> {
  let p = pending.get(uuid)
  if (!p) {
    p = backend.playerSkinUrl(uuid).catch(() => null)
    pending.set(uuid, p)
  }
  return p
}
