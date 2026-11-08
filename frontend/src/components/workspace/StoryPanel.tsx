import { useEffect, useMemo, useRef, useState } from 'react'
import { Button, Radio, RadioGroup, Switch } from '@heroui/react'
import { FiBookOpen, FiClock, FiEye, FiInfo } from 'react-icons/fi'
import type { AnchorEvent, StoryChapter } from '@/content/gallery/story-anchors'
import { momentFacts, resolveAnchor, type AnchorStatus, type HeldEvents, type MomentFacts, type StateCheck } from '@/lib/story'
import { seconds } from '@/lib/replay-seek'
import { stateLabel } from '@/lib/coroutine-state-colors'

/**
 * Story mode (spec S-9, "Story anchors"; plan P-7 to P-12): a demo's guide as chapters in step
 * order. A chapter waits for its moment in the run; Reveal moves the page there ([onReveal]) and
 * shows what the tree held at that moment. Later chapters stay hidden until the one before is
 * revealed (or skipped, when its moment cannot be shown). Moving on by itself is off by default.
 *
 * The guide's text explains; the "Recorded at" lines are what vizcore recorded. Each is marked as
 * what it is (S-6).
 */
export function StoryPanel({
  chapters,
  held,
  onReveal,
  revealBlocked = null,
}: {
  chapters: StoryChapter[]
  held: HeldEvents
  /** Moves the page's one moment to the anchor event and selects its coroutine (S-2, S-11). */
  onReveal: (seq: number, coroutineId: string) => void
  /** Why Reveal cannot move the page now (a recording runs), or null. */
  revealBlocked?: string | null
}) {
  // What each passed chapter left: its facts when revealed, null when skipped or read (no moment).
  const [done, setDone] = useState<Array<Outcome>>([])
  const [answers, setAnswers] = useState<Record<number, number>>({})
  const [auto, setAuto] = useState(false)
  // Where keyboard focus goes after the reader's own Reveal, Next or Skip removed the button it was on.
  const focusAfter = useRef<{ index: number; on: 'recorded' | 'heading' } | null>(null)
  const panel = useRef<HTMLElement>(null)
  useEffect(() => {
    const target = focusAfter.current
    if (!target) return
    focusAfter.current = null
    const id = target.on === 'recorded' ? `story-recorded-${target.index}` : `story-chapter-${target.index}`
    panel.current?.querySelector<HTMLElement>(`#${id}`)?.focus()
  }, [done])

  const statuses = useMemo(
    () => chapters.map(c => (c.anchor ? resolveAnchor(c.anchor, held) : null)),
    [chapters, held],
  )
  const current = done.length < chapters.length ? done.length : null

  const factsFor = (chapter: StoryChapter, status: AnchorStatus | null): Outcome => {
    if (!status || status.kind !== 'ready') return { kind: status ? 'skipped' : 'read' }
    const index = held.events.findIndex(e => e.seq === status.seq)
    const facts = momentFacts(held.events, index, chapter.expect)
    // A coroutine the session has but whose creation was trimmed: its state here is not known, not absent.
    const checks = facts.checks.map(c => ({ ...c, trimmed: c.actual === null && held.trimmed && held.sessionLabels.has(c.label) }))
    return { kind: 'revealed', seq: status.seq, coroutineId: status.coroutineId, facts: { ...facts, checks } }
  }

  // Chapter [index] passes once: a second call for it (a repeated effect, a double click) changes nothing.
  const pass = (index: number, outcome: Outcome) => setDone(prev => (prev.length === index ? [...prev, outcome] : prev))

  const reveal = (chapter: StoryChapter) => {
    const status = statuses[chapter.index] ?? null
    const outcome = factsFor(chapter, status)
    focusAfter.current =
      outcome.kind === 'revealed' ? { index: chapter.index, on: 'recorded' } : { index: chapter.index + 1, on: 'heading' }
    pass(chapter.index, outcome)
    if (outcome.kind === 'revealed') onReveal(outcome.seq, outcome.coroutineId)
  }

  // Moving on by itself (P-11): the current chapter is shown once the run has reached its moment,
  // without moving the page; a chapter whose question is unanswered waits for its answer.
  useEffect(() => {
    if (!auto || current === null) return
    const chapter = chapters[current]!
    const status = statuses[current] ?? null
    const answered = !chapter.prediction || answers[current] !== undefined
    if (answered && (status === null || status.kind === 'ready')) pass(current, factsFor(chapter, status))
  }, [auto, current, statuses, answers, chapters])

  const hidden = current === null ? 0 : chapters.length - current - 1

  return (
    <section ref={panel} data-look-part="story" aria-labelledby="story-heading" className="space-y-3">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <h3 id="story-heading" className="flex items-center gap-2 text-sm font-semibold text-default-700">
          <FiBookOpen className="h-4 w-4 text-primary" aria-hidden />
          Story · {chapters.length} chapters
        </h3>
        <Switch size="sm" isSelected={auto} onValueChange={setAuto}>
          <span className="text-xs text-default-600">Show chapters as the run reaches them</span>
        </Switch>
      </div>

      {chapters.slice(0, (current ?? chapters.length - 1) + 1).map(chapter => (
        <Chapter
          key={chapter.index}
          chapter={chapter}
          total={chapters.length}
          status={statuses[chapter.index] ?? null}
          outcome={done[chapter.index]}
          answer={answers[chapter.index]}
          onAnswer={answer => setAnswers(prev => ({ ...prev, [chapter.index]: answer }))}
          revealBlocked={revealBlocked}
          onReveal={() => reveal(chapter)}
          onSkip={() => {
            focusAfter.current = { index: chapter.index + 1, on: 'heading' }
            pass(chapter.index, { kind: 'skipped' })
          }}
          canShowAgain={seq => held.events.some(e => e.seq === seq)}
          onShowAgain={(seq, id) => onReveal(seq, id)}
        />
      ))}

      {hidden > 0 && (
        <p className="text-xs text-default-500">{hidden === 1 ? '1 more chapter' : `${hidden} more chapters`}</p>
      )}
    </section>
  )
}

