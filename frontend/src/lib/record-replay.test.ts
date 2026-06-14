import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import type { VizEvent } from '@/types/api'

// --- Mock html2canvas (jsdom has no real renderer) ---
const mockHtml2Canvas = vi.fn()
vi.mock('html2canvas', () => ({
  default: mockHtml2Canvas,
}))

// --- Mock the SVG serialization reuse from export-svg ---
// record-replay reuses findSvgRoot to decide the SVG fast path. We mock the
// module so the SVG-native vs DOM-panel branch is exercised deterministically.
const mockFindSvgRoot = vi.fn()
vi.mock('./export-svg', () => ({
  findSvgRoot: (el: HTMLElement) => mockFindSvgRoot(el),
}))

// Import after the mocks are registered.
const { pickMimeType, estimateDurationMs, createReplayRecorder } = await import(
  './record-replay'
)

// ---------------------------------------------------------------------------
// Test doubles for the browser recording APIs jsdom lacks.
// ---------------------------------------------------------------------------

interface FakeTrack {
  requestFrame: ReturnType<typeof vi.fn>
}

interface FakeStream {
  getVideoTracks: () => FakeTrack[]
}

let fakeTrack: FakeTrack
let fakeStream: FakeStream
let recorderInstances: FakeMediaRecorder[]

interface FakeRecorderOptions {
  mimeType?: string
  videoBitsPerSecond?: number
}

class FakeMediaRecorder {
  static isTypeSupported = vi.fn<(t: string) => boolean>(() => true)
  stream: unknown
  options: FakeRecorderOptions | undefined
  state: 'inactive' | 'recording' | 'paused' = 'inactive'
  ondataavailable: ((e: { data: Blob }) => void) | null = null
  onstop: (() => void) | null = null
  start = vi.fn(() => {
    this.state = 'recording'
  })
  stop = vi.fn(() => {
    this.state = 'inactive'
    // Emit a chunk then fire onstop, mirroring the real lifecycle.
    this.ondataavailable?.({ data: new Blob(['chunk'], { type: 'video/webm' }) })
    this.onstop?.()
  })
  constructor(stream: unknown, options?: FakeRecorderOptions) {
    this.stream = stream
    this.options = options
    recorderInstances.push(this)
  }
}

function mockEvent(seq: number, tsNanos: number): VizEvent {
  return {
    sessionId: 's1',
    seq,
    tsNanos,
    kind: 'coroutine.created',
    coroutineId: `c-${seq}`,
    jobId: `j-${seq}`,
    parentCoroutineId: null,
    scopeId: 'scope-1',
    label: `e-${seq}`,
  } as VizEvent
}

describe('pickMimeType', () => {
  afterEach(() => vi.restoreAllMocks())

  it('returns the first codec MediaRecorder.isTypeSupported approves (vp9)', () => {
    const supported = vi.fn((t: string) => t === 'video/webm;codecs=vp9')
    expect(pickMimeType(supported)).toBe('video/webm;codecs=vp9')
  })

  it('cascades vp9 → vp8 when vp9 is unsupported', () => {
    const supported = vi.fn((t: string) => t === 'video/webm;codecs=vp8')
    expect(pickMimeType(supported)).toBe('video/webm;codecs=vp8')
  })

  it('cascades down to bare webm when only it is supported', () => {
    const supported = vi.fn((t: string) => t === 'video/webm')
    expect(pickMimeType(supported)).toBe('video/webm')
  })

  it('returns null when none are supported (Safari case)', () => {
    const supported = vi.fn(() => false)
    expect(pickMimeType(supported)).toBeNull()
  })
})

describe('estimateDurationMs', () => {
  it('sums clamp(gap_ms, 50, 2000) / speed across consecutive events', () => {
    // gaps in ns → ms: [10ms (→ clamped to 50), 5000ms (→ clamped to 2000)]
    const events = [
      mockEvent(0, 0),
      mockEvent(1, 10 * 1_000_000),
      mockEvent(2, (10 + 5000) * 1_000_000),
    ]
    // (50 + 2000) / 2 = 1025
    expect(estimateDurationMs(events, 2)).toBe(1025)
  })

  it('returns 0 for fewer than two events', () => {
    expect(estimateDurationMs([], 1)).toBe(0)
    expect(estimateDurationMs([mockEvent(0, 0)], 1)).toBe(0)
  })

  it('uses the same 50–2000ms base clamp as useReplay (speed 1)', () => {
    const events = [mockEvent(0, 0), mockEvent(1, 100 * 1_000_000)]
    expect(estimateDurationMs(events, 1)).toBe(100)
  })
})

