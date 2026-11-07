import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import type { VizEvent } from '@/types/api'
import type { StoryChapter } from '@/content/gallery/story-anchors'
import type { HeldEvents } from '@/lib/story'
import { StoryPanel } from './StoryPanel'

let seq = 0
const ev = (kind: string, id: string, ms: number, extra: Record<string, unknown> = {}) =>
  ({ seq: ++seq, tsNanos: ms * 1e6, sessionId: 's', kind, coroutineId: id, jobId: `j-${id}`, parentCoroutineId: null, scopeId: 'sc', label: id, ...extra }) as unknown as VizEvent

const chapters: StoryChapter[] = [
  {
    index: 0,
    title: 'The product comes first',
    text: 'load-product-page starts fetch-product and waits for it.',
    anchor: { event: 'CoroutineSuspended', coroutine: 'fetch-product', occurrence: 1 },
    expect: { 'load-product-page': 'ACTIVE', 'fetch-product': 'SUSPENDED' },
    prediction: { question: 'Have the sections started yet?', answers: ['Yes', 'No: join() waits'], correct: 1 },
  },
  {
    index: 1,
    title: 'Three sections start together',
    text: 'The price box, the reviews and the recommendations start at once.',
    anchor: { event: 'CoroutineCompleted', coroutine: 'fetch-product', occurrence: 1 },
    expect: { 'fetch-product': 'COMPLETED' },
  },
  { index: 2, title: 'A chapter without a moment', text: 'Just words.' },
  {
    index: 3,
    title: 'The page waits for everything',
    text: 'It completes last.',
    anchor: { event: 'CoroutineCompleted', coroutine: 'load-product-page', occurrence: 1 },
  },
]

let page: VizEvent
let product: VizEvent
let suspended: VizEvent
let completed: VizEvent
beforeEach(() => {
  seq = 0
  page = ev('coroutine.created', 'load-product-page', 0)
  product = ev('coroutine.created', 'fetch-product', 0, { parentCoroutineId: 'load-product-page' })
  suspended = ev('coroutine.suspended', 'fetch-product', 1500)
  completed = ev('coroutine.completed', 'fetch-product', 2500)
})

const started = (id: string, ms: number) => ev('coroutine.started', id, ms)

const held = (events: VizEvent[], over: Partial<HeldEvents> = {}): HeldEvents => ({
  events,
  streaming: true,
  shedCount: 0,
  trimmed: false,
  sessionLabels: new Set(),
  ...over,
})

function setup(events: VizEvent[], over: Partial<HeldEvents> = {}, props: Partial<Parameters<typeof StoryPanel>[0]> = {}) {
  const onReveal = vi.fn()
  const utils = render(<StoryPanel chapters={chapters} held={held(events, over)} onReveal={onReveal} {...props} />)
  return { onReveal, ...utils }
}

const chapter = (title: string) => screen.getByRole('article', { name: new RegExp(title) })

