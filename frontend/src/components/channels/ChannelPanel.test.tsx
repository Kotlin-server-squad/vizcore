import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import type { ReactNode } from 'react'
import { ChannelPanel } from './ChannelPanel'
import { ChannelBufferGauge } from './ChannelBufferGauge'
import { apiClient } from '@/lib/api-client'
import type { VizEvent } from '@/types/api'

vi.mock('@/lib/api-client', () => ({
  apiClient: {
    getSessionEvents: vi.fn(),
  },
}))

const mockedApiClient = vi.mocked(apiClient)

function createWrapper() {
  const queryClient = new QueryClient({
    defaultOptions: {
      queries: {
        retry: false,
        gcTime: 0,
      },
    },
  })
  return function Wrapper({ children }: { children: ReactNode }) {
    return (
      <QueryClientProvider client={queryClient}>
        {children}
      </QueryClientProvider>
    )
  }
}

function makeChannelEvent(kind: string, seq: number, overrides: Record<string, unknown> = {}): VizEvent {
  return {
    sessionId: 'session-1',
    seq,
    tsNanos: seq * 1_000_000,
    kind: kind as VizEvent['kind'],
    channelId: 'ch-1',
    ...overrides,
  } as unknown as VizEvent
}

describe('ChannelPanel', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('reads the events it is given, so it follows the live stream', () => {
    mockedApiClient.getSessionEvents.mockResolvedValue([])
    const events = [
      makeChannelEvent('ChannelCreated', 1, { channelId: 'ch-1', name: 'orders', capacity: 0, channelType: 'RENDEZVOUS' }),
    ]
    render(<ChannelPanel sessionId="session-1" events={events} />, { wrapper: createWrapper() })
    expect(screen.getByTestId('channel-selector-ch-1')).toBeInTheDocument()
  })

  it('shows the channel the page selected', () => {
    mockedApiClient.getSessionEvents.mockResolvedValue([])
    const events = [
      makeChannelEvent('ChannelCreated', 1, { channelId: 'ch-1', name: 'orders', capacity: 0, channelType: 'RENDEZVOUS' }),
      makeChannelEvent('ChannelCreated', 2, { channelId: 'ch-2', name: 'invoices', capacity: 0, channelType: 'RENDEZVOUS' }),
    ]
    render(<ChannelPanel sessionId="session-1" events={events} focusChannelId="ch-2" />, { wrapper: createWrapper() })
    expect(screen.getByText('Buffer State: invoices')).toBeInTheDocument()
  })

  it('marks the channel the page selected and scrolls it into view', () => {
    mockedApiClient.getSessionEvents.mockResolvedValue([])
    const scrollIntoView = vi.fn()
    Element.prototype.scrollIntoView = scrollIntoView
    const events = [
      makeChannelEvent('ChannelCreated', 1, { channelId: 'ch-1', name: 'orders', capacity: 0, channelType: 'RENDEZVOUS' }),
      makeChannelEvent('ChannelCreated', 2, { channelId: 'ch-2', name: 'invoices', capacity: 0, channelType: 'RENDEZVOUS' }),
    ]
    render(<ChannelPanel sessionId="session-1" events={events} focusChannelId="ch-2" revealKey={{}} />, { wrapper: createWrapper() })
    expect(screen.getByTestId('channel-selector-ch-2')).toHaveAttribute('data-focused', 'true')
    expect(screen.getByTestId('channel-selector-ch-1')).toHaveAttribute('data-focused', 'false')
    expect(scrollIntoView.mock.contexts[0]).toBe(screen.getByTestId('channel-selector-ch-2'))
  })

  it('tells assistive technology which channel is selected and which is shown', () => {
    mockedApiClient.getSessionEvents.mockResolvedValue([])
    const events = [
      makeChannelEvent('ChannelCreated', 1, { channelId: 'ch-1', name: 'orders', capacity: 0, channelType: 'RENDEZVOUS' }),
      makeChannelEvent('ChannelCreated', 2, { channelId: 'ch-2', name: 'invoices', capacity: 0, channelType: 'RENDEZVOUS' }),
    ]
    render(<ChannelPanel sessionId="session-1" events={events} focusChannelId="ch-2" />, { wrapper: createWrapper() })
    expect(screen.getByTestId('channel-selector-ch-2')).toHaveAttribute('aria-current', 'true')
    expect(screen.getByTestId('channel-selector-ch-2')).toHaveAttribute('aria-pressed', 'true')
    expect(screen.getByTestId('channel-selector-ch-1')).not.toHaveAttribute('aria-current')
    expect(screen.getByTestId('channel-selector-ch-1')).toHaveAttribute('aria-pressed', 'false')
  })

  it('picking a channel selects it on the page, which then shows it (one selection, S-2)', async () => {
    mockedApiClient.getSessionEvents.mockResolvedValue([])
    const events = [
      makeChannelEvent('ChannelCreated', 1, { channelId: 'ch-1', name: 'orders', capacity: 0, channelType: 'RENDEZVOUS' }),
      makeChannelEvent('ChannelCreated', 2, { channelId: 'ch-2', name: 'invoices', capacity: 0, channelType: 'RENDEZVOUS' }),
    ]
    const onSelectChannel = vi.fn()
    const wrapper = createWrapper()
    const { rerender } = render(
      <ChannelPanel sessionId="session-1" events={events} focusChannelId="ch-1" onSelectChannel={onSelectChannel} />,
      { wrapper },
    )
    await userEvent.click(screen.getByTestId('channel-selector-ch-2'))
    expect(onSelectChannel).toHaveBeenCalledWith('ch-2')
    // The page's selection decides what is shown: still ch-1 until the page says otherwise.
    expect(screen.getByText('Buffer State: orders')).toBeInTheDocument()
    rerender(<ChannelPanel sessionId="session-1" events={events} focusChannelId="ch-2" onSelectChannel={onSelectChannel} />)
    // The detail swaps with an exit animation (AnimatePresence mode="wait").
    expect(await screen.findByText('Buffer State: invoices')).toBeInTheDocument()
  })

  it('renders empty state when no channel events exist', async () => {
    mockedApiClient.getSessionEvents.mockResolvedValue([])

    render(<ChannelPanel sessionId="session-1" />, {
      wrapper: createWrapper(),
    })

    // Should show the empty state
    const emptyPanel = await screen.findByTestId('channel-panel-empty')
    expect(emptyPanel).toBeInTheDocument()
    expect(screen.getByText('No Channel Activity')).toBeInTheDocument()
  })

  it('renders channel panel with channel events', async () => {
    const events = [
      makeChannelEvent('ChannelCreated', 1, {
        channelId: 'ch-1',
        name: 'TestChannel',
        capacity: 10,
        channelType: 'BUFFERED',
      }),
      makeChannelEvent('ChannelSendStarted', 2, {
        channelId: 'ch-1',
        coroutineId: 'producer-1',
        valueDescription: 'Item-1',
      }),
      makeChannelEvent('ChannelSendCompleted', 3, {
        channelId: 'ch-1',
        coroutineId: 'producer-1',
        valueDescription: 'Item-1',
      }),
      makeChannelEvent('ChannelBufferStateChanged', 4, {
        channelId: 'ch-1',
        currentSize: 1,
        capacity: 10,
      }),
      makeChannelEvent('ChannelReceiveStarted', 5, {
        channelId: 'ch-1',
        coroutineId: 'consumer-1',
      }),
      makeChannelEvent('ChannelReceiveCompleted', 6, {
        channelId: 'ch-1',
        coroutineId: 'consumer-1',
        valueDescription: 'Item-1',
      }),
    ]

    mockedApiClient.getSessionEvents.mockResolvedValue(events)

    render(<ChannelPanel sessionId="session-1" />, {
      wrapper: createWrapper(),
    })

    // Should render the panel with channel data
    const panel = await screen.findByTestId('channel-panel')
    expect(panel).toBeInTheDocument()

    // Should show the channel in the list
    expect(screen.getByText('TestChannel')).toBeInTheDocument()
  })

  it('allows selecting a different channel', async () => {
    const events = [
      makeChannelEvent('ChannelCreated', 1, {
        channelId: 'ch-1',
        name: 'Alpha',
        capacity: 5,
        channelType: 'BUFFERED',
      }),
      makeChannelEvent('ChannelCreated', 2, {
        channelId: 'ch-2',
        name: 'Beta',
        capacity: 8,
        channelType: 'RENDEZVOUS',
      }),
      makeChannelEvent('ChannelSendStarted', 3, {
        channelId: 'ch-2',
        coroutineId: 'p1',
        valueDescription: 'val',
      }),
    ]

    mockedApiClient.getSessionEvents.mockResolvedValue(events)

    render(<ChannelPanel sessionId="session-1" />, {
      wrapper: createWrapper(),
    })

    // Wait for panel to render
    await screen.findByTestId('channel-panel')

    // First channel is auto-selected
    expect(screen.getByText('Alpha')).toBeInTheDocument()
    expect(screen.getByText('Beta')).toBeInTheDocument()

    // Click on second channel
    const betaSelector = screen.getByTestId('channel-selector-ch-2')
    fireEvent.click(betaSelector)

    // After clicking, the buffer state header should show Beta (wait for animation)
    await waitFor(() => {
      expect(screen.getByText(/Buffer State:.*Beta/)).toBeInTheDocument()
    })
  })
})

