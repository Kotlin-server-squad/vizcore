import { useMemo } from 'react'
import { useSessionEvents } from '@/hooks/use-sessions'
import { inSeqOrder } from '@/lib/projections/in-seq-order'
import { JOB_EVENT_KINDS } from '@/types/api'
import type {
  JobStateChangedEvent,
  WaitingForChildrenEvent,
  JobJoinRequestedEvent,
  JobJoinCompletedEvent,
  JobCancellationRequestedEvent,
  VizEvent,
} from '@/types/api'

/** Job state derived from JobStateChanged events. */
export type JobStatus = 'New' | 'Active' | 'Completing' | 'Completed' | 'Cancelled' | 'Failed'

/** Tracked state for a single job. */
export interface JobState {
  jobId: string
  coroutineId: string
  label: string | null
  parentCoroutineId: string | null
  status: JobStatus
  isActive: boolean
  isCompleted: boolean
  isCancelled: boolean
  childrenCount: number
  /** Children this job is waiting for (from WaitingForChildren events). */
  waitingForChildren: string[]
  activeChildrenCount: number
  /** Coroutines that joined on this job. */
  joiners: string[]
  /** Whether a cancellation was requested. */
  cancellationRequested: boolean
  cancellationCause: string | null
}

export interface UseJobEventsResult {
  jobEvents: VizEvent[]
  jobs: JobState[]
  jobsByParent: Map<string | null, JobState[]>
  roots: JobState[]
  waitingForChildren: WaitingForChildrenEvent[]
}

function deriveStatus(isActive: boolean, isCompleted: boolean, isCancelled: boolean): JobStatus {
  if (isCancelled) return 'Cancelled'
  if (isCompleted) return 'Completed'
  if (isActive) return 'Active'
  return 'New'
}

/**
 * Filters session events to job events, tracks job states,
 * parent-child relationships, and waiting-for-children blocking.
 *
 * Reads `given` when passed — the events the page shows, so the facts follow
 * the live stream and the replay cursor — else the stored history, which live
 * updates never invalidate. It subscribes to that history either way (#124).
 */
export function useJobEvents(sessionId: string, given?: readonly VizEvent[]): UseJobEventsResult {
  const { data: stored } = useSessionEvents(sessionId)
  const events = given ?? stored

  return useMemo(() => {
    const jobEvents: VizEvent[] = []
    const jobStates = new Map<string, JobState>()
    const waitingForChildrenEvents: WaitingForChildrenEvent[] = []

    if (!events || events.length === 0) {
      return {
        jobEvents: [],
        jobs: [],
        jobsByParent: new Map(),
        roots: [],
        waitingForChildren: [],
      }
    }

    // In seq order: the live stream can deliver an earlier event late (inSeqOrder).
    for (const event of inSeqOrder(events)) {
      if (!JOB_EVENT_KINDS.has(event.kind)) continue

      jobEvents.push(event)

      switch (event.kind) {
        case 'JobStateChanged': {
          const e = event as unknown as JobStateChangedEvent
          const existing = jobStates.get(e.jobId)
          jobStates.set(e.jobId, {
            jobId: e.jobId,
            coroutineId: e.coroutineId,
            label: e.label,
            parentCoroutineId: e.parentCoroutineId,
            status: deriveStatus(e.isActive, e.isCompleted, e.isCancelled),
            isActive: e.isActive,
            isCompleted: e.isCompleted,
            isCancelled: e.isCancelled,
            childrenCount: e.childrenCount,
            waitingForChildren: existing?.waitingForChildren ?? [],
            activeChildrenCount: existing?.activeChildrenCount ?? 0,
            joiners: existing?.joiners ?? [],
            cancellationRequested: existing?.cancellationRequested ?? false,
            cancellationCause: existing?.cancellationCause ?? null,
          })
          break
        }
        case 'WaitingForChildren': {
          const e = event as unknown as WaitingForChildrenEvent
          waitingForChildrenEvents.push(e)
          // Find the job belonging to this coroutine
          for (const [, job] of jobStates) {
            if (job.coroutineId === e.coroutineId) {
              job.waitingForChildren = e.activeChildrenIds
              job.activeChildrenCount = e.activeChildrenCount
              break
            }
          }
          break
        }
        case 'JobJoinRequested': {
          const e = event as unknown as JobJoinRequestedEvent
          const job = jobStates.get(e.jobId)
          if (job && e.waitingCoroutineId) {
            job.joiners.push(e.waitingCoroutineId)
          }
          break
        }
        case 'JobJoinCompleted': {
          const e = event as unknown as JobJoinCompletedEvent
          const job = jobStates.get(e.jobId)
          if (job && e.waitingCoroutineId) {
            job.joiners = job.joiners.filter((id) => id !== e.waitingCoroutineId)
          }
          break
        }
        case 'JobCancellationRequested': {
          const e = event as unknown as JobCancellationRequestedEvent
          const job = jobStates.get(e.jobId)
          if (job) {
            job.cancellationRequested = true
            job.cancellationCause = e.cause
          }
          break
        }
      }
    }

    const jobs = Array.from(jobStates.values())

    // Group jobs by parent
    const jobsByParent = new Map<string | null, JobState[]>()
    for (const job of jobs) {
      const parent = job.parentCoroutineId
      if (!jobsByParent.has(parent)) {
        jobsByParent.set(parent, [])
      }
      jobsByParent.get(parent)!.push(job)
    }

    // Root jobs have no parent or parent not in the job set
    const jobCoroutineIds = new Set(jobs.map((j) => j.coroutineId))
    const roots = jobs.filter(
      (j) => j.parentCoroutineId == null || !jobCoroutineIds.has(j.parentCoroutineId),
    )

    return {
      jobEvents,
      jobs,
      jobsByParent,
      roots,
      waitingForChildren: waitingForChildrenEvents,
    }
  }, [events])
}
