import { describe, it, expect, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { createRef } from 'react'
import { CoroutineCanvas } from './CoroutineCanvas'
import type { CoroutineNode } from '@/types/api'

vi.mock('../CoroutineTreeGraph', () => ({
  CoroutineTreeGraph: () => <div data-testid="coroutine-tree-graph" />,
}))
vi.mock('../CoroutineTree', () => ({
  CoroutineTree: () => <div data-testid="coroutine-tree" />,
}))

const node = { id: 'c-1', jobId: 'j-1', parentId: null, scopeId: 's', label: 'a', state: 'ACTIVE' } as CoroutineNode

function renderCanvas(props: Partial<Parameters<typeof CoroutineCanvas>[0]> = {}) {
  const handlers = { onViewModeChange: vi.fn(), onToggleMaximise: vi.fn(), onToggleCompleted: vi.fn() }
  render(
    <CoroutineCanvas
      coroutines={[node]}
      viewMode="graph"
      completedCount={0}
      showCompleted={false}
      moreCount={0}
      stateFilter="all"
      selectedCoroutineId={null}
      panelRef={createRef()}
      maximised={false}
      stateBar={<div data-testid="state-bar" />}
      {...handlers}
      {...props}
    />,
  )
  return handlers
}

describe('CoroutineCanvas — the main view', () => {
  it('is a labelled region with its toolbar first and the canvas filling the rest', () => {
    renderCanvas()
    const main = screen.getByRole('region', { name: 'Main view' })
    expect(main.className).toContain('flex-1')
    expect(screen.getByRole('group', { name: 'View' })).toBeInTheDocument()
    expect(screen.getByTestId('state-bar')).toBeInTheDocument()
    expect(screen.getByText(/What's running now/)).toBeInTheDocument()
    expect(screen.getByTestId('coroutine-tree-graph')).toBeInTheDocument()
  })

  it('switches between the graph and the list', async () => {
    const { onViewModeChange } = renderCanvas()
    expect(screen.getByRole('button', { name: 'Graph' })).toHaveAttribute('aria-pressed', 'true')
    await userEvent.click(screen.getByRole('button', { name: 'List' }))
    expect(onViewModeChange).toHaveBeenCalledWith('list')
  })

  it('maximises, and says when it is maximised', async () => {
    const { onToggleMaximise } = renderCanvas()
    const button = screen.getByRole('button', { name: 'Maximise' })
    expect(button).toHaveAttribute('aria-pressed', 'false')
    await userEvent.click(button)
    expect(onToggleMaximise).toHaveBeenCalledTimes(1)
  })

  it('reads "Restore dock" while maximised', () => {
    renderCanvas({ maximised: true })
    expect(screen.getByRole('button', { name: 'Restore dock' })).toHaveAttribute('aria-pressed', 'true')
  })

  it('keeps its empty states inside the main view', () => {
    renderCanvas({ coroutines: [], isDemo: true })
    expect(screen.getByRole('region', { name: 'Main view' })).toHaveTextContent('Run the scenario to see its coroutines here.')
  })
})
