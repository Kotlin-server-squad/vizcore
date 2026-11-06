import { createContext, useCallback, useContext, useEffect, useLayoutEffect, useMemo, useRef, useState } from 'react'
import type { ReactNode } from 'react'
import { LOOKS, parseLook, type LookId } from './registry'
import { classicSlots } from './classic'
import type { LookDefinition, LookSlots } from './types'

/** Where the chosen look is remembered, per browser. */
export const LOOK_STORAGE_KEY = 'vizcore-look'

interface LookState {
  look: LookId
  /** Null outside a LookProvider: there the look is Classic and cannot be changed. */
  setLook: ((id: LookId) => void) | null
  looks: typeof LOOKS
}

const LookContext = createContext<LookState | null>(null)

const OUTSIDE: LookState = { look: 'classic', setLook: null, looks: LOOKS }

/**
 * The look chosen during this page visit, for when storage is blocked: every
 * session page mounts its own provider, so without it the choice would be lost
 * on the next navigation.
 */
let visitLook: LookId | null = null

/** Test hook: forget this visit's choice. */
export function forgetVisitLook() {
  visitLook = null
}

/** The stored look, or this visit's when storage cannot be read. */
function storedLook(): LookId {
  try {
    return parseLook(localStorage.getItem(LOOK_STORAGE_KEY))
  } catch {
    return visitLook ?? 'classic'
  }
}

function remember(id: LookId) {
  visitLook = id
  try {
    localStorage.setItem(LOOK_STORAGE_KEY, id)
  } catch {
    // Storage blocked (private mode, policy): the look still applies for this visit.
  }
}

interface LookProviderProps {
  /** The raw `?look=` value from the page address, if there is one. It wins and is remembered. */
  link?: string
  /** Called once a link has been read, so the page can take `?look=` out of its address. */
  onLinkRead?: () => void
  children: ReactNode
}

/**
 * Holds the session page's look (PR L0, the Look switch) and shows it as
 * `data-look` on <html> while the page is mounted: the look's CSS then reaches
 * the page, the body behind it and HeroUI's popovers, which render in a portal.
 * Leaving the page takes the attribute off, so every other page is Classic.
 */
export function LookProvider({ link, onLinkRead, children }: LookProviderProps) {
  const [look, setLookState] = useState<LookId>(() => (link !== undefined ? parseLook(link) : storedLook()))

  const onLinkReadRef = useRef(onLinkRead)
  useEffect(() => {
    onLinkReadRef.current = onLinkRead
  })

  // A link, on arrival or later (a client-side navigation to ?look=…), wins and is remembered.
  useEffect(() => {
    if (link === undefined) return
    const id = parseLook(link)
    setLookState(id)
    remember(id)
    onLinkReadRef.current?.()
  }, [link])

  useLayoutEffect(() => {
    document.documentElement.dataset.look = look
  }, [look])
  useLayoutEffect(
    () => () => {
      delete document.documentElement.dataset.look
    },
    [],
  )

  const setLook = useCallback((id: LookId) => {
    setLookState(id)
    remember(id)
  }, [])

  const value = useMemo(() => ({ look, setLook, looks: LOOKS }), [look, setLook])
  return <LookContext.Provider value={value}>{children}</LookContext.Provider>
}

/** The current look and a way to change it (null outside the session page's provider). */
export function useLook(): LookState {
  return useContext(LookContext) ?? OUTSIDE
}

/** The current look's version of a layout slot, or Classic's when the look has none. */
export function useLookSlot<K extends keyof LookSlots>(name: K): LookSlots[K] {
  const { look } = useLook()
  const definition: LookDefinition | undefined = LOOKS.find((l) => l.id === look)
  return definition?.slots?.[name] ?? classicSlots[name]
}
