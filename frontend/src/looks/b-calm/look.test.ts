import { describe, it, expect } from 'vitest'
import { calm } from '.'
import { summarise, type SummaryFacts } from './summary'
import type { WaitKind } from '@/types/api'

describe('B · Calm', () => {
  it('is the trial look "b", labelled as the ⋯ menu shows it, with its own line under the title', () => {
    expect(calm).toMatchObject({ id: 'b', label: 'B · Calm', trial: true })
    expect(Object.keys(calm.slots)).toEqual(['HeaderSummary'])
  })
})

const wait = (waitKind: WaitKind, resourceId: string | null = null) => ({ waitKind, resourceId })

const NAMES = new Map([
  ['mutex-1', 'inventory-lock'],
  ['sem-1', 'payment-gateway'],
  ['ch-1', 'orders'],
])

function facts(over: Partial<SummaryFacts> = {}): SummaryFacts {
  return {
    outcome: null,
    runSpanNanos: null,
    spanIsFloor: false,
    replayActive: false,
    replayAtNanos: null,
    waits: [],
    names: NAMES,
    ...over,
  }
}

const running = { outcome: 'running', failedCount: 0, ended: false } as const
const finished = { outcome: 'finished', failedCount: 0, ended: true } as const

describe('the sentence under the title (summarise)', () => {
  describe('how the run is going', () => {
    it('says nothing for an attached app with no waits: it has no run outcome', () => {
      expect(summarise(facts())).toBeNull()
    })

    it('a demo not run yet', () => {
      expect(summarise(facts({ outcome: { outcome: 'notStarted', failedCount: 0, ended: false } }))).toBe(
        'Not run yet.',
      )
    })

    it('a running demo, for at least the span of the events the page holds', () => {
      expect(summarise(facts({ outcome: running, runSpanNanos: 6e9 }))).toBe('Running for at least 6.0 s.')
    })

    it('a running demo with no span yet', () => {
      expect(summarise(facts({ outcome: running, runSpanNanos: null }))).toBe('Running.')
      expect(summarise(facts({ outcome: running, runSpanNanos: 0 }))).toBe('Running.')
    })

    it('a finished demo, and how long it took', () => {
      expect(summarise(facts({ outcome: finished, runSpanNanos: 0.6e9 }))).toBe('Finished after 600 ms.')
    })

    it('a finished demo whose events were partly dropped took at least that long', () => {
      expect(summarise(facts({ outcome: finished, runSpanNanos: 0.6e9, spanIsFloor: true }))).toBe(
        'Finished after at least 600 ms.',
      )
    })

    it('a finished demo with no span', () => {
      expect(summarise(facts({ outcome: finished }))).toBe('Finished.')
    })

    it('a failed run that has ended', () => {
      expect(summarise(facts({ outcome: { outcome: 'failed', failedCount: 1, ended: true }, runSpanNanos: 3e9 }))).toBe(
        '1 coroutine failed. The run ended after 3.0 s.',
      )
    })

    it('a failed run that is still going', () => {
      expect(summarise(facts({ outcome: { outcome: 'failed', failedCount: 2, ended: false }, runSpanNanos: 3e9 }))).toBe(
        '2 coroutines failed, and the run is still going.',
      )
    })

    it('in replay, where the cursor is, never the live outcome', () => {
      expect(summarise(facts({ outcome: finished, runSpanNanos: 6e9, replayActive: true, replayAtNanos: 2.3e9 }))).toBe(
        'Replay at 2.3 s.',
      )
      expect(summarise(facts({ outcome: finished, replayActive: true, replayAtNanos: null }))).toBe('Replay.')
    })
  })

  describe('what is waiting', () => {
    it('one coroutine waiting for a named lock', () => {
      expect(summarise(facts({ waits: [wait('MUTEX', 'mutex-1')] }))).toBe('1 coroutine is waiting for inventory-lock.')
    })

    it('several waiting for the same thing', () => {
      expect(summarise(facts({ outcome: running, runSpanNanos: 6e9, waits: [wait('MUTEX', 'mutex-1'), wait('MUTEX', 'mutex-1')] }))).toBe(
        'Running for at least 6.0 s. 2 coroutines are waiting for inventory-lock.',
      )
    })

    it('groups by what they wait for, largest group first (the sketch’s sentence)', () => {
      const waits = [wait('SEMAPHORE', 'sem-1'), wait('MUTEX', 'mutex-1'), wait('MUTEX', 'mutex-1')]
      expect(summarise(facts({ outcome: running, runSpanNanos: 6e9, waits }))).toBe(
        'Running for at least 6.0 s. 3 coroutines are waiting: 2 for inventory-lock, 1 for a payment-gateway permit.',
      )
    })

    it('keeps the order they started waiting in for groups of the same size', () => {
      const waits = [wait('SEMAPHORE', 'sem-1'), wait('MUTEX', 'mutex-1')]
      expect(summarise(facts({ waits }))).toBe(
        '2 coroutines are waiting: 1 for a payment-gateway permit, 1 for inventory-lock.',
      )
    })

    it('words channels as sending and receiving', () => {
      const waits = [wait('CHANNEL_SEND', 'ch-1'), wait('CHANNEL_RECEIVE', 'ch-1'), wait('CHANNEL_RECEIVE', 'ch-1')]
      expect(summarise(facts({ waits }))).toBe('3 coroutines are waiting: 2 to receive from orders, 1 to send to orders.')
    })

    it('never shows an internal id: an unnamed resource reads generically, and unnamed locks are one group', () => {
      const waits = [wait('MUTEX', 'mutex-9'), wait('MUTEX', 'mutex-8'), wait('SEMAPHORE', 'sem-9'), wait('CHANNEL_SEND', null), wait('CHANNEL_RECEIVE', 'ch-9')]
      const sentence = summarise(facts({ waits }))!
      expect(sentence).toBe('5 coroutines are waiting: 2 for a lock, 1 for a permit, 1 to send, and 1 for other things.')
      expect(sentence).not.toMatch(/mutex-|sem-|ch-/)
    })

    it('names at most three groups; the rest are "other things"', () => {
      const names = new Map([...NAMES, ['mutex-2', 'audit-lock'], ['mutex-3', 'cache-lock']])
      const waits = [wait('MUTEX', 'mutex-1'), wait('MUTEX', 'mutex-2'), wait('MUTEX', 'mutex-3'), wait('SEMAPHORE', 'sem-1'), wait('CHANNEL_SEND', 'ch-1')]
      expect(summarise(facts({ waits, names }))).toBe(
        '5 coroutines are waiting: 1 for inventory-lock, 1 for audit-lock, 1 for cache-lock, and 2 for other things.',
      )
    })

    it('an "an" before a vowel', () => {
      const names = new Map([['sem-2', 'inventory']])
      expect(summarise(facts({ waits: [wait('SEMAPHORE', 'sem-2')], names }))).toBe('1 coroutine is waiting for an inventory permit.')
    })

    it('says nothing about waits it has no record of', () => {
      expect(summarise(facts({ outcome: running, runSpanNanos: 1e9, waits: [] }))).toBe('Running for at least 1.0 s.')
    })

    it('in replay, the waits at the cursor', () => {
      expect(summarise(facts({ replayActive: true, replayAtNanos: 2e9, waits: [wait('MUTEX', 'mutex-1')] }))).toBe(
        'Replay at 2.0 s. 1 coroutine is waiting for inventory-lock.',
      )
    })
  })
})
