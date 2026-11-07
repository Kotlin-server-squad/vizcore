import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState } from 'react'
import type { ReactNode } from 'react'
import { storedLookChoice, useLook } from '../look-context'
import { LOOKS, type LookId } from '../registry'
import { sendVote, type ExperimentConfig } from './api'
import {
  FLUSH_EVERY_MS,
  LATER_VISIT_DELAY_MS,
  VOTE_AFTER_MS,
  VOTE_CHECK_EVERY_MS,
} from './constants'
import { LookHint } from './LookHint'
import { LookVoteCard } from './LookVoteCard'
import { enqueueEvent, flushEvents } from './queue'
import { readExperimentState, updateExperimentState } from './storage'

interface LookExperimentApi {
  /** Opens the vote card for the current look (⋯ → Rate this look). */
  openVote: () => void
  /** The person changed the look in the menu. */
  switched: (from: LookId, to: LookId) => void
}

const LookExperimentContext = createContext<LookExperimentApi | null>(null)

/** The running experiment's actions; null while it is off or outside the session page. */
export function useLookExperiment(): LookExperimentApi | null {
  return useContext(LookExperimentContext)
}

/** Looks counted as visited in this page load (a later page load is "a later visit"). */
let visitedThisLoad = new Set<LookId>()

/** Test hook: a new page load. */
export function forgetLoadVisits() {
  visitedThisLoad = new Set()
}

const labelOf = (look: LookId) => LOOKS.find(l => l.id === look)?.label ?? look

interface LookExperimentLayerProps {
  /** Null until the server answered; off unless it says enabled. */
  config: ExperimentConfig | null
  /** The session page shown; only to count a new page as seen, never sent. */
  sessionId: string
  children: ReactNode
}

/**
 * The look experiment on the session page: counts the page as seen in its look,
 * measures its visible time, records a switch made in the menu, and shows the
 * hint and the vote card in a corner. While the experiment is off it renders
 * its children and nothing else, and measures nothing; the tree has the same
 * shape either way, so the page is never remounted when the config arrives.
 */
