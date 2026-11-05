import { useMemo, useState } from 'react'
import { Button } from '@heroui/react'
import { formatDuration } from '@/lib/format-duration'
import { earliestThreadEvent, groupThreadsByName } from '@/lib/thread-groups'
import type { ThreadActivity } from '@/types/api'

/** Rows shown before "Show all". */
const ROW_CAP = 10

/**
 * Threads as a table grouped by thread name (triage T-5): how many JVM threads
 * carried the name, how many coroutines were seen on them, how many
 * assignments were observed, and when the last one happened, as time into the
 * capture. It replaces a list with one row per event.
 */
export function ThreadTable({
  threadActivity,
  captureStartNanos,
}: {
  threadActivity: ThreadActivity
  /** The first retained event's timestamp; null falls back to the first thread event. */
  captureStartNanos: number | null
}) {
  const [showAll, setShowAll] = useState(false)
  const groups = useMemo(() => groupThreadsByName(threadActivity), [threadActivity])
  const start = captureStartNanos ?? earliestThreadEvent(threadActivity)

  if (groups.length === 0) {
    return <p className="text-sm text-default-400">No thread activity captured yet.</p>
  }

  const shown = showAll ? groups : groups.slice(0, ROW_CAP)
  return (
    <div className="space-y-2">
      <div className="overflow-x-auto">
        <table aria-label="Threads by name" className="w-full text-left text-sm">
          <thead className="text-xs text-default-500">
            <tr>
              <th scope="col" className="py-1 pr-4 font-semibold">Thread</th>
              <th scope="col" className="py-1 pr-4 text-right font-semibold">JVM threads</th>
              <th scope="col" className="py-1 pr-4 text-right font-semibold">Coroutines seen</th>
              <th scope="col" className="py-1 pr-4 text-right font-semibold">Assignments observed</th>
              <th scope="col" className="py-1 text-right font-semibold">Last assignment</th>
            </tr>
          </thead>
          <tbody>
            {shown.map(g => (
              <tr key={g.name} className="border-t border-default-200">
                <th scope="row" className="py-1 pr-4 font-mono text-xs font-normal">{g.name}</th>
                <td className="py-1 pr-4 text-right tabular-nums">{g.jvmThreads}</td>
                <td className="py-1 pr-4 text-right tabular-nums">{g.coroutinesSeen}</td>
                <td className="py-1 pr-4 text-right tabular-nums">{g.assignments}</td>
                <td className="py-1 text-right tabular-nums">
                  {g.lastAssignedNanos === null || start === null
                    ? 'none observed'
                    : // Thread activity outlives a trimmed event history: an
                      // assignment older than the first retained event is not "0 ms in".
                      g.lastAssignedNanos < start
                      ? 'before the retained events'
                      : `${formatDuration(g.lastAssignedNanos - start)} in`}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      <p className="text-xs text-default-400">Last assignment: time since the first retained event.</p>
      {groups.length > ROW_CAP && (
        <Button size="sm" variant="light" aria-expanded={showAll} onPress={() => setShowAll(v => !v)}>
          {showAll ? 'Show fewer' : `Show all (${groups.length})`}
        </Button>
      )}
    </div>
  )
}
