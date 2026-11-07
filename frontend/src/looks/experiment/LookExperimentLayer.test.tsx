import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { act, fireEvent, render, screen } from '@testing-library/react'
import { LookProvider, forgetVisitLook, useLook } from '../look-context'
import type { LookId } from '../registry'
import { sendBatch, sendVote, type ExperimentConfig, type ExperimentEvent } from './api'
import { LATER_VISIT_DELAY_MS, VOTE_AFTER_MS } from './constants'
import { LookExperimentLayer, forgetLoadVisits, useLookExperiment } from './LookExperimentLayer'
import { forgetQueue } from './queue'
import { forgetExperimentState, readExperimentState, updateExperimentState } from './storage'

vi.mock('./api', async importOriginal => ({
  ...(await importOriginal<typeof import('./api')>()),
  sendBatch: vi.fn(),
  sendVote: vi.fn(async () => true),
}))

const ON: ExperimentConfig = {
  enabled: true,
  weights: { classic: 25, a: 25, b: 25, c: 25 },
  surveyUrl: null,
}
const OFF: ExperimentConfig = { enabled: false, weights: {}, surveyUrl: null }

/** Every event sent so far, in order. */
function sent(): ExperimentEvent[] {
  return vi.mocked(sendBatch).mock.calls.flatMap(([, events]) => events)
}

let visibility: 'visible' | 'hidden' = 'visible'
function setVisibility(state: 'visible' | 'hidden') {
  visibility = state
  act(() => {
    document.dispatchEvent(new Event('visibilitychange'))
  })
}

function Probe() {
  const { look, setLook } = useLook()
  const experiment = useLookExperiment()
  return (
    <div>
      <span data-testid="look">{look}</span>
      <span data-testid="experiment">{experiment ? 'on' : 'off'}</span>
      <button
        onClick={() => {
          experiment?.switched(look, 'c')
          setLook?.('c')
        }}
      >
        switch to c
      </button>
      <button onClick={() => experiment?.openVote()}>rate</button>
    </div>
  )
}

function Page({
  config,
  sessionId = 's-1',
  assigned = null,
}: {
  config: ExperimentConfig | null
  sessionId?: string
  assigned?: LookId | null
}) {
  return (
    <LookProvider assigned={assigned}>
      <LookExperimentLayer config={config} sessionId={sessionId}>
        <Probe />
      </LookExperimentLayer>
    </LookProvider>
  )
}

const renderPage = (props: Parameters<typeof Page>[0]) => render(<Page {...props} />)

beforeEach(() => {
  vi.useFakeTimers()
  localStorage.clear()
  forgetVisitLook()
  forgetExperimentState()
  forgetQueue()
  forgetLoadVisits()
  vi.mocked(sendBatch).mockClear()
  vi.mocked(sendVote).mockClear()
  visibility = 'visible'
  Object.defineProperty(document, 'visibilityState', { configurable: true, get: () => visibility })
})

afterEach(() => {
  vi.useRealTimers()
})

describe('while the experiment is off', () => {
  it.each([null, OFF])('measures nothing, shows nothing and offers nothing (config %o)', config => {
    const { unmount, container } = renderPage({ config })
    act(() => vi.advanceTimersByTime(VOTE_AFTER_MS * 2))
    setVisibility('hidden')
    window.dispatchEvent(new Event('pagehide'))
    unmount()
    expect(sendBatch).not.toHaveBeenCalled()
    expect(container.querySelector('[data-testid="look-experiment"]')).toBeNull()
  })

  it('keeps the page mounted when the config arrives', () => {
    const { rerender } = renderPage({ config: null })
    const before = screen.getByTestId('look')
    rerender(<Page config={ON} />)
    expect(screen.getByTestId('look')).toBe(before)
    expect(screen.getByTestId('experiment')).toHaveTextContent('on')
  })
})

