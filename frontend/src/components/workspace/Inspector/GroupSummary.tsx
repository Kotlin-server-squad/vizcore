import { useMemo } from 'react'
import { useRoundHierarchy } from '@/hooks/use-round-hierarchy'
import { creationSiteText, groupMix, type CreationSite, type NameGroup } from '@/lib/name-groups'

/** Members listed by name; the rest are reached by expanding the group. */
const LISTED = 50

interface GroupSummaryProps {
  sessionId: string
  group: NameGroup
  /** The parent the siblings share, by name; null for root siblings. */
  parentLabel: string | null
  memberLabel: (id: string) => string
  /** Draw the member (expanding its group, past the cap), select it and bring it into view. */
  onRevealMember: (id: string) => void
  onToggle: (id: string) => void
  /** Back to the session summary. */
  onClearSelection?: () => void
}

/**
 * The Inspector for a group of siblings (dock S-12): counts by state, what is below them, the members, and the shared
 * creation site if there is one — read once from the round's hierarchy while the group is selected.
 */
export function GroupSummary({
  sessionId,
  group,
  parentLabel,
  memberLabel,
  onRevealMember,
  onToggle,
  onClearSelection,
}: GroupSummaryProps) {
  const size = group.memberIds.length
  const hierarchy = useRoundHierarchy(sessionId, group.roundId, group.memberIds)
  const creation = useMemo(() => {
    // Siblings without a round have no hierarchy read: their site is not known (H-3).
    if (!group.roundId) return 'Creation site unavailable'
    if (hierarchy.status === 'pending') return 'Loading creation sites…'
    if (hierarchy.status === 'error') return 'Creation sites unavailable (the request failed)'
    const sites = new Map<string, CreationSite | null>(hierarchy.data.map(n => [n.id, n.creationPoint ?? null]))
    return creationSiteText(group.memberIds, sites)
  }, [group.roundId, group.memberIds, hierarchy.status, hierarchy.data])

  return (
    <div data-testid="group-summary" data-look-part="group-summary" className="space-y-3 text-sm">
      <div className="flex flex-wrap items-baseline gap-x-2">
        <h2 className="flex flex-wrap items-baseline gap-x-2 text-base font-semibold">
          <span className="font-mono">{group.label}</span>{' '}
          <span className="text-sm font-normal text-default-500">{`group of ${size.toLocaleString('en-US')} siblings`}</span>
        </h2>
        {onClearSelection && (
          <button type="button" onClick={onClearSelection} className="ml-auto text-xs text-primary hover:underline">
            Session summary
          </button>
        )}
      </div>
      {parentLabel && <p className="text-default-600">{`Under ${parentLabel}`}</p>}
      <p>{groupMix(group.counts)}</p>
      {group.below > 0 && (
        <p className="text-default-600">{`+${group.below.toLocaleString('en-US')} coroutines below them`}</p>
      )}
      <p data-testid="group-creation" className="text-default-600">
        {creation}
      </p>
      <button
        type="button"
        onClick={() => onToggle(group.id)}
        className="rounded-medium border border-default-300 px-2 py-1 text-xs text-default-700 hover:bg-default-100"
      >
        {group.expanded ? 'Collapse the group' : 'Expand the group'}
      </button>
      <div>
        <p className="mb-1 text-xs font-semibold uppercase tracking-wide text-default-500">Members</p>
        <ul className="flex flex-wrap gap-1">
          {group.memberIds.slice(0, LISTED).map(id => (
            <li key={id}>
              <button
                type="button"
                onClick={() => onRevealMember(id)}
                className="rounded-small bg-default-100 px-2 py-0.5 font-mono text-xs text-foreground hover:bg-default-200"
              >
                {memberLabel(id)}
              </button>
            </li>
          ))}
        </ul>
        {size > LISTED && (
          <p className="mt-1 text-xs text-default-500">
            {`and ${(size - LISTED).toLocaleString('en-US')} more: expand the group to see them`}
          </p>
        )}
      </div>
    </div>
  )
}
