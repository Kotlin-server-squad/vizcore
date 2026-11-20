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
  isNewDemoDisabled,
  onCompare,
  publicDemo,
}: {
  onConnect: () => void
  onNewDemo?: () => void
  isNewDemoDisabled?: boolean
  onCompare?: () => void
  publicDemo?: boolean
}) {
  return (
    <SessionsSidebar
      onConnect={onConnect}
      onNewDemo={onNewDemo}
      isNewDemoDisabled={isNewDemoDisabled}
      onCompare={onCompare}
      publicDemo={publicDemo}
      className="w-full"
    />
  )
}
