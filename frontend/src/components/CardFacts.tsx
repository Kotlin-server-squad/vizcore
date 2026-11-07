import { useContext } from 'react'
import { CardFactsContext, lifetimeText } from '@/lib/card-facts'
import type { CoroutineNode } from '@/types/api'

/**
 * A tree card's C-2 facts (Part 7, P-9; triage "Cards"): its lifetime and creation site from the server's summary, and
 * the observation badges the session health lists for it. Nothing when the node carries none (a replay projection).
 * Same markup in every look: `node-meta`, `node-badges`, `node-badge` (with `data-badge`).
 */
export function CardFacts({ node }: { node: CoroutineNode }) {
  const fact = useContext(CardFactsContext).get(node.id)
  const lifetime = lifetimeText(node)
  const site = node.creationLocation?.fileName
    ? `${node.creationLocation.fileName}${node.creationLocation.lineNumber != null ? `:${node.creationLocation.lineNumber}` : ''}`
    : null
  const meta = [lifetime, site].filter(Boolean).join(' · ')
  const badges = fact?.badges ?? []
  if (!meta && badges.length === 0) return null
  // One row (`node-facts`): the meta text gives way first, so the badges keep their words.
  return (
    <div data-look-part="node-facts" className="mt-0.5 flex min-w-0 items-center gap-1.5 overflow-hidden">
      {meta && (
        <span
          data-testid="node-meta"
          data-look-part="node-meta"
          className="min-w-0 shrink-[100] truncate text-xs text-default-500"
          title={[lifetime && `Lifetime: ${lifetime}`, node.creationLocation && `Created at ${node.creationLocation.function}`].filter(Boolean).join('\n')}
        >
          {meta}
        </span>
      )}
      {badges.length > 0 && (
        <span data-look-part="node-badges" className="flex min-w-0 shrink gap-1 overflow-hidden">
          {badges.map(b => (
            <span
              key={b.kind}
              data-look-part="node-badge"
              data-badge={b.kind}
              title={b.text}
              className={
                b.kind === 'possibly-blocked'
                  ? 'min-w-0 truncate rounded-full bg-warning/15 px-2 text-xs font-medium text-warning-700 dark:text-warning'
                  : 'min-w-0 truncate rounded-full bg-default-200 px-2 text-xs text-default-700'
              }
            >
              {b.text}
            </span>
          ))}
        </span>
      )}
    </div>
  )
}

/** The words after a card's state that the health supplies: "· after the failure" (context, not a cause). */
export function CardStateContext({ id }: { id: string }) {
  const context = useContext(CardFactsContext).get(id)?.context
  return context ? <span data-look-part="node-context"> · {context}</span> : null
}
