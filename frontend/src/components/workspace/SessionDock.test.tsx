import { describe, it, expect, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { SessionDock } from './SessionDock'

function renderDock(folded = false) {
  const onToggleFold = vi.fn()
  render(
    <SessionDock
      height={300}
      maxHeight={500}
      folded={folded}
      onResize={vi.fn()}
      onToggleFold={onToggleFold}
      bar={<h2>Details</h2>}
    >
      <p>dock body</p>
    </SessionDock>,
  )
  return { onToggleFold }
}

describe('SessionDock', () => {
  it('is a labelled region with a separator, its bar and its body at the size asked', () => {
    renderDock()
    const dock = screen.getByRole('region', { name: 'Dock' })
    expect(dock).toHaveAttribute('id', 'session-dock')
    expect(screen.getByRole('separator')).toHaveAttribute('aria-controls', 'session-dock')
    expect(dock).toHaveStyle({ height: '300px' })
    expect(screen.getByRole('separator', { name: 'Resize the dock' })).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'Details' })).toBeInTheDocument()
    expect(screen.getByText('dock body')).toBeVisible()
  })

  it('folds to its bar, and says so on the Fold button', async () => {
    const { onToggleFold } = renderDock()
    const fold = screen.getByRole('button', { name: 'Fold the dock' })
    expect(fold).toHaveAttribute('aria-expanded', 'true')
    expect(fold).toHaveAttribute('aria-controls', 'dock-body')
    await userEvent.click(fold)
    expect(onToggleFold).toHaveBeenCalledTimes(1)
  })

  it('folded, shows only its bar and offers to unfold', () => {
    renderDock(true)
    const dock = screen.getByRole('region', { name: 'Dock' })
    expect(dock.style.height).toBe('')
    // Hidden, not unmounted: panels keep their state and fetch nothing again (D-17, #124).
    expect(screen.getByText('dock body')).not.toBeVisible()
    const unfold = screen.getByRole('button', { name: 'Unfold the dock' })
    expect(unfold).toHaveAttribute('aria-expanded', 'false')
    expect(unfold).toHaveAttribute('aria-controls', 'dock-body')
  })

  it('is hidden, not unmounted, while the main view is maximised', () => {
    render(
      <SessionDock height={300} maxHeight={500} folded={false} hidden onResize={vi.fn()} onToggleFold={vi.fn()} bar={null}>
        <p>dock body</p>
      </SessionDock>,
    )
    expect(screen.queryByRole('region', { name: 'Dock' })).toBeNull()
    expect(screen.getByText('dock body')).not.toBeVisible()
  })
})