/** What was recorded at a revealed chapter's moment; a check marks a state the trimming left unknown. */
type RevealedFacts = Pick<MomentFacts, 'atNanos'> & { checks: Array<StateCheck & { trimmed: boolean }> }

type Outcome =
  | { kind: 'revealed'; seq: number; coroutineId: string; facts: RevealedFacts }
  /** Its moment could not be shown and the reader went on. */
  | { kind: 'skipped' }
  /** A chapter without a moment, read. */
  | { kind: 'read' }

const UNAVAILABLE: Partial<Record<AnchorStatus['kind'], string>> = {
  'not-held': "This page's events stop before this moment. Resume the view to follow the run.",
  missed: 'This moment did not happen in this run.',
  trimmed: "This moment's events were trimmed, so it can't be shown.",
  incomplete:
    "Some of this run's events did not reach this page, so this moment can't be found. Reload the page to read the full history.",
}

const VERB: Record<AnchorEvent, string> = {
  CoroutineCreated: 'was created',
  CoroutineStarted: 'started',
  CoroutineSuspended: 'suspended',
  CoroutineResumed: 'resumed',
  CoroutineBodyCompleted: 'finished its own code',
  CoroutineCompleted: 'completed',
  CoroutineCancelled: 'was cancelled',
  CoroutineFailed: 'failed',
}

const ordinal = (n: number) => (n === 2 ? '2nd' : n === 3 ? '3rd' : `${n}th`)

