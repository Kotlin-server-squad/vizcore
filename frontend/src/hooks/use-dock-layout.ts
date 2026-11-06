import { useCallback, useEffect, useRef, useState } from 'react'
import {
  clampDockHeight,
  dockMaxHeight,
  initialDockHeight,
  readDockPrefs,
  writeDockPrefs,
} from '@/lib/dock-layout'

export interface DockLayout {
  /** The dock's height in px, already clamped to the space measured. */
  height: number
  /** The largest height the space allows; Infinity until measured. */
  maxHeight: number
  folded: boolean
  maximised: boolean
  resize: (height: number) => void
  toggleFold: () => void
  toggleMaximise: () => void
  /** Put on the element the main view and the dock share. */
  sharedRef: (el: HTMLElement | null) => void
}

/**
 * The dock's size, fold and maximise (spec S-1). The height the user asked for
 * is kept as asked; what is shown is clamped to the space the main view and
 * the dock share, so a window that grows back gives the dock its size back.
 */
export function useDockLayout(
  /** The look's starting share of the window (its card.ts `dockShare`); the default without it. */
  startShare?: number,
): DockLayout {
  const [prefs] = useState(readDockPrefs)
  // The size the user gave the dock (resized now, or remembered) wins. Without one, the dock
  // follows the look's starting share, also when the look changes while the page is open, and
  // nothing but a resize stores a size: folding must not turn one look's start into everyone's.
  const [userHeight, setUserHeight] = useState<number | null>(prefs.height)
  const [windowHeight] = useState(() => window.innerHeight)
  const wanted = userHeight ?? initialDockHeight(windowHeight, startShare)
  const [folded, setFolded] = useState(prefs.folded)
  const [maximised, setMaximised] = useState(false)
  const [shared, setShared] = useState(0)

  // Remember size and fold whenever either changes after the first render.
  const first = useRef(true)
  useEffect(() => {
    if (first.current) {
      first.current = false
      return
    }
    writeDockPrefs({ height: userHeight, folded })
  }, [userHeight, folded])

  const observer = useRef<ResizeObserver | null>(null)
  const sharedRef = useCallback((el: HTMLElement | null) => {
    observer.current?.disconnect()
    observer.current = null
    if (!el || typeof ResizeObserver === 'undefined') return
    observer.current = new ResizeObserver(([entry]) => {
      if (entry) setShared(Math.round(entry.contentRect.height))
    })
    observer.current.observe(el)
  }, [])

  const resize = useCallback(
    (height: number) => {
      setUserHeight(clampDockHeight(height, shared))
      setFolded(false)
    },
    [shared],
  )

  return {
    height: clampDockHeight(wanted, shared),
    maxHeight: dockMaxHeight(shared),
    folded,
    maximised,
    resize,
    toggleFold: useCallback(() => setFolded(f => !f), []),
    toggleMaximise: useCallback(() => setMaximised(m => !m), []),
    sharedRef,
  }
}