describe('measuring', () => {
  it('counts a session page seen in its look, and another one for the next session page', () => {
    const { rerender, unmount } = renderPage({ config: ON, assigned: 'b' })
    rerender(<Page config={ON} assigned="b" sessionId="s-2" />)
    unmount()
    expect(sent().filter(e => e.type === 'seen')).toEqual([
      { type: 'seen', look: 'b' },
      { type: 'seen', look: 'b' },
    ])
  })

  it('sends the visible time when the page is hidden, by beacon, and stops counting while hidden', () => {
    renderPage({ config: ON, assigned: 'a' })
    act(() => vi.advanceTimersByTime(12_000))
    setVisibility('hidden')
    expect(vi.mocked(sendBatch).mock.calls.at(-1)![2]).toBe(true)
    act(() => vi.advanceTimersByTime(60_000))
    setVisibility('visible')
    act(() => vi.advanceTimersByTime(3_000))
    window.dispatchEvent(new Event('pagehide'))
    const times = sent().filter(e => e.type === 'time')
    expect(times).toEqual([
      { type: 'time', look: 'a', ms: 12_000 },
      { type: 'time', look: 'a', ms: 3_000 },
    ])
  })

  it('records a switch made by the person, with the time in each look', () => {
    const { unmount } = renderPage({ config: ON, assigned: 'a' })
    act(() => vi.advanceTimersByTime(5_000))
    fireEvent.click(screen.getByText('switch to c'))
    act(() => vi.advanceTimersByTime(2_000))
    unmount()
    expect(sent()).toEqual(
      expect.arrayContaining([
        { type: 'switch', from: 'a', to: 'c' },
        { type: 'time', look: 'a', ms: 5_000 },
        { type: 'time', look: 'c', ms: 2_000 },
        { type: 'seen', look: 'c' },
      ])
    )
  })

  it('when the config and the assignment arrive together, counts the page only in the assigned look', () => {
    const { rerender, unmount } = renderPage({ config: null, assigned: null })
    rerender(<Page config={ON} assigned="b" />)
    unmount()
    expect(sent().filter(e => e.type === 'seen' || e.type === 'time').map(e => e.look)).toEqual(['b'])
  })

  it('a look given by the experiment is not a switch', () => {
    const { rerender, unmount } = renderPage({ config: ON, assigned: null })
    rerender(<Page config={ON} assigned="b" />)
    unmount()
    expect(sent().some(e => e.type === 'switch')).toBe(false)
  })
})

