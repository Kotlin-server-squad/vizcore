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
        { id: 'c-1', jobId: 'j-1', parentId: null, scopeId: 's', label: 'worker', state: 'ACTIVE' },
      ],
    }
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
    expect(screen.getByText('Enable Live Stream')).toBeInTheDocument()

    await advance(5 * MINUTE)

    // The old 2 s metrics + threads polls alone were 60 requests a minute.
    expect(requestCount() - afterLoad).toBe(0)
  })

  it('stream ON with a busy stream: stays well under 60 requests a minute', async () => {
    renderWorkspace()
    await advance(1000)
    fireEvent.click(screen.getByText('Enable Live Stream'))
    await advance(1000)
    const es = FakeEventSource.instances[0]!
    act(() => es.open())
    const start = requestCount()

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
    // Three reads (snapshot, threads, metrics) per 5 s refresh window at most.
    expect(perMinute).toBeLessThanOrEqual(36)
    // ...and the stream genuinely drives refreshes (the panels stay live).
    expect(perMinute).toBeGreaterThanOrEqual(30)
    // The full event history is never re-downloaded while SSE is connected.
    const eventsRefetches = fetchMock.mock.calls.filter(([u]) => String(u).endsWith('/events'))
    expect(eventsRefetches).toHaveLength(1) // the initial stored-events load only
    // One long-lived stream, not a reconnect storm.
    expect(FakeEventSource.instances).toHaveLength(1)
  })

  it('stream ON but SSE down: falls back to a slow poll, still well under the limit', async () => {
    renderWorkspace()
    await advance(1000)
    fireEvent.click(screen.getByText('Enable Live Stream'))
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
    // snapshot + metrics + threads every 10 s = 18/min.
    expect(perMinute).toBeLessThanOrEqual(18)
    expect(perMinute).toBeGreaterThan(0)
    expect(screen.getByTestId('stream-status')).toHaveTextContent(/connection lost/i)
  })

  it('a 429 is honoured: no request is sent again before Retry-After elapses', async () => {
    renderWorkspace()
    await advance(1000)
    fireEvent.click(screen.getByText('Enable Live Stream'))
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
