import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { act, renderHook } from '@testing-library/react'
import type { UseReplayReturn } from './use-replay'

// --- Mock the pure pipeline so no browser API is touched ---
const mockRecorder = {
  start: vi.fn(),
  captureFrame: vi.fn(() => Promise.resolve()),
  stop: vi.fn(() => Promise.resolve('sess-replay-20260614-1200.webm')),
  discard: vi.fn(),
}
const mockCreateReplayRecorder = vi.fn((..._args: unknown[]) => mockRecorder)
const mockEstimateDurationMs = vi.fn((..._args: unknown[]) => 0)
const mockPickMimeType = vi.fn<() => string | null>(() => 'video/webm;codecs=vp9')

vi.mock('@/lib/record-replay', () => ({
  createReplayRecorder: (...args: unknown[]) => mockCreateReplayRecorder(...args),
  estimateDurationMs: (...args: unknown[]) => mockEstimateDurationMs(...args),
  pickMimeType: () => mockPickMimeType(),
}))

// --- Mock the toasts so we can assert the locked copy ---
const mockToastSuccess = vi.fn()
const mockToastError = vi.fn()
vi.mock('@/lib/toast', () => ({
  toastSuccess: (t: string) => mockToastSuccess(t),
  toastError: (t: string) => mockToastError(t),
}))

const { useRecordReplay } = await import('./use-record-replay')

interface TimedEvent {
  tsNanos: number
}

function makeEvents(n: number): TimedEvent[] {
  return Array.from({ length: n }, (_, i) => ({ tsNanos: i * 1_000_000 }))
}

/** A minimal, mutable replay stub exposing the fields the hook reads. */
function makeReplay(overrides: Partial<UseReplayReturn> = {}): UseReplayReturn {
  return {
    isPlaying: false,
    currentIndex: 0,
    currentEvent: null,
    speed: 1,
    progress: 0,
    visibleEvents: [],
    totalEvents: 0,
    play: vi.fn(),
    pause: vi.fn(),
    stepForward: vi.fn(),
    stepBack: vi.fn(),
    reset: vi.fn(),
    seekTo: vi.fn(),
    setSpeed: vi.fn(),
    ...overrides,
  } as UseReplayReturn
}

function renderRecordHook(opts: {
  events: TimedEvent[]
  replay: UseReplayReturn
  enterReplay?: (snapshot: readonly TimedEvent[]) => void
  mimeTypeOverride?: string | null
}) {
  const enterReplay = opts.enterReplay ?? vi.fn()
  const getPanelEl = vi.fn(() => document.createElement('div'))
  return renderHook(
    ({ replay }: { replay: UseReplayReturn }) =>
      useRecordReplay(
        {
          getPanelEl,
          getRecordSnapshot: () => opts.events,
          replay,
          enterReplay,
          sessionId: 'sess',
        },
        opts.mimeTypeOverride,
      ),
    { initialProps: { replay: opts.replay } },
  )
}

