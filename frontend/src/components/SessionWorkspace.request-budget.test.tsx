import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, act, fireEvent } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import type { ReactNode } from 'react'
import { SessionWorkspace } from './SessionWorkspace'
import { apiClient } from '@/lib/api-client'
import { shouldRetryQuery } from '@/lib/query-client'

/**
 * Request budget for an open workspace (#124 / #137).
 *
 * Unlike the sibling SessionWorkspace tests, NOTHING on the data path is
 * mocked here: the real hooks run against the real api-client, and the only
 * stubs are the network itself (`fetch`, counted per URL) and `EventSource`.
 * The earlier unit tests mocked `apiClient`, which is how a workspace that
 * issued >60 requests a minute shipped unnoticed. Only purely visual children
 * that cannot issue requests are stubbed, to keep jsdom fast.
 *
 * Target: a workspace left open for 5 minutes, stream on or off, stays well
 * under 60 requests a minute in total.
 */

vi.mock('./CoroutineTreeGraph', () => ({
  CoroutineTreeGraph: () => <div data-testid="coroutine-tree-graph" />,
}))
vi.mock('./export/ExportMenu', () => ({ ExportMenu: () => <div data-testid="export-menu" /> }))
vi.mock('@/hooks/use-record-replay', () => ({
  useRecordReplay: () => ({
    canRecord: false,
    isRecording: false,
    isArming: false,
    elapsedMs: 0,
    startRecording: vi.fn(),
    stopRecording: vi.fn(),
    confirmOpen: false,
    confirmEstimateMs: 0,
    confirmSpeed: 1,
    confirmRecord: vi.fn(),
    cancelConfirm: vi.fn(),
  }),
}))
vi.mock('framer-motion', () => ({
  motion: {
    div: ({ children, ...props }: Record<string, unknown>) => (
      <div {...(props as object)}>{children as ReactNode}</div>
    ),
    span: ({ children, ...props }: Record<string, unknown>) => (
      <span {...(props as object)}>{children as ReactNode}</span>
    ),
  },
  AnimatePresence: ({ children }: { children: ReactNode }) => <>{children}</>,
  LayoutGroup: ({ children }: { children: ReactNode }) => <>{children}</>,
}))
vi.mock('@/lib/animation-throttle', () => ({ useAnimationSlot: () => false }))
vi.mock('@tanstack/react-router', () => ({ useNavigate: vi.fn(() => vi.fn()) }))

const SESSION = 'budget-session'
/** Rounds the fake app has. */
const ROUND_COUNT = 600

/** Minimal server: plausible JSON for every read the workspace makes. */
function respond(url: string) {
  const path = url.replace(/^\/api/, '').split('?')[0]!
  let body: unknown = {}
  if (path === `/sessions/${SESSION}`) {
    body = {
      sessionId: SESSION,
      coroutineCount: 1,
      eventCount: 1,
      coroutines: [
        // A big app's node: with its server round and origin, so the round bar and its list are part of the budget.
        { id: 'c-1', jobId: 'j-1', parentId: null, scopeId: 's', label: 'worker', state: 'ACTIVE', roundId: 'round:r0', origin: 'APP' },
      ],
      kind: 'ATTACHED',
      origin: 'REPORTED',
      captureStartAppNanos: 0,
    }
  } else if (path.endsWith('/rounds')) {
    // Several hundred rounds: the list is one request whatever it shows (the limit grows, pages do not).
    const limit = Number(new URLSearchParams(url.split('?')[1] ?? '').get('limit') ?? 50)
    const items = Array.from({ length: Math.min(limit, ROUND_COUNT) }, (_, i) => ({
      roundId: `round:r${i}`, anchorId: `r${i}`, anchorName: `request-${i}`, anchorOrigin: 'APP',
      anchorCreatedNanos: i, anchorCreatedSeq: i, inProgress: true, countsByState: {}, memberCount: 1,
      problem: false, problemKinds: [],
    }))
    body = { asOfSeq: 1, total: ROUND_COUNT, items, nextCursor: limit < ROUND_COUNT ? 'next' : null, unknownAtSeq: 0, asOfAppNanos: 1 }
  } else if (path.endsWith('/events')) {
    body = []
  } else if (path.endsWith('/metrics')) {
    body = {
      active: 1,
      peak: 1,
      throughputPerSec: 0,
      dispatcherUtilization: {},
      leaks: [],
      leakThresholdMs: 30_000,
    }
  } else if (path === '/health') {
    body = { sharingEnabled: false }
  } else if (path === `/sessions/${SESSION}/health`) {
    // The session health (Part 7): read once, then on the stream's own 10 s throttle.
    const capped = { items: [], total: 0 }
    body = {
      state: 'NO_PROBLEMS', asOf: { kind: 'LAST_EVENT', appNanos: 1, seq: 1 }, asOfSeq: 1, sessionOrigin: 'REPORTED',
      problemCount: 0, failedRoundCount: 0, ungroupedFailureCount: 0, possiblyBlockedCount: 0, incidents: capped,
      possiblyBlocked: { status: 'EVALUATED' }, observations: { longLived: capped, longSuspended: capped },
      counts: { app: 1, vizcore: 0, unknown: 0, untracked: 0 }, failuresAtLeast: false,
      vizcoreConnection: { captured: 0, failed: 0, failures: capped }, transport: {}, roundCount: 1, problemRoundCount: 0,
      problemRounds: capped,
    }
  } else if (path.endsWith('/timeline')) {
    body = { coroutineId: 'c-1', name: 'worker', state: 'ACTIVE', totalDuration: null, childrenIds: [], events: [] }
  } else if (path === `/validate/session/${SESSION}`) {
    body = { sessionId: SESSION, lastRun: null, newEventsSince: null }
  }
  return {
    ok: true,
    status: 200,
    headers: new Headers(),
    json: () => Promise.resolve(body),
  }
}

