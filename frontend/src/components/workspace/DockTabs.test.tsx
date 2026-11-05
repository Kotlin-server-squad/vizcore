import { describe, it, expect } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { useState } from 'react'
import { DockTabPanel, DockTabs, type DockTabId } from './DockTabs'

const TABS = [
  { id: 'inspector', label: 'Inspector' },
  { id: 'evidence', label: 'Evidence · 1 lock' },
  { id: 'events', label: 'Events' },
  { id: 'learn', label: 'Learn' },
] as const

function Harness({ start = 'inspector' }: { start?: DockTabId }) {
  const [tab, setTab] = useState<DockTabId>(start)
  return (
    <>
      <DockTabs tabs={TABS} selected={tab} onSelect={setTab} />
      {TABS.map(t => (
        <DockTabPanel key={t.id} id={t.id} selected={tab === t.id}>
          {t.label} body
        </DockTabPanel>
      ))}
    </>
  )
}

describe('DockTabs — the WAI-ARIA tabs pattern (S-14)', () => {
  it('lists the tabs in a labelled tablist, wired to their panels', () => {
    render(<Harness />)
    expect(screen.getByRole('tablist', { name: 'Dock' })).toBeInTheDocument()
    const tab = screen.getByRole('tab', { name: 'Inspector' })
    expect(tab).toHaveAttribute('aria-selected', 'true')
    expect(tab).toHaveAttribute('aria-controls', 'dock-panel-inspector')
    expect(screen.getByRole('tabpanel', { name: 'Inspector' })).toHaveTextContent('Inspector body')
  })

  it('keeps one tab in the Tab order', () => {
    render(<Harness />)
    expect(screen.getByRole('tab', { name: 'Inspector' })).toHaveAttribute('tabindex', '0')
    expect(screen.getByRole('tab', { name: 'Learn' })).toHaveAttribute('tabindex', '-1')
  })

  it('moves with the arrow keys, wrapping, and jumps with Home and End', async () => {
    render(<Harness />)
    screen.getByRole('tab', { name: 'Inspector' }).focus()
    await userEvent.keyboard('{ArrowRight}')
    expect(screen.getByRole('tab', { name: 'Evidence · 1 lock' })).toHaveFocus()
    expect(screen.getByRole('tab', { name: 'Evidence · 1 lock' })).toHaveAttribute('aria-selected', 'true')
    await userEvent.keyboard('{End}')
    expect(screen.getByRole('tab', { name: 'Learn' })).toHaveFocus()
    await userEvent.keyboard('{ArrowRight}')
    expect(screen.getByRole('tab', { name: 'Inspector' })).toHaveFocus()
    await userEvent.keyboard('{ArrowLeft}')
    expect(screen.getByRole('tab', { name: 'Learn' })).toHaveFocus()
    await userEvent.keyboard('{Home}')
    expect(screen.getByRole('tab', { name: 'Inspector' })).toHaveFocus()
  })

  it('selects on click and hides the other panels', async () => {
    render(<Harness />)
    await userEvent.click(screen.getByRole('tab', { name: 'Learn' }))
    expect(screen.getByRole('tabpanel', { name: 'Learn' })).toBeVisible()
    expect(screen.getByText('Inspector body')).not.toBeVisible()
  })
})