describe('StoryPanel (S-9, "Story anchors")', () => {
  it('shows the first chapter, marked as explanation, and hides the later ones', () => {
    setup([page])
    expect(screen.getByRole('article', { name: /The product comes first/ })).toBeInTheDocument()
    expect(screen.queryByRole('article', { name: /Three sections/ })).not.toBeInTheDocument()
    expect(screen.getByText('3 more chapters')).toBeInTheDocument()
    expect(within(chapter('The product comes first')).getByText('From the guide · explanation')).toBeInTheDocument()
  })

  it('waits for its moment live, with Reveal disabled', () => {
    setup([page, started('load-product-page', 0)])
    expect(within(chapter('The product comes first')).getByText('Waiting for this moment…')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Reveal' })).toBeDisabled()
  })

  it('reveals: moves the page to the anchor and shows what the tree held there', async () => {
    const events = [page, started('load-product-page', 0), product, started('fetch-product', 0), suspended]
    const { onReveal } = setup(events)
    await userEvent.click(screen.getByRole('button', { name: 'Reveal' }))
    expect(onReveal).toHaveBeenCalledWith(suspended.seq, 'fetch-product')
    const recorded = within(chapter('The product comes first')).getByTestId('story-recorded')
    expect(recorded).toHaveTextContent('Recorded at 1.5 s: fetch-product suspended')
    expect(recorded).toHaveTextContent('load-product-page · Active ✓')
    expect(recorded).toHaveTextContent('fetch-product · Suspended ✓')
    // The next chapter is shown now.
    expect(chapter('Three sections start together')).toBeInTheDocument()
    expect(screen.getByText('2 more chapters')).toBeInTheDocument()
  })

  it('asks the prediction first and shows the answer on Reveal', async () => {
    setup([page, started('load-product-page', 0), product, started('fetch-product', 0), suspended])
    const question = screen.getByRole('radiogroup', { name: 'Have the sections started yet?' })
    await userEvent.click(within(question).getByRole('radio', { name: 'Yes' }))
    await userEvent.click(screen.getByRole('button', { name: 'Reveal' }))
    expect(within(chapter('The product comes first')).getByText('Not quite. The answer: No: join() waits')).toBeInTheDocument()
  })

  it('says Right for the right answer', async () => {
    setup([page, started('load-product-page', 0), product, started('fetch-product', 0), suspended])
    await userEvent.click(screen.getByRole('radio', { name: 'No: join() waits' }))
    await userEvent.click(screen.getByRole('button', { name: 'Reveal' }))
    expect(within(chapter('The product comes first')).getByText('Right: No: join() waits')).toBeInTheDocument()
  })

  it('says honestly when the tree at the moment differs from the guide', async () => {
    // load-product-page never started: the tree shows it Created, not Active.
    const events = [page, product, started('fetch-product', 0), suspended]
    setup(events)
    await userEvent.click(screen.getByRole('button', { name: 'Reveal' }))
    expect(within(chapter('The product comes first')).getByTestId('story-recorded')).toHaveTextContent(
      'load-product-page · expected Active · the tree shows Created',
    )
  })

  it('reads Next for a chapter without a moment, and moves nothing', async () => {
    const events = [page, started('load-product-page', 0), product, started('fetch-product', 0), suspended, completed]
    const { onReveal } = setup(events)
    await userEvent.click(screen.getByRole('button', { name: 'Reveal' }))
    await userEvent.click(within(chapter('Three sections start together')).getByRole('button', { name: 'Reveal' }))
    expect(onReveal).toHaveBeenCalledTimes(2)
    await userEvent.click(within(chapter('A chapter without a moment')).getByRole('button', { name: 'Next' }))
    expect(onReveal).toHaveBeenCalledTimes(2)
    expect(chapter('The page waits for everything')).toBeInTheDocument()
  })

  it('says a trimmed moment cannot be shown, and lets you skip it', async () => {
    setup([suspended], { trimmed: true, sessionLabels: new Set(['fetch-product', 'load-product-page']) })
    expect(within(chapter('The product comes first')).getByText("This moment's events were trimmed, so it can't be shown.")).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Reveal' })).toBeDisabled()
    await userEvent.click(screen.getByRole('button', { name: 'Skip this chapter' }))
    expect(chapter('Three sections start together')).toBeInTheDocument()
  })

  it('says a moment did not happen once the run has ended without it', () => {
    setup([page, ev('coroutine.cancelled', 'load-product-page', 1)])
    expect(screen.getByText('This moment did not happen in this run.')).toBeInTheDocument()
  })

  it('says the paused page holds no events that far', () => {
    setup([page], { streaming: false })
    expect(screen.getByText("This page's events stop before this moment. Resume the view to follow the run.")).toBeInTheDocument()
  })

  it('says when shed events make a suspension uncountable', () => {
    setup([page, product, suspended], { shedCount: 2 })
    expect(screen.getByText(/Some of this run's events did not reach this page/)).toBeInTheDocument()
  })

  it('keeps Reveal off while a recording runs', () => {
    setup([page, started('load-product-page', 0), product, started('fetch-product', 0), suspended], {}, { revealBlocked: 'Recording…' })
    expect(screen.getByRole('button', { name: 'Reveal' })).toBeDisabled()
    expect(screen.getByText('Recording…')).toBeInTheDocument()
  })

  it('does not move on by itself unless asked (S-9)', () => {
    setup([page, started('load-product-page', 0), product, started('fetch-product', 0), suspended, completed])
    expect(screen.getByRole('switch', { name: 'Show chapters as the run reaches them' })).not.toBeChecked()
    expect(screen.queryByTestId('story-recorded')).not.toBeInTheDocument()
  })

  it('when asked, shows each chapter as the run reaches it, without moving the page, and waits at a question', async () => {
    const events = [page, started('load-product-page', 0), product, started('fetch-product', 0), suspended, completed]
    const { onReveal, rerender } = setup(events)
    await userEvent.click(screen.getByRole('switch', { name: 'Show chapters as the run reaches them' }))
    // Chapter 1 has an unanswered question: it waits.
    expect(screen.queryByTestId('story-recorded')).not.toBeInTheDocument()
    await userEvent.click(screen.getByRole('radio', { name: 'Yes' }))
    rerender(<StoryPanel chapters={chapters} held={held([...events])} onReveal={onReveal} />)
    // Chapters 1 and 2 have arrived and are shown; chapter 3 has no moment and follows; chapter 4 waits.
    expect(within(chapter('The product comes first')).getByTestId('story-recorded')).toBeInTheDocument()
    expect(within(chapter('Three sections start together')).getByTestId('story-recorded')).toBeInTheDocument()
    expect(within(chapter('The page waits for everything')).getByText('Waiting for this moment…')).toBeInTheDocument()
    expect(onReveal).not.toHaveBeenCalled()
  })

  it('shows a revealed moment again', async () => {
    const events = [page, started('load-product-page', 0), product, started('fetch-product', 0), suspended]
    const { onReveal } = setup(events)
    await userEvent.click(screen.getByRole('button', { name: 'Reveal' }))
    await userEvent.click(within(chapter('The product comes first')).getByRole('button', { name: 'Show this moment again' }))
    expect(onReveal).toHaveBeenCalledTimes(2)
    expect(onReveal).toHaveBeenLastCalledWith(suspended.seq, 'fetch-product')
  })

  it('carries the look hooks on its parts', async () => {
    const { container } = setup([page, started('load-product-page', 0), product, started('fetch-product', 0), suspended])
    for (const part of ['story', 'story-chapter', 'story-explanation', 'story-prediction', 'story-reveal']) {
      expect(container.querySelector(`[data-look-part='${part}']`), part).not.toBeNull()
    }
    await userEvent.click(screen.getByRole('button', { name: 'Reveal' }))
    expect(container.querySelector("[data-look-part='story-recorded']")).not.toBeNull()
  })

  it('keeps keyboard focus: on what was recorded after Reveal, on the next chapter after Next or Skip', async () => {
    const events = [page, started('load-product-page', 0), product, started('fetch-product', 0), suspended, completed]
    setup(events)
    await userEvent.click(screen.getByRole('button', { name: 'Reveal' }))
    expect(document.activeElement).toBe(within(chapter('The product comes first')).getByTestId('story-recorded'))
    await userEvent.click(within(chapter('Three sections start together')).getByRole('button', { name: 'Reveal' }))
    await userEvent.click(within(chapter('A chapter without a moment')).getByRole('button', { name: 'Next' }))
    expect(document.activeElement).toBe(within(chapter('The page waits for everything')).getByRole('heading'))
  })

  it('says a state is not known when that coroutine\'s early events were trimmed, never that it is absent', async () => {
    // The anchor is held; load-product-page's creation was trimmed away.
    const events = [product, started('fetch-product', 0), suspended]
    setup(events, { trimmed: true, sessionLabels: new Set(['fetch-product', 'load-product-page']) })
    await userEvent.click(screen.getByRole('button', { name: 'Reveal' }))
    const recorded = within(chapter('The product comes first')).getByTestId('story-recorded')
    expect(recorded).toHaveTextContent('load-product-page · not known at this moment: its early events were trimmed')
    expect(recorded).not.toHaveTextContent('no such coroutine')
  })
})