/** Stand-in for the browser EventSource the stream hook opens. */
class FakeEventSource {
  static instances: FakeEventSource[] = []
  readyState = 0
  onopen: (() => void) | null = null
  onerror: (() => void) | null = null
  private listeners = new Map<string, ((e: Event) => void)[]>()

  constructor(public url: string) {
    FakeEventSource.instances.push(this)
  }

  addEventListener(type: string, handler: (e: Event) => void) {
    const list = this.listeners.get(type) ?? []
    list.push(handler)
    this.listeners.set(type, list)
  }

  close() {
    this.readyState = 2
  }

  open() {
    this.readyState = 1
    this.onopen?.()
  }

  fail() {
    this.readyState = 2
    this.onerror?.()
  }

  emit(type: string, data: unknown) {
    for (const h of this.listeners.get(type) ?? []) h({ data: JSON.stringify(data) } as MessageEvent)
  }
}

const fetchMock = vi.fn((input: string) => Promise.resolve(respond(input)))

function requestCount() {
  return fetchMock.mock.calls.length + FakeEventSource.instances.length
}

function renderWorkspace() {
  const queryClient = new QueryClient({
    defaultOptions: {
      queries: { staleTime: 30_000, refetchOnWindowFocus: false, retry: shouldRetryQuery },
    },
  })
  return render(
    <QueryClientProvider client={queryClient}>
      <SessionWorkspace sessionId={SESSION} />
    </QueryClientProvider>,
  )
}

async function advance(ms: number, step = 1000, each?: (t: number) => void) {
  for (let t = 0; t < ms; t += step) {
    await act(async () => {
      each?.(t)
      await vi.advanceTimersByTimeAsync(step)
    })
  }
}

const MINUTE = 60_000

