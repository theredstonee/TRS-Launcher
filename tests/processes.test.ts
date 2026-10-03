import { describe, expect, it } from 'vitest'
import { aggregatePhase, defaultExtraAccount, isExtraKey } from '../app/utils/processes'

describe('Mehrere Prozesse je Instanz', () => {
  it('erkennt zusätzliche Prozesse am Schlüssel', () => {
    expect(isExtraKey('a', 'a')).toBe(false)
    expect(isExtraKey('a', 'a~2')).toBe(true)
    // Ältere Ereignisse ohne Schlüssel zählen als erster Prozess.
    expect(isExtraKey('a', undefined)).toBe(false)
  })

  it('läuft, solange irgendein Prozess läuft', () => {
    expect(aggregatePhase('running', false, 0)).toBe('idle')
    expect(aggregatePhase('running', true, 0)).toBe('running')
    // Der erste Prozess ist zu, ein weiterer läuft noch.
    expect(aggregatePhase('idle', false, 1)).toBe('running')
    expect(aggregatePhase('running', true, 2)).toBe('running')
    // Die Vorbereitung hat Vorrang.
    expect(aggregatePhase('preparing', false, 0)).toBe('preparing')
  })

  it('schlägt für den zweiten Start ein anderes Konto vor', () => {
    const accounts = [{ id: 'a' }, { id: 'b' }]
    expect(defaultExtraAccount(accounts, 'a', ['a'])).toBe('b')
    expect(defaultExtraAccount(accounts, 'a', [])).toBe('a')
    // Nur ein Konto: das aktive.
    expect(defaultExtraAccount([{ id: 'a' }], 'a', ['a'])).toBe('a')
    expect(defaultExtraAccount([], null, [])).toBeNull()
    expect(defaultExtraAccount(accounts, 'b', ['a', 'b'])).toBe('b')
  })
})
