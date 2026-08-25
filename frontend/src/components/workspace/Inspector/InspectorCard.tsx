import type { ReactNode } from 'react'

/**
 * The shared frame for one inspector card.
 *
 * A heading and a body, nothing else — the cards are read top-to-bottom in a
 * ~320px column, so anything decorative competes with the thing being read.
 */
export function InspectorCard({
  title,
  testId,
  children,
}: {
  title: string
  testId: string
  children: ReactNode
}) {
  return (
    <section
      data-testid={testId}
      className="rounded-medium border border-default-200 bg-content1 p-4"
    >
      <h4 className="mb-3 text-xs font-semibold uppercase tracking-wide text-default-500">
        {title}
      </h4>
      {children}
    </section>
  )
}

/** A label/value row. `value` is monospaced because it is nearly always an id. */
export function InspectorRow({ label, value }: { label: string; value: ReactNode }) {
  return (
    <div className="flex items-baseline justify-between gap-3 py-1 text-sm">
      <span className="shrink-0 text-default-500">{label}</span>
      <span className="truncate text-right font-mono text-xs text-default-700">{value}</span>
    </div>
  )
}

/**
 * What a field says when the backend did not report it.
 *
 * Never `0`, never `—`: a zero duration and an unreported duration are
 * different claims, and the timeline projection legitimately omits fields
 * (they are a deferred stub, D-02). Saying so is the honest render.
 */
export function NotReported() {
  return <span className="italic text-default-400">not reported</span>
}
