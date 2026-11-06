import type { SessionBodyProps } from '../types'

/**
 * The main view and the dock share the rest of the page (S-1). Each keeps its
 * own minimum, so a maximised view needs only the main view's.
 */
export function ClassicSessionBody({ sharedRef, main, dock }: SessionBodyProps) {
  return (
    <div ref={sharedRef} data-look-part="body" className="flex min-h-0 flex-1 flex-col gap-3">
      {main}
      {dock}
    </div>
  )
}
