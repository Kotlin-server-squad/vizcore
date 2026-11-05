import type { DockTabId } from '@/components/workspace/DockTabs'
import type { RunOutcome } from './run-outcome'

/**
 * Which dock tab a session opens on (spec S-5), and the tab the user last
 * picked for it. Problems is not offered until Part 7, so the "detected
 * problem" and "attached app" rows open on the Inspector, whose empty state
 * is the session summary.
 */
export const DOCK_TABS_KEY = 'vizcore.dockTabs'
const KEEP = 50
const TABS: readonly DockTabId[] = ['inspector', 'evidence', 'events', 'learn']

export function openingDockTab({
  startedAsDemo,
  outcome,
}: {
  /** Opened with its scenario: from the Gallery or the new-demo dialog. */
  startedAsDemo: boolean
  outcome: RunOutcome | null
}): DockTabId {
  return startedAsDemo && (outcome === 'notStarted' || outcome === 'running') ? 'learn' : 'inspector'
}

function read(): Array<[string, DockTabId]> {
  try {
    const parsed: unknown = JSON.parse(localStorage.getItem(DOCK_TABS_KEY) ?? '[]')
    if (!Array.isArray(parsed)) return []
    return parsed.filter(
      (e): e is [string, DockTabId] =>
        Array.isArray(e) && typeof e[0] === 'string' && TABS.includes(e[1] as DockTabId),
    )
  } catch {
    return []
  }
}

export function rememberedDockTab(sessionId: string): DockTabId | null {
  return read().find(([id]) => id === sessionId)?.[1] ?? null
}

/** Most recent first; the oldest sessions fall off after [KEEP]. */
export function rememberDockTab(sessionId: string, tab: DockTabId): void {
  const next = [[sessionId, tab] as [string, DockTabId], ...read().filter(([id]) => id !== sessionId)].slice(0, KEEP)
  try {
    localStorage.setItem(DOCK_TABS_KEY, JSON.stringify(next))
  } catch {
    // Private mode or storage full: the tab still works for this visit.
  }
}