describe('useRecordReplay', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mockPickMimeType.mockReturnValue('video/webm;codecs=vp9')
    mockEstimateDurationMs.mockReturnValue(0)
  })

  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('scripted flow: startRecording freezes the snapshot, then arms (seek 0, start, play)', () => {
    const enterReplay = vi.fn()
    const events = makeEvents(3)
    const replay = makeReplay({ totalEvents: 3, currentIndex: 0 })
    const { result } = renderRecordHook({ events, replay, enterReplay })

    act(() => {
      result.current.startRecording()
    })

    // WR-08: the SAME frozen snapshot is passed to enterReplay (computed once).
    expect(enterReplay).toHaveBeenCalledOnce()
    expect(enterReplay).toHaveBeenCalledWith(events)
    // CR-01: the arm effect fires once totalEvents matches the frozen length,
    // seeking to 0 (NOT the end) before starting the recorder + playback.
    expect(replay.seekTo).toHaveBeenCalledWith(0)
    expect(mockCreateReplayRecorder).toHaveBeenCalledOnce()
    expect(mockRecorder.start).toHaveBeenCalledOnce()
    expect(replay.play).toHaveBeenCalledOnce()
    expect(result.current.isRecording).toBe(true)
  })

  it('CR-01: isArming is true between request and arm, suppressing the end-seek window', () => {
    // The arm effect needs the frozen snapshot length to settle. Render with a
    // replay whose totalEvents does NOT yet match (simulating the snapshot not
    // applied), assert isArming stays true and the recorder is NOT yet built;
    // then settle totalEvents and assert it arms.
    const events = makeEvents(3)
    const replay = makeReplay({ totalEvents: 0, currentIndex: 0 })
    const { result, rerender } = renderRecordHook({ events, replay })

    act(() => {
      result.current.startRecording()
    })
    // Snapshot not yet applied (totalEvents 0 !== 3) → still arming, no recorder.
    expect(result.current.isArming).toBe(true)
    expect(mockCreateReplayRecorder).not.toHaveBeenCalled()

    // Snapshot applied: totalEvents now matches the frozen length → arm fires.
    act(() => {
      rerender({ replay: makeReplay({ totalEvents: 3, currentIndex: 0 }) })
    })
    expect(result.current.isArming).toBe(false)
    expect(result.current.isRecording).toBe(true)
    expect(replay.seekTo).not.toHaveBeenCalled() // the original stub's seekTo
    expect(mockCreateReplayRecorder).toHaveBeenCalledOnce()
  })

  it('auto-stops at the last event and fires a success toast (D-06)', async () => {
    const replay = makeReplay({ totalEvents: 3, currentIndex: 0 })
    const { result, rerender } = renderRecordHook({ events: makeEvents(3), replay })

    await act(async () => {
      result.current.startRecording()
    })

    // Advance the replay cursor to the last event — the auto-stop effect runs.
    await act(async () => {
      rerender({ replay: makeReplay({ totalEvents: 3, currentIndex: 2 }) })
    })

    expect(mockRecorder.stop).toHaveBeenCalledOnce()
    expect(mockToastSuccess).toHaveBeenCalledWith(
      'Saved sess-replay-20260614-1200.webm',
    )
    expect(result.current.isRecording).toBe(false)
  })

  it('CR-02: stop() fires exactly once even if the last-index effect re-runs mid-stop', async () => {
    // Make stop() resolve on a controllable deferred so we can re-run the
    // capture effect (a trailing totalEvents/currentIndex churn) WHILE the
    // first stop() is still pending — the stoppingRef guard must prevent a
    // second stop() call.
    let resolveStop: (name: string) => void = () => {}
    mockRecorder.stop.mockImplementationOnce(
      () => new Promise<string>(res => { resolveStop = res }),
    )

    const replay = makeReplay({ totalEvents: 3, currentIndex: 0 })
    const { result, rerender } = renderRecordHook({ events: makeEvents(3), replay })

    await act(async () => {
      result.current.startRecording()
    })

    // Reach the last index → first auto-stop begins (stop() pending).
    await act(async () => {
      rerender({ replay: makeReplay({ totalEvents: 3, currentIndex: 2 }) })
    })
    expect(mockRecorder.stop).toHaveBeenCalledTimes(1)

    // A trailing identity churn at the SAME last index re-runs the capture
    // effect before stop() resolves — guard must keep it a single stop().
    await act(async () => {
      rerender({ replay: makeReplay({ totalEvents: 3, currentIndex: 2 }) })
    })
    expect(mockRecorder.stop).toHaveBeenCalledTimes(1)

    // Resolve the in-flight stop(); the success toast fires once.
    await act(async () => {
      resolveStop('sess-replay-20260614-1200.webm')
    })
    expect(mockRecorder.stop).toHaveBeenCalledTimes(1)
    expect(mockToastSuccess).toHaveBeenCalledOnce()
    expect(result.current.isRecording).toBe(false)
  })

  it('WR-07: a hidden-tab discard during the stop() await is a no-op (no discard, no double terminal)', async () => {
    // stop() is pending when the tab is hidden; discardRecording must early-out
    // via stoppingRef so it neither calls recorder.discard() nor fires the abort
    // toast — the in-flight download wins.
    let resolveStop: (name: string) => void = () => {}
    mockRecorder.stop.mockImplementationOnce(
      () => new Promise<string>(res => { resolveStop = res }),
    )

    const replay = makeReplay({ totalEvents: 3, currentIndex: 0 })
    const { result, rerender } = renderRecordHook({ events: makeEvents(3), replay })

    await act(async () => {
      result.current.startRecording()
    })
    await act(async () => {
      rerender({ replay: makeReplay({ totalEvents: 3, currentIndex: 2 }) })
    })
    // Auto-stop has begun (stop() pending, stoppingRef set).
    expect(mockRecorder.stop).toHaveBeenCalledTimes(1)

    // Background the tab mid-stop → discard must be suppressed.
    act(() => {
      Object.defineProperty(document, 'visibilityState', {
        value: 'hidden',
        configurable: true,
      })
      document.dispatchEvent(new Event('visibilitychange'))
    })
    expect(mockRecorder.discard).not.toHaveBeenCalled()
    expect(mockToastError).not.toHaveBeenCalled()

    await act(async () => {
      resolveStop('sess-replay-20260614-1200.webm')
    })
    // The download path completed; the success toast fired, abort toast never did.
    expect(mockToastSuccess).toHaveBeenCalledOnce()
    expect(mockToastError).not.toHaveBeenCalled()

    Object.defineProperty(document, 'visibilityState', {
      value: 'visible',
      configurable: true,
    })
  })

  it('opens the confirm modal when estimate > 120s instead of recording (D-26)', () => {
    mockEstimateDurationMs.mockReturnValue(150_000)
    const replay = makeReplay({ totalEvents: 3, currentIndex: 0 })
    const { result } = renderRecordHook({ events: makeEvents(3), replay })

    act(() => {
      result.current.startRecording()
    })

    expect(result.current.confirmOpen).toBe(true)
    expect(result.current.confirmEstimateMs).toBe(150_000)
    expect(mockCreateReplayRecorder).not.toHaveBeenCalled()
  })

  it('confirmRecord proceeds; cancelConfirm does not record (D-26)', () => {
    mockEstimateDurationMs.mockReturnValue(150_000)
    const replay = makeReplay({ totalEvents: 3, currentIndex: 0 })
    const { result } = renderRecordHook({ events: makeEvents(3), replay })

    act(() => {
      result.current.startRecording()
    })
    act(() => {
      result.current.cancelConfirm()
    })
    expect(result.current.confirmOpen).toBe(false)
    expect(mockCreateReplayRecorder).not.toHaveBeenCalled()

    act(() => {
      result.current.startRecording()
    })
    act(() => {
      result.current.confirmRecord()
    })
    expect(result.current.confirmOpen).toBe(false)
    expect(mockCreateReplayRecorder).toHaveBeenCalledOnce()
  })

  it('aborts + discards on visibilitychange→hidden with the locked copy (D-24)', () => {
    const replay = makeReplay({ totalEvents: 3, currentIndex: 0 })
    const { result } = renderRecordHook({ events: makeEvents(3), replay })
    act(() => {
      result.current.startRecording()
    })

    act(() => {
      Object.defineProperty(document, 'visibilityState', {
        value: 'hidden',
        configurable: true,
      })
      document.dispatchEvent(new Event('visibilitychange'))
    })

    expect(mockRecorder.discard).toHaveBeenCalled()
    expect(mockToastError).toHaveBeenCalledWith(
      'Recording cancelled — keep the tab visible while recording.',
    )
    expect(result.current.isRecording).toBe(false)

    // restore
    Object.defineProperty(document, 'visibilityState', {
      value: 'visible',
      configurable: true,
    })
  })

  it('stopRecording discards the partial with the locked Stop copy (D-23)', () => {
    const replay = makeReplay({ totalEvents: 3, currentIndex: 0 })
    const { result } = renderRecordHook({ events: makeEvents(3), replay })

    act(() => {
      result.current.startRecording()
    })
    act(() => {
      result.current.stopRecording()
    })

    expect(mockRecorder.discard).toHaveBeenCalled()
    expect(mockToastError).toHaveBeenCalledWith('Recording discarded')
    expect(result.current.isRecording).toBe(false)
  })

  it('is inert when pickMimeType returns null (Safari, D-25)', () => {
    const replay = makeReplay({ totalEvents: 3, currentIndex: 0 })
    const { result } = renderRecordHook({
      events: makeEvents(3),
      replay,
      mimeTypeOverride: null,
    })

    expect(result.current.canRecord).toBe(false)

    act(() => {
      result.current.startRecording()
    })
    expect(mockCreateReplayRecorder).not.toHaveBeenCalled()
    expect(result.current.isRecording).toBe(false)
  })
})