describe('createReplayRecorder', () => {
  let panelEl: HTMLElement
  let canvasEl: HTMLCanvasElement
  let mockCtx: { drawImage: ReturnType<typeof vi.fn> }
  let mockLink: { href: string; download: string; click: ReturnType<typeof vi.fn> }
  const originalCreateObjectURL = URL.createObjectURL
  const originalRevokeObjectURL = URL.revokeObjectURL

  beforeEach(() => {
    vi.clearAllMocks()
    recorderInstances = []
    mockFindSvgRoot.mockReturnValue(null)

    fakeTrack = { requestFrame: vi.fn() }
    fakeStream = { getVideoTracks: () => [fakeTrack] }

    // Provide MediaRecorder on the global.
    vi.stubGlobal('MediaRecorder', FakeMediaRecorder)

    // Mock canvas + captureStream + 2d context.
    mockCtx = { drawImage: vi.fn() }
    panelEl = document.createElement('div')
    Object.defineProperty(panelEl, 'clientWidth', { value: 400, configurable: true })
    Object.defineProperty(panelEl, 'clientHeight', { value: 300, configurable: true })

    // document.createElement('canvas') returns a canvas whose getContext +
    // captureStream are mocked; document.createElement('a') tracked for download.
    canvasEl = document.createElement('canvas')
    ;(canvasEl as unknown as { captureStream: () => FakeStream }).captureStream = vi.fn(
      () => fakeStream
    )
    canvasEl.getContext = vi.fn(() => mockCtx) as unknown as HTMLCanvasElement['getContext']

    mockLink = { href: '', download: '', click: vi.fn() }
    const realCreate = document.createElement.bind(document)
    vi.spyOn(document, 'createElement').mockImplementation((tag: string) => {
      if (tag === 'canvas') return canvasEl
      if (tag === 'a') return mockLink as unknown as HTMLAnchorElement
      return realCreate(tag)
    })
    vi.spyOn(document.body, 'appendChild').mockImplementation((n) => n)
    vi.spyOn(document.body, 'removeChild').mockImplementation((n) => n)

    URL.createObjectURL = vi.fn(() => 'blob:rec-url')
    URL.revokeObjectURL = vi.fn()

    mockHtml2Canvas.mockResolvedValue({ width: 800, height: 600 })
  })

  afterEach(() => {
    URL.createObjectURL = originalCreateObjectURL
    URL.revokeObjectURL = originalRevokeObjectURL
    vi.restoreAllMocks()
    vi.unstubAllGlobals()
  })

  it('sizes the mirror canvas at 2x panel dimensions (D-27)', () => {
    createReplayRecorder({
      panelEl,
      speed: 1,
      mimeType: 'video/webm;codecs=vp9',
      sessionId: 'abc',
    })
    expect(canvasEl.width).toBe(800)
    expect(canvasEl.height).toBe(600)
  })

  it('constructs MediaRecorder with the chosen mimeType + 2.5Mbps bitrate', () => {
    createReplayRecorder({
      panelEl,
      speed: 1,
      mimeType: 'video/webm;codecs=vp8',
      sessionId: 'abc',
    })
    expect(recorderInstances).toHaveLength(1)
    expect(recorderInstances[0]!.options).toEqual({
      mimeType: 'video/webm;codecs=vp8',
      videoBitsPerSecond: 2_500_000,
    })
  })

  it('captureFrame on an SVG-native panel takes the SVG path (no html2canvas)', async () => {
    const svgEl = document.createElementNS('http://www.w3.org/2000/svg', 'svg')
    mockFindSvgRoot.mockReturnValue(svgEl)

    // Stub the Image used by the SVG fast path so onload fires synchronously.
    const FakeImage = class {
      onload: (() => void) | null = null
      onerror: (() => void) | null = null
      set src(_v: string) {
        // Fire onload on next microtask.
        queueMicrotask(() => this.onload?.())
      }
    }
    vi.stubGlobal('Image', FakeImage)

    const recorder = createReplayRecorder({
      panelEl,
      speed: 1,
      mimeType: 'video/webm',
      sessionId: 'abc',
    })
    await recorder.captureFrame()

    expect(mockHtml2Canvas).not.toHaveBeenCalled()
    expect(mockCtx.drawImage).toHaveBeenCalled()
    expect(fakeTrack.requestFrame).toHaveBeenCalled()
  })

  it('captureFrame on a DOM panel calls html2canvas then requestFrame', async () => {
    mockFindSvgRoot.mockReturnValue(null)
    const recorder = createReplayRecorder({
      panelEl,
      speed: 1,
      mimeType: 'video/webm',
      sessionId: 'abc',
    })
    await recorder.captureFrame()

    expect(mockHtml2Canvas).toHaveBeenCalledWith(panelEl, expect.any(Object))
    expect(mockCtx.drawImage).toHaveBeenCalled()
    expect(fakeTrack.requestFrame).toHaveBeenCalled()
  })

  it('stop() assembles a Blob and downloads a .webm', async () => {
    const recorder = createReplayRecorder({
      panelEl,
      speed: 1,
      mimeType: 'video/webm',
      sessionId: 'abc',
    })
    recorder.start()
    await recorder.stop()

    expect(mockLink.download).toMatch(/^abc-replay-\d{8}-\d{4}\.webm$/)
    expect(mockLink.click).toHaveBeenCalledOnce()
    expect(URL.revokeObjectURL).toHaveBeenCalledWith('blob:rec-url')
  })

  it('discard() stops the recorder without triggering a download', () => {
    const recorder = createReplayRecorder({
      panelEl,
      speed: 1,
      mimeType: 'video/webm',
      sessionId: 'abc',
    })
    recorder.start()
    recorder.discard()

    expect(recorderInstances[0]!.stop).toHaveBeenCalled()
    expect(mockLink.click).not.toHaveBeenCalled()
  })
})
