import { DatabaseSync, type SQLInputValue, type StatementSync } from 'node:sqlite'
import { MIGRATIONS } from './migrations'

export type Db = DatabaseSync
export type Param = SQLInputValue

const cache = new WeakMap<Db, Map<string, StatementSync>>()

function stmt(db: Db, sql: string): StatementSync {
  let m = cache.get(db)
  if (!m) {
    m = new Map()
    cache.set(db, m)
  }
  let s = m.get(sql)
  if (!s) {
    s = db.prepare(sql)
    m.set(sql, s)
  }
  return s
}

/** Eine Zeile (oder `undefined`). Nur mit Platzhaltern – nie Werte in SQL einsetzen. */
export function one<T>(db: Db, sql: string, ...params: Param[]): T | undefined {
  return stmt(db, sql).get(...params) as T | undefined
}

export function all<T>(db: Db, sql: string, ...params: Param[]): T[] {
  return stmt(db, sql).all(...params) as T[]
}

export function run(db: Db, sql: string, ...params: Param[]): number {
  return Number(stmt(db, sql).run(...params).changes)
}

/** Führt `fn` in einer Transaktion aus (BEGIN IMMEDIATE). Nicht verschachteln. */
export function tx<T>(db: Db, fn: () => T): T {
  db.exec('BEGIN IMMEDIATE')
  try {
    const result = fn()
    db.exec('COMMIT')
    return result
  } catch (err) {
    db.exec('ROLLBACK')
    throw err
  }
}

/** `?, ?, ?` für IN-Listen (die Werte selbst gehen als Parameter hinein). */
export function placeholders(n: number): string {
  return Array.from({ length: n }, () => '?').join(', ')
}

export function openDb(file: string): Db {
  const db = new DatabaseSync(file)
  db.exec('PRAGMA foreign_keys = ON')
  db.exec('PRAGMA busy_timeout = 5000')
  if (file !== ':memory:') {
    db.exec('PRAGMA journal_mode = WAL')
    db.exec('PRAGMA synchronous = NORMAL')
  }
  migrate(db)
  return db
}

export function migrate(db: Db): number {
  db.exec(`CREATE TABLE IF NOT EXISTS schema_migrations (
    version INTEGER PRIMARY KEY,
    applied_at INTEGER NOT NULL
  )`)
  const row = one<{ v: number | null }>(db, 'SELECT MAX(version) AS v FROM schema_migrations')
  const current = row?.v ?? 0
  let applied = 0
  for (const m of MIGRATIONS) {
    if (m.version <= current) continue
    tx(db, () => {
      if (m.sql) db.exec(m.sql)
      m.run?.(db)
      run(db, 'INSERT INTO schema_migrations (version, applied_at) VALUES (?, ?)', m.version, Date.now())
    })
    applied++
  }
  return applied
}
