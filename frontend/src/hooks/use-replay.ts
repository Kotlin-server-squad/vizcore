/**
 * Replay hook for stepping through session events
 *
 * Manages playback state for a sorted list of VizEvents,
 * supporting play/pause, step-through, speed control, and seeking.
 */

import { useState, useCallback, useRef, useMemo, useEffect } from 'react'
import type { VizEvent } from '@/types/api'

export interface ReplayState {
  /** Whether playback is currently running */
  isPlaying: boolean
  /** Index of the current event (0-based) */
  currentIndex: number
  /** The event at the current index, or null if events are empty */
  currentEvent: VizEvent | null
  /** Playback speed multiplier (e.g. 0.5, 1, 2, 5) */
  speed: number
  /** Progress through the event list as a fraction from 0 to 1 */
  progress: number
  /** Events from index 0 up to and including currentIndex */
  visibleEvents: VizEvent[]
  /** Total number of events */
  totalEvents: number
}

export interface ReplayControls {
  /** Start auto-advancing through events */
  play: () => void
  /** Pause auto-advance */
  pause: () => void
  /** Move to the next event (stops playback) */
  stepForward: () => void
  /** Move to the previous event (stops playback) */
  stepBack: () => void
  /** Reset to the beginning (stops playback) */
  reset: () => void
  /** Jump to a specific event index (stops playback) */
  seekTo: (index: number) => void
  /** Change the playback speed multiplier */
  setSpeed: (multiplier: number) => void
}

export type UseReplayReturn = ReplayState & ReplayControls

/**
 * Hook that manages replay state for a session's events.
 *
 * @param events - VizEvents sorted by seq (ascending)
 * @returns Replay state and control functions
 */
export function useReplay(events: VizEvent[]): UseReplayReturn {
  const [currentIndex, setCurrentIndex] = useState(0)
  const [isPlaying, setIsPlaying] = useState(false)
  const [speed, setSpeedState] = useState(1)

  const timerRef = useRef<ReturnType<typeof setTimeout> | null>(null)
  const eventsRef = useRef(events)
  eventsRef.current = events

  const speedRef = useRef(speed)
  speedRef.current = speed

  const currentIndexRef = useRef(currentIndex)
  currentIndexRef.current = currentIndex

  // Clean up timer on unmount
  useEffect(() => {
    return () => {
      if (timerRef.current !== null) {
        clearTimeout(timerRef.current)
        timerRef.current = null
      }
    }
  }, [])

  // Schedule the next auto-advance tick
  const scheduleNext = useCallback(() => {
    const evts = eventsRef.current
    const idx = currentIndexRef.current

    if (idx >= evts.length - 1) {
      setIsPlaying(false)
      return
    }

    const current = evts[idx]
    const next = evts[idx + 1]

    if (!current || !next) {
      setIsPlaying(false)
      return
    }

    // ADR-017: clamp the BASE inter-event gap to [50, 2000] ms first, THEN
    // divide by the speed multiplier. Clamping the base (not the post-speed
    // delay) keeps playback within the readable 50ms–2s window regardless of
    // raw timing, while still letting speed scale within that window.
    const baseMs = Math.min(Math.max((next.tsNanos - current.tsNanos) / 1_000_000, 50), 2000)
    const delayMs = baseMs / speedRef.current

    timerRef.current = setTimeout(() => {
      timerRef.current = null
      setCurrentIndex(prev => {
        const nextIdx = prev + 1
        if (nextIdx >= eventsRef.current.length) {
          setIsPlaying(false)
          return prev
        }
        return nextIdx
      })
    }, delayMs)
  }, [])

  // When currentIndex changes while playing, schedule the next tick
  useEffect(() => {
    if (isPlaying) {
      scheduleNext()
    }
    return () => {
      if (timerRef.current !== null) {
        clearTimeout(timerRef.current)
        timerRef.current = null
      }
    }
  }, [isPlaying, currentIndex, scheduleNext])

  // Reset index when events array changes identity (new session)
  useEffect(() => {
    setCurrentIndex(0)
    setIsPlaying(false)
  }, [events])

  const play = useCallback(() => {
    if (eventsRef.current.length === 0) return
    // If at the end, restart from beginning
    if (currentIndexRef.current >= eventsRef.current.length - 1) {
      setCurrentIndex(0)
    }
    setIsPlaying(true)
  }, [])

  const pause = useCallback(() => {
    setIsPlaying(false)
  }, [])

  const stepForward = useCallback(() => {
    setIsPlaying(false)
    setCurrentIndex(prev => Math.min(prev + 1, eventsRef.current.length - 1))
  }, [])

  const stepBack = useCallback(() => {
    setIsPlaying(false)
    setCurrentIndex(prev => Math.max(prev - 1, 0))
  }, [])

  const reset = useCallback(() => {
    setIsPlaying(false)
    setCurrentIndex(0)
  }, [])

  const seekTo = useCallback((index: number) => {
    setIsPlaying(false)
    const clamped = Math.max(0, Math.min(index, eventsRef.current.length - 1))
    setCurrentIndex(clamped)
  }, [])

  const setSpeed = useCallback((multiplier: number) => {
    setSpeedState(multiplier)
  }, [])

  const currentEvent = events.length > 0 ? (events[currentIndex] ?? null) : null

  const progress = useMemo(() => {
    if (events.length <= 1) return 0
    return currentIndex / (events.length - 1)
  }, [currentIndex, events.length])

  const visibleEvents = useMemo(() => {
    return events.slice(0, currentIndex + 1)
  }, [events, currentIndex])

  return {
    isPlaying,
    currentIndex,
    currentEvent,
    speed,
    progress,
    visibleEvents,
    totalEvents: events.length,
    play,
    pause,
    stepForward,
    stepBack,
    reset,
    seekTo,
    setSpeed,
  }
}
