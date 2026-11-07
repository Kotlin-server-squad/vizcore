import { describe, expect, it } from 'vitest'
import { asOfText, headline, notEvaluatedText, problemOf, qualifierLines, healthErrorText } from './health-text'
import { health } from '@/test/health-fixture'
import { ApiError } from './api-errors'

const UTC = { timeZone: 'UTC' }

describe('asOfText (H-2)', () => {
  it('uses the app wall clock when the observation carries it', () => {
    expect(asOfText(health().asOf, 0, UTC)).toBe('as of 14:02:31')
  })
  it('falls back to time into the capture, never the browser clock', () => {
    expect(asOfText({ kind: 'LAST_EVENT', appNanos: 5_200_000_000, seq: 9 }, 1_000_000_000, UTC)).toBe(
      'as of 4.2 s into the capture',
    )
  })
  it('falls back to the event number without a capture start', () => {
    expect(asOfText({ kind: 'LAST_EVENT', appNanos: 5_200_000_000, seq: 9 }, null, UTC)).toBe('as of event 9')
  })
  it('gives a replay cursor its time into the capture and its event number', () => {
    expect(asOfText({ kind: 'REPLAY_CURSOR', appNanos: 3_000_000_000, appEpochMs: 1, seq: 12 }, 1_000_000_000, UTC)).toBe(
      'as of 2.0 s into the capture · event 12',
    )
  })
  it('says a lower bound is one', () => {
    expect(
      asOfText({ kind: 'REPLAY_CURSOR', appNanos: 3_000_000_000, seq: 12, lowerBound: true }, 1_000_000_000, UTC),
    ).toBe('as of at least 2.0 s into the capture · event 12')
  })
})

describe('headline (T-1, S-8)', () => {
  it('counts problems by kind', () => {
    const h = health({ state: 'PROBLEMS', problemCount: 3, failedRoundCount: 1, ungroupedFailureCount: 1, possiblyBlockedCount: 1 })
    expect(headline(h, 0, UTC)).toEqual({
      tone: 'problem',
      text: '3 problems detected · as of 14:02:31',
      detail: 'Failed 2 (1 round · 1 ungrouped) · Possibly blocked 1',
    })
  })
  it('says one problem in the singular, and "at least" when counts may be low', () => {
    const h = health({ state: 'PROBLEMS', problemCount: 1, failedRoundCount: 1, failuresAtLeast: true })
    expect(headline(h, 0, UTC).text).toBe('at least 1 problem detected · as of 14:02:31')
  })
  it('is green only for NO_PROBLEMS, and about the captured coroutines', () => {
    expect(headline(health(), 0, UTC)).toEqual({
      tone: 'clean',
      text: 'No problems detected in the captured coroutines · as of 14:02:31',
    })
  })
  it('is neutral when not provably clean', () => {
    expect(headline(health({ state: 'NO_FAILURES_PARTIAL' }), 0, UTC)).toEqual({
      tone: 'neutral',
      text: 'No failures detected in the captured coroutines · as of 14:02:31',
    })
  })
  it('says when there is nothing to evaluate yet', () => {
    expect(headline(health({ state: 'NO_APP_COROUTINES' }), 0, UTC).text).toBe(
      'No application coroutines captured · nothing to evaluate yet · as of 14:02:31',
    )
  })
  it('waits for the first data without an "as of"', () => {
    expect(headline(health({ state: 'AWAITING_FIRST_DATA', asOf: null }), 0, UTC)).toEqual({
      tone: 'neutral',
      text: 'Waiting for the first data from the app',
    })
  })
})

describe('notEvaluatedText', () => {
  it('names every reason in words', () => {
    expect(notEvaluatedText('NO_WATERMARK')).toBe('no poll data from this app')
    expect(notEvaluatedText('DEMO')).toMatch(/^demo session/)
    expect(notEvaluatedText('REPLAY')).toBe('replay')
    expect(notEvaluatedText('OVERFLOW')).toMatch(/capacity/)
    expect(notEvaluatedText('EVENTS_DROPPED')).toMatch(/lost/)
    expect(notEvaluatedText('HISTORY_TRIMMED')).toMatch(/trimmed/)
  })
})

