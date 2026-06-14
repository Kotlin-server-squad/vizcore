import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { exportToSvg, findSvgRoot } from './export-svg'

const SVG_NS = 'http://www.w3.org/2000/svg'

/**
 * Helper that temporarily overrides globalThis.Blob to capture SVG content
 * passed to the Blob constructor during export.
 */
function withSvgCapture(
  fn: (getSvg: () => string, getType: () => string) => void
): void {
  let captured = ''
  let capturedType = ''
  const origBlob = globalThis.Blob
  globalThis.Blob = class extends origBlob {
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    constructor(parts?: any[], options?: { type?: string }) {
      super(parts, options)
      if (parts && options?.type?.includes('svg')) {
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

/** Build a real SVG element tree (createElementNS so it is a true SVGSVGElement). */
function buildSvg(): SVGSVGElement {
  const svg = document.createElementNS(SVG_NS, 'svg') as SVGSVGElement
  svg.setAttribute('viewBox', '0 0 100 100')
  const rect = document.createElementNS(SVG_NS, 'rect')
  rect.setAttribute('x', '10')
  rect.setAttribute('y', '10')
  svg.appendChild(rect)

  // jsdom does not lay out elements — stub getBoundingClientRect.
  vi.spyOn(svg, 'getBoundingClientRect').mockReturnValue({
    width: 320,
    height: 240,
    x: 0,
    y: 0,
    top: 0,
    right: 320,
    bottom: 240,
    left: 0,
    toJSON: () => ({}),
  })
  return svg
}

describe('findSvgRoot (D-21 auto-detect)', () => {
  it('returns the element itself when it is an <svg>', () => {
    const svg = buildSvg()
    expect(findSvgRoot(svg as unknown as HTMLElement)).toBe(svg)
  })

  it('returns a descendant svg[viewBox] when the panel wraps one', () => {
    const panel = document.createElement('div')
    const svg = buildSvg()
    panel.appendChild(svg)
    expect(findSvgRoot(panel)).toBe(svg)
  })

  it('returns null for an HTML/CSS panel with no <svg> root (OQ-2 coroutine graph)', () => {
    const panel = document.createElement('div')
    panel.innerHTML = '<div class="node">coroutine graph</div>'
    expect(findSvgRoot(panel)).toBeNull()
  })
})

describe('exportToSvg', () => {
  let svg: SVGSVGElement
  let mockLink: { href: string; download: string; click: ReturnType<typeof vi.fn> }
  const originalCreateObjectURL = URL.createObjectURL
  const originalRevokeObjectURL = URL.revokeObjectURL

  beforeEach(() => {
    vi.clearAllMocks()
    svg = buildSvg()

    URL.createObjectURL = vi.fn().mockReturnValue('blob:mock-svg-url')
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

  it('serializes the svg with xmlns + explicit dimensions and downloads image/svg+xml', () => {
    withSvgCapture((getSvg, getType) => {
      exportToSvg(svg)

      const out = getSvg()
      expect(out).toContain('<svg')
      expect(out).toContain('xmlns="http://www.w3.org/2000/svg"')
      expect(out).toContain('width="320"')
      expect(out).toContain('height="240"')
      expect(out).toContain('<rect')
      expect(getType()).toBe('image/svg+xml')
    })
  })

  it('inlines whitelisted computed styles onto cloned elements', () => {
    // Force getComputedStyle to return a whitelisted prop so the inline path runs.
    vi.spyOn(window, 'getComputedStyle').mockReturnValue({
      getPropertyValue: (prop: string) =>
        prop === 'fill' ? 'rgb(255, 0, 0)' : '',
      length: 0,
    } as unknown as CSSStyleDeclaration)

    withSvgCapture((getSvg) => {
      exportToSvg(svg)
      expect(getSvg()).toContain('fill:rgb(255, 0, 0)')
    })
  })

  it('includes the ADR-018 metadata comment', () => {
    withSvgCapture((getSvg) => {
      exportToSvg(svg)
      expect(getSvg()).toContain('ADR-018')
    })
  })

  it('triggers download with the correct custom filename', () => {
    exportToSvg(svg, 'my-diagram.svg')
    expect(mockLink.download).toBe('my-diagram.svg')
    expect(mockLink.click).toHaveBeenCalledOnce()
  })

  it('uses a default timestamped filename when none provided', () => {
    const before = Date.now()
    exportToSvg(svg)
    const after = Date.now()

    expect(mockLink.download).toMatch(/^coroutine-viz-\d+\.svg$/)
    const match = mockLink.download.match(/coroutine-viz-(\d+)\.svg/)
    expect(match).not.toBeNull()
    const ts = Number(match![1])
    expect(ts).toBeGreaterThanOrEqual(before)
    expect(ts).toBeLessThanOrEqual(after)
  })

  it('revokes the object URL after download', () => {
    exportToSvg(svg)
    expect(URL.createObjectURL).toHaveBeenCalledOnce()
    expect(URL.revokeObjectURL).toHaveBeenCalledWith('blob:mock-svg-url')
  })
})
