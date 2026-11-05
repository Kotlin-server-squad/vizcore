import type { ReactNode } from 'react'
import { Button } from '@heroui/react'
import { FiChevronDown, FiChevronUp } from 'react-icons/fi'
import { DockSeparator } from './DockSeparator'

interface SessionDockProps {
  height: number
  maxHeight: number
  folded: boolean
  onResize: (height: number) => void
  onToggleFold: () => void
  /** The dock's bar: the tab row (3b), with the Fold button added on the right. */
  bar: ReactNode
  children: ReactNode
}

/**
 * The dock under the main view (spec S-1): a separator to resize it, a bar
 * that stays when the dock is folded, and a body that scrolls on its own.
 * Maximise is the caller's business: a maximised page does not mount the dock.
 */
export function SessionDock({ height, maxHeight, folded, onResize, onToggleFold, bar, children }: SessionDockProps) {
  return (
    <section
      aria-label="Dock"
      className="flex shrink-0 flex-col rounded-xl border border-default-200 bg-content1"
      style={folded ? undefined : { height }}
    >
      <DockSeparator
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
          // Only while the body is mounted: an id that resolves to nothing is a
          // broken relationship for assistive tech.
          aria-controls={folded ? undefined : 'dock-body'}
          startContent={folded ? <FiChevronUp aria-hidden /> : <FiChevronDown aria-hidden />}
          onPress={onToggleFold}
        >
          {folded ? 'Unfold' : 'Fold'}
        </Button>
      </div>
      {!folded && (
        <div id="dock-body" className="min-h-0 flex-1 overflow-auto p-4">
          {children}
        </div>
      )}
    </section>
  )
}
