/**
 * The dock's size rules (spec S-1): it starts at 40 % of the window, the main
 * view keeps at least 320 px, and the size and the fold are remembered per
 * browser. Maximise is not remembered: coming back to a page with no dock
 * would hide the Inspector with no hint of where it went.
 */

/** The main view's smallest height (S-1). */
export const MAIN_MIN_PX = 320
/** The smallest dock that still shows a line of its content under the tab row. */
export const DOCK_MIN_PX = 120
/** The gap between the main view and the dock (`gap-3`). */
const GAP_PX = 12
/** One arrow-key step of the separator; PageUp/PageDown move four. */
export const DOCK_STEP_PX = 24

export const DOCK_STORAGE_KEY = 'vizcore.dock'

/** The dock's starting height as a share of the window (S-1). A look may start it smaller. */
export const DOCK_SHARE = 0.4

export interface DockPrefs {
  /** Null until the user has resized the dock. */
  height: number | null
  folded: boolean
}

export function initialDockHeight(windowHeight: number, share: number = DOCK_SHARE): number {
  return Math.round(windowHeight * share)
}

/**
 * The largest dock that leaves the main view its minimum, given the height the
 * two share. Zero means "not measured yet" (first render, or jsdom): no limit.
 */
export function dockMaxHeight(sharedHeight: number): number {
  if (sharedHeight <= 0) return Number.POSITIVE_INFINITY
  return Math.max(DOCK_MIN_PX, sharedHeight - MAIN_MIN_PX - GAP_PX)
}

export function clampDockHeight(height: number, sharedHeight: number): number {
  return Math.round(Math.min(dockMaxHeight(sharedHeight), Math.max(DOCK_MIN_PX, height)))
}

export function readDockPrefs(): DockPrefs {
  try {
    const raw = localStorage.getItem(DOCK_STORAGE_KEY)
    if (!raw) return { height: null, folded: false }
    const parsed = JSON.parse(raw) as Partial<Record<keyof DockPrefs, unknown>>
    return {
      height: typeof parsed.height === 'number' && Number.isFinite(parsed.height) ? parsed.height : null,
      folded: parsed.folded === true,
    }
  } catch {
    return { height: null, folded: false }
  }
}

export function writeDockPrefs(prefs: DockPrefs): void {
  try {
    localStorage.setItem(DOCK_STORAGE_KEY, JSON.stringify(prefs))
  } catch {
    // Private mode or storage full: the dock still works for this visit.
  }
}
