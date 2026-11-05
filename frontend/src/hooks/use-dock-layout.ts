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
export function useDockLayout(): DockLayout {
  const [prefs] = useState(readDockPrefs)
  const [wanted, setWanted] = useState(() => prefs.height ?? initialDockHeight(window.innerHeight))
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
    writeDockPrefs({ height: wanted, folded })
  }, [wanted, folded])

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
      setWanted(clampDockHeight(height, shared))
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
