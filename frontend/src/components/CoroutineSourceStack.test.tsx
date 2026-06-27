import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent } from '@testing-library/react'
import { CoroutineSourceStack } from './CoroutineSourceStack'

// Mock the lazy timeline data path so the body can be driven directly.
const useCoroutineTimeline = vi.fn()
const useSuspensionPoints = vi.fn()
vi.mock('@/hooks/use-timeline', () => ({
  useCoroutineTimeline: (sessionId?: string, coroutineId?: string) =>
    useCoroutineTimeline(sessionId, coroutineId),
  useSuspensionPoints: (sessionId?: string, coroutineId?: string) =>
    useSuspensionPoints(sessionId, coroutineId),
}))

// Mock the toast so the jump-to-code success copy is assertable without a provider.
const toastSuccess = vi.fn()
vi.mock('@/lib/toast', () => ({
  toastSuccess: (title: string) => toastSuccess(title),
}))

const writeText = vi.fn()

beforeEach(() => {
  vi.clearAllMocks()
  Object.assign(navigator, { clipboard: { writeText } })
})

// A timeline whose started event carries a user-code creation frame.
function timelineWithCreation() {
  return {
    data: {
      coroutineId: 'c-1',
      name: 'Worker',
      events: [
        {
          seq: 1,
          timestamp: 100,
          kind: 'coroutine.started',
          suspensionPoint: {
            function: 'com.app.Service.run',
            fileName: 'Service.kt',
            lineNumber: 42,
            reason: 'launch',
            timestamp: 100,
          },
        },
      ],
    },
    isLoading: false,
    isError: false,
  }
}

const userSuspension = {
  function: 'com.app.Service.fetch',
  fileName: 'Service.kt',
  lineNumber: 99,
  reason: 'delay',
  timestamp: 200,
}

