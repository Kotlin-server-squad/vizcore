import type { ReactNode } from 'react'
import { FiAlertTriangle, FiCheckCircle, FiClock, FiInfo } from 'react-icons/fi'

/**
 * The Learn tab (spec S-9, triage T-6): how to read the page. The legend and
 * the structured-concurrency explanation that used to sit on the page, the
 * sampling note (H-4), and a demo's own pipeline view when it has one. Story
 * mode joins it in Part 9.
 *
 * Everything here explains rather than records, and says so (S-6).
 */
export function LearnPanel({ children }: { children?: ReactNode }) {
  return (
    <div className="space-y-4 text-sm">
      <p className="flex items-center gap-2 text-xs text-default-500">
        <FiInfo className="h-4 w-4 text-primary" aria-hidden />
        <span>Explanation, not recorded data</span>
      </p>

      {children}

      <section aria-labelledby="learn-legend-heading" className="rounded-lg border border-default-200 p-3">
        <h3 id="learn-legend-heading" className="mb-2 text-xs font-semibold text-default-700">
          How to read the tree
        </h3>
        <div data-testid="learn-legend" className="grid grid-cols-2 gap-2 text-xs sm:grid-cols-3">
          <div className="flex items-center gap-2">
            <span className="inline-block h-2 w-2 rounded-full bg-primary animate-pulse"></span>
            <span>Active</span>
          </div>
          <div className="flex items-center gap-2">
            <span className="inline-block h-2 w-2 rounded-full bg-primary"></span>
            <span>Waiting for children</span>
          </div>
          <div className="flex items-center gap-2">
            <span className="inline-block h-2 w-2 rounded-full bg-warning"></span>
            <span>Suspended</span>
          </div>
          <div className="flex items-center gap-2">
            <span className="inline-block h-2 w-2 rounded-full bg-success"></span>
            <span>Completed</span>
          </div>
          <div className="flex items-center gap-2">
            <span className="inline-block h-2 w-2 rounded-full bg-danger"></span>
            <span>Failed</span>
          </div>
          <div className="flex items-center gap-2">
            <span className="inline-block h-2 w-2 rounded-full bg-warning opacity-60"></span>
            <span>Cancelled</span>
          </div>
        </div>
      </section>

      <div className="grid gap-3 lg:grid-cols-3">
        <div className="flex items-start gap-3 rounded-lg bg-primary/10 p-3">
          <FiClock className="mt-0.5 h-4 w-4 flex-shrink-0 text-primary" aria-hidden />
          <div>
            <div className="font-semibold text-primary">Waiting for children</div>
            <div className="mt-1 text-xs text-default-600">
              When a parent's own code finishes while its children still run, it waits for them. Structured
              concurrency means a parent never finishes before its children.
            </div>
          </div>
        </div>
        <div className="flex items-start gap-3 rounded-lg bg-success/10 p-3">
          <FiCheckCircle className="mt-0.5 h-4 w-4 flex-shrink-0 text-success" aria-hidden />
          <div>
            <div className="font-semibold text-success">Normal completion</div>
            <div className="mt-1 text-xs text-default-600">
              A coroutine is Completed only when its own code and all of its children have finished.
            </div>
          </div>
        </div>
        <div className="flex items-start gap-3 rounded-lg bg-danger/10 p-3">
          <FiAlertTriangle className="mt-0.5 h-4 w-4 flex-shrink-0 text-danger" aria-hidden />
          <div>
            <div className="font-semibold text-danger">How a failure spreads</div>
            <div className="mt-1 text-xs text-default-600">
              A child that throws is recorded Failed. In an ordinary Job its failure fails the parent, which
              cancels its other children: they are recorded Cancelled. Under a SupervisorJob (or inside
              supervisorScope) the other children keep running and the parent does not fail.
            </div>
          </div>
        </div>
      </div>

      <p data-testid="learn-sampling" className="text-xs text-default-500">
        This page shows what vizcore captured. For an attached app, coroutines are sampled on a poll, so a step
        shorter than the poll can be missed and durations are approximate; a demo's wrappers record each step
        as it happens. When the page says nothing was found, it means nothing in the captured coroutines.
      </p>
    </div>
  )
}
