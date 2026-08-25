import { SessionsSidebar } from './SessionsSidebar'

/**
 * The root-route presentation of the sessions list.
 *
 * Same component and same behaviour as the sidebar — only the width constraint
 * and the secondary actions differ. Kept as a thin wrapper rather than a forked
 * layout so the two placements cannot drift apart.
 */
export function SessionsHome({
  onConnect,
  onNewDemo,
  onCompare,
}: {
  onConnect: () => void
  onNewDemo?: () => void
  onCompare?: () => void
}) {
  return (
    <SessionsSidebar
      onConnect={onConnect}
      onNewDemo={onNewDemo}
      onCompare={onCompare}
      className="w-full"
    />
  )
}
