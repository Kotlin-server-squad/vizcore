import type { ReactNode } from 'react'
import { formatDuration } from '@/lib/format-duration'
import { headline, healthErrorText, qualifierLines, type ClockOptions, type Headline } from '@/lib/health-text'
import type { SessionHealthResult } from '@/hooks/use-session-health'
import type { ChecksStatus, FailureEntry, Incident } from '@/types/health'

/** What the checks status line shows (T-8): the last run, or that its status could not be read. */
export type ChecksLine =
  | {
      kind: 'status'
      status: ChecksStatus
      /** The newest event seq the page holds, so "N new events since" grows without a request; null to use the server's. */
      latestSeq: number | null
      /** Run the checks (not in a shared link). */
      onRun?: () => void
      /** Open the checks (not in a shared link). */
      onOpen?: () => void
    }
  | { kind: 'loading' }
  | { kind: 'unavailable' }

export interface ProblemsPanelProps {
  result: Pick<SessionHealthResult, 'health' | 'status' | 'error' | 'retry'>
  /** App-clock time of the session's first event: "as of" falls back to time into the capture (H-2). */
  captureStartAppNanos: number | null | undefined
  clock?: ClockOptions
  /** Whether the page draws this coroutine at its moment. */
  onPage: (coroutineId: string) => boolean
  /** A coroutine's name as the page knows it (for a round's anchor). */
  labelOf: (coroutineId: string) => string | undefined
  /** "Show": select the coroutine and open the Inspector (S-2, T-3). */
  onShow: (coroutineId: string) => void
  /** "Long-lived (n)": the tree's Long-lived filter (T-5). */
  onLongLived: () => void
  /** Said beside Long-lived when its filter cannot hold every coroutine the health counts. */
  longLivedNote?: string
  checks: ChecksLine
}

const SAMPLING =
  "vizcore samples an attached app's coroutines on a poll; one that starts and ends between two polls may not appear."
const count = (n: number) => n.toLocaleString('en-US')

/**
 * The Problems tab (S-8): what the session health says, and nothing else. No problem is derived here (D-8): the
 * headline, the incidents, the observations and every "not evaluated" come from `GET …/health` at the page's moment.
 */
export function ProblemsPanel(props: ProblemsPanelProps) {
  const { result, captureStartAppNanos, clock } = props
  const health = result.health

  if (!health) {
    const pending = result.status === 'pending' || result.status === 'updating'
    return (
      <div data-look-part="problems" className="space-y-3">
        <HeadlineView
          headline={{
            tone: 'neutral',
            text: pending ? 'Reading the session health…' : `Problems not evaluated (${healthErrorText(result.error)})`,
          }}
        />
        {result.status === 'error' && result.retry && <RetryButton onPress={result.retry} />}
        <ChecksStatusLine line={props.checks} />
      </div>
    )
  }

  const head = headline(health, captureStartAppNanos, clock)
  const lines = qualifierLines(health, captureStartAppNanos)
  const nothingDetected = health.state !== 'PROBLEMS' && health.state !== 'AWAITING_FIRST_DATA'
  const incidents = health.incidents
  return (
    <div data-look-part="problems" className="space-y-3">
      <HeadlineView headline={head} />
      {result.status === 'updating' && <p className="text-xs text-default-400">Updating to this moment…</p>}
      <ul className="space-y-0.5 text-sm text-default-500">
        {lines.map(line => (
          <li key={line} data-look-part="problems-line">
            {line}
          </li>
        ))}
        {/* H-4: where nothing was detected, say how capture works. */}
        {nothingDetected && (
          <li data-look-part="problems-line" className="text-default-400">
            {SAMPLING}
          </li>
        )}
      </ul>
      {incidents.items.length > 0 && (
        <ol className="space-y-2">
          {incidents.items.map(incident => (
            <IncidentView key={incident.id} incident={incident} {...props} />
          ))}
        </ol>
      )}
      {incidents.total > incidents.items.length && (
        <p className="text-xs text-default-500">
          Showing {count(incidents.items.length)} of {count(incidents.total)} problems; the round picker lists every problem
          round.
        </p>
      )}
      <Observations {...props} />
      <ChecksStatusLine line={props.checks} />
    </div>
  )
}

function HeadlineView({ headline: h }: { headline: Headline }) {
  const tone =
    h.tone === 'problem' ? 'text-danger' : h.tone === 'clean' ? 'text-success-600 dark:text-success' : 'text-foreground'
  return (
    <div data-testid="problems-headline" data-look-part="problems-headline" data-tone={h.tone}>
      <p className={`text-sm font-semibold ${tone}`}>{h.text}</p>
      {h.detail && <p className="text-sm text-default-600">{h.detail}</p>}
    </div>
  )
}

function RetryButton({ onPress }: { onPress: () => void }) {
  return (
    <button type="button" onClick={onPress} className="text-xs text-primary hover:underline">
      Try again
    </button>
  )
}

