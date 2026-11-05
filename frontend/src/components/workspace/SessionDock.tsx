import type { ReactNode } from 'react'
import { Button } from '@heroui/react'
import { FiChevronDown, FiChevronUp } from 'react-icons/fi'
import { DockSeparator } from './DockSeparator'

interface SessionDockProps {
  height: number
  maxHeight: number
  folded: boolean
  /** The main view is maximised: the dock is hidden, not unmounted. */
  hidden?: boolean
  onResize: (height: number) => void
  onToggleFold: () => void
  /** The dock's bar: the tab row (3b), with the Fold button added on the right. */
  bar: ReactNode
  children: ReactNode
}

/**
 * The dock under the main view (spec S-1): a separator to resize it, a bar
 * that stays when the dock is folded, and a body that scrolls on its own.
 *
 * Folding and maximising hide the body rather than unmount it: the panels keep
 * their state, and none of them re-subscribes to a query that has gone stale
 * meanwhile — a stale `/events` read would refetch history, and in the shared
 * view it would be a protected request (#124, T-03-22).
 */
export function SessionDock({
  height,
  maxHeight,
  folded,
  hidden = false,
  onResize,
  onToggleFold,
  bar,
  children,
}: SessionDockProps) {
  return (
    <section
      id="session-dock"
      aria-label="Dock"
      hidden={hidden}
      // `flex` would beat the `[hidden]` rule of Tailwind's preflight, so a
      // hidden dock drops it.
      className={hidden ? 'hidden' : 'flex shrink-0 flex-col rounded-xl border border-default-200 bg-content1'}
      style={folded ? undefined : { height }}
    >
      <DockSeparator
        controls="session-dock"
        height={height}
        maxHeight={maxHeight}
        folded={folded}
        onResize={onResize}
        onToggleFold={onToggleFold}
      />
      <div className="flex min-w-0 items-center gap-2 border-b border-default-200 px-2 pb-1">
        <div className="flex min-w-0 flex-1 items-center">{bar}</div>
        <Button
          size="sm"
          variant="light"
          aria-label={folded ? 'Unfold the dock' : 'Fold the dock'}
          aria-expanded={!folded}
          aria-controls="dock-body"
          startContent={folded ? <FiChevronUp aria-hidden /> : <FiChevronDown aria-hidden />}
          onPress={onToggleFold}
        >
          {folded ? 'Unfold' : 'Fold'}
        </Button>
      </div>
      <div id="dock-body" hidden={folded} className="min-h-0 flex-1 overflow-auto p-4">
        {children}
      </div>
    </section>
  )
}
