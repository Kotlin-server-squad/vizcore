import html2canvas from 'html2canvas'

/**
 * ADR-018 info-header data composited onto PNG exports (D-08): session name,
 * timestamp, and event count. Optional so existing call sites keep working.
 */
export interface PngHeaderInfo {
  /** Session display name / id shown in the header. */
  sessionName: string
  /** Number of events captured in the exported view. */
  eventCount: number
}

/**
 * Build the styled ADR-018 info-header node (session name / ISO timestamp /
 * event count). Exported for tests and so callers can inspect the contract.
 */
export function buildPngHeader(info: PngHeaderInfo): HTMLElement {
  const header = document.createElement('div')
  header.dataset.exportHeader = 'true'
  header.style.cssText = [
    'display:flex',
    'justify-content:space-between',
    'align-items:center',
    'gap:16px',
    'padding:12px 16px',
    'background:#27272a',
    'color:#fafafa',
    "font-family:ui-sans-serif, system-ui, sans-serif",
    'font-size:13px',
    'border-bottom:1px solid #3f3f46',
  ].join(';')

  const name = document.createElement('span')
  name.style.fontWeight = '600'
  name.textContent = info.sessionName

  const meta = document.createElement('span')
  meta.style.color = '#a1a1aa'
  meta.textContent = `${new Date().toISOString()} · ${info.eventCount} events`

  header.appendChild(name)
  header.appendChild(meta)
  return header
}

/**
 * Captures an HTML element as a PNG image and triggers a download.
 *
 * When `header` is provided, the ADR-018 info header (session name, ISO
 * timestamp, event count) is composited above the captured region before the
 * html2canvas pass (D-08), then removed afterward.
 *
 * @param elementRef - The HTML element to capture
 * @param filename - Optional filename (defaults to `coroutine-viz-{timestamp}.png`)
 * @param header - Optional ADR-018 info-header data (D-08)
 */
export async function exportToPng(
  elementRef: HTMLElement,
  filename?: string,
  header?: PngHeaderInfo
): Promise<void> {
  const resolvedFilename =
    filename ?? `coroutine-viz-${Date.now()}.png`

  // D-08: composite the info header above the captured region before capture.
  let headerEl: HTMLElement | null = null
  if (header) {
    headerEl = buildPngHeader(header)
    elementRef.insertBefore(headerEl, elementRef.firstChild)
  }

  try {
    const canvas = await html2canvas(elementRef, {
      backgroundColor: '#18181b',
      scale: 2,
      useCORS: true,
      logging: false,
    })

    const blob = await new Promise<Blob>((resolve, reject) => {
      canvas.toBlob((b) => {
        if (b) {
          resolve(b)
        } else {
          reject(new Error('Failed to create PNG blob from canvas'))
        }
      }, 'image/png')
    })

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
  } finally {
    // Always remove the temporary header so the live DOM is left untouched.
    if (headerEl && headerEl.parentNode) {
      headerEl.parentNode.removeChild(headerEl)
    }
  }
}
