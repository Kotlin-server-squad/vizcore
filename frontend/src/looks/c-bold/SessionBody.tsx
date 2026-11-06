import type { SessionBodyProps } from '../types'
import { RightNow } from './RightNow'

/**
 * C's main view and dock: Classic's column, with the "Right now" overview
 * drawn above them (sketch K-C-Bold). Same shape as Classic's body (one div,
 * the main view and the dock first), so switching looks remounts neither. A
 * render function with no hooks of its own: `RightNow` holds them.
 */
export function boldSessionBody({ sharedRef, main, dock, overview }: SessionBodyProps) {
  return (
    <div ref={sharedRef} data-look-part="body" className="flex min-h-0 flex-1 flex-col gap-3">
      {main}
      {dock}
      <RightNow overview={overview} />
    </div>
  )
}
