import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { exportEventsToJson } from './export-json'

/**
 * Capture the content + type passed to the Blob constructor for JSON exports.
 */
function withJsonCapture(
  fn: (getJson: () => string, getType: () => string) => void
): void {
  let captured = ''
  let capturedType = ''
  const origBlob = globalThis.Blob
  globalThis.Blob = class extends origBlob {
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    constructor(parts?: any[], options?: { type?: string }) {
      super(parts, options)
      if (parts && options?.type?.includes('json')) {
        captured = parts.map(String).join('')
        capturedType = options.type
      }
    }
  } as typeof Blob

  try {
    fn(
      () => captured,
      () => capturedType
    )
  } finally {
    globalThis.Blob = origBlob
  }
}

describe('exportEventsToJson (D-22)', () => {
  let mockLink: { href: string; download: string; click: ReturnType<typeof vi.fn> }
  const originalCreateObjectURL = URL.createObjectURL
  const originalRevokeObjectURL = URL.revokeObjectURL

  beforeEach(() => {
    vi.clearAllMocks()
    URL.createObjectURL = vi.fn().mockReturnValue('blob:mock-json-url')
    URL.revokeObjectURL = vi.fn()

    mockLink = { href: '', download: '', click: vi.fn() }
    const originalCreateElement = document.createElement.bind(document)
    vi.spyOn(document, 'createElement').mockImplementation((tag: string) => {
      if (tag === 'a') return mockLink as unknown as HTMLAnchorElement
      return originalCreateElement(tag)
    })
    vi.spyOn(document.body, 'appendChild').mockImplementation((node) => node)
    vi.spyOn(document.body, 'removeChild').mockImplementation((node) => node)
  })

  afterEach(() => {
    URL.createObjectURL = originalCreateObjectURL
    URL.revokeObjectURL = originalRevokeObjectURL
    vi.restoreAllMocks()
  })

  it('stringifies the events array and downloads an application/json blob', () => {
    const events = [
      { kind: 'CoroutineCreated', coroutineId: '1' },
      { kind: 'CoroutineCompleted', coroutineId: '1' },
    ]

    withJsonCapture((getJson, getType) => {
      exportEventsToJson(events)

      expect(getType()).toBe('application/json')
      expect(JSON.parse(getJson())).toEqual(events)
    })
  })

  it('triggers download with the correct custom filename', () => {
    exportEventsToJson([], 'session-abc-events-20260612-1430.json')
    expect(mockLink.download).toBe('session-abc-events-20260612-1430.json')
    expect(mockLink.click).toHaveBeenCalledOnce()
  })

  it('uses a default timestamped filename when none provided', () => {
    exportEventsToJson([])
    expect(mockLink.download).toMatch(/^coroutine-viz-events-\d+\.json$/)
  })

  it('revokes the object URL after download', () => {
    exportEventsToJson([])
    expect(URL.createObjectURL).toHaveBeenCalledOnce()
    expect(URL.revokeObjectURL).toHaveBeenCalledWith('blob:mock-json-url')
  })
})
