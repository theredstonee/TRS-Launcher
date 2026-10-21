import { describe, expect, it } from 'vitest'
import { WAIT_SECTIONS, parseWaitTimeline, type WaitTimeline } from '../shared/wait-song'
import { buildChat } from '../app/utils/waitChat'
import { INTRO, SONG_LENGTH, fallbackTimeline } from '../app/utils/waitSong'
import { SCENE_OF, cueTime, cutFx, cutList, shotAt, shotList } from '../app/utils/waitVideo/plan'

/** Zeitraster des echten Suno-Lieds (so liegt es auf dem Server). */
const SUNO = parseWaitTimeline({
  intro: 0, verse1: 8.38, pre1: 38.08, chorus1: 47.77, verse2: 67.17, pre2: 88.99, chorus2: 93.83, bridge: 122.93,
  final: 135.05, outro: 154.44, end: 190.8, beats: { bpm: 99, offset: 1.713 },
  cues: { finalStop: 144.74, finalHit: 147.17, launch: 164.14, fade: 176.26, swell: 184.74 },
})!

describe('fallback timeline (chiptune placeholder)', () => {
  it('is a valid timeline derived from the song constants', () => {
    const tl = fallbackTimeline()
    expect(parseWaitTimeline(tl)).not.toBeNull()
    expect(tl.verse1).toBeCloseTo(INTRO)
    expect(tl.end).toBeCloseTo(SONG_LENGTH)
    for (let i = 1; i < WAIT_SECTIONS.length; i++) expect(tl[WAIT_SECTIONS[i]!]).toBeGreaterThan(tl[WAIT_SECTIONS[i - 1]!])
    expect(tl.beats).toEqual({ bpm: 112, offset: 0 })
  })
})

describe('cues', () => {
  it('uses cues from timeline.json and estimates missing ones inside their section', () => {
    expect(cueTime(SUNO, 'launch')).toBe(164.14)
    const { cues: _cues, ...plain } = SUNO
    const tl = plain as WaitTimeline
    for (const cue of ['finalStop', 'finalHit'] as const) {
      const t = cueTime(tl, cue)
      expect(t).toBeGreaterThan(tl.final)
      expect(t).toBeLessThan(tl.outro)
    }
    for (const cue of ['launch', 'fade', 'swell'] as const) {
      const t = cueTime(tl, cue)
      expect(t).toBeGreaterThan(tl.outro)
      expect(t).toBeLessThan(tl.end)
    }
    expect(cueTime(tl, 'finalStop')).toBeLessThan(cueTime(tl, 'finalHit'))
  })
})

describe('shot list / scene selection', () => {
  for (const [name, tl] of [['suno', SUNO], ['fallback', fallbackTimeline() as WaitTimeline]] as const) {
    it(`covers the whole song without gaps (${name})`, () => {
      const list = shotList(tl)
      expect(list[0]!.start).toBe(0)
      for (let i = 1; i < list.length; i++) {
        expect(list[i]!.start).toBeCloseTo(list[i - 1]!.end)
        expect(list[i]!.end).toBeGreaterThan(list[i]!.start)
      }
      expect(list.at(-1)!.end).toBe(tl.end)
      // Jeder Teil beginnt mit seinem ersten Shot genau am Teil-Anfang
      for (const s of WAIT_SECTIONS) expect(list.find((x) => x.section === s)!.start).toBe(tl[s])
    })
  }

  it('picks the scene per section and cue', () => {
    const list = shotList(SUNO)
    const at = (t: number) => shotAt(list, SUNO, t)
    expect(at(0)).toMatchObject({ shot: 'night', scene: 'room', section: 'intro' })
    expect(at(20).scene).toBe('room')
    expect(at(SUNO.chorus1 + 0.01)).toMatchObject({ shot: 'breach', scene: 'space' })
    expect(at(70)).toMatchObject({ scene: 'street', section: 'verse2' })
    expect(at(SUNO.pre2 + 0.1)).toMatchObject({ shot: 'gauge', scene: 'room' })
    expect(at(100).scene).toBe('space')
    expect(at(125)).toMatchObject({ shot: 'wall', scene: 'bridge' })
    expect(at(145)).toMatchObject({ shot: 'stop', scene: 'launch' })
    expect(at(147.2)).toMatchObject({ shot: 'breakout', scene: 'launch' })
    expect(at(160)).toMatchObject({ shot: 'console', scene: 'finale' })
    expect(at(170).shot).toBe('launch')
    expect(at(180).shot).toBe('friday')
    expect(at(186).shot).toBe('door')
    const after = at(500)
    expect(after.shot).toBe('door')
    expect(after.p).toBe(1)
    expect(at(-3).shot).toBe('night')
  })

  it('snaps cuts inside a section to downbeats', () => {
    const bar = 240 / 99
    for (const s of shotList(SUNO)) {
      if (s.start === SUNO[s.section]) continue
      if (Object.values(SUNO.cues!).includes(s.start)) continue
      const k = (s.start - SUNO.beats!.offset) / bar
      expect(Math.abs(k - Math.round(k))).toBeLessThan(1e-6)
    }
  })

  it('maps every shot id to a scene', () => {
    for (const s of shotList(SUNO)) expect(SCENE_OF[s.shot]).toBeDefined()
  })
})

