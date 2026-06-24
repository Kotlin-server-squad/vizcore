import { Tooltip } from '@heroui/react'
import { FiAlertCircle } from 'react-icons/fi'
import type { LeakDto } from '@/types/api'

interface LeakListProps {
  leaks: LeakDto[]
  /** Server-resolved (clamped) leak threshold in ms — surfaced in the tooltip. */
  leakThresholdMs: number
}

/**
 * Renders flagged long-lived coroutines as warning (amber) rows (D-07).
 *
 * A leak is "attention," not a hard failure, so it uses the `warning` token
 * (literal `bg-warning/10 text-warning` strings, IN-12) — `danger` stays
 * reserved for the FAILED coroutine state (StateIndicator locked contract).
 * Zero leaks render nothing (no badge / nominal state).
 */
export function LeakList({ leaks, leakThresholdMs }: LeakListProps) {
  if (leaks.length === 0) {
    return null
  }

  const tooltipCopy = `Active longer than the leak threshold (${formatMs(leakThresholdMs)}). May be a stuck or never-completing coroutine.`

  return (
    <div className="space-y-2">
      <Tooltip content={tooltipCopy}>
        <div
          aria-label={tooltipCopy}
          className="inline-flex items-center gap-2 rounded-lg bg-warning/10 px-2 py-1 text-warning"
        >
          <FiAlertCircle className="h-4 w-4 text-warning" />
          <span className="text-sm font-semibold">
            {leaks.length} potential leak{leaks.length === 1 ? '' : 's'}
          </span>
        </div>
      </Tooltip>

      <ul className="space-y-1">
        {leaks.map(leak => (
          <li
            key={leak.coroutineId}
            className="flex items-center gap-2 rounded-lg border border-warning/20 bg-warning/10 p-2 text-warning"
          >
            <FiAlertCircle className="h-4 w-4 text-warning" />
            <span className="text-sm">
              {leak.label ?? leak.coroutineId} · alive {formatMs(leak.aliveMs)}
            </span>
          </li>
        ))}
      </ul>
    </div>
  )
}

/** Human-readable duration: sub-second as "Nms", otherwise whole "Ns". */
function formatMs(ms: number): string {
  if (ms < 1000) {
    return `${Math.round(ms)}ms`
  }
  return `${Math.round(ms / 1000)}s`
}
