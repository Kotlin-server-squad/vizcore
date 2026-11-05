import { describe, it, expect, vi } from 'vitest'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import type { EventCategories } from '@/hooks/use-event-categories'
import type { EvidenceCounts } from '@/lib/evidence-summary'
import type { ThreadActivity } from '@/types/api'
import { EvidencePanels } from './EvidencePanels'

vi.mock('../channels/ChannelPanel', () => ({ ChannelPanel: () => <div data-testid="channel-panel" /> }))
vi.mock('../flow/FlowPanel', () => ({ FlowPanel: () => <div data-testid="flow-panel" /> }))
vi.mock('../sync/SyncPanel', () => ({ SyncPanel: () => <div data-testid="sync-panel" /> }))
vi.mock('../jobs/JobPanel', () => ({ JobPanel: () => <div data-testid="job-panel" /> }))
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
  return render(
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
    />,
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
