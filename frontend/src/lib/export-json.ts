/**
 * JSON event export (D-22).
 *
 * Serializes the normalized event array and triggers an in-browser download
 * as `application/json`. Reuses the object-URL download idiom from
 * `export-png.ts` (URL.createObjectURL → temp anchor → revokeObjectURL).
 */

/**
 * Download the given events array as a pretty-printed `.json` file.
 *
 * @param events - The normalized event array to serialize.
 * @param filename - Optional filename (defaults to `coroutine-viz-events-{timestamp}.json`).
 */
export function exportEventsToJson(
  events: readonly unknown[],
  filename?: string
): void {
  const resolvedFilename =
    filename ?? `coroutine-viz-events-${Date.now()}.json`

  const json = JSON.stringify(events, null, 2)
  const blob = new Blob([json], { type: 'application/json' })

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
