import { describe, it, expect, beforeEach, vi } from 'vitest'
import { act, render, renderHook } from '@testing-library/react'
import type { ReactNode } from 'react'
import { LOOK_STORAGE_KEY, LookProvider, forgetVisitLook, useLook, useLookSlot } from './look-context'
import { ClassicSessionBody } from './classic/SessionBody'

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
    localStorage.setItem(LOOK_STORAGE_KEY, 'b')
    const { result } = renderHook(() => useLook(), { wrapper: withProvider() })
    expect(result.current.look).toBe('b')
    expect(html().dataset.look).toBe('b')
  })

  it('reads an unknown stored look as Classic', () => {
    localStorage.setItem(LOOK_STORAGE_KEY, 'purple')
    const { result } = renderHook(() => useLook(), { wrapper: withProvider() })
    expect(result.current.look).toBe('classic')
  })

  it('switches, marks <html> and remembers the choice', () => {
    const { result } = renderHook(() => useLook(), { wrapper: withProvider() })
    act(() => result.current.setLook!('c'))
    expect(result.current.look).toBe('c')
    expect(html().dataset.look).toBe('c')
    expect(localStorage.getItem(LOOK_STORAGE_KEY)).toBe('c')
  })

  it('lets a ?look= link win over the stored look, remembers it and reports it read', () => {
    localStorage.setItem(LOOK_STORAGE_KEY, 'b')
    const onLinkRead = vi.fn()
    const { result } = renderHook(() => useLook(), { wrapper: withProvider('a', onLinkRead) })
    expect(result.current.look).toBe('a')
    expect(html().dataset.look).toBe('a')
    expect(localStorage.getItem(LOOK_STORAGE_KEY)).toBe('a')
    expect(onLinkRead).toHaveBeenCalledTimes(1)
  })

  it('reads an unknown link as Classic, and remembers Classic', () => {
    localStorage.setItem(LOOK_STORAGE_KEY, 'b')
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
      </LookProvider>,
    )
    expect(onLinkRead).not.toHaveBeenCalled()
    rerender(
      <LookProvider link="c" onLinkRead={onLinkRead}>
        <Probe />
      </LookProvider>,
    )
    expect(probe.look).toBe('c')
    expect(html().dataset.look).toBe('c')
    expect(onLinkRead).toHaveBeenCalledTimes(1)
  })

  it('takes data-look off <html> when the page goes, so other pages stay Classic', () => {
    const { unmount } = renderHook(() => useLook(), { wrapper: withProvider('b') })
    expect(html().dataset.look).toBe('b')
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
    act(() => first.result.current.setLook!('a'))
    expect(html().dataset.look).toBe('a')
    first.unmount()
    // Another session page in the same visit.
    const second = renderHook(() => useLook(), { wrapper: withProvider() })
    expect(second.result.current.look).toBe('a')
  })
})

describe('useLookSlot', () => {
  it("gives Classic's layout for every look until a look brings its own", () => {
    for (const id of ['classic', 'a', 'b', 'c']) {
      localStorage.setItem(LOOK_STORAGE_KEY, id)
      const { result, unmount } = renderHook(() => useLookSlot('SessionBody'), { wrapper: withProvider() })
      expect(result.current).toBe(ClassicSessionBody)
      unmount()
    }
    const outside = renderHook(() => useLookSlot('SessionBody'))
    expect(outside.result.current).toBe(ClassicSessionBody)
  })
})

describe("Classic's SessionBody", () => {
  it('stacks the main view above the dock in one column, with the ref on it', () => {
    const ref = { current: null as HTMLDivElement | null }
    const { container } = render(
      <ClassicSessionBody sharedRef={ref} main={<p>main</p>} dock={<p>dock</p>} />,
    )
    const column = container.firstElementChild as HTMLDivElement
    expect(column.className).toBe('flex min-h-0 flex-1 flex-col gap-3')
    expect([...column.children].map((c) => c.textContent)).toEqual(['main', 'dock'])
    expect(ref.current).toBe(column)
  })
})