describe('transitions', () => {
  it('flashes, whips and fades at the right moments', () => {
    const cuts = cutList(SUNO)
    expect(cutFx(cuts, SUNO.verse1 + 0.01, SUNO.end).flash).toBeGreaterThan(0.9)
    expect(cutFx(cuts, SUNO.chorus1 - 0.05, SUNO.end).whip).toBeGreaterThan(0.5)
    expect(cutFx(cuts, SUNO.verse2 + 0.02, SUNO.end).whip).toBeLessThan(-0.5)
    expect(cutFx(cuts, SUNO.bridge, SUNO.end).black).toBeCloseTo(1)
    expect(cutFx(cuts, 30, SUNO.end)).toEqual({ flash: 0, black: 0, whip: 0, white: 0 })
    expect(cutFx(cuts, SUNO.end, SUNO.end).black).toBe(1)
    expect(cutFx(cuts, SUNO.end + 5, SUNO.end).black).toBe(1)
  })
})

describe('chat events', () => {
  it('are sorted, inside the song and tell the story', () => {
    const launch = cueTime(SUNO, 'launch')
    const fade = cueTime(SUNO, 'fade')
    const chat = buildChat(SUNO, launch, fade)
    for (let i = 1; i < chat.length; i++) expect(chat[i]!.at).toBeGreaterThanOrEqual(chat[i - 1]!.at)
    for (const m of chat) {
      expect(m.at).toBeGreaterThanOrEqual(0)
      expect(m.at).toBeLessThan(SUNO.end)
      // Jede Nachricht liegt in ihrem Teil
      const i = WAIT_SECTIONS.indexOf(m.section)
      const end = i + 1 < WAIT_SECTIONS.length ? SUNO[WAIT_SECTIONS[i + 1]!] : SUNO.end
      expect(m.at).toBeGreaterThanOrEqual(SUNO[m.section])
      expect(m.at).toBeLessThan(end + 0.001)
    }
    const countdown = chat.filter((m) => m.section === 'outro' && m.from === 'team').map((m) => m.text)
    expect(countdown.slice(0, 5)).toEqual(['10 …', '9 …', '8 …', '7 …', '6 …'])
    expect(chat.filter((m) => m.section === 'outro' && /^\d/.test(m.text)).every((m) => m.at < launch)).toBe(true)
    expect(chat.filter((m) => m.section === 'bridge').length).toBeGreaterThanOrEqual(6)
    expect(chat.some((m) => m.text === 'yo?!')).toBe(true)
    expect(chat.filter((m) => m.section === 'chorus2').length).toBeGreaterThan(chat.filter((m) => m.section === 'chorus1').length)
  })
})
