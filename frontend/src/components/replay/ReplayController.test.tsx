import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import type { ReactNode } from 'react'
import { ReplayController } from './ReplayController'
import { LiveDataNotice } from './LiveDataNotice'
import type { UseReplayReturn } from '@/hooks/use-replay'
import type { VizEvent } from '@/types/api'

// framer-motion stubbed to plain DOM so MotionValues do not need a real
// animation loop in jsdom (mirrors SessionWorkspace.test.tsx).
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
    events: [],
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

describe('ReplayController — keys owned by the dock', () => {
  it('leaves the arrow keys to a focused separator or tab', async () => {
    const replay = makeReplay()
    render(
      <>
        <ReplayController replay={replay} />
        <div role="separator" tabIndex={0} aria-label="Resize the dock" />
        <button role="tab" type="button">Inspector</button>
      </>,
    )
    screen.getByRole('separator').focus()
    await userEvent.keyboard('{ArrowRight}{ArrowLeft}{Home}{End}')
    screen.getByRole('tab').focus()
    await userEvent.keyboard('{ArrowRight}{ArrowLeft}{Home}{End}')
    expect(replay.stepForward).not.toHaveBeenCalled()
    expect(replay.stepBack).not.toHaveBeenCalled()
    expect(replay.seekTo).not.toHaveBeenCalled()
  })

  it('leaves Home and End to an open menu', async () => {
    const replay = makeReplay()
    render(
      <>
        <ReplayController replay={replay} />
        <ul role="menu" aria-label="More actions">
          <li role="menuitem" tabIndex={-1}>Share</li>
        </ul>
      </>,
    )
    screen.getByRole('menuitem').focus()
    await userEvent.keyboard('{End}{Home}{ArrowLeft}')
    screen.getByRole('menu').focus()
    await userEvent.keyboard('{End}')
    expect(replay.seekTo).not.toHaveBeenCalled()
    expect(replay.stepBack).not.toHaveBeenCalled()
  })

  it('leaves a key alone that a focused control has already handled', () => {
    const replay = makeReplay()
    render(
      <>
        <ReplayController replay={replay} />
        <div data-testid="widget" tabIndex={0} onKeyDown={e => e.preventDefault()} />
      </>,
    )
    const widget = screen.getByTestId('widget')
    widget.focus()
    fireEvent.keyDown(widget, { key: 'End' })
    expect(replay.seekTo).not.toHaveBeenCalled()
  })

  it('leaves Space to a focused button, so a disclosure opens instead of playback starting', async () => {
    const replay = makeReplay()
    const onToggle = vi.fn()
    render(
      <>
        <ReplayController replay={replay} />
        <button type="button" aria-expanded={false} onClick={onToggle}>Threads</button>
      </>,
    )
    screen.getByRole('button', { name: 'Threads' }).focus()
    await userEvent.keyboard(' ')
    expect(onToggle).toHaveBeenCalledTimes(1)
    expect(replay.play).not.toHaveBeenCalled()
    // Arrows still step the replay from a button.
    await userEvent.keyboard('{ArrowRight}')
    expect(replay.stepForward).toHaveBeenCalledTimes(1)
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

describe('ReplayController — the slider says where in time it is (S-14)', () => {
  const at = (tsNanos: number) => ({ seq: 1, tsNanos, sessionId: 's', kind: 'CoroutineCreated' }) as unknown as VizEvent

  it('announces the cursor as seconds of the whole replay', () => {
    render(<ReplayController replay={makeReplay({ events: [at(0), at(4.2e9), at(12e9)], currentIndex: 1, totalEvents: 3 })} />)
    expect(screen.getByRole('slider')).toHaveAttribute('aria-valuetext', '4.2 seconds of 12.0')
  })
})

describe('ReplayController — the slider moves in seconds (Part 8, M-2)', () => {
  let seq = 0
  const at = (tsNanos: number) => ({ seq: ++seq, tsNanos, sessionId: 's', kind: 'coroutine.created' }) as unknown as VizEvent
  // 0 s, 1 s, 1 s, 4 s, 12 s
  const events = () => [at(0), at(1e9), at(1e9), at(4e9), at(12e9)]

  it('labels the cursor in seconds and events', () => {
    render(<ReplayController replay={makeReplay({ events: events(), currentIndex: 3, totalEvents: 5 })} />)
    expect(screen.getByTestId('replay-time')).toHaveTextContent('4.0 s / 12.0 s · event 4 of 5')
    expect(screen.getByTestId('replay-time')).toHaveAttribute('data-look-part', 'replay-time')
    expect(screen.getByTestId('replay-controller')).toHaveAttribute('data-look-part', 'replay-bar')
  })

  it('sets the slider to the cursor’s time, in tenths of a second', () => {
    render(<ReplayController replay={makeReplay({ events: events(), currentIndex: 3, totalEvents: 5 })} />)
    const slider = screen.getByRole('slider')
    expect(slider).toHaveAttribute('max', '120')
    expect(slider).toHaveValue('40')
  })

  it('seeks a dragged time to the last event at or before it', () => {
    const seekTo = vi.fn()
    render(<ReplayController replay={makeReplay({ events: events(), currentIndex: 0, totalEvents: 5, seekTo })} />)
    fireEvent.change(screen.getByRole('slider'), { target: { value: '35' } })
    expect(seekTo).toHaveBeenLastCalledWith(2)
  })

  it('steps one event with the arrow keys on the slider', () => {
    const stepForward = vi.fn()
    const stepBack = vi.fn()
    const seekTo = vi.fn()
    render(
      <ReplayController
        replay={makeReplay({ events: events(), currentIndex: 1, totalEvents: 5, stepForward, stepBack, seekTo })}
      />,
    )
    const slider = screen.getByRole('slider')
    fireEvent.keyDown(slider, { key: 'ArrowRight' })
    fireEvent.keyDown(slider, { key: 'ArrowLeft' })
    expect(stepForward).toHaveBeenCalledTimes(1)
    expect(stepBack).toHaveBeenCalledTimes(1)
    expect(seekTo).not.toHaveBeenCalled()
  })

  it('falls back to steps by event when no time passes', () => {
    render(<ReplayController replay={makeReplay({ events: [at(5), at(5)], currentIndex: 1, totalEvents: 2 })} />)
    expect(screen.getByTestId('replay-time')).toHaveTextContent('Event 2 / 2')
    expect(screen.getByRole('slider')).toHaveAttribute('max', '1')
  })

  it('shows a note about where the replay starts only when it is given one', () => {
    const { rerender } = render(<ReplayController replay={makeReplay({ events: events(), totalEvents: 5 })} />)
    expect(screen.queryByTestId('replay-start-note')).toBeNull()
    rerender(<ReplayController replay={makeReplay({ events: events(), totalEvents: 5 })} startNote="starts at event 40" />)
    expect(screen.getByTestId('replay-start-note')).toHaveTextContent('starts at event 40')
    expect(screen.getByTestId('replay-start-note')).toHaveAttribute('data-look-part', 'trimmed-note')
  })
})
