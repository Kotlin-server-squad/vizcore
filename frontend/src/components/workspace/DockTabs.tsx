import { useRef, type KeyboardEvent, type ReactNode } from 'react'

/**
 * The dock's tabs, one per question (spec S-4): what is this coroutine, what
 * did the resources do, what exactly happened, how do I read this. Problems
 * ("what is wrong?") is the fifth and arrives with the health API (Part 7).
 */
export type DockTabId = 'inspector' | 'evidence' | 'events' | 'learn'

export interface DockTabSpec {
  id: DockTabId
  label: string
}

const tabId = (id: DockTabId) => `dock-tab-${id}`
const panelId = (id: DockTabId) => `dock-panel-${id}`

/**
 * The WAI-ARIA tabs pattern (S-14) with automatic activation: one tab is in
 * the Tab order, the arrow keys move between tabs (wrapping), Home and End go
 * to the first and last. The row scrolls sideways rather than wrapping, so a
 * narrow window keeps one line of tabs.
 */
export function DockTabs({
  tabs,
  selected,
  onSelect,
}: {
  tabs: readonly DockTabSpec[]
  selected: DockTabId
  onSelect: (id: DockTabId) => void
}) {
  const refs = useRef(new Map<DockTabId, HTMLButtonElement>())

  const onKeyDown = (e: KeyboardEvent<HTMLDivElement>) => {
    const n = tabs.length
    const i = tabs.findIndex(t => t.id === selected)
    const next = { ArrowRight: (i + 1) % n, ArrowLeft: (i - 1 + n) % n, Home: 0, End: n - 1 }[e.key]
    if (next === undefined) return
    e.preventDefault()
    const to = tabs[next]!.id
    onSelect(to)
    refs.current.get(to)?.focus()
  }

  return (
    <div
      role="tablist"
      aria-label="Dock"
      onKeyDown={onKeyDown}
      className="flex min-w-0 flex-nowrap gap-1 overflow-x-auto py-1"
    >
      {tabs.map(tab => {
        const active = tab.id === selected
        return (
          <button
            key={tab.id}
            ref={el => {
              if (el) refs.current.set(tab.id, el)
              else refs.current.delete(tab.id)
            }}
            type="button"
            role="tab"
            id={tabId(tab.id)}
            aria-selected={active}
            aria-controls={panelId(tab.id)}
            tabIndex={active ? 0 : -1}
            onClick={() => onSelect(tab.id)}
            className={
              active
                ? 'shrink-0 whitespace-nowrap rounded-medium bg-default-200 px-3 py-1.5 text-sm font-semibold text-foreground outline-none focus-visible:ring-2 focus-visible:ring-primary'
                : 'shrink-0 whitespace-nowrap rounded-medium px-3 py-1.5 text-sm text-default-500 outline-none hover:text-foreground focus-visible:ring-2 focus-visible:ring-primary'
            }
          >
            {tab.label}
          </button>
        )
      })}
    </div>
  )
}

/** A tab's panel. Always in the DOM so `aria-controls` resolves; hidden when not selected. */
export function DockTabPanel({ id, selected, children }: { id: DockTabId; selected: boolean; children: ReactNode }) {
  return (
    <div
      role="tabpanel"
      id={panelId(id)}
      aria-labelledby={tabId(id)}
      hidden={!selected}
      tabIndex={0}
      className="outline-none focus-visible:ring-2 focus-visible:ring-primary"
    >
      {children}
    </div>
  )
}