describe('qualifierLines', () => {
  it('says nothing possibly blocked when it was evaluated', () => {
    expect(qualifierLines(health(), 0)).toEqual(['Nothing possibly blocked'])
  })
  it('adds no possibly blocked line when the headline already counts them', () => {
    expect(qualifierLines(health({ state: 'PROBLEMS', possiblyBlockedCount: 2 }), 0)).toEqual([])
  })

  it('gives the reason when possibly blocked was not evaluated', () => {
    expect(qualifierLines(health({ possiblyBlocked: { status: 'NOT_EVALUATED', reason: 'REPLAY' } }), 0)).toEqual([
      'Possibly blocked: not evaluated (replay)',
    ])
  })
  it('names lost events, untracked coroutines, unknown origin and a trimmed replay', () => {
    const lines = qualifierLines(
      health({
        possiblyBlocked: { status: 'NOT_EVALUATED', reason: 'EVENTS_DROPPED' },
        transport: { lostEvents: 3 },
        counts: { app: 10, vizcore: 0, unknown: 2, untracked: 5 },
        sessionOrigin: 'UNKNOWN',
        asOf: { kind: 'REPLAY_CURSOR', appNanos: 9, seq: 4 },
        retention: { oldestRetainedSeq: 20, oldestRetainedAppNanos: 3_000_000_000, trimmed: true },
      }),
      1_000_000_000,
    )
    expect(lines).toContain('some events may have been lost on the way from the app; results may be incomplete')
    expect(lines).toContain('5 coroutines beyond capacity were not tracked: health covers the first 12')
    expect(lines).toContain("origin not available: vizcore's own connection may be included")
    expect(lines).toContain('replay starts at 2.0 s; earlier events were dropped, so this may be incomplete')
  })
})

describe('problemOf', () => {
  const failure = { coroutineId: 'c2', name: 'pay', exceptionType: 'IllegalStateException', message: 'no', failedAtSeq: 9, detailsDropped: false }
  const h = health({
    state: 'PROBLEMS',
    incidents: {
      total: 2,
      items: [
        { type: 'FAILED_ROUND', id: 'r', roundId: 'r1', anchorId: 'c1', failures: { items: [failure], total: 2 }, cancelledAfter: { items: ['c3'], total: 4 }, firstFailureSeq: 9, sortSeq: 9 },
        {
          type: 'POSSIBLY_BLOCKED', id: 'b', coroutineId: 'c5', name: 'poll', roundId: null, anchorId: null, lastThread: null,
          dispatcher: null, lastSuspension: null, lastSuspensionProvenance: null, lastRunStart: { kind: 'RESUMED', appNanos: 1, seq: 2 },
          runningForNanos: 12_000_000_000, transitions: [], transitionsGap: false, firstObservedRunning: false, eventLoopClue: null, sortSeq: 2,
        },
      ],
    },
  })
  it('finds a failure in a failed round, with its round', () => {
    expect(problemOf(h, 'c2')).toMatchObject({ kind: 'FAILED', failure, round: { failed: 2, cancelledAfter: 4 } })
  })
  it('finds a possibly blocked coroutine', () => {
    expect(problemOf(h, 'c5')).toMatchObject({ kind: 'POSSIBLY_BLOCKED', incident: { coroutineId: 'c5' } })
  })
  it('finds nothing for a coroutine the health does not list', () => {
    expect(problemOf(h, 'c3')).toBeNull()
    expect(problemOf(undefined, 'c2')).toBeNull()
  })
})

describe('healthErrorText', () => {
  it('names the 503 reasons', () => {
    expect(healthErrorText(new ApiError(503, 'x', 'summaries-unavailable'))).toBe('the session keeps no usable summaries')
    expect(healthErrorText(new ApiError(503, 'x', 'history-trimmed'))).toBe("this session's early events were trimmed")
    expect(healthErrorText(new Error('boom'))).toBe('the health could not be read')
  })
})
