import type { ReactNode } from 'react'
import { Card, CardBody, Spinner } from '@heroui/react'
import { ChannelPanel } from '../channels/ChannelPanel'
import { FlowPanel } from '../flow/FlowPanel'
import { SyncPanel } from '../sync/SyncPanel'
import { JobPanel } from '../jobs/JobPanel'
import { ThreadTimeline } from '../ThreadTimeline'
import { DispatcherOverview } from '../DispatcherOverview'
import { LiveDataNotice } from '../replay/LiveDataNotice'
import { LockedPanel } from '../LockedPanel'
import type { EventCategories } from '@/hooks/use-event-categories'
import type { Rung } from '@/lib/fidelity-rung'
import type { ThreadActivity } from '@/types/api'

/**
 * A wrapper-only capability: present, or absent-and-explainable.
 *
 * The unlock copy is the product's growth path written down, so it names the
 * exact change (`VizMutex`, `.instrumented()`, `InstrumentedChannel`) rather
 * than "enable instrumentation" — a developer cannot act on the latter.
 */
interface Capability {
  key: string
  title: string
  present: boolean
  whatYouWouldSee: string
  unlockWith: string
  panel: ReactNode
}

interface EvidencePanelsProps {
  sessionId: string
  rung: Rung
  categories: EventCategories
  replayActive: boolean
  readOnly: boolean
  streamEnabled: boolean
  /** Lanes for the threads panel — server snapshot, or projected in replay. */
  threadActivity: ThreadActivity | undefined
}

/**
 * The evidence section: what this session can show beyond the coroutine tree.
 *
 * These were four capability-gated tabs, which had the effect of *shrinking the
 * tab bar* when you attached to a real app — the product's whole growth path
 * hidden behind a conditional render. Here a capability the session cannot
 * produce is not removed; it says what it would show and what unlocks it (D-5).
 *
 * Full width beneath the canvas/inspector grid (M-1): these are session-scoped
 * tables, not per-coroutine detail, and none of them is legible at 320px.
 *
 * Nothing is locked on a demo session — a demo has full fidelity already, and
 * telling its user to swap in `VizMutex` names code that is not theirs.
 */
export function EvidencePanels({
  sessionId,
  rung,
  categories,
  replayActive,
  readOnly,
  streamEnabled,
  threadActivity,
}: EvidencePanelsProps) {
  const capabilities: Capability[] = [
    {
      key: 'sync',
      title: 'Lock contention',
      present: categories.hasSyncPrimitives,
      whatYouWouldSee:
        'Who holds each mutex and semaphore, who is queued behind it, and for how long.',
      unlockWith: 'Swap Mutex() for VizMutex() in the code you want to watch.',
      panel: <SyncPanel sessionId={sessionId} />,
    },
    {
      key: 'flow',
      title: 'Flow backpressure',
      present: categories.hasFlowOps,
      whatYouWouldSee:
        'Emissions, operator stages, and where a slow collector is stalling the producer.',
      unlockWith: 'Wrap the flow you care about with .instrumented().',
      panel: <FlowPanel sessionId={sessionId} />,
    },
    {
      key: 'channels',
      title: 'Channel traffic',
      present: categories.hasChannels,
      whatYouWouldSee:
        'Send and receive pairs, buffer occupancy, and which side is waiting.',
      unlockWith: 'Create the channel with InstrumentedChannel(...).',
      panel: <ChannelPanel sessionId={sessionId} />,
    },
    {
      key: 'jobs',
      title: 'Jobs',
      present: categories.hasJobs,
      whatYouWouldSee:
        'Job lifecycle, cancellation propagation, and who is joining on whom.',
      // Job events are not confirmed wrapper-only (the same reason `hasJobs`
      // does not promote a session to instrumented), so there is no honest
      // one-line unlock to offer for them.
      unlockWith: '',
      panel: <JobPanel sessionId={sessionId} />,
    },
  ]

  const present = capabilities.filter(c => c.present)
  // Only a real session can be told what to change, and only a capability with
  // an actual unlock is worth standing in for.
  const locked =
    rung === 'demo' ? [] : capabilities.filter(c => !c.present && c.unlockWith !== '')

  return (
    <section className="space-y-4">
      <h2 className="text-lg font-semibold">Evidence</h2>

      <div data-testid="evidence-present" className="space-y-4">
        {/* Threads is not rung-gated: the attach path reports thread and
            dispatcher assignment, so this panel is populated at every rung. */}
        <EvidenceSection title="Threads" replayActive={replayActive}>
          {threadActivity ? (
            <ThreadTimeline threadActivity={threadActivity} />
          ) : (
            <Card>
              <CardBody>
                <div className="py-4 text-center text-default-400">
                  <Spinner size="sm" className="mb-2" />
                  <p>Loading thread activity...</p>
                </div>
              </CardBody>
            </Card>
          )}
          <DispatcherOverview
            sessionId={sessionId}
            isLive={streamEnabled}
            enabled={!readOnly}
          />
        </EvidenceSection>

        {present.map(capability => (
          <EvidenceSection
            key={capability.key}
            title={capability.title}
            replayActive={replayActive}
          >
            {capability.panel}
          </EvidenceSection>
        ))}
      </div>

      {locked.length > 0 && (
        <div data-testid="evidence-locked" className="grid gap-3 md:grid-cols-3">
          {locked.map(capability => (
            <LockedPanel
              key={capability.key}
              title={capability.title}
              whatYouWouldSee={capability.whatYouWouldSee}
              unlockWith={capability.unlockWith}
            />
          ))}
        </div>
      )}
    </section>
  )
}

function EvidenceSection({
  title,
  replayActive,
  children,
}: {
  title: string
  /** These panels query the live session, not the replay cursor (D-17). */
  replayActive: boolean
  children: ReactNode
}) {
  return (
    <div className="space-y-2">
      <h3 className="text-sm font-semibold text-default-600">{title}</h3>
      {replayActive && <LiveDataNotice />}
      {children}
    </div>
  )
}
