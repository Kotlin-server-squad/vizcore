/**
 * LiveDataNotice — D-17 banner shown at the top of projection-backed tabs
 * (Flow/Channels/Sync/Jobs/Validation/Dispatchers) while replay is active.
 *
 * These tabs render the CURRENT session state from their own server queries,
 * not the replay `visibleEvents` cursor, so this banner tells the user the
 * panel is unaffected by the replay position. Copy + classes locked by
 * 02-UI-SPEC §3 / Copywriting Contract.
 */

import { FiInfo } from 'react-icons/fi'

const LIVE_DATA_COPY =
  'Live data — this tab shows the current session state and is not affected by replay.'

export function LiveDataNotice() {
  return (
    <div
      className="flex items-center gap-2 rounded-md bg-default-100 px-3 py-2 text-xs text-default-500"
      data-testid="live-data-notice"
    >
      <FiInfo aria-hidden className="h-4 w-4 shrink-0" />
      <span>{LIVE_DATA_COPY}</span>
    </div>
  )
}