function Chapter({
  chapter,
  total,
  status,
  outcome,
  answer,
  onAnswer,
  revealBlocked,
  onReveal,
  onSkip,
  canShowAgain,
  onShowAgain,
}: {
  chapter: StoryChapter
  total: number
  status: AnchorStatus | null
  outcome: Outcome | undefined
  answer: number | undefined
  onAnswer: (answer: number) => void
  revealBlocked: string | null
  onReveal: () => void
  onSkip: () => void
  canShowAgain: (seq: number) => boolean
  onShowAgain: (seq: number, coroutineId: string) => void
}) {
  const headingId = `story-chapter-${chapter.index}`
  const statusId = `story-status-${chapter.index}`
  const passed = outcome !== undefined
  const unavailable = status ? UNAVAILABLE[status.kind] : undefined
  const { prediction, anchor } = chapter

  return (
    <article
      data-look-part="story-chapter"
      aria-labelledby={headingId}
      className="space-y-2 rounded-lg border border-default-200 p-3"
    >
      <h4 id={headingId} tabIndex={-1} className="text-sm font-semibold text-default-800 outline-none">
        <span className="mr-2 text-xs font-normal text-default-500">
          Chapter {chapter.index + 1} of {total}
        </span>
        {chapter.title}
      </h4>

      <div data-look-part="story-explanation" className="border-l-2 border-primary/40 pl-3">
        <p className="mb-1 flex items-center gap-1 text-[11px] uppercase tracking-wide text-default-500">
          <FiInfo className="h-3 w-3" aria-hidden />
          From the guide · explanation
        </p>
        <p className="text-sm text-default-700">{chapter.text}</p>
      </div>

      {prediction && (
        <div data-look-part="story-prediction" className="space-y-1">
          <RadioGroup
            size="sm"
            label={prediction.question}
            value={answer === undefined ? '' : String(answer)}
            onValueChange={value => onAnswer(Number(value))}
            isDisabled={passed}
            classNames={{ label: 'text-sm text-default-700' }}
          >
            {prediction.answers.map((text, i) => (
              <Radio key={i} value={String(i)}>
                <span className="text-sm">{text}</span>
              </Radio>
            ))}
          </RadioGroup>
          {passed && (
            <p className="text-sm font-medium" role="status">
              {answer === undefined
                ? `The answer: ${prediction.answers[prediction.correct]}`
                : answer === prediction.correct
                  ? `Right: ${prediction.answers[prediction.correct]}`
                  : `Not quite. The answer: ${prediction.answers[prediction.correct]}`}
            </p>
          )}
        </div>
      )}

      {outcome?.kind === 'revealed' && anchor && (
        <div
          id={`story-recorded-${chapter.index}`}
          tabIndex={-1}
          data-look-part="story-recorded"
          data-testid="story-recorded"
          className="space-y-1 rounded-md bg-default-100 p-2 text-xs">
          <p className="font-medium text-default-700">
            Recorded at {seconds(outcome.facts.atNanos)} s: {anchor.coroutine} {VERB[anchor.event]}
            {anchor.occurrence > 1 ? ` (${ordinal(anchor.occurrence)} time)` : ''}
          </p>
          {outcome.facts.checks.length > 0 && (
            <ul className="space-y-0.5 text-default-600">
              {outcome.facts.checks.map(check => (
                <li key={check.label} className="flex items-center gap-1">
                  {check.actual === check.expected ? (
                    <>
                      {check.label} · {stateLabel(check.expected)}{' '}
                      <span aria-hidden className="text-success">
                        ✓
                      </span>
                      <span className="sr-only">, as the guide says</span>
                    </>
                  ) : check.trimmed ? (
                    <span className="text-default-500">
                      {check.label} · not known at this moment: its early events were trimmed
                    </span>
                  ) : (
                    <span className="text-warning-600">
                      {check.label} · expected {stateLabel(check.expected)} · the tree shows{' '}
                      {check.actual ? stateLabel(check.actual) : 'no such coroutine'}
                    </span>
                  )}
                </li>
              ))}
            </ul>
          )}
          <Button
            size="sm"
            variant="light"
            className="h-6 min-w-0 px-1 text-xs"
            startContent={<FiEye aria-hidden />}
            isDisabled={!canShowAgain(outcome.seq) || !!revealBlocked}
            onPress={() => onShowAgain(outcome.seq, outcome.coroutineId)}
          >
            Show this moment again
          </Button>
        </div>
      )}
      {outcome?.kind === 'skipped' && <p className="text-xs text-default-500">Skipped: this moment could not be shown.</p>}

      {!passed && (
        <div className="flex flex-wrap items-center gap-2">
          {anchor ? (
            <Button
              data-look-part="story-reveal"
              size="sm"
              color="primary"
              variant="flat"
              aria-describedby={statusId}
              isDisabled={status?.kind !== 'ready' || !!revealBlocked}
              onPress={onReveal}
            >
              Reveal
            </Button>
          ) : (
            <Button data-look-part="story-reveal" size="sm" color="primary" variant="flat" onPress={onReveal}>
              Next
            </Button>
          )}
          <span id={statusId} className="flex items-center gap-1 text-xs text-default-500">
            {revealBlocked && status?.kind === 'ready' ? (
              revealBlocked
            ) : status?.kind === 'waiting' ? (
              <>
                <FiClock className="h-3 w-3" aria-hidden />
                Waiting for this moment…
              </>
            ) : (
              unavailable
            )}
          </span>
          {unavailable && (
            <Button size="sm" variant="light" className="h-6 min-w-0 px-1 text-xs" onPress={onSkip}>
              Skip this chapter
            </Button>
          )}
        </div>
      )}
    </article>
  )
}
