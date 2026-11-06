import { describe, it, expect, vi } from 'vitest'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import type { EventCategories } from '@/hooks/use-event-categories'
import type { EvidenceCounts } from '@/lib/evidence-summary'
import type { ThreadActivity, VizEvent } from '@/types/api'
import { EvidencePanels } from './EvidencePanels'

vi.mock('../channels/ChannelPanel', () => ({
  ChannelPanel: (p: { events?: unknown[]; focusChannelId?: string | null; onSelectChannel?: (id: string) => void }) => (
    <button
      type="button"
      data-testid="channel-panel"
      data-events={p.events?.length ?? 'stored'}
      data-focus={p.focusChannelId ?? ''}
      onClick={() => p.onSelectChannel?.('ch-2')}
    />
  ),
}))
vi.mock('../flow/FlowPanel', () => ({
  FlowPanel: (p: { events?: unknown[] }) => <div data-testid="flow-panel" data-events={p.events?.length ?? 'stored'} />,
}))
vi.mock('../sync/SyncPanel', () => ({
  SyncPanel: (p: { events?: unknown[]; selectedResourceId?: string | null; selectedCoroutineId?: string | null; picker?: unknown }) => (
    <div
      data-testid="sync-panel"
      data-events={p.events?.length ?? 'stored'}
      data-resource={p.selectedResourceId ?? ''}
      data-coroutine={p.selectedCoroutineId ?? ''}
      data-picker={String(!!p.picker)}
    />
  ),
}))
vi.mock('../jobs/JobPanel', () => ({
  JobPanel: (p: { events?: unknown[] }) => <div data-testid="job-panel" data-events={p.events?.length ?? 'stored'} />,
}))
vi.mock('../ThreadTable', () => ({ ThreadTable: () => <div data-testid="thread-table" /> }))
vi.mock('../DispatcherOverview', () => ({ DispatcherOverview: () => <div data-testid="dispatcher-overview" /> }))

const none: EvidenceCounts = { locks: 0, semaphores: 0, channels: 0, flows: 0, jobEvents: 0 }
const activity: ThreadActivity = {
  '1': [{ threadId: 1, threadName: 'worker-1', coroutineId: 'c', timestamp: 1, eventType: 'ASSIGNED', dispatcherName: 'Default' }],
}

function categories(overrides: Partial<EventCategories> = {}): EventCategories {
  return { hasChannels: false, hasFlowOps: false, hasSyncPrimitives: false, hasJobs: false, hasValidation: true, ...overrides }
}

function renderPanels(props: Partial<Parameters<typeof EvidencePanels>[0]> = {}) {
  return render(panels(props))
}

function panels(props: Partial<Parameters<typeof EvidencePanels>[0]> = {}) {
  return (
    <EvidencePanels
      sessionId="s-1"
      rung="attached"
      categories={categories()}
      counts={none}
      replayActive={false}
      readOnly={false}
      threadActivity={activity}
      captureStartNanos={0}
      {...props}
    />
  )
}

const SECTIONS = ['Threads', 'Dispatchers', 'Locks and permits', 'Channels', 'Flows', 'Jobs']

