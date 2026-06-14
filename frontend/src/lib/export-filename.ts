/**
 * Builds an export filename following the UI-SPEC §4 locked naming pattern:
 *   `{sessionId}-{panel}-{yyyyMMdd-HHmm}.{ext}`
 *
 * Example: buildExportFilename('abc', 'tree', 'png', new Date(2026, 5, 12, 14, 30))
 *   => 'abc-tree-20260612-1430.png'
 */

/** Panel keys allowed by the UI-SPEC file-naming contract. */
export type ExportPanel =
  | 'tree'
  | 'graph'
  | 'threads'
  | 'timeline'
  | 'events'
  | 'replay'

/** Export file extensions produced by this phase. */
export type ExportExt = 'png' | 'svg' | 'webm' | 'json'

function pad2(n: number): string {
  return n < 10 ? `0${n}` : String(n)
}

/**
 * Format a Date as the UI-SPEC `yyyyMMdd-HHmm` stamp using LOCAL time.
 */
function formatStamp(date: Date): string {
  const yyyy = date.getFullYear()
  const MM = pad2(date.getMonth() + 1)
  const dd = pad2(date.getDate())
  const HH = pad2(date.getHours())
  const mm = pad2(date.getMinutes())
  return `${yyyy}${MM}${dd}-${HH}${mm}`
}

/**
 * Build the locked export filename.
 *
 * @param sessionId - The session identifier.
 * @param panel - The active panel key (tree/graph/threads/timeline/events/replay).
 * @param ext - The file extension (png/svg/webm/json).
 * @param date - The timestamp to encode (defaults to now).
 */
export function buildExportFilename(
  sessionId: string,
  panel: ExportPanel,
  ext: ExportExt,
  date: Date = new Date()
): string {
  return `${sessionId}-${panel}-${formatStamp(date)}.${ext}`
}
