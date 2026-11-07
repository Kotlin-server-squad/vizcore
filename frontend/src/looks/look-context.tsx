import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useLayoutEffect,
  useMemo,
  useRef,
  useState,
} from 'react'
import type { ReactNode } from 'react'
import { CARDS, LOOKS, parseLook, type LookId } from './registry'
import { classicSlots } from './classic'
import type { LookCard, LookDefinition, LookSlots } from './types'

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
 * The look chosen during this page visit. Every session page mounts its own
 * provider, so without it a choice that storage refused would be lost on the
 * next navigation.
 */
let visitLook: LookId | null = null

/** Test hook: forget this visit's choice. */
export function forgetVisitLook() {
  visitLook = null
}

/**
 * The look the person picked earlier in this visit, else the stored one, or
 * null when they never picked one (then the look experiment may assign one).
 * The visit's choice comes first, so it also survives storage that reads but
 * will not write (full, or a policy).
 */
export function storedLookChoice(): LookId | null {
  if (visitLook !== null) return visitLook
  try {
    const stored = localStorage.getItem(LOOK_STORAGE_KEY)
    return stored !== null && parseLook(stored) === stored ? parseLook(stored) : null
  } catch {
    return null
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

/**
 * The providers that are showing a look on <html>, oldest first. During a
 * route transition two session pages can be mounted at once; <html> shows the
 * newest one's look, and a page that leaves never wipes another's.
 */
const htmlOwners: { token: object; look: LookId }[] = []

function showOwnersLook() {
  const newest = htmlOwners[htmlOwners.length - 1]
  if (newest) document.documentElement.dataset.look = newest.look
  else delete document.documentElement.dataset.look
}

/** Joins the owners as the newest, or updates this owner's look where it stands. */
function showOnHtml(token: object, look: LookId) {
  const mine = htmlOwners.find(owner => owner.token === token)
  if (mine) mine.look = look
  else htmlOwners.push({ token, look })
  showOwnersLook()
}

function releaseHtml(token: object) {
  const at = htmlOwners.findIndex(owner => owner.token === token)
  if (at >= 0) htmlOwners.splice(at, 1)
  showOwnersLook()
}

interface LookProviderProps {
  /** The raw `?look=` value from the page address, if there is one. It wins and is remembered. */
  link?: string
  /** Called once a link has been read, so the page can take `?look=` out of its address. */
  onLinkRead?: () => void
  /**
   * The look experiment's assignment (its own storage, not a choice): shown only
   * while there is no link and the person has picked no look. Null or absent:
   * Classic, as without the experiment.
   */
  assigned?: LookId | null
  children: ReactNode
}

/**
 * Holds the session page's look (PR L0, the Look switch) and shows it as
 * `data-look` on <html> while the page is mounted: the look's CSS then reaches
 * the page, the body behind it and HeroUI's popovers, which render in a portal.
 * Leaving the page takes the attribute off, so every other page is Classic.
 */
export function LookProvider({ link, onLinkRead, assigned = null, children }: LookProviderProps) {
  const [look, setLookState] = useState<LookId>(() =>
    link !== undefined ? parseLook(link) : (storedLookChoice() ?? assigned ?? 'classic')
  )

  // A new link (a client-side navigation to ?look=…) shows at once, during
  // render, so nothing below commits the look it replaces; the effect below
  // remembers it and reports it read.
  const [linkShown, setLinkShown] = useState(link)
  if (linkShown !== link) {
    setLinkShown(link)
    if (link !== undefined) setLookState(parseLook(link))
  }

  // The experiment's assignment, arriving or withdrawn later, applies only
  // while nothing the person did (a link, a pick) says otherwise. Adjusted
  // during render, not in an effect, so the page never commits (and the
  // experiment never counts) the look it is about to leave.
  const [assignedSeen, setAssignedSeen] = useState(assigned)
  if (assignedSeen !== assigned) {
    setAssignedSeen(assigned)
    if (link === undefined && storedLookChoice() === null) setLookState(assigned ?? 'classic')
  }

  const onLinkReadRef = useRef(onLinkRead)
  useEffect(() => {
    onLinkReadRef.current = onLinkRead
  })

  // A link, on arrival or later (a client-side navigation to ?look=…), wins and
  // is remembered. Read once per arrival: StrictMode runs effects twice, and the
  // page would otherwise rewrite its address twice.
  const linkRead = useRef<string | undefined>(undefined)
  useEffect(() => {
    if (link === undefined) {
      linkRead.current = undefined
      return
    }
    if (linkRead.current === link) return
    linkRead.current = link
    const id = parseLook(link)
    setLookState(id)
    remember(id)
    onLinkReadRef.current?.()
  }, [link])

  // Joining <html>'s owners on mount and leaving on unmount only, so a change of
  // look keeps this page's place behind a newer one.
  const owner = useRef<object>({})
  useLayoutEffect(() => {
    showOnHtml(owner.current, look)
  }, [look])
  useLayoutEffect(() => {
    const token = owner.current
    return () => releaseHtml(token)
  }, [])

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

/**
 * The current look's version of a layout slot, or Classic's when the look has
 * none. Call the result as a function (`slot(props)`): see `LookSlots`.
 */
export function useLookSlot<K extends keyof LookSlots>(name: K): LookSlots[K] {
  const { look } = useLook()
  const definition: LookDefinition | undefined = LOOKS.find(l => l.id === look)
  return definition?.slots?.[name] ?? classicSlots[name]
}

/** The current look's graph card size; Classic's outside the session page. */
export function useLookCard(): LookCard {
  return CARDS[useLook().look]
}
