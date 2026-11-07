import { describe, it, expect, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { createRef } from 'react'
import { CoroutineCanvas } from './CoroutineCanvas'
import type { CoroutineNode } from '@/types/api'

vi.mock('../CoroutineTreeGraph', () => ({
  CoroutineTreeGraph: ({ activity, reveal }: { activity?: Map<string, unknown>; reveal?: { id: string } }) => (
    <div data-testid="coroutine-tree-graph" data-activity={activity?.size ?? 0} data-reveal={reveal?.id ?? ''} />
  ),
}))
vi.mock('../CoroutineTree', () => ({
  CoroutineTree: ({ activity }: { activity?: Map<string, unknown> }) => (
    <div data-testid="coroutine-tree" data-activity={activity?.size ?? 0}>
      <div data-node-id="c-1" />
    </div>
  ),
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
  it('passes the second lines to the graph and the list', () => {
    const activity = new Map([['c-1', { text: 'holds x', waiting: false }]])
    renderCanvas({ activity })
    expect(screen.getByTestId('coroutine-tree-graph')).toHaveAttribute('data-activity', '1')
  })

  it('passes them to the list view too', () => {
    const activity = new Map([['c-1', { text: 'holds x', waiting: false }]])
    renderCanvas({ activity, viewMode: 'list' })
    expect(screen.getByTestId('coroutine-tree')).toHaveAttribute('data-activity', '1')
  })

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

  it('explains sampling in an attached app\'s empty state (H-4)', () => {
    renderCanvas({ coroutines: [], isDemo: false })
    expect(screen.getByRole('region', { name: 'Main view' })).toHaveTextContent('on a poll')
  })

  describe('big apps (S-12)', () => {
    it('puts the round bar under the toolbar', () => {
      renderCanvas({ roundBar: <div data-testid="round-bar" /> })
      expect(screen.getByTestId('round-bar')).toBeInTheDocument()
    })

    it('says what the cap leaves out, and the notes on what the page holds', () => {
      renderCanvas({
        capNote: 'Showing 200 of 1,234 coroutines in this round. Find reaches one by its name or creation site.',
        notes: [{ key: 'history', text: 'The Events tab reads the newest 10,000 events this page received.' }],
      })
      const main = screen.getByRole('region', { name: 'Main view' })
      expect(main).toHaveTextContent('Showing 200 of 1,234 coroutines in this round. Find reaches one by its name or creation site.')
      expect(main.querySelector('[data-look-part="cap-note"]')).not.toBeNull()
      expect(main.querySelector('[data-look-part="history-note"]')).toHaveTextContent('newest 10,000 events')
    })

    it('says it is loading the rounds instead of drawing every coroutine first', () => {
      renderCanvas({ loadingRounds: true })
      expect(screen.getByText('Loading rounds…')).toBeInTheDocument()
      expect(screen.queryByTestId('coroutine-tree-graph')).toBeNull()
    })

    it('says why a view is empty when only vizcore coroutines are hidden', () => {
      renderCanvas({ coroutines: [], emptyReason: 'vizcore-hidden' })
      expect(screen.getByRole('region', { name: 'Main view' })).toHaveTextContent(
        "Only vizcore's own coroutines were captured in this view. Turn on vizcore connection to see them.",
      )
    })

    it('says a chip matches nothing in this round, not in the session', () => {
      renderCanvas({ coroutines: [], stateFilter: 'failed', emptyReason: 'round' })
      expect(screen.getByRole('region', { name: 'Main view' })).toHaveTextContent('No failed coroutines in this round')
    })
  })

  describe('revealing a found coroutine', () => {
    it('hands the reveal to the graph', () => {
      renderCanvas({ reveal: { id: 'c-1', key: 1 } })
      expect(screen.getByTestId('coroutine-tree-graph')).toHaveAttribute('data-reveal', 'c-1')
    })

    it('scrolls the list to it, once per reveal', () => {
      const scrolled = vi.fn()
      Element.prototype.scrollIntoView = scrolled
      renderCanvas({ viewMode: 'list', reveal: { id: 'c-1', key: 1 } })
      expect(scrolled).toHaveBeenCalledWith({ block: 'center' })
      expect(scrolled.mock.instances[0]).toHaveAttribute('data-node-id', 'c-1')
    })

    it('says "this round" in its heading when it shows one round', () => {
      renderCanvas({ inRound: true })
      expect(screen.getByText("What's running in this round")).toBeInTheDocument()
    })

    it('says a finished round has finished, not everything', () => {
      renderCanvas({ inRound: true, allFinished: true })
      expect(screen.getByText('This round has finished')).toBeInTheDocument()
    })
  })
})