export function LookExperimentLayer({ config, sessionId, children }: LookExperimentLayerProps) {
  const enabled = config?.enabled === true
  const { look } = useLook()
  const lookRef = useRef(look)
  lookRef.current = look

  // The running stretch of visible time in the current look.
  const segment = useRef<{ look: LookId; since: number } | null>(null)
  // Visible time on this page in the current look, for "a later visit".
  const pageMs = useRef(0)

  const closeSegment = useCallback(() => {
    const open = segment.current
    if (!open) return
    segment.current = null
    const ms = Math.round(Date.now() - open.since)
    if (ms < 1) return
    if (open.look === lookRef.current) pageMs.current += ms
    const state = updateExperimentState(s => ({
      ...s,
      visibleMs: { ...s.visibleMs, [open.look]: (s.visibleMs[open.look] ?? 0) + ms },
    }))
    enqueueEvent(state.anonId, { type: 'time', look: open.look, ms })
  }, [])

  const openSegment = useCallback(() => {
    if (document.visibilityState === 'visible')
      segment.current = { look: lookRef.current, since: Date.now() }
  }, [])

  // Seen: each session page shown, and each look it is shown in.
  useEffect(() => {
    if (!enabled) return
    let state = readExperimentState()
    if (!visitedThisLoad.has(look)) {
      visitedThisLoad.add(look)
      state = updateExperimentState(s => ({
        ...s,
        visits: { ...s.visits, [look]: (s.visits[look] ?? 0) + 1 },
      }))
    }
    enqueueEvent(state.anonId, { type: 'seen', look })
  }, [enabled, look, sessionId])

  // Visible time, per look.
  useEffect(() => {
    if (!enabled) return
    pageMs.current = 0
    openSegment()
    return closeSegment
  }, [enabled, look, openSegment, closeSegment])

  // Sending: when the page is hidden or closed (a beacon), every 30 s, and on leaving the session page.
  useEffect(() => {
    if (!enabled) return
    const onVisibility = () => {
      if (document.visibilityState === 'hidden') {
        closeSegment()
        flushEvents({ leaving: true })
      } else if (!segment.current) {
        openSegment()
      }
    }
    const onPageHide = () => {
      closeSegment()
      flushEvents({ leaving: true })
    }
    const timer = window.setInterval(() => {
      if (segment.current) {
        closeSegment()
        openSegment()
      }
      flushEvents({ leaving: false })
    }, FLUSH_EVERY_MS)
    document.addEventListener('visibilitychange', onVisibility)
    window.addEventListener('pagehide', onPageHide)
    return () => {
      window.clearInterval(timer)
      document.removeEventListener('visibilitychange', onVisibility)
      window.removeEventListener('pagehide', onPageHide)
      closeSegment()
      flushEvents({ leaving: false })
    }
  }, [enabled, openSegment, closeSegment])

  // The vote card: for which look it is open (null: closed).
  const [voteFor, setVoteFor] = useState<LookId | null>(null)
  // Bumped by the menu, so a card that shows its thanks opens ready for a new rating.
  const [voteOpening, setVoteOpening] = useState(0)
  // A card belongs to its look: a change of look closes it, so it never holds back the next look's.
  useEffect(() => {
    setVoteFor(null)
  }, [look])
  useEffect(() => {
    if (!enabled) return
    const timer = window.setInterval(() => {
      const current = lookRef.current
      const state = readExperimentState()
      if (state.voted[current] !== undefined || state.dismissedVote[current]) return
      const running = segment.current?.look === current ? Date.now() - segment.current.since : 0
      const total = (state.visibleMs[current] ?? 0) + running
      const laterVisit =
        (state.visits[current] ?? 0) >= 2 && pageMs.current + running >= LATER_VISIT_DELAY_MS
      if (total >= VOTE_AFTER_MS || laterVisit) setVoteFor(open => open ?? current)
    }, VOTE_CHECK_EVERY_MS)
    return () => window.clearInterval(timer)
  }, [enabled])

  // The hint, for a browser the experiment gave its look, until closed or the person picks one.
  const [hint, setHint] = useState(false)
  useEffect(() => {
    if (!enabled) return
    const state = readExperimentState()
    setHint(state.entry?.kind === 'assigned' && !state.hintDismissed && storedLookChoice() === null)
  }, [enabled])
  const closeHint = useCallback(() => {
    setHint(false)
    updateExperimentState(s => ({ ...s, hintDismissed: true }))
  }, [])

  const api = useMemo<LookExperimentApi | null>(
    () =>
      enabled
        ? {
            openVote: () => {
              setVoteFor(lookRef.current)
              setVoteOpening(n => n + 1)
            },
            switched: (from, to) => {
              if (from === to) return
              enqueueEvent(readExperimentState().anonId, { type: 'switch', from, to })
              closeHint()
            },
          }
        : null,
    [enabled, closeHint]
  )

  const closeVote = useCallback((forLook: LookId) => {
    setVoteFor(null)
    if (readExperimentState().voted[forLook] === undefined) {
      updateExperimentState(s => ({ ...s, dismissedVote: { ...s.dismissedVote, [forLook]: true } }))
    }
  }, [])

  const submitVote = useCallback(async (forLook: LookId, rating: number, comment: string) => {
    const ok = await sendVote(readExperimentState().anonId, forLook, rating, comment)
    if (ok) updateExperimentState(s => ({ ...s, voted: { ...s.voted, [forLook]: rating } }))
    return ok
  }, [])

  const showVote = enabled && voteFor === look
  const showHint = enabled && hint
  return (
    <LookExperimentContext.Provider value={api}>
      {children}
      {(showVote || showHint) && (
        <div
          data-testid="look-experiment"
          className="pointer-events-none fixed bottom-4 right-4 z-50 flex w-80 max-w-[calc(100vw-2rem)] flex-col gap-2"
        >
          {showHint && <LookHint label={labelOf(look)} count={LOOKS.length} onClose={closeHint} />}
          {showVote && (
            <LookVoteCard
              key={`${look}-${voteOpening}`}
              look={look}
              label={labelOf(look)}
              previous={readExperimentState().voted[look]}
              surveyUrl={config?.surveyUrl ?? null}
              onSubmit={(rating, comment) => submitVote(look, rating, comment)}
              onClose={() => closeVote(look)}
            />
          )}
        </div>
      )}
    </LookExperimentContext.Provider>
  )
}
