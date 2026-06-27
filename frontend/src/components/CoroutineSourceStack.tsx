import { useState } from 'react'
import { Button, Card, CardBody, Chip, Tooltip } from '@heroui/react'
import { useCoroutineTimeline, useSuspensionPoints } from '@/hooks/use-timeline'
import { isUserFrame } from '@/lib/source-frames'
import { toastSuccess } from '@/lib/toast'
import { EmptyState } from './EmptyState'

/**
 * Delta S1 jump-to-code (v1): copy the already-rendered `file:line` label (or bare
 * `file` when no line is known) to the clipboard and confirm with a success toast.
 * Takes the final label rather than `(file, line)` so a null line never produces a
 * literal `file:null` on the clipboard (WR-02) — this view feeds nullable lines that
 * the original CoroutineTimelineView did not.
 */
function copyFileLine(label: string): void {
  void navigator.clipboard?.writeText(label)
  toastSuccess(`Copied ${label}`)
}

/**
 * Renders a suspension point's `file:line`. User-code frames (per `isUserFrame`)
 * become an accent jump-to-code target wrapped in a "Copy file:line" tooltip;
 * library frames keep the call site's existing dimmed `text-default-500` text.
 *
 * LOCKED v1 — copied verbatim from `CoroutineTimelineView.tsx` (do NOT re-author).
 */
function TimelineSourceRef({
  fn,
  fileName,
  lineNumber,
}: {
  fn: string | null | undefined
  fileName: string
  lineNumber?: number | null
}) {
  const label = lineNumber ? `${fileName}:${lineNumber}` : fileName

  if (isUserFrame(fn)) {
    return (
      <Tooltip content="Copy file:line">
        <span
          role="button"
          tabIndex={0}
          aria-label={`Jump to code: ${label}`}
          className="font-semibold text-primary cursor-pointer hover:underline"
          onClick={() => copyFileLine(label)}
          onKeyDown={(e) => {
            if (e.key === 'Enter' || e.key === ' ') {
              e.preventDefault()
              copyFileLine(label)
            }
          }}
        >
          {label}
        </span>
      </Tooltip>
    )
  }

  return <>{label}</>
}

interface SourceFrame {
  function: string | null | undefined
  fileName?: string | null
  lineNumber?: number | null
  reason?: string | null
}

/**
 * A single `file:line` frame row (sketch 002-B shape). User frames are bold/accent
 * jump targets; library frames stay dimmed and inert. Same literal markup as the
 * suspension-point rows in `CoroutineTimelineView.tsx` (lines 177–200).
 */
function SourceFrameRow({ frame }: { frame: SourceFrame }) {
  return (
    <div className="flex items-start gap-3 p-3 bg-default-50 rounded-lg">
      {frame.reason && (
        <Chip size="sm" variant="flat" color="secondary">
          {frame.reason}
        </Chip>
      )}
      <div className="flex-1">
        <div className="font-mono text-sm font-semibold">{frame.function}</div>
        {frame.fileName && (
          <div className="text-xs text-default-500">
            <TimelineSourceRef
              fn={frame.function}
              fileName={frame.fileName}
              lineNumber={frame.lineNumber}
            />
          </div>
        )}
      </div>
    </div>
  )
}

interface CoroutineSourceStackProps {
  sessionId: string
  coroutineId: string | null
}

/**
 * Headerless source-stack body for the in-dock source panel (Surface 002).
 *
 * Collapsed default (sketch 002-A): compact `Created at` / `Suspended at`
 * `file:line` chips (the `srcchip` token) each with a Jump affordance for fast
 * triage. A "Show full stack" expander reveals the full creation + suspension
 * stack (sketch 002-B) in two sections — `Created at — creation stack` /
 * `Suspended at — last observed` — with user frames bold/accent jump targets and
 * library frames dimmed/inert.
 *
 * Reuses the LOCKED jump-to-code (copy + toast) and the lazy
 * `useCoroutineTimeline`/`useSuspensionPoints` data path verbatim. The timeline
 * query stays disabled (no eager fetch) while `coroutineId` is null. The stat
 * cards, time-distribution bar, and timeline-events list from
 * `CoroutineTimelineView` are intentionally suppressed (source-focused, D-03).
 */
