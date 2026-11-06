import { describe, it, expect, beforeEach, vi } from 'vitest'
import { act, render, renderHook } from '@testing-library/react'
import type { ReactNode } from 'react'
import {
  LOOK_STORAGE_KEY,
  LookProvider,
  forgetVisitLook,
  useLook,
  useLookSlot,
} from './look-context'
import { classicSessionBody } from './classic/SessionBody'
import { LOOKS } from './registry'

// Registry-driven, so deleting a trial look needs no change here.
const TRIAL = LOOKS.filter(l => l.trial).map(l => l.id)
const ONE = TRIAL[0]!
const TWO = TRIAL[1] ?? 'classic'

const html = () => document.documentElement

beforeEach(() => {
  localStorage.clear()
  delete html().dataset.look
  forgetVisitLook()
  vi.restoreAllMocks()
})

function withProvider(link?: string, onLinkRead?: () => void) {
  return ({ children }: { children: ReactNode }) => (
    <LookProvider link={link} onLinkRead={onLinkRead}>
      {children}
    </LookProvider>
  )
}

describe('useLook outside a provider', () => {
  it('is Classic with no way to switch, and marks nothing on <html>', () => {
    const { result } = renderHook(() => useLook())
    expect(result.current.look).toBe('classic')
    expect(result.current.setLook).toBeNull()
    expect(html().dataset.look).toBeUndefined()
  })
})

describe('LookProvider', () => {
  it('starts on Classic and marks <html> with data-look', () => {
    const { result } = renderHook(() => useLook(), { wrapper: withProvider() })
    expect(result.current.look).toBe('classic')
    expect(html().dataset.look).toBe('classic')
  })

  it('starts from the stored look', () => {
    localStorage.setItem(LOOK_STORAGE_KEY, ONE)
    const { result } = renderHook(() => useLook(), { wrapper: withProvider() })
    expect(result.current.look).toBe(ONE)
    expect(html().dataset.look).toBe(ONE)
  })

  it('reads an unknown stored look as Classic', () => {
    localStorage.setItem(LOOK_STORAGE_KEY, 'purple')
    const { result } = renderHook(() => useLook(), { wrapper: withProvider() })
    expect(result.current.look).toBe('classic')
  })

  it('switches, marks <html> and remembers the choice', () => {
    const { result } = renderHook(() => useLook(), { wrapper: withProvider() })
    act(() => result.current.setLook!(ONE))
    expect(result.current.look).toBe(ONE)
    expect(html().dataset.look).toBe(ONE)
    expect(localStorage.getItem(LOOK_STORAGE_KEY)).toBe(ONE)
  })

  it('lets a ?look= link win over the stored look, remembers it and reports it read', () => {
    localStorage.setItem(LOOK_STORAGE_KEY, TWO)
    const onLinkRead = vi.fn()
    const { result } = renderHook(() => useLook(), { wrapper: withProvider(ONE, onLinkRead) })
    expect(result.current.look).toBe(ONE)
    expect(html().dataset.look).toBe(ONE)
    expect(localStorage.getItem(LOOK_STORAGE_KEY)).toBe(ONE)
    expect(onLinkRead).toHaveBeenCalledTimes(1)
  })

  it('reads an unknown link as Classic, and remembers Classic', () => {
    localStorage.setItem(LOOK_STORAGE_KEY, ONE)
    const { result } = renderHook(() => useLook(), { wrapper: withProvider('z') })
    expect(result.current.look).toBe('classic')
    expect(localStorage.getItem(LOOK_STORAGE_KEY)).toBe('classic')
  })

  it('follows a link that arrives while the page is open', () => {
    const onLinkRead = vi.fn()
    const probe: { look?: string } = {}
    function Probe() {
      probe.look = useLook().look
      return null
    }
    const { rerender } = render(
      <LookProvider link={undefined} onLinkRead={onLinkRead}>
        <Probe />
      </LookProvider>
    )
    expect(onLinkRead).not.toHaveBeenCalled()
    rerender(
      <LookProvider link={ONE} onLinkRead={onLinkRead}>
        <Probe />
      </LookProvider>
    )
    expect(probe.look).toBe(ONE)
    expect(html().dataset.look).toBe(ONE)
    expect(onLinkRead).toHaveBeenCalledTimes(1)
  })

  it('takes data-look off <html> when the page goes, so other pages stay Classic', () => {
    const { unmount } = renderHook(() => useLook(), { wrapper: withProvider(ONE) })
    expect(html().dataset.look).toBe(ONE)
    unmount()
    expect(html().dataset.look).toBeUndefined()
  })

  it('still switches when storage is blocked, and keeps the choice for this visit', () => {
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
      throw new Error('blocked')
    })
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new Error('blocked')
    })
    const first = renderHook(() => useLook(), { wrapper: withProvider() })
    act(() => first.result.current.setLook!(ONE))
    expect(html().dataset.look).toBe(ONE)
    first.unmount()
    // Another session page in the same visit.
    const second = renderHook(() => useLook(), { wrapper: withProvider() })
    expect(second.result.current.look).toBe(ONE)
  })
})

describe('storage that reads but will not write', () => {
  it('keeps the choice of this visit over the stored look', () => {
    localStorage.setItem(LOOK_STORAGE_KEY, 'classic')
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new Error('full')
    })
    const first = renderHook(() => useLook(), { wrapper: withProvider() })
    act(() => first.result.current.setLook!(ONE))
    first.unmount()
    const second = renderHook(() => useLook(), { wrapper: withProvider() })
    expect(second.result.current.look).toBe(ONE)
  })
})

describe('useLookSlot', () => {
  it("gives Classic's layout for every look until a look brings its own", () => {
    // Update this test when a look brings its own SessionBody.
    for (const { id } of LOOKS) {
      localStorage.setItem(LOOK_STORAGE_KEY, id)
      const { result, unmount } = renderHook(() => useLookSlot('SessionBody'), {
        wrapper: withProvider(),
      })
      expect(result.current).toBe(classicSessionBody)
      unmount()
    }
    const outside = renderHook(() => useLookSlot('SessionBody'))
    expect(outside.result.current).toBe(classicSessionBody)
  })
})

describe("Classic's SessionBody", () => {
  it('stacks the main view above the dock in one column, with the ref on it', () => {
    const ref = { current: null as HTMLDivElement | null }
    const { container } = render(
      classicSessionBody({ sharedRef: ref, main: <p>main</p>, dock: <p>dock</p> })
    )
    const column = container.firstElementChild as HTMLDivElement
    expect(column.className).toBe('flex min-h-0 flex-1 flex-col gap-3')
    expect([...column.children].map(c => c.textContent)).toEqual(['main', 'dock'])
    expect(ref.current).toBe(column)
  })
})
