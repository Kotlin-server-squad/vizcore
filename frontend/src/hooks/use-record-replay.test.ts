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
  enterReplay?: () => void
  mimeTypeOverride?: string | null
}) {
  const enterReplay = opts.enterReplay ?? vi.fn()
  const getPanelEl = vi.fn(() => document.createElement('div'))
  return renderHook(
    ({ replay }: { replay: UseReplayReturn }) =>
      useRecordReplay(
        {
          getPanelEl,
          events: opts.events,
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

  it('scripted flow: startRecording enters replay, seeks 0, starts recorder, plays', () => {
    const enterReplay = vi.fn()
    const replay = makeReplay({ totalEvents: 3, currentIndex: 0 })
    const { result } = renderRecordHook({ events: makeEvents(3), replay, enterReplay })

    act(() => {
      result.current.startRecording()
    })

    expect(enterReplay).toHaveBeenCalledOnce()
    expect(replay.seekTo).toHaveBeenCalledWith(0)
    expect(mockCreateReplayRecorder).toHaveBeenCalledOnce()
    expect(mockRecorder.start).toHaveBeenCalledOnce()
    expect(replay.play).toHaveBeenCalledOnce()
    expect(result.current.isRecording).toBe(true)
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
