import { describe, it, expect, beforeEach, vi } from 'vitest'
import { act, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { useRef, useState } from 'react'
import { LookProvider, forgetVisitLook, useLook, useLookSlot } from './look-context'
import type { SessionBodyProps } from './types'

// A registry with one look that brings its own SessionBody, to prove that
// switching to and from it keeps what the body holds mounted.
vi.mock('./registry', async importOriginal => {
  const real = await importOriginal<typeof import('./registry')>()
  const sideBySide = {
    id: 'a',
    label: 'Side by side',
    trial: true,
    slots: {
      SessionBody: ({ sharedRef, main, dock }: SessionBodyProps) => (
        <div ref={sharedRef} data-look-part="body" className="side-by-side">
          {main}
          {dock}
          <p>an extra child after the two</p>
        </div>
      ),
    },
  }
  return { ...real, LOOKS: [real.LOOKS[0], sideBySide] }
})

/** Counts its own mounts and keeps a click count in state, like the graph's pan and zoom. */
const mounts = { main: 0, dock: 0 }
function Stateful({ name }: { name: 'main' | 'dock' }) {
  const [clicks, setClicks] = useState(0)
  const counted = useRef(false)
  if (!counted.current) {
    counted.current = true
    mounts[name] += 1
  }
  return (
    <button type="button" onClick={() => setClicks(c => c + 1)}>
      {name} {clicks}
    </button>
  )
}

let setLook: ReturnType<typeof useLook>['setLook'] = null
function Page() {
  const look = useLook()
  setLook = look.setLook
  const body = useLookSlot('SessionBody')
  const ref = useRef<HTMLDivElement>(null)
  return body({ sharedRef: ref, main: <Stateful name="main" />, dock: <Stateful name="dock" /> })
}

beforeEach(() => {
  localStorage.clear()
  forgetVisitLook()
  mounts.main = 0
  mounts.dock = 0
})

describe('a look with its own layout slot', () => {
  it('lays the page out its way, and switching looks keeps the main view and dock mounted', async () => {
    const { container } = render(
      <LookProvider>
        <Page />
      </LookProvider>
    )
    await userEvent.click(screen.getByRole('button', { name: 'main 0' }))
    await userEvent.click(screen.getByRole('button', { name: 'dock 0' }))

    act(() => setLook!('a'))
    expect(container.querySelector('[data-look-part="body"]')).toHaveClass('side-by-side')
    expect(screen.getByText('an extra child after the two')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'main 1' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'dock 1' })).toBeInTheDocument()

    act(() => setLook!('classic'))
    expect(container.querySelector('[data-look-part="body"]')).not.toHaveClass('side-by-side')
    expect(screen.getByRole('button', { name: 'main 1' })).toBeInTheDocument()
    expect(mounts).toEqual({ main: 1, dock: 1 })
  })
})