describe('the vote card', () => {
  it('offers itself after 3 minutes of visible time in the look, not before', () => {
    renderPage({ config: ON, assigned: 'b' })
    act(() => vi.advanceTimersByTime(VOTE_AFTER_MS - 10_000))
    expect(screen.queryByRole('region', { name: /rate this look/i })).toBeNull()
    act(() => vi.advanceTimersByTime(15_000))
    expect(screen.getByRole('region', { name: /rate this look/i })).toHaveTextContent('B · Calm')
  })

  it('counts the time of earlier visits', () => {
    updateExperimentState(s => ({ ...s, visibleMs: { b: VOTE_AFTER_MS - 6_000 } }))
    renderPage({ config: ON, assigned: 'b' })
    act(() => vi.advanceTimersByTime(10_000))
    expect(screen.getByRole('region', { name: /rate this look/i })).toBeInTheDocument()
  })

  it('on a later visit, offers itself after 30 seconds', () => {
    updateExperimentState(s => ({ ...s, visits: { b: 1 } }))
    renderPage({ config: ON, assigned: 'b' })
    act(() => vi.advanceTimersByTime(LATER_VISIT_DELAY_MS + 5_000))
    expect(screen.getByRole('region', { name: /rate this look/i })).toBeInTheDocument()
  })

  it('once closed, no longer offers itself for that look; the menu still opens it', () => {
    renderPage({ config: ON, assigned: 'b' })
    act(() => vi.advanceTimersByTime(VOTE_AFTER_MS + 5_000))
    fireEvent.click(screen.getByRole('button', { name: /close the rating/i }))
    expect(readExperimentState().dismissedVote).toEqual({ b: true })
    act(() => vi.advanceTimersByTime(VOTE_AFTER_MS))
    expect(screen.queryByRole('region', { name: /rate this look/i })).toBeNull()
    fireEvent.click(screen.getByText('rate'))
    expect(screen.getByRole('region', { name: /rate this look/i })).toBeInTheDocument()
  })

  it('the menu reopens a saved card ready for a new rating', async () => {
    renderPage({ config: ON, assigned: 'b' })
    fireEvent.click(screen.getByText('rate'))
    fireEvent.click(screen.getByRole('radio', { name: '2 of 5' }))
    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: 'Send' }))
    })
    fireEvent.click(screen.getByText('rate'))
    expect(screen.getByRole('radio', { name: '2 of 5' })).toHaveAttribute('aria-checked', 'true')
    expect(screen.getByRole('button', { name: 'Send' })).toBeInTheDocument()
  })

  it('a card left open in one look does not hold back the next look’s', () => {
    renderPage({ config: ON, assigned: 'b' })
    act(() => vi.advanceTimersByTime(VOTE_AFTER_MS + 5_000))
    expect(screen.getByRole('region', { name: /rate this look/i })).toHaveTextContent('B · Calm')
    fireEvent.click(screen.getByText('switch to c'))
    expect(screen.queryByRole('region', { name: /rate this look/i })).toBeNull()
    act(() => vi.advanceTimersByTime(VOTE_AFTER_MS + 5_000))
    expect(screen.getByRole('region', { name: /rate this look/i })).toHaveTextContent('C · Bold')
  })

  it('never offers itself for a look already rated', () => {
    updateExperimentState(s => ({ ...s, voted: { b: 4 } }))
    renderPage({ config: ON, assigned: 'b' })
    act(() => vi.advanceTimersByTime(VOTE_AFTER_MS * 2))
    expect(screen.queryByRole('region', { name: /rate this look/i })).toBeNull()
  })

  it('saves the rating and remembers it', async () => {
    renderPage({ config: ON, assigned: 'b' })
    fireEvent.click(screen.getByText('rate'))
    fireEvent.click(screen.getByRole('radio', { name: '4 of 5' }))
    fireEvent.change(screen.getByRole('textbox'), { target: { value: 'calm is nice' } })
    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: 'Send' }))
    })
    expect(sendVote).toHaveBeenCalledWith(readExperimentState().anonId, 'b', 4, 'calm is nice')
    expect(readExperimentState().voted).toEqual({ b: 4 })
    expect(screen.getByRole('region', { name: /rate this look/i })).toHaveTextContent(/thanks/i)
  })
})

describe('the hint', () => {
  function assignedEntry(look: LookId) {
    updateExperimentState(s => ({ ...s, entry: { look, kind: 'assigned' }, assigned: look }))
  }

  it('tells an assigned browser where to switch, until closed', () => {
    assignedEntry('b')
    const { unmount } = renderPage({ config: ON, assigned: 'b' })
    expect(screen.getByRole('status')).toHaveTextContent('B · Calm')
    expect(screen.getByRole('status')).toHaveTextContent('⋯ → Look')
    fireEvent.click(screen.getByRole('button', { name: /close the hint/i }))
    expect(screen.queryByRole('status')).toBeNull()
    unmount()
    renderPage({ config: ON, assigned: 'b' })
    expect(screen.queryByRole('status')).toBeNull()
  })

  it('goes once the person switches', () => {
    assignedEntry('b')
    renderPage({ config: ON, assigned: 'b' })
    fireEvent.click(screen.getByText('switch to c'))
    expect(screen.queryByRole('status')).toBeNull()
    expect(readExperimentState().hintDismissed).toBe(true)
  })

  it('is not shown to a browser that chose its look', () => {
    updateExperimentState(s => ({ ...s, entry: { look: 'b', kind: 'chose' } }))
    renderPage({ config: ON })
    expect(screen.queryByRole('status')).toBeNull()
  })
})