describe('EvidencePanels — six sections, always listed (S-4)', () => {
  it('lists every section in order, whatever was captured', () => {
    renderPanels()
    const titles = screen.getAllByTestId(/^evidence-section-/).map(s => s.getAttribute('data-testid'))
    expect(titles).toEqual(SECTIONS.map(t => `evidence-section-${t}`))
  })

  it('folds a captured section behind a disclosure with a one-line summary', async () => {
    renderPanels({ categories: categories({ hasSyncPrimitives: true }), counts: { ...none, locks: 1, semaphores: 1 } })
    const sync = screen.getByRole('button', { name: /Locks and permits/ })
    expect(sync).toHaveAttribute('aria-expanded', 'false')
    expect(sync).toHaveTextContent('1 lock, 1 semaphore')
    // Mounted from the start (its query subscribes at page load), only hidden.
    expect(screen.getByTestId('sync-panel')).not.toBeVisible()
    await userEvent.click(sync)
    expect(sync).toHaveAttribute('aria-expanded', 'true')
    expect(screen.getByTestId('sync-panel')).toBeVisible()
  })

  it('says "none captured" and what unlocks it, on a real session (D-5)', () => {
    renderPanels()
    const locks = screen.getByTestId('evidence-section-Locks and permits')
    expect(locks).toHaveTextContent('none captured')
    expect(locks).toHaveTextContent('Swap Mutex() for VizMutex()')
    expect(within(locks).queryByRole('button')).toBeNull()
    expect(screen.getByTestId('evidence-section-Flows')).toHaveTextContent('.instrumented()')
    expect(screen.getByTestId('evidence-section-Channels')).toHaveTextContent('InstrumentedChannel(...)')
  })

  it('says loading, not "none captured", while the session history is still loading', () => {
    renderPanels({ history: 'loading' })
    const locks = screen.getByTestId('evidence-section-Locks and permits')
    expect(locks).toHaveTextContent('loading')
    expect(locks).not.toHaveTextContent('none captured')
    expect(locks).not.toHaveTextContent('VizMutex')
  })

  it('says unavailable when the session history could not be read (H-3)', () => {
    renderPanels({ history: 'unavailable' })
    const channels = screen.getByTestId('evidence-section-Channels')
    expect(channels).toHaveTextContent('unavailable')
    expect(channels).not.toHaveTextContent('none captured')
    expect(channels).not.toHaveTextContent('InstrumentedChannel')
  })

  it('names no code change on a demo, which has full fidelity already', () => {
    renderPanels({ rung: 'demo' })
    const locks = screen.getByTestId('evidence-section-Locks and permits')
    expect(locks).toHaveTextContent('none captured')
    expect(locks).not.toHaveTextContent('VizMutex')
  })

  it('offers no unlock for Jobs, which are not wrapper-only', () => {
    renderPanels()
    expect(screen.getByTestId('evidence-section-Jobs')).toHaveTextContent('none captured')
    expect(screen.getByTestId('evidence-section-Jobs')).not.toHaveTextContent('Swap')
  })

  it('summarises threads by name and opens the grouped table', async () => {
    renderPanels()
    const threads = screen.getByRole('button', { name: /Threads/ })
    expect(threads).toHaveTextContent('1 name (1 JVM thread)')
    await userEvent.click(threads)
    expect(screen.getByTestId('thread-table')).toBeVisible()
  })

  it('summarises the dispatchers named in thread activity', async () => {
    renderPanels()
    const dispatchers = screen.getByRole('button', { name: /Dispatchers/ })
    expect(dispatchers).toHaveTextContent('1 dispatcher')
    await userEvent.click(dispatchers)
    expect(screen.getByTestId('dispatcher-overview')).toBeVisible()
  })

  it('warns that an opened panel is not following the replay cursor (D-17)', async () => {
    renderPanels({ replayActive: true })
    await userEvent.click(screen.getByRole('button', { name: /Dispatchers/ }))
    expect(within(screen.getByTestId('evidence-section-Dispatchers')).getByTestId('live-data-notice')).toBeVisible()
  })

  it('gives Threads no live-data warning in replay: its table follows the cursor', async () => {
    renderPanels({ replayActive: true })
    await userEvent.click(screen.getByRole('button', { name: /Threads/ }))
    expect(within(screen.getByTestId('evidence-section-Threads')).queryByTestId('live-data-notice')).toBeNull()
  })

  it('shows no live-data warning outside replay', async () => {
    renderPanels()
    await userEvent.click(screen.getByRole('button', { name: /Dispatchers/ }))
    expect(screen.queryByTestId('live-data-notice')).toBeNull()
  })

  it('opens and closes with Space and Enter, like any disclosure button', async () => {
    renderPanels()
    const threads = screen.getByRole('button', { name: /Threads/ })
    threads.focus()
    await userEvent.keyboard(' ')
    expect(threads).toHaveAttribute('aria-expanded', 'true')
    await userEvent.keyboard('{Enter}')
    expect(threads).toHaveAttribute('aria-expanded', 'false')
  })
})