describe('ChannelBufferGauge', () => {
  it('renders green color for low fill (< 50%)', () => {
    render(<ChannelBufferGauge currentSize={2} capacity={10} channelType="BUFFERED" />)

    const gauge = screen.getByTestId('channel-buffer-gauge')
    expect(gauge).toBeInTheDocument()

    // Should show 2 / 10
    expect(screen.getByText(/2 \/ 10/)).toBeInTheDocument()
    expect(screen.getByText(/20% full/)).toBeInTheDocument()

    // Fill bar should use success (green) color class
    const fill = screen.getByTestId('buffer-fill')
    expect(fill.className).toContain('bg-success')
  })

  it('renders yellow color for medium fill (50-80%)', () => {
    render(<ChannelBufferGauge currentSize={6} capacity={10} channelType="BUFFERED" />)

    const fill = screen.getByTestId('buffer-fill')
    expect(fill.className).toContain('bg-warning')
    expect(screen.getByText(/60% full/)).toBeInTheDocument()
  })

  it('renders red color for high fill (> 80%)', () => {
    render(<ChannelBufferGauge currentSize={9} capacity={10} channelType="BUFFERED" />)

    const fill = screen.getByTestId('buffer-fill')
    expect(fill.className).toContain('bg-danger')
    expect(screen.getByText(/90% full/)).toBeInTheDocument()
  })

  it('renders channel type badge', () => {
    render(<ChannelBufferGauge currentSize={0} capacity={0} channelType="RENDEZVOUS" />)

    const badge = screen.getByTestId('channel-type-badge')
    expect(badge).toBeInTheDocument()
    expect(badge.textContent).toBe('RENDEZVOUS')
  })

  it('renders CLOSED badge when channel is closed', () => {
    render(
      <ChannelBufferGauge currentSize={0} capacity={10} channelType="BUFFERED" isClosed />,
    )

    const closedBadge = screen.getByTestId('channel-closed-badge')
    expect(closedBadge).toBeInTheDocument()
    expect(closedBadge.textContent).toBe('CLOSED')
  })

  it('renders unlimited channel without progress bar', () => {
    render(<ChannelBufferGauge currentSize={5} capacity={0} channelType="UNLIMITED" />)

    expect(screen.getByText(/Unlimited buffer/)).toBeInTheDocument()
    expect(screen.queryByTestId('buffer-bar')).not.toBeInTheDocument()
  })
})
