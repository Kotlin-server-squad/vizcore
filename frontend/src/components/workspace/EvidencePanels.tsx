import { useMemo, useState, type ReactNode } from 'react'
import { Spinner } from '@heroui/react'
import { FiArrowUpRight, FiChevronDown, FiChevronRight } from 'react-icons/fi'
import { ChannelPanel } from '../channels/ChannelPanel'
import { FlowPanel } from '../flow/FlowPanel'
import { SyncPanel } from '../sync/SyncPanel'
import { JobPanel } from '../jobs/JobPanel'
import { ThreadTable } from '../ThreadTable'
import { DispatcherOverview } from '../DispatcherOverview'
import { LiveDataNotice } from '../replay/LiveDataNotice'
import { MomentNote, type Moment } from './MomentNote'
import { plural, resourceSummary, type EvidenceCounts } from '@/lib/evidence-summary'
import { groupThreadsByName, threadSummary } from '@/lib/thread-groups'
import type { EventCategories } from '@/hooks/use-event-categories'
import type { Rung } from '@/lib/fidelity-rung'
import type { ThreadActivity, VizEvent } from '@/types/api'
import type { Selection } from '@/lib/selection'
import type { CoroutinePicker } from '../sync/CoroutineName'

interface EvidencePanelsProps {
  sessionId: string
  rung: Rung
  categories: EventCategories
  /** Distinct resources and job events in the session's events. */
  counts: EvidenceCounts
  replayActive: boolean
  readOnly: boolean
  /** Lanes for the threads table — server snapshot, or projected in replay. */
  threadActivity: ThreadActivity | undefined
  /** The first retained event's timestamp, for "time into the capture". */
  captureStartNanos: number | null
  /**
   * Whether the session's event history has been read. "None captured" is a
   * claim only a successful read can make (H-3); before it, a section says
   * loading, and after a failed read, unavailable.
   */
  history?: 'ready' | 'loading' | 'unavailable'
  /**
   * The events the page shows: live, paused, or at the replay cursor. Locks
   * and permits, Channels, Flows and Jobs follow them, so their facts refresh
   * with each new event; without them each reads the stored history.
   */
  events?: readonly VizEvent[]
  /** The page's one selection (S-2). A resource unfolds its section; a coroutine is marked in it. */
  selection?: Selection | null
  /** Selects a coroutine named in Locks and permits, when the page can (S-2). */
  picker?: CoroutinePicker
  /** Selects a resource on the page: a channel picked in Channels (S-2). */
  onSelectResource?: (selection: Selection) => void
  /**
   * The moment the page shows outside the live view (Part 8, M-6, M-7): named at the top, and Dispatchers then reads
   * [threadActivity] (projected at the moment) instead of the live session.
   */
  moment?: Moment | null
}

/** The section a selected resource lives in. */
function sectionOf(selection: Selection | null): string | null {
  if (selection?.kind !== 'resource') return null
  return selection.resource === 'channel' ? 'Channels' : 'Locks and permits'
}

/**
 * A wrapper-only capability: captured, or absent-and-explainable.
 *
 * The unlock copy is the product's growth path written down, so it names the
 * exact change (`VizMutex`, `.instrumented()`, `InstrumentedChannel`) rather
 * than "enable instrumentation" — a developer cannot act on the latter.
 */
interface Capability {
  title: string
  present: boolean
  summary: string
  whatYouWouldSee: string
  /** Empty when no single change unlocks it. */
  unlockWith: string
  panel: ReactNode
}

/**
 * The Evidence tab (spec S-4, triage T-5): what the resources did. Six
 * sections, always listed in the same order, each folded to a one-line
 * summary. A section with nothing captured is not hidden — it says "none
 * captured" and, on a real session, the one change that would capture it
 * (D-5). Nothing is locked on a demo: it has full fidelity already, and
 * telling its user to swap in `VizMutex` names code that is not theirs.
 *
 * Folded sections are hidden, not unmounted: every panel subscribes at page
 * load, exactly as before, so opening one later never refetches. Locks and
 * permits, Channels, Flows and Jobs read the events the page shows, so they
 * refresh live and follow the replay cursor; a selected resource unfolds its
 * section (S-2).
 */
