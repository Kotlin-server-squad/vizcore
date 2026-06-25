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

  it('renders Created at + Suspended at sections with a user-frame jump target', () => {
    useCoroutineTimeline.mockReturnValue({
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
    })
    useSuspensionPoints.mockReturnValue([
      {
        function: 'com.app.Service.fetch',
        fileName: 'Service.kt',
        lineNumber: 99,
        reason: 'delay',
        timestamp: 200,
      },
    ])

    render(<CoroutineSourceStack sessionId="s-1" coroutineId="c-1" />)

    expect(screen.getByText('Created at')).toBeInTheDocument()
    expect(screen.getByText('Suspended at')).toBeInTheDocument()

    const jumpTarget = screen.getByRole('button', { name: 'Jump to code: Service.kt:99' })
    fireEvent.click(jumpTarget)
    expect(writeText).toHaveBeenCalledWith('Service.kt:99')
    expect(toastSuccess).toHaveBeenCalledWith('Copied Service.kt:99')
  })

  it('renders a library frame inert (no role=button)', () => {
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
