import { describe, it, expect, vi } from 'vitest'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { RoundBar, type RoundBarProps } from './RoundBar'

const props = (over: Partial<RoundBarProps> = {}): RoundBarProps => ({
  showPicker: true,
  value: 'round:r2',
  options: [
    { value: 'round:r1', label: 'r1 · 2 coroutines · failed', problem: true },
    { value: 'round:r2', label: 'r2 · 6 coroutines · in progress', problem: false },
    { value: 'round:r3', label: 'r3 · 1 coroutine · finished', problem: false },
  ],
  allRounds: true,
  problemLabel: 'With a problem · as of 1 m 4 s',
  ungrouped: 0,
  unknownRound: 0,
  canLoadMore: false,
  olderRounds: 0,
  onChange: vi.fn(),
  onLoadMore: vi.fn(),
  onPickerFocus: vi.fn(),
  vizcoreCount: 0,
  includeVizcore: false,
  onToggleVizcore: vi.fn(),
  originUnknown: false,
  find: { results: null, onSubmit: vi.fn(), onChoose: vi.fn(), onOpenRound: vi.fn(), onClose: vi.fn() },
  ...over,
})

describe('RoundBar', () => {
  it('lists All rounds, then problem rounds, then the rest, newest first', () => {
    render(<RoundBar {...props()} />)
    const picker = screen.getByRole('combobox', { name: 'Round' })
    expect(picker).toHaveValue('round:r2')
    const groups = within(picker).getAllByRole('group')
    expect(groups.map(g => g.getAttribute('label'))).toEqual(['With a problem · as of 1 m 4 s', 'Newest first'])
    expect(within(groups[0]!).getAllByRole('option').map(o => o.textContent)).toEqual(['r1 · 2 coroutines · failed'])
    expect(within(picker).getAllByRole('option').map(o => o.textContent)).toEqual([
      'All rounds',
      'r1 · 2 coroutines · failed',
      'r2 · 6 coroutines · in progress',
      'r3 · 1 coroutine · finished',
    ])
  })

  it('offers Ungrouped only when some coroutines have no round, and more rounds only when the server has them', async () => {
    const onLoadMore = vi.fn()
    const onChange = vi.fn()
    const { rerender } = render(<RoundBar {...props()} />)
    expect(screen.queryByRole('option', { name: /Ungrouped/ })).toBeNull()
    expect(screen.queryByRole('option', { name: /more rounds/ })).toBeNull()

    rerender(<RoundBar {...props({ ungrouped: 4, canLoadMore: true, onLoadMore, onChange })} />)
    expect(screen.getByRole('option', { name: 'Ungrouped (4)' })).toBeInTheDocument()
    await userEvent.selectOptions(screen.getByRole('combobox', { name: 'Round' }), 'Show 50 more rounds…')
    expect(onLoadMore).toHaveBeenCalledOnce()
    expect(onChange).not.toHaveBeenCalled()
    expect(screen.getByRole('combobox', { name: 'Round' })).toHaveValue('round:r2')
  })

  it('offers the coroutines whose round is unknown as a view of their own', () => {
    render(<RoundBar {...props({ unknownRound: 1265004 })} />)
    expect(screen.getByRole('option', { name: 'Round unknown (1,265,004)' })).toHaveValue('unknown')
  })

  it('says how many older rounds only Find reaches', () => {
    render(<RoundBar {...props({ olderRounds: 1234 })} />)
    expect(screen.getByRole('option', { name: '1,234 older rounds are not listed: Find reaches them by name or creation site' })).toBeDisabled()
  })

  it('changes the view and reads the list again on focus', async () => {
    const onChange = vi.fn()
    const onPickerFocus = vi.fn()
    render(<RoundBar {...props({ onChange, onPickerFocus })} />)
    await userEvent.selectOptions(screen.getByRole('combobox', { name: 'Round' }), 'All rounds')
    expect(onPickerFocus).toHaveBeenCalled()
    expect(onChange).toHaveBeenCalledWith('all')
  })

  it('has no picker when there is nothing to pick', () => {
    render(<RoundBar {...props({ showPicker: false })} />)
    expect(screen.queryByRole('combobox', { name: 'Round' })).toBeNull()
  })

  it('shows the vizcore toggle only when vizcore coroutines were captured', async () => {
    const onToggleVizcore = vi.fn()
    const { rerender } = render(<RoundBar {...props()} />)
    expect(screen.queryByRole('checkbox', { name: /vizcore connection/ })).toBeNull()

    rerender(<RoundBar {...props({ vizcoreCount: 3, onToggleVizcore })} />)
    const toggle = screen.getByRole('checkbox', { name: 'vizcore connection (3)' })
    expect(toggle).not.toBeChecked()
    await userEvent.click(toggle)
    expect(onToggleVizcore).toHaveBeenCalledWith(true)
  })

  it('says when origins are not available', () => {
    render(<RoundBar {...props({ originUnknown: true })} />)
    expect(screen.getByText("origin not available: vizcore's own coroutines may be included")).toBeInTheDocument()
  })

  describe('find in all rounds', () => {
    it('submits on Enter and on the button, never per keystroke', async () => {
      const onSubmit = vi.fn()
      render(<RoundBar {...props({ find: { results: null, onSubmit, onChoose: vi.fn(), onOpenRound: vi.fn(), onClose: vi.fn() } })} />)
      const field = screen.getByRole('searchbox', { name: 'Find in all rounds' })
      await userEvent.type(field, 'fetch-2')
      expect(onSubmit).not.toHaveBeenCalled()
      await userEvent.keyboard('{Enter}')
      expect(onSubmit).toHaveBeenCalledWith('fetch-2')
      await userEvent.click(screen.getByRole('button', { name: 'Find' }))
      expect(onSubmit).toHaveBeenCalledTimes(2)
    })

    it('lists the matches by round, says what it leaves out, and chooses one', async () => {
      const onChoose = vi.fn()
      const onOpenRound = vi.fn()
      render(
        <RoundBar
          {...props({
            find: {
              results: {
                status: 'ready',
                summary: 'Found 302 (300 in 1 round, 2 ungrouped) · as of 1 m 4 s',
                groups: [
                  {
                    key: 'round:r3',
                    title: 'request-3 · at 12.0 s',
                    matches: [{ id: 'c7', name: 'fetch-240', state: 'Completed', selectable: true }],
                    partial: 'first 1 of 300 matches in request-3: refine the search (names, functions and files match) to reach the rest',
                  },
                  {
                    key: 'ungrouped',
                    title: 'Ungrouped',
                    matches: [{ id: 'c9', name: 'fetch-24', state: null, selectable: false }],
                    partial: null,
                  },
                ],
                moreRounds: '12 more rounds matched: refine the search',
                note: null,
                unsearched: '3 coroutines without a summary were not searched.',
              },
              onSubmit: vi.fn(),
              onChoose,
              onOpenRound,
              onClose: vi.fn(),
            },
          })}
        />,
      )
      const results = screen.getByRole('region', { name: 'Find results' })
      expect(within(results).getByText('Found 302 (300 in 1 round, 2 ungrouped) · as of 1 m 4 s')).toBeInTheDocument()
      const round = within(results).getByRole('group', { name: 'request-3 · at 12.0 s' })
      const first = within(round).getByRole('button', { name: 'fetch-240 Completed' })
      expect(within(round).getByText(/first 1 of 300 matches in request-3/)).toBeInTheDocument()
      const trimmed = within(results).getByRole('button', { name: /^fetch-24 not drawn/ })
      expect(trimmed).toBeDisabled()
      expect(trimmed).toHaveTextContent("not drawn: the page's snapshot does not hold it")
      expect(within(results).getByText('12 more rounds matched: refine the search')).toBeInTheDocument()
      expect(within(results).getByText('3 coroutines without a summary were not searched.')).toBeInTheDocument()
      await userEvent.click(within(round).getByRole('button', { name: 'Open this round' }))
      expect(onOpenRound).toHaveBeenCalledWith('round:r3')
      expect(within(results).getAllByRole('button', { name: 'Open this round' })).toHaveLength(1)
      await userEvent.click(first)
      expect(onChoose).toHaveBeenCalledWith('c7', 'round:r3')
    })

    it('adds the sampling sentence when nothing matched', () => {
      render(
        <RoundBar
          {...props({
            find: {
              results: { status: 'ready', summary: 'Nothing matched in the captured coroutines · as of 3.0 s', groups: [], moreRounds: null, note: 'vizcore samples the app on a poll.', unsearched: null },
              onSubmit: vi.fn(),
              onChoose: vi.fn(),
              onOpenRound: vi.fn(),
              onClose: vi.fn(),
            },
          })}
        />,
      )
      expect(screen.getByText('vizcore samples the app on a poll.')).toBeInTheDocument()
    })

    it('says when nothing matched, while it searches, and when the search failed', () => {
      const find = (status: 'ready' | 'pending' | 'error') => ({
        results: { status, summary: status === 'ready' ? 'Nothing matched · as of 3.0 s' : '', groups: [], moreRounds: null, note: null, unsearched: null },
        onSubmit: vi.fn(),
        onChoose: vi.fn(),
        onOpenRound: vi.fn(),
        onClose: vi.fn(),
      })
      const { rerender } = render(<RoundBar {...props({ find: find('ready') })} />)
      expect(screen.getByText('Nothing matched · as of 3.0 s')).toBeInTheDocument()
      rerender(<RoundBar {...props({ find: find('pending') })} />)
      expect(screen.getByText('Searching…')).toBeInTheDocument()
      rerender(<RoundBar {...props({ find: find('error') })} />)
      expect(screen.getByText('The search failed. Try again.')).toBeInTheDocument()
    })

    it('closes on Escape and on Close', async () => {
      const onClose = vi.fn()
      render(
        <RoundBar
          {...props({
            find: { results: { status: 'ready', summary: 'x', groups: [], moreRounds: null, note: null, unsearched: null }, onSubmit: vi.fn(), onChoose: vi.fn(), onOpenRound: vi.fn(), onClose },
          })}
        />,
      )
      await userEvent.click(screen.getByRole('searchbox', { name: 'Find in all rounds' }))
      await userEvent.keyboard('{Escape}')
      expect(onClose).toHaveBeenCalledTimes(1)
      await userEvent.click(screen.getByRole('button', { name: 'Close' }))
      expect(onClose).toHaveBeenCalledTimes(2)
    })
  })
})