export function EvidencePanels({
  sessionId,
  rung,
  categories,
  counts,
  replayActive,
  readOnly,
  threadActivity,
  captureStartNanos,
  history = 'ready',
  events,
  selection = null,
  picker,
  onSelectResource,
  moment = null,
}: EvidencePanelsProps) {
  // A resource already selected when Evidence mounts has its section open.
  const [open, setOpen] = useState<ReadonlySet<string>>(() => {
    const section = sectionOf(selection)
    return new Set(section ? [section] : [])
  })
  const toggle = (title: string) =>
    setOpen(prev => {
      const next = new Set(prev)
      if (next.has(title)) next.delete(title)
      else next.add(title)
      return next
    })

  // A resource selected anywhere on the page unfolds its section (S-2). Each
  // selection is a new object, so choosing the same lock again re-opens it.
  const [seenSelection, setSeenSelection] = useState<Selection | null>(selection)
  if (selection !== seenSelection) {
    setSeenSelection(selection)
    const section = sectionOf(selection)
    if (section && !open.has(section)) setOpen(prev => new Set(prev).add(section))
  }
  const selectedResource = selection?.kind === 'resource' ? selection : null
  const selectedCoroutineId = selection?.kind === 'coroutine' ? selection.id : null

  const groups = useMemo(() => groupThreadsByName(threadActivity), [threadActivity])
  const dispatchers = useMemo(() => {
    const names = new Set<string>()
    for (const events of Object.values(threadActivity ?? {})) {
      for (const e of events) if (e.dispatcherName) names.add(e.dispatcherName)
    }
    return names.size
  }, [threadActivity])

  const capabilities: Capability[] = [
    {
      title: 'Locks and permits',
      present: categories.hasSyncPrimitives,
      summary: resourceSummary([plural(counts.locks, 'lock'), plural(counts.semaphores, 'semaphore')]) ?? 'captured',
      whatYouWouldSee: 'Who holds each mutex and semaphore, who is queued behind it, and for how long.',
      unlockWith: 'Swap Mutex() for VizMutex() in the code you want to watch.',
      panel: (
        <SyncPanel
          sessionId={sessionId}
          events={events}
          selectedResourceId={selectedResource && selectedResource.resource !== 'channel' ? selectedResource.id : null}
          selectedCoroutineId={selectedCoroutineId}
          picker={picker}
          revealKey={selection}
        />
      ),
    },
    {
      title: 'Channels',
      present: categories.hasChannels,
      summary: resourceSummary([plural(counts.channels, 'channel')]) ?? 'captured',
      whatYouWouldSee: 'Send and receive pairs, buffer occupancy, and which side is waiting.',
      unlockWith: 'Create the channel with InstrumentedChannel(...).',
      panel: (
        <ChannelPanel
          sessionId={sessionId}
          events={events}
          focusChannelId={selectedResource?.resource === 'channel' ? selectedResource.id : null}
          revealKey={selection}
          onSelectChannel={
            onSelectResource ? id => onSelectResource({ kind: 'resource', resource: 'channel', id }) : undefined
          }
        />
      ),
    },
    {
      title: 'Flows',
      present: categories.hasFlowOps,
      summary: resourceSummary([plural(counts.flows, 'flow')]) ?? 'captured',
      whatYouWouldSee: 'Emissions, operator stages, and where a slow collector is stalling the producer.',
      unlockWith: 'Wrap the flow you care about with .instrumented().',
      panel: <FlowPanel sessionId={sessionId} events={events} />,
    },
    {
      title: 'Jobs',
      present: categories.hasJobs,
      summary: resourceSummary([plural(counts.jobEvents, 'job event')]) ?? 'captured',
      whatYouWouldSee: 'Job lifecycle, cancellation propagation, and who is joining on whom.',
      // Job events are not confirmed wrapper-only (the same reason `hasJobs`
      // does not promote a session to instrumented), so there is no honest
      // one-line unlock to offer for them.
      unlockWith: '',
      panel: <JobPanel sessionId={sessionId} events={events} />,
    },
  ]

  return (
    <div className="space-y-2">
      <MomentNote moment={moment} />
      {/* Threads is not rung-gated: the attach path reports thread and
          dispatcher assignment, so it is populated at every rung. */}
      <Section
        title="Threads"
        summary={threadActivity ? threadSummary(groups) : 'loading'}
        open={open.has('Threads')}
        onToggle={() => toggle('Threads')}
        // The table is projected from the replay cursor, so it needs no
        // "showing current data" warning (D-17).
        replayActive={false}
      >
        {threadActivity ? (
          <ThreadTable threadActivity={threadActivity} captureStartNanos={captureStartNanos} />
        ) : (
          <div className="py-4 text-center text-default-400">
            <Spinner size="sm" className="mb-2" />
            <p>Loading thread activity...</p>
          </div>
        )}
      </Section>
      <Section
        title="Dispatchers"
        summary={dispatchers > 0 ? plural(dispatchers, 'dispatcher') : 'none named in the thread activity'}
        open={open.has('Dispatchers')}
        onToggle={() => toggle('Dispatchers')}
        // At a moment it reads the page's activity, so it follows the cursor like Threads (Part 8, M-7).
        replayActive={replayActive && !moment}
      >
        <DispatcherOverview
          sessionId={sessionId}
          enabled={!readOnly && !moment}
          activity={moment ? threadActivity : undefined}
        />
      </Section>
      {capabilities.map(c =>
        c.present ? (
          <Section
            key={c.title}
            title={c.title}
            summary={c.summary}
            open={open.has(c.title)}
            onToggle={() => toggle(c.title)}
            // Read from the page's events, so it follows the replay cursor like Threads (D-17).
            replayActive={false}
          >
            {c.panel}
          </Section>
        ) : (
          <div
            key={c.title}
            data-testid={`evidence-section-${c.title}`}
            className="rounded-medium border border-dashed border-default-200 px-3 py-2 text-sm"
          >
            <span className="font-semibold">{c.title}</span>
            <span className="text-default-500">
              {history === 'ready'
                ? ' · none captured'
                : history === 'loading'
                  ? ' · loading'
                  : " · unavailable: the session's events could not be read"}
            </span>
            {/* Only a real session can be told what to change, and only once
                we know nothing was captured. */}
            {history === 'ready' && rung !== 'demo' && c.unlockWith !== '' && (
              <>
                <p className="mt-1 text-default-500">{c.whatYouWouldSee}</p>
                <p className="mt-1 flex items-start gap-2 text-primary">
                  <FiArrowUpRight className="mt-0.5 h-4 w-4 shrink-0" aria-hidden="true" />
                  <span>{c.unlockWith}</span>
                </p>
              </>
            )}
          </div>
        ),
      )}
    </div>
  )
}