describe('EvidencePanels — the page\'s events and selection (S-2)', () => {
  const synced = {
    categories: categories({ hasSyncPrimitives: true, hasChannels: true, hasFlowOps: true, hasJobs: true }),
    counts: { ...none, locks: 1, channels: 1, flows: 1, jobEvents: 1 },
  }
  const events = [{ seq: 1 }, { seq: 2 }] as unknown as VizEvent[]

  it('gives every event-backed panel the page\'s events', () => {
    renderPanels({ ...synced, events })
    for (const id of ['sync-panel', 'channel-panel', 'flow-panel', 'job-panel']) {
      expect(screen.getByTestId(id)).toHaveAttribute('data-events', '2')
    }
  })

  it('unfolds Locks and permits for a selected lock, and passes the lock on', () => {
    const { rerender } = renderPanels({ ...synced, events })
    expect(screen.getByRole('button', { name: /Locks and permits/ })).toHaveAttribute('aria-expanded', 'false')
    rerender(panels({ ...synced, events, selection: { kind: 'resource', resource: 'mutex', id: 'm-1' } }))
    expect(screen.getByRole('button', { name: /Locks and permits/ })).toHaveAttribute('aria-expanded', 'true')
    expect(screen.getByTestId('sync-panel')).toHaveAttribute('data-resource', 'm-1')
  })

  it('re-opens the section when the same lock is selected again after folding it', async () => {
    const { rerender } = renderPanels({ ...synced, events, selection: { kind: 'resource', resource: 'mutex', id: 'm-1' } })
    await userEvent.click(screen.getByRole('button', { name: /Locks and permits/ }))
    expect(screen.getByRole('button', { name: /Locks and permits/ })).toHaveAttribute('aria-expanded', 'false')
    rerender(panels({ ...synced, events, selection: { kind: 'resource', resource: 'mutex', id: 'm-1' } }))
    expect(screen.getByRole('button', { name: /Locks and permits/ })).toHaveAttribute('aria-expanded', 'true')
  })

  it('unfolds Channels for a selected channel, shows it, and a pick there selects on the page', async () => {
    const onSelectResource = vi.fn()
    const { rerender } = renderPanels({ ...synced, events, onSelectResource })
    rerender(panels({ ...synced, events, onSelectResource, selection: { kind: 'resource', resource: 'channel', id: 'ch-1' } }))
    expect(screen.getByRole('button', { name: /Channels/ })).toHaveAttribute('aria-expanded', 'true')
    expect(screen.getByTestId('channel-panel')).toHaveAttribute('data-focus', 'ch-1')
    await userEvent.click(screen.getByTestId('channel-panel'))
    expect(onSelectResource).toHaveBeenCalledWith({ kind: 'resource', resource: 'channel', id: 'ch-2' })
  })

  it('passes a selected coroutine to Locks and permits without unfolding anything', () => {
    renderPanels({ ...synced, events, selection: { kind: 'coroutine', id: 'c-1' }, picker: { select: vi.fn(), canSelect: () => true } })
    expect(screen.getByTestId('sync-panel')).toHaveAttribute('data-coroutine', 'c-1')
    expect(screen.getByTestId('sync-panel')).toHaveAttribute('data-picker', 'true')
    expect(screen.getByRole('button', { name: /Locks and permits/ })).toHaveAttribute('aria-expanded', 'false')
  })

  it('reads the events it is given in replay too, so Locks and permits carries no live-data warning', async () => {
    renderPanels({ ...synced, events, replayActive: true })
    await userEvent.click(screen.getByRole('button', { name: /Locks and permits/ }))
    expect(within(screen.getByTestId('evidence-section-Locks and permits')).queryByTestId('live-data-notice')).toBeNull()
  })
})

