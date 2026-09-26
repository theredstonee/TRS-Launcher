import type { ChatWorld, HostingRoom } from '../../app/utils/hosting'

// Welt-Hosting: Beispieldaten so, wie der Kern sie ans Webview schickt (camelCase).

export const HOST = { uuid: 'b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0', name: 'Bob' }
export const ROOM_ID = 'h0123456789abcdef0123'

export function room(id: string = ROOM_ID, extra: Partial<HostingRoom> = {}): HostingRoom {
  return {
    id,
    code: null,
    name: 'Insel',
    host: HOST,
    mcVersion: '1.21.11',
    loader: 'fabric',
    maxPlayers: 4,
    gameMode: 'survival',
    pvp: true,
    cheats: false,
    open: true,
    visibility: null,
    players: 2,
    createdAt: '2026-09-26T10:00:00.000Z',
    expiresAt: null,
    members: [],
    myState: null,
    ...extra,
  }
}

export const CARD: ChatWorld = { roomId: ROOM_ID, code: 'K7QM2X', name: 'Insel', mcVersion: '1.21.11', loader: 'fabric', host: HOST }