describe('SessionWorkspace request budget (#124 / #137)', { timeout: 30_000 }, () => {
  beforeEach(() => {
    vi.useFakeTimers()
    fetchMock.mockClear()
    FakeEventSource.instances = []
    apiClient.clearRateLimits()
    vi.stubGlobal('fetch', fetchMock)
    vi.stubGlobal('EventSource', FakeEventSource)
  })

  afterEach(() => {
    vi.unstubAllGlobals()
    vi.useRealTimers()
  })

  it('stream OFF: after the initial load an idle workspace issues no requests at all', async () => {
    renderWorkspace()
    await advance(MINUTE)
    const afterLoad = requestCount()
    expect(screen.getByRole('button', { name: 'Resume view' })).toBeInTheDocument()

    await advance(5 * MINUTE)

    // The old 2 s metrics + threads polls alone were 60 requests a minute.
    expect(requestCount() - afterLoad).toBe(0)
  })

  it('folding, unfolding, maximising and restoring the dock long after load refetch nothing', async () => {
    renderWorkspace()
    await advance(MINUTE)
    const afterLoad = requestCount()

    // Past every query's stale time: a panel that re-subscribed now would refetch.
    fireEvent.click(screen.getByRole('button', { name: 'Fold the dock' }))
    await advance(5_000)
    fireEvent.click(screen.getByRole('button', { name: 'Unfold the dock' }))
    fireEvent.click(screen.getByRole('button', { name: 'Maximise' }))
    await advance(5_000)
    fireEvent.click(screen.getByRole('button', { name: 'Restore dock' }))
    await advance(5_000)

    expect(requestCount() - afterLoad).toBe(0)
  })

  it('stream ON with a busy stream: stays well under 60 requests a minute', async () => {
    renderWorkspace()
    await advance(1000)
    fireEvent.click(screen.getByRole('button', { name: 'Resume view' }))
    await advance(1000)
    const es = FakeEventSource.instances[0]!
    act(() => es.open())
    // List 200 rounds before measuring: still one request per list read.
    for (let i = 0; i < 3; i++) {
      fireEvent.change(screen.getByRole('combobox', { name: 'Round' }), { target: { value: '__more__' } })
      await advance(1000)
    }
    expect(fetchMock.mock.calls.at(-1)![0]).toContain('limit=200')
    await advance(15_000)
    const start = requestCount()
    const fetchesBefore = fetchMock.mock.calls.length

    // A new event every second for five minutes.
    let seq = 1
    await advance(5 * MINUTE, 1000, () => {
      es.emit('CoroutineSuspended', {
        kind: 'CoroutineSuspended',
        sessionId: SESSION,
        seq: seq++,
        tsNanos: seq,
        coroutineId: 'c-1',
      })
    })

    const perMinute = (requestCount() - start) / 5
    // Three reads (snapshot, threads, metrics) per 5 s refresh window at most (36), plus the round list at most every
    // 15 s (Part 6, 4) — one request even with 200 rounds listed — and the session health at most every 10 s (Part 7, 6).
    expect(perMinute).toBeLessThanOrEqual(46)
    const healthReads = fetchMock.mock.calls.slice(fetchesBefore).filter(([u]) => String(u).endsWith(`/sessions/${SESSION}/health`))
    expect(healthReads.length / 5).toBeLessThanOrEqual(6)
    const roundReads = fetchMock.mock.calls.slice(fetchesBefore).filter(([u]) => String(u).includes('/rounds'))
    expect(roundReads.length).toBeGreaterThan(0)
    expect(roundReads.every(([u]) => String(u).includes('limit=200'))).toBe(true)
    // ...and the stream genuinely drives refreshes (the panels stay live).
    expect(perMinute).toBeGreaterThanOrEqual(30)
    // The full event history is never re-downloaded while SSE is connected.
    const eventsRefetches = fetchMock.mock.calls.filter(([u]) => String(u).endsWith('/events'))
    expect(eventsRefetches).toHaveLength(1) // the initial stored-events load only
    // One long-lived stream, not a reconnect storm.
    expect(FakeEventSource.instances).toHaveLength(1)
  })

  it('stream ON with a selected coroutine that keeps getting events: its timeline adds at most 6 a minute (Part 7)', async () => {
    renderWorkspace()
    await advance(1000)
    fireEvent.click(screen.getByRole('button', { name: 'Resume view' }))
    await advance(1000)
    const es = FakeEventSource.instances[0]!
    act(() => es.open())
    fireEvent.click(screen.getByRole('button', { name: 'List' }))
    fireEvent.click(screen.getByRole('button', { name: 'Open source for worker' }))
    await advance(15_000)
    const fetchesBefore = fetchMock.mock.calls.length
    const start = requestCount()
    let seq = 1
    await advance(5 * MINUTE, 1000, () => {
      es.emit('CoroutineSuspended', { kind: 'CoroutineSuspended', sessionId: SESSION, seq: seq++, tsNanos: seq, coroutineId: 'c-1' })
    })
    const timelineReads = fetchMock.mock.calls.slice(fetchesBefore).filter(([u]) => String(u).includes('/coroutines/c-1/timeline'))
    expect(timelineReads.length / 5).toBeLessThanOrEqual(6)
    expect(timelineReads.length).toBeGreaterThan(0)
    // 46 for the page (see above) + 6 for the selected coroutine's timeline.
    expect((requestCount() - start) / 5).toBeLessThanOrEqual(52)
  })

  it('stream ON but SSE down: falls back to a slow poll, still well under the limit', async () => {
    renderWorkspace()
    await advance(1000)
    fireEvent.click(screen.getByRole('button', { name: 'Resume view' }))
    await advance(1000)

    // Every connection attempt fails; the hook's bounded retries run out.
    await advance(MINUTE, 500, () => {
      for (const es of FakeEventSource.instances) if (es.readyState !== 2) es.fail()
    })
    const start = requestCount()
    await advance(5 * MINUTE, 1000, () => {
      for (const es of FakeEventSource.instances) if (es.readyState !== 2) es.fail()
    })

    const perMinute = (requestCount() - start) / 5
    // snapshot + metrics + threads every 10 s = 18/min, plus the round list every 20 s (Part 6) = 21, plus the session
    // health every 20 s (Part 7) = 24.
    expect(perMinute).toBeLessThanOrEqual(24)
    expect(perMinute).toBeGreaterThan(0)
    expect(screen.getByTestId('view-status')).toHaveTextContent(/connection lost/i)
  })

  it('a 429 is honoured: no request is sent again before Retry-After elapses', async () => {
    renderWorkspace()
    await advance(1000)
    fireEvent.click(screen.getByRole('button', { name: 'Resume view' }))
    await advance(1000)
    await advance(MINUTE, 500, () => {
      for (const es of FakeEventSource.instances) if (es.readyState !== 2) es.fail()
    })

    // From now on the server rate-limits every read for 30 s.
    fetchMock.mockImplementation(() =>
      Promise.resolve({
        ok: false,
        status: 429,
        headers: new Headers({ 'Retry-After': '30' }),
        json: () => Promise.reject(new Error('not json')),
      } as unknown as ReturnType<typeof respond>),
    )
    const start = fetchMock.mock.calls.length
    await advance(2 * MINUTE)

    // Fallback polling would be 18/min (36 in 2 min). After a 429 every read
    // fails fast locally until Retry-After (30 s) has passed, so only about one
    // request per cool-down window reaches the server.
    const sent = fetchMock.mock.calls.length - start
    expect(sent).toBeGreaterThan(0)
    expect(sent).toBeLessThanOrEqual(8)
    fetchMock.mockImplementation((input: string) => Promise.resolve(respond(input)))
  })
})