function IncidentView({ incident, ...props }: { incident: Incident } & ProblemsPanelProps) {
  if (incident.type === 'FAILED_ROUND') {
    const anchor = props.labelOf(incident.anchorId) ?? incident.anchorId
    const more = incident.failures.total - incident.failures.items.length
    return (
      <li data-testid="problem" data-look-part="problem" data-kind="failed-round" className="rounded-medium border border-default-200 p-2">
        <p className="text-sm font-semibold text-danger">Failed round · {anchor}</p>
        <p className="text-xs text-default-500">
          {count(incident.failures.total)} failed · {count(incident.cancelledAfter.total)} cancelled after the first failure
          (context, not a cause)
        </p>
        <ul className="mt-1 space-y-1">
          {incident.failures.items.map(f => (
            <FailureRow key={f.coroutineId} failure={f} {...props} />
          ))}
        </ul>
        {more > 0 && <p className="text-xs text-default-500">and {count(more)} more failed in this round</p>}
      </li>
    )
  }
  if (incident.type === 'UNGROUPED_FAILURE') {
    return (
      <li data-testid="problem" data-look-part="problem" data-kind="ungrouped-failure" className="rounded-medium border border-default-200 p-2">
        <p className="text-sm font-semibold text-danger">Failed · {incident.failure.name ?? incident.failure.coroutineId}</p>
        <p className="text-xs text-default-500">round unavailable: its ancestry could not be resolved</p>
        <ul className="mt-1">
          <FailureRow failure={incident.failure} {...props} />
        </ul>
      </li>
    )
  }
  const name = incident.name ?? incident.coroutineId
  return (
    <li data-testid="problem" data-look-part="problem" data-kind="possibly-blocked" className="rounded-medium border border-default-200 p-2">
      <ProblemRow
        id={incident.coroutineId}
        name={name}
        title={<span className="font-semibold text-warning-600 dark:text-warning">Possibly blocked · {name}</span>}
        detail={`running for ${formatDuration(incident.runningForNanos)} with no suspension observed · ${
          incident.lastThread ? `on ${incident.lastThread.threadName}` : 'last thread not observed for the current run'
        }`}
        {...props}
      />
    </li>
  )
}

function FailureRow({ failure, ...props }: { failure: FailureEntry } & ProblemsPanelProps) {
  const name = failure.name ?? failure.coroutineId
  const what = failure.detailsDropped
    ? 'details dropped'
    : `${failure.exceptionType ?? 'exception not reported'}${failure.message ? `: ${failure.message}` : ''}`
  return (
    <li>
      <ProblemRow id={failure.coroutineId} name={name} title={<span className="font-mono">{name}</span>} detail={what} {...props} />
    </li>
  )
}

function ProblemRow({
  id,
  name,
  title,
  detail,
  onPage,
  onShow,
}: { id: string; name: string; title: ReactNode; detail: string } & ProblemsPanelProps) {
  const drawn = onPage(id)
  return (
    <div className="flex flex-wrap items-baseline gap-x-2 text-sm">
      {title}
      <span className="min-w-0 flex-1 text-xs text-default-600 [overflow-wrap:anywhere]">
        {detail}
        {!drawn && <span className="text-default-400"> · not in the tree at this moment</span>}
      </span>
      <button
        type="button"
        data-look-part="problem-show"
        aria-label={`Show ${name}`}
        onClick={() => onShow(id)}
        className="shrink-0 rounded-small border border-default-300 px-2 text-xs text-primary hover:bg-default-100"
      >
        Show
      </button>
    </div>
  )
}

function Observations({ result, onLongLived, longLivedNote }: ProblemsPanelProps) {
  const health = result.health!
  const { longLived, longSuspended } = health.observations
  const viz = health.vizcoreConnection
  return (
    <div data-testid="observations" data-look-part="observations" className="space-y-1 border-t border-default-200 pt-2 text-sm">
      <p className="text-xs font-semibold uppercase tracking-wide text-default-500">Observations, not problems</p>
      <div className="flex flex-wrap items-baseline gap-x-3 gap-y-1">
        <button type="button" onClick={onLongLived} className="text-primary hover:underline">
          Long-lived ({count(longLived.total)})
        </button>
        <span className="text-default-600">Long suspended ({count(longSuspended.total)})</span>
        {longLivedNote && <span className="text-xs text-default-400">{longLivedNote}</span>}
      </div>
      {viz.captured > 0 && (
        <p className="text-default-600">
          vizcore connection: {count(viz.captured)} captured,{' '}
          {viz.failed === 0 ? 'no failed coroutines captured' : `${count(viz.failed)} failed`}
        </p>
      )}
    </div>
  )
}

function ChecksStatusLine({ line }: { line: ChecksLine }) {
  let text: string
  let action: ReactNode = null
  if (line.kind === 'loading') text = 'Instrumentation checks: reading…'
  else if (line.kind === 'unavailable') text = 'Instrumentation checks: status unavailable'
  else {
    const run = line.status.lastRun
    if (!run) {
      text = 'Instrumentation checks: not run'
      if (line.onRun) {
        action = (
          <button type="button" onClick={line.onRun} className="text-primary hover:underline">
            Run checks
          </button>
        )
      }
    } else {
      const since = line.latestSeq != null ? Math.max(0, line.latestSeq - run.checkedSeq) : line.status.newEventsSince
      text =
        run.failedRuleCount > 0
          ? `Instrumentation checks: failed at event ${count(run.checkedSeq)} · ${count(run.failedRuleCount)} ${run.failedRuleCount === 1 ? 'rule' : 'rules'} failed`
          : `Instrumentation checks: passed at event ${count(run.checkedSeq)}${since != null ? ` · ${count(since)} new events since` : ''}`
      if (line.onOpen) {
        action = (
          <button type="button" onClick={line.onOpen} className="text-primary hover:underline">
            Open checks
          </button>
        )
      }
    }
  }
  return (
    <p data-testid="checks-status" data-look-part="checks-status" className="flex flex-wrap items-baseline gap-x-2 text-sm text-default-600">
      <span>{text}</span>
      {action}
      <span className="text-xs text-default-400">They check vizcore&apos;s event stream, not your app.</span>
    </p>
  )
}