export function CoroutineSourceStack({ sessionId, coroutineId }: CoroutineSourceStackProps) {
  // Compact-chips → expand-to-full-stack toggle (PD-08). Default collapsed.
  const [expanded, setExpanded] = useState(false)
  const { data: timeline, isLoading, isError } = useCoroutineTimeline(
    sessionId,
    coroutineId ?? undefined,
  )
  const suspensionPoints = useSuspensionPoints(sessionId, coroutineId ?? undefined)

  if (isLoading) {
    return (
      <div className="flex items-center justify-center py-8">
        <div className="text-center">
          <div className="animate-spin rounded-full h-8 w-8 border-b-2 border-primary mx-auto mb-2" />
          <p className="text-sm text-default-500">Loading timeline...</p>
        </div>
      </div>
    )
  }

  if (isError) {
    return (
      <div className="py-8 text-center">
        <p className="text-sm font-semibold text-default-700">Couldn't load coroutine source.</p>
        <p className="mt-1 text-xs text-default-500">
          Close the drawer and reopen it to retry, or check that the session is still live.
        </p>
      </div>
    )
  }

  // `Created at` derives from the started/created timeline event's source frame
  // (the timeline view surfaces no dedicated creation stack — D-03 read_first note).
  // We do NOT fall back to events[0]: the first event is frequently a
  // `coroutine.suspended`, and rendering a *suspension* frame under the "Created at"
  // heading is a factually wrong attribution (and duplicates a Suspended-at row).
  const startedEvent = timeline?.events.find(
    (e) => e.kind === 'coroutine.started' || e.kind === 'coroutine.created',
  )
  const creationFrame = startedEvent?.suspensionPoint

  const hasCreationFrame = !!creationFrame?.fileName
  const hasSuspensionFrames = suspensionPoints.length > 0

  if (!hasCreationFrame && !hasSuspensionFrames) {
    return (
      <EmptyState
        title="No source attribution yet"
        description="This coroutine has no recorded creation or suspension frames yet. Source appears once the coroutine suspends or is captured by DebugProbes."
      />
    )
  }

  // The first suspension point backs the collapsed "Suspended at" compact chip.
  const firstSuspension = hasSuspensionFrames ? suspensionPoints[0] : undefined

  return (
    <div className="space-y-4">
      {/* Collapsed default (PD-08): compact `Created at` / `Suspended at`
          `file:line` chips with a Jump affordance for fast triage. */}
      <div className="space-y-2">
        {hasCreationFrame && creationFrame!.fileName && (
          <div className="flex items-center gap-2">
            <span className="text-xs uppercase tracking-wide text-default-400">Created at</span>
            <span className="font-mono text-xs text-primary bg-primary/10 px-2 py-0.5 rounded-md">
              <TimelineSourceRef
                fn={creationFrame!.function}
                fileName={creationFrame!.fileName}
                lineNumber={creationFrame!.lineNumber}
              />
            </span>
          </div>
        )}

        {firstSuspension?.fileName && (
          <div className="flex items-center gap-2">
            <span className="text-xs uppercase tracking-wide text-default-400">Suspended at</span>
            <span className="font-mono text-xs text-primary bg-primary/10 px-2 py-0.5 rounded-md">
              <TimelineSourceRef
                fn={firstSuspension.function}
                fileName={firstSuspension.fileName}
                lineNumber={firstSuspension.lineNumber}
              />
            </span>
          </div>
        )}

        <Button
          size="sm"
          variant="light"
          className="text-xs"
          onPress={() => setExpanded((prev) => !prev)}
        >
          {expanded ? 'Hide full stack' : 'Show full stack'}
        </Button>
      </div>

      {/* Expanded (PD-07): the full creation + suspension stacks, headers at
          text-sm font-semibold (NOT text-lg). */}
      {expanded && (
        <div className="space-y-6">
          {hasCreationFrame && (
            <div className="space-y-2">
              <h3 className="text-sm font-semibold">Created at — creation stack</h3>
              <SourceFrameRow
                frame={{
                  function: creationFrame!.function,
                  fileName: creationFrame!.fileName,
                  lineNumber: creationFrame!.lineNumber,
                  reason: creationFrame!.reason,
                }}
              />
            </div>
          )}

          {hasSuspensionFrames && (
            <div className="space-y-2">
              <h3 className="text-sm font-semibold">Suspended at — last observed</h3>
              {suspensionPoints.map((point) => (
                <Card key={point.eventSeq} shadow="none">
                  <CardBody className="p-0">
                    <SourceFrameRow
                      frame={{
                        function: point.function,
                        fileName: point.fileName,
                        lineNumber: point.lineNumber,
                        reason: point.reason,
                      }}
                    />
                  </CardBody>
                </Card>
              ))}
            </div>
          )}
        </div>
      )}
    </div>
  )
}
