import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import type { ReactNode } from 'react'
import { ReplayController } from './ReplayController'
import { LiveDataNotice } from './LiveDataNotice'
import type { UseReplayReturn } from '@/hooks/use-replay'

// framer-motion stubbed to plain DOM so MotionValues do not need a real
// animation loop in jsdom (mirrors SessionDetails.test.tsx).
vi.mock('framer-motion', () => ({
  motion: {
    div: ({ children, ...props }: Record<string, unknown>) => (
      <div {...props}>{children as ReactNode}</div>
    ),
  },
}))

// useReplayMotion returns a plain string for progressWidth (no MotionValue).
vi.mock('@/hooks/use-replay-motion', () => ({
  useReplayMotion: () => ({ progressWidth: '0%' }),
}))

function makeReplay(overrides: Partial<UseReplayReturn> = {}): UseReplayReturn {
  return {
    isPlaying: false,
    currentIndex: 2,
    currentEvent: null,
    speed: 1,
    progress: 0.2,
    visibleEvents: [],
    totalEvents: 10,
    play: vi.fn(),
    pause: vi.fn(),
    stepForward: vi.fn(),
    stepBack: vi.fn(),
    reset: vi.fn(),
    seekTo: vi.fn(),
    setSpeed: vi.fn(),
    ...overrides,
  }
}

describe('ReplayController — Stop / FastForward', () => {
  it('Stop button (FiSquare) seeks to 0 and pauses', async () => {
    const seekTo = vi.fn()
    const pause = vi.fn()
    render(<ReplayController replay={makeReplay({ seekTo, pause })} />)

    await userEvent.click(screen.getByRole('button', { name: 'Stop replay' }))

    expect(seekTo).toHaveBeenCalledWith(0)
    expect(pause).toHaveBeenCalled()
  })

  it('FastForward / jump-to-end seeks to the last event index', async () => {
    const seekTo = vi.fn()
    render(<ReplayController replay={makeReplay({ seekTo, totalEvents: 10 })} />)

    await userEvent.click(screen.getByRole('button', { name: 'Jump to end' }))

    expect(seekTo).toHaveBeenCalledWith(9)
  })
})

describe('ReplayController — ADR-017 keyboard shortcuts', () => {
  beforeEach(() => {
    // Ensure no leftover focus from prior tests
    if (document.activeElement instanceof HTMLElement) document.activeElement.blur()
  })

  it('Space toggles play/pause and preventDefaults', () => {
    const play = vi.fn()
    render(<ReplayController replay={makeReplay({ isPlaying: false, play })} />)

    const event = new KeyboardEvent('keydown', { key: ' ', bubbles: true, cancelable: true })
    const prevented = !document.dispatchEvent(event)

    expect(play).toHaveBeenCalled()
    expect(prevented).toBe(true)
  })

  it('Space pauses when already playing', () => {
    const pause = vi.fn()
    render(<ReplayController replay={makeReplay({ isPlaying: true, pause })} />)

    fireEvent.keyDown(document, { key: ' ' })

    expect(pause).toHaveBeenCalled()
  })

  it('ArrowRight steps forward, ArrowLeft steps back', () => {
    const stepForward = vi.fn()
    const stepBack = vi.fn()
    render(<ReplayController replay={makeReplay({ stepForward, stepBack })} />)

    fireEvent.keyDown(document, { key: 'ArrowRight' })
    fireEvent.keyDown(document, { key: 'ArrowLeft' })

    expect(stepForward).toHaveBeenCalled()
    expect(stepBack).toHaveBeenCalled()
  })

  it('Home seeks to 0, End seeks to last', () => {
    const seekTo = vi.fn()
    render(<ReplayController replay={makeReplay({ seekTo, totalEvents: 10 })} />)

    fireEvent.keyDown(document, { key: 'Home' })
    expect(seekTo).toHaveBeenCalledWith(0)

    fireEvent.keyDown(document, { key: 'End' })
    expect(seekTo).toHaveBeenCalledWith(9)
  })

  it('keys 1–4 set speed to 0.5/1/2/5x', () => {
    const setSpeed = vi.fn()
    render(<ReplayController replay={makeReplay({ setSpeed })} />)

    fireEvent.keyDown(document, { key: '1' })
    fireEvent.keyDown(document, { key: '2' })
    fireEvent.keyDown(document, { key: '3' })
    fireEvent.keyDown(document, { key: '4' })

    expect(setSpeed).toHaveBeenNthCalledWith(1, 0.5)
    expect(setSpeed).toHaveBeenNthCalledWith(2, 1)
    expect(setSpeed).toHaveBeenNthCalledWith(3, 2)
    expect(setSpeed).toHaveBeenNthCalledWith(4, 5)
  })

  it('suppresses shortcuts when focus is in a form field', () => {
    const play = vi.fn()
    render(
      <div>
        <input data-testid="text-field" />
        <ReplayController replay={makeReplay({ play })} />
      </div>,
    )

    const input = screen.getByTestId('text-field')
    input.focus()
    fireEvent.keyDown(input, { key: ' ' })

    expect(play).not.toHaveBeenCalled()
  })
})

describe('ReplayController — recording state', () => {
  it('renders the recording cluster and disables playback when isRecording', () => {
    const onStopRecording = vi.fn()
    render(
      <ReplayController
        replay={makeReplay()}
        isRecording
        elapsedMs={42_000}
        onStopRecording={onStopRecording}
      />,
    )

    // Elapsed rendered as m:ss
    expect(screen.getByText('0:42')).toBeInTheDocument()
    // Stop-recording button present and danger
    const stop = screen.getByRole('button', { name: 'Stop recording' })
    expect(stop).toBeInTheDocument()

    // Playback controls disabled while recording
    expect(screen.getByRole('button', { name: 'Play' })).toBeDisabled()
    expect(screen.getByRole('button', { name: 'Step forward' })).toBeDisabled()
  })

  it('does not render the recording cluster when isRecording is false', () => {
    render(<ReplayController replay={makeReplay()} />)
    expect(screen.queryByRole('button', { name: 'Stop recording' })).not.toBeInTheDocument()
    // Normal playback control enabled
    expect(screen.getByRole('button', { name: 'Play' })).not.toBeDisabled()
  })
})

describe('LiveDataNotice', () => {
  it('renders the locked D-17 copy', () => {
    render(<LiveDataNotice />)
    expect(
      screen.getByText(
        'Live data — this tab shows the current session state and is not affected by replay.',
      ),
    ).toBeInTheDocument()
  })
})