describe('CoroutineSourceStack', () => {
  it('keeps the timeline query disabled when coroutineId is null (no eager fetch)', () => {
    useCoroutineTimeline.mockReturnValue({ data: undefined, isLoading: false, isError: false })
    useSuspensionPoints.mockReturnValue([])

    render(<CoroutineSourceStack sessionId="s-1" coroutineId={null} />)

    // The hooks are called with the null id so the enabled-guard governs fetching.
    expect(useCoroutineTimeline).toHaveBeenCalledWith('s-1', undefined)
    expect(useSuspensionPoints).toHaveBeenCalledWith('s-1', undefined)
  })

  it('renders the loading state while the timeline query is in-flight', () => {
    useCoroutineTimeline.mockReturnValue({ data: undefined, isLoading: true, isError: false })
    useSuspensionPoints.mockReturnValue([])

    render(<CoroutineSourceStack sessionId="s-1" coroutineId="c-1" />)

    expect(screen.getByText('Loading timeline...')).toBeInTheDocument()
  })

  it('renders the error state in-body (not a toast) when the query errors', () => {
    useCoroutineTimeline.mockReturnValue({ data: undefined, isLoading: false, isError: true })
    useSuspensionPoints.mockReturnValue([])

    render(<CoroutineSourceStack sessionId="s-1" coroutineId="c-1" />)

    expect(screen.getByText("Couldn't load coroutine source.")).toBeInTheDocument()
    expect(
      screen.getByText(
        'Close the drawer and reopen it to retry, or check that the session is still live.',
      ),
    ).toBeInTheDocument()
    expect(toastSuccess).not.toHaveBeenCalled()
  })

  it('renders the empty state when there are no creation or suspension frames', () => {
    useCoroutineTimeline.mockReturnValue({
      data: { coroutineId: 'c-1', name: 'Worker', events: [] },
      isLoading: false,
      isError: false,
    })
    useSuspensionPoints.mockReturnValue([])

    render(<CoroutineSourceStack sessionId="s-1" coroutineId="c-1" />)

    expect(screen.getByText('No source attribution yet')).toBeInTheDocument()
    expect(
      screen.getByText(
        'This coroutine has no recorded creation or suspension frames yet. Source appears once the coroutine suspends or is captured by DebugProbes.',
      ),
    ).toBeInTheDocument()
  })

  it('collapsed default renders the compact Created at / Suspended at chips (not the full stack)', () => {
    useCoroutineTimeline.mockReturnValue(timelineWithCreation())
    useSuspensionPoints.mockReturnValue([userSuspension])

    render(<CoroutineSourceStack sessionId="s-1" coroutineId="c-1" />)

    // Compact chip labels render by default.
    expect(screen.getByText('Created at')).toBeInTheDocument()
    expect(screen.getByText('Suspended at')).toBeInTheDocument()

    // Each compact chip surfaces its file:line.
    expect(screen.getByText('Service.kt:42')).toBeInTheDocument()
    expect(screen.getByText('Service.kt:99')).toBeInTheDocument()

    // The full-stack section headers are NOT shown until expanded.
    expect(screen.queryByText('Created at — creation stack')).toBeNull()
    expect(screen.queryByText('Suspended at — last observed')).toBeNull()
  })

  it('expanding reveals the full creation + suspension stack sections', () => {
    useCoroutineTimeline.mockReturnValue(timelineWithCreation())
    useSuspensionPoints.mockReturnValue([userSuspension])

    render(<CoroutineSourceStack sessionId="s-1" coroutineId="c-1" />)

    fireEvent.click(screen.getByRole('button', { name: /show full stack/i }))

    expect(screen.getByText('Created at — creation stack')).toBeInTheDocument()
    expect(screen.getByText('Suspended at — last observed')).toBeInTheDocument()
    // Full frame rows render the function names.
    expect(screen.getByText('com.app.Service.run')).toBeInTheDocument()
    expect(screen.getByText('com.app.Service.fetch')).toBeInTheDocument()
  })

  it('the expanded section headers render text-sm (not text-lg)', () => {
    useCoroutineTimeline.mockReturnValue(timelineWithCreation())
    useSuspensionPoints.mockReturnValue([userSuspension])

    render(<CoroutineSourceStack sessionId="s-1" coroutineId="c-1" />)

    fireEvent.click(screen.getByRole('button', { name: /show full stack/i }))

    const created = screen.getByText('Created at — creation stack')
    const suspended = screen.getByText('Suspended at — last observed')
    expect(created.className).toContain('text-sm')
    expect(created.className).not.toContain('text-lg')
    expect(suspended.className).toContain('text-sm')
    expect(suspended.className).not.toContain('text-lg')
  })

  it('a user-frame jump copies {file}:{line} + toasts verbatim (collapsed compact chip)', () => {
    useCoroutineTimeline.mockReturnValue(timelineWithCreation())
    useSuspensionPoints.mockReturnValue([userSuspension])

    render(<CoroutineSourceStack sessionId="s-1" coroutineId="c-1" />)

    const jumpTarget = screen.getByRole('button', { name: 'Jump to code: Service.kt:99' })
    fireEvent.click(jumpTarget)
    expect(writeText).toHaveBeenCalledWith('Service.kt:99')
    expect(toastSuccess).toHaveBeenCalledWith('Copied Service.kt:99')
  })

  it('renders a library frame inert (no jump target)', () => {
    useCoroutineTimeline.mockReturnValue({
      data: { coroutineId: 'c-1', name: 'Worker', events: [] },
      isLoading: false,
      isError: false,
    })
    useSuspensionPoints.mockReturnValue([
      {
        function: 'kotlinx.coroutines.DelayKt.delay',
        fileName: 'Delay.kt',
        lineNumber: 12,
        reason: 'delay',
        timestamp: 200,
      },
    ])

    render(<CoroutineSourceStack sessionId="s-1" coroutineId="c-1" />)

    expect(screen.queryByRole('button', { name: /Jump to code/ })).not.toBeInTheDocument()
    expect(screen.getByText('Delay.kt:12')).toBeInTheDocument()
  })
})
