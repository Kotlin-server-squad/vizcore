import { Card, CardBody, Chip, Tooltip } from '@heroui/react'
import { useCoroutineTimeline, useSuspensionPoints } from '@/hooks/use-timeline'
import { isUserFrame } from '@/lib/source-frames'
import { toastSuccess } from '@/lib/toast'
import { EmptyState } from './EmptyState'

/**
 * Delta S1 jump-to-code (v1, LOCKED — copied verbatim from CoroutineTimelineView):
 * copy `{fileName}:{lineNumber}` to the clipboard and confirm with a success toast.
 */
function copyFileLine(fileName: string, lineNumber: number | string): void {
  const target = `${fileName}:${lineNumber}`
  void navigator.clipboard?.writeText(target)
  toastSuccess(`Copied ${target}`)
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
          onClick={() => copyFileLine(fileName, lineNumber!)}
          onKeyDown={(e) => {
            if (e.key === 'Enter' || e.key === ' ') {
              e.preventDefault()
              copyFileLine(fileName, lineNumber!)
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
 * Headerless source-stack body for the per-coroutine source drawer (D-03/D-04/D-08/D-09).
 *
 * Renders the `Created at` / `Suspended at` `file:line` frames per sketch 002-B,
 * reusing the LOCKED jump-to-code (copy + toast) and the lazy
 * `useCoroutineTimeline`/`useSuspensionPoints` data path verbatim. The timeline
 * query stays disabled (no eager fetch) while `coroutineId` is null. The stat
 * cards, time-distribution bar, and timeline-events list from
 * `CoroutineTimelineView` are intentionally suppressed (source-focused, D-03).
 */
export function CoroutineSourceStack({ sessionId, coroutineId }: CoroutineSourceStackProps) {
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

  // `Created at` derives from the first/started timeline event's source frame
  // (the timeline view surfaces no dedicated creation stack — D-03 read_first note).
  const startedEvent = timeline?.events.find(
    (e) => e.kind === 'coroutine.started' || e.kind === 'coroutine.created',
  )
  const creationFrame = startedEvent?.suspensionPoint ?? timeline?.events[0]?.suspensionPoint

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

  return (
    <div className="space-y-6">
      {hasCreationFrame && (
        <div className="space-y-2">
          <h3 className="text-lg font-semibold">Created at</h3>
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
          <h3 className="text-lg font-semibold">Suspended at</h3>
          {suspensionPoints.map((point, i) => (
            <Card key={i} shadow="none">
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
  )
}
