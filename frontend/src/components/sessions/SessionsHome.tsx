import { SessionsSidebar } from './SessionsSidebar'

/**
 * The root-route presentation of the sessions list.
 *
 * Same component and same behaviour as the sidebar — only the width constraint
 * differs. Kept as a thin wrapper rather than a forked layout so the two
 * placements cannot drift apart.
 */
export function SessionsHome({ onConnect }: { onConnect: () => void }) {
  return <SessionsSidebar onConnect={onConnect} className="w-full" />
}
