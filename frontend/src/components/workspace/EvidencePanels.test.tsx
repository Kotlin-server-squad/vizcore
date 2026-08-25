import { describe, it, expect, vi } from 'vitest'
import { render, screen, within } from '@testing-library/react'
import type { EventCategories } from '@/hooks/use-event-categories'
import { EvidencePanels } from './EvidencePanels'

// Each evidence panel owns its own protected fetch; this suite is about WHICH
// panels mount, so they are stubbed down to a probe.
vi.mock('../channels/ChannelPanel', () => ({
  ChannelPanel: () => <div data-testid="channel-panel" />,
}))
vi.mock('../flow/FlowPanel', () => ({
  FlowPanel: () => <div data-testid="flow-panel" />,
}))
vi.mock('../sync/SyncPanel', () => ({
  SyncPanel: () => <div data-testid="sync-panel" />,
}))
vi.mock('../jobs/JobPanel', () => ({
  JobPanel: () => <div data-testid="job-panel" />,
}))
vi.mock('../ThreadTimeline', () => ({
  ThreadTimeline: () => <div data-testid="thread-timeline" />,
}))
vi.mock('../DispatcherOverview', () => ({
  DispatcherOverview: () => <div data-testid="dispatcher-overview" />,
}))

function categories(overrides: Partial<EventCategories> = {}): EventCategories {
  return {
    hasChannels: false,
    hasFlowOps: false,
    hasSyncPrimitives: false,
    hasJobs: false,
    ...overrides,
  } as EventCategories
}

function renderPanels(props: Partial<Parameters<typeof EvidencePanels>[0]> = {}) {
  return render(
    <EvidencePanels
      sessionId="s-1"
      rung="attached"
      categories={categories()}
      replayActive={false}
      readOnly={false}
      streamEnabled={false}
      threadActivity={undefined}
      {...props}
    />,
  )
}

describe('EvidencePanels', () => {
  it('mounts a panel when its events are present, and no locked stand-in for it', () => {
    renderPanels({ categories: categories({ hasChannels: true }) })

    expect(screen.getByTestId('channel-panel')).toBeInTheDocument()
    // The section heading uses the same words, so scope the check: what must be
    // absent is the locked STAND-IN, not the title.
    const locked = screen.queryByTestId('evidence-locked')
    expect(locked).not.toBeNull()
    expect(within(locked!).queryByText('Channel traffic')).toBeNull()
  })

  it('invites the next rung when a wrapper-only capability is absent on a real session', () => {
    renderPanels({ rung: 'attached', categories: categories() })

    // The unlock names the concrete change, not "upgrade" or "enable".
    expect(screen.getByText(/InstrumentedChannel/)).toBeInTheDocument()
    expect(screen.getByText(/VizMutex/)).toBeInTheDocument()
    expect(screen.getByText(/instrumented\(\)/)).toBeInTheDocument()

    // And it does NOT mount the panel it is standing in for.
    expect(screen.queryByTestId('channel-panel')).toBeNull()
    expect(screen.queryByTestId('sync-panel')).toBeNull()
    expect(screen.queryByTestId('flow-panel')).toBeNull()
  })

  it('locks nothing on a demo session', () => {
    renderPanels({ rung: 'demo', categories: categories() })

    // A demo already has full fidelity. Telling its user to swap in VizMutex is
    // an instruction they cannot act on — the code is not theirs.
    expect(screen.queryByTestId('evidence-locked')).toBeNull()
    expect(screen.queryByText(/VizMutex/)).toBeNull()
    expect(screen.queryByText(/InstrumentedChannel/)).toBeNull()
  })

  it('shows the threads panel at every rung', () => {
    for (const rung of ['demo', 'attached', 'instrumented'] as const) {
      const { unmount } = renderPanels({ rung })
      expect(screen.getByTestId('dispatcher-overview')).toBeInTheDocument()
      unmount()
    }
  })

  it('warns that a live-data panel is not following the replay cursor', () => {
    renderPanels({ replayActive: true, categories: categories({ hasChannels: true }) })

    expect(screen.getAllByTestId('live-data-notice').length).toBeGreaterThan(0)
  })

  it('shows no live-data warning outside replay', () => {
    renderPanels({ categories: categories({ hasChannels: true }) })

    expect(screen.queryByTestId('live-data-notice')).toBeNull()
  })

  it('groups locked panels after the panels that carry real evidence', () => {
    const { container } = renderPanels({ categories: categories({ hasChannels: true }) })

    const evidence = container.querySelector('[data-testid="evidence-present"]')
    const locked = container.querySelector('[data-testid="evidence-locked"]')
    expect(evidence).not.toBeNull()
    expect(locked).not.toBeNull()
    // An invitation must not outrank the thing the session actually has.
    expect(evidence!.compareDocumentPosition(locked!) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
  })
})