/** A section folded to its title and summary; a disclosure button with `aria-expanded` (triage accessibility). */
function Section({
  title,
  summary,
  open,
  onToggle,
  replayActive,
  children,
}: {
  title: string
  summary: string
  open: boolean
  onToggle: () => void
  /** These panels query the live session, not the replay cursor (D-17). */
  replayActive: boolean
  children: ReactNode
}) {
  const bodyId = `evidence-${title.toLowerCase().replace(/\W+/g, '-')}`
  return (
    <div data-testid={`evidence-section-${title}`} className="rounded-medium border border-default-200">
      <button
        type="button"
        aria-expanded={open}
        aria-controls={bodyId}
        onClick={onToggle}
        className="flex w-full items-center gap-2 px-3 py-2 text-left text-sm"
      >
        {open ? <FiChevronDown className="h-4 w-4" aria-hidden /> : <FiChevronRight className="h-4 w-4" aria-hidden />}
        <span className="font-semibold">{title}</span>{' '}
        <span className="text-default-500">· {summary}</span>
      </button>
      {/* Hidden, not unmounted: the panels subscribe to the session's events
          at page load, as before, and never re-subscribe to a stale query (a
          protected refetch in the shared view, T-03-22). */}
      <div id={bodyId} hidden={!open} className="space-y-2 px-3 pb-3">
        {replayActive && <LiveDataNotice />}
        {children}
      </div>
    </div>
  )
}
