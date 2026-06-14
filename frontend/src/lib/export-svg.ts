/**
 * Standalone style-inlined SVG export (EXPT-02, D-21).
 *
 * `findSvgRoot` auto-detects whether a panel renders a genuine `<svg viewBox>`
 * root. Per OQ-2 the HTML/CSS coroutine graph has no `<svg>` root and falls
 * back to PNG; only genuinely-SVG panels (DeadlockVisualization /
 * FlowParticlePath / SemaphoreGauge / JobStatusDisplay) export to SVG.
 *
 * `exportToSvg` deep-clones the SVG, inlines a WHITELIST of computed-style
 * props (avoiding the multi-MB bloat of a full computed-style dump — Pattern 4
 * / threat T-02-08), sets `xmlns` + explicit dimensions, and downloads
 * `image/svg+xml` using the export-png object-URL download idiom.
 */

/**
 * Computed-style properties inlined onto every element of the cloned SVG.
 * Whitelisted (10 props) per Pattern 4 — NOT a full computed-style dump.
 */
const STYLE_WHITELIST = [
  'fill',
  'stroke',
  'stroke-width',
  'stroke-dasharray',
  'color',
  'opacity',
  'font-family',
  'font-size',
  'font-weight',
  'transform',
] as const

/**
 * Find the panel's `<svg>` export root (D-21 auto-detect).
 *
 * @returns the element itself if it is an `<svg>`, else the first descendant
 *   `svg[viewBox]` / `svg[width]`, else `null` (panel is not SVG-native →
 *   the SVG export option is hidden and PNG remains).
 */
export function findSvgRoot(el: HTMLElement): SVGSVGElement | null {
  if (el.matches('svg')) {
    return el as unknown as SVGSVGElement
  }
  return el.querySelector<SVGSVGElement>('svg[viewBox], svg[width]')
}

/**
 * Inline the whitelisted computed styles from `source` onto `target`, then
 * recurse over matching child element pairs (cloned tree mirrors the live one).
 */
function inlineStyles(source: Element, target: Element): void {
  const computed = window.getComputedStyle(source)
  const decls: string[] = []
  for (const prop of STYLE_WHITELIST) {
    const value = computed.getPropertyValue(prop)
    if (value && value !== 'none' && value !== 'normal') {
      decls.push(`${prop}:${value}`)
    }
  }
  if (decls.length > 0) {
    const existing = target.getAttribute('style')
    target.setAttribute(
      'style',
      existing ? `${existing};${decls.join(';')}` : decls.join(';')
    )
  }

  const sourceChildren = source.children
  const targetChildren = target.children
  for (let i = 0; i < sourceChildren.length; i++) {
    const sc = sourceChildren[i]
    const tc = targetChildren[i]
    if (sc && tc) {
      inlineStyles(sc, tc)
    }
  }
}

/**
 * Serialize a live `<svg>` into a standalone, style-inlined SVG file and
 * trigger an in-browser download.
 *
 * @param svg - The live SVG root (from {@link findSvgRoot}).
 * @param filename - Optional filename (defaults to `coroutine-viz-{timestamp}.svg`).
 */
export function exportToSvg(svg: SVGSVGElement, filename?: string): void {
  const resolvedFilename = filename ?? `coroutine-viz-${Date.now()}.svg`

  const clone = svg.cloneNode(true) as SVGSVGElement

  // Inline whitelisted computed styles (Pattern 4).
  inlineStyles(svg, clone)

  // Ensure xmlns + explicit dimensions so the file renders standalone.
  clone.setAttribute('xmlns', 'http://www.w3.org/2000/svg')
  const rect = svg.getBoundingClientRect()
  const width = rect.width || Number(svg.getAttribute('width')) || 0
  const height = rect.height || Number(svg.getAttribute('height')) || 0
  if (width) clone.setAttribute('width', String(width))
  if (height) clone.setAttribute('height', String(height))

  // ADR-018 metadata comment.
  const metadata = `<!-- Exported by Coroutine Visualizer (ADR-018) at ${new Date().toISOString()} -->`
  const serialized = new XMLSerializer().serializeToString(clone)
  const svgString = `<?xml version="1.0" encoding="UTF-8"?>\n${metadata}\n${serialized}`

  const blob = new Blob([svgString], { type: 'image/svg+xml' })
  const url = URL.createObjectURL(blob)
  try {
    const link = document.createElement('a')
    link.href = url
    link.download = resolvedFilename
    document.body.appendChild(link)
    link.click()
    document.body.removeChild(link)
  } finally {
    URL.revokeObjectURL(url)
  }
}
