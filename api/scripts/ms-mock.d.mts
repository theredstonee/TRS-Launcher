export interface MockAccount {
  name: string
  uuid: string
  xerr?: number
  noGame?: boolean
  denied?: boolean
}

export interface MsMock {
  base: string
  authority: string
  xbl: string
  xsts: string
  minecraft: string
  readonly calls: string[]
  next: string | null
  issueCode(name: string, challenge: string, redirect: string): string
  close(): Promise<void>
}

export function startMsMock(opts: { clientId: string, clientSecret: string, accounts: MockAccount[], port?: number }): Promise<MsMock>
