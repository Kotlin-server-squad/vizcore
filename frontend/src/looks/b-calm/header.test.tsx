import { describe, it, expect, vi, beforeEach } from 'vitest'
import { act, render, screen } from '@testing-library/react'
import { createRef } from 'react'
import { SessionHeader } from '@/components/workspace/SessionHeader'
import { LookProvider, forgetVisitLook, useLook } from '../look-context'
import type { SessionSnapshot, VizEvent } from '@/types/api'

vi.mock('@/hooks/use-capabilities', () => ({ useCapabilities: () => ({ data: { sharingEnabled: true } }) }))
vi.mock('@/components/export/ExportMenu', () => ({ ExportMenu: () => <div data-testid="export-menu" /> }))

const session = { sessionId: 's-1', coroutineCount: 3, eventCount: 6, coroutines: [] } as unknown as SessionSnapshot

let seq = 0
function event(kind: string, coroutineId: string, extra: Record<string, unknown> = {}): VizEvent {
  seq += 1
  return { sessionId: 's-1', seq, tsNanos: seq * 1_000, kind, coroutineId, jobId: `job-${coroutineId}`, parentCoroutineId: null, scopeId: 'scope-1', label: coroutineId, ...extra } as unknown as VizEvent
}

const events = [
  event('mutex.created', 'order-1', { mutexId: 'mutex-1', mutexLabel: 'inventory-lock' }),
  event('coroutine.suspended', 'order-5', { reason: 'mutex', waitKind: 'MUTEX', resourceId: 'mutex-1' }),
  event('coroutine.suspended', 'order-6', { reason: 'mutex', waitKind: 'MUTEX', resourceId: 'mutex-1' }),
]

let setLook: ReturnType<typeof useLook>['setLook'] = null
function Switch() {
  setLook = useLook().setLook
  return null
}

function renderHeader(look: string) {
  render(
    <LookProvider link={look}>
      <Switch />
      <SessionHeader
        sessionId="s-1"
        session={session}
        title="E-commerce Orders"
        rung="demo"
        readOnly={false}
        outcome={{ outcome: 'running', failedCount: 0, ended: false }}
        runSpanNanos={6e9}
        streamEnabled
        isConnected
        replayActive={false}
        replayAtNanos={null}
        newEventsCount={0}
        loadedAtMs={0}
        panelRef={createRef()}
        panelEvents={events}
        viewMode="graph"
        onToggleStream={vi.fn()}
        onToggleReplay={vi.fn()}
        onExitReplay={vi.fn()}
        onRefetch={vi.fn()}
        onOpenShares={vi.fn()}
        onOpenChecks={vi.fn()}
      />
    </LookProvider>,
  )
}

beforeEach(() => {
  localStorage.clear()
  forgetVisitLook()
})

describe('the line under the title', () => {
  it('B says how the run goes and what waits, by name, right after the title', () => {
    renderHeader('b')
    const line = screen.getByText('Running for at least 6.0 s. 2 coroutines are waiting for inventory-lock.')
    expect(line.tagName).toBe('P')
    // Read straight after the title, inside the header.
    expect(screen.getByRole('heading', { name: 'E-commerce Orders' }).nextElementSibling).toBe(line)
    expect(line.closest('[data-look-part="header"]')).not.toBeNull()
    expect(line).not.toHaveAttribute('aria-live')
  })

  it('Classic has no such line: the title is followed by the rung badge, as before', () => {
    renderHeader('classic')
    expect(screen.queryByText(/coroutines are waiting/)).toBeNull()
    expect(screen.getByRole('heading', { name: 'E-commerce Orders' }).nextElementSibling).toBe(screen.getByTestId('rung-badge'))
  })
})

describe('switching looks', () => {
  it('adds and removes the line in place: the header itself is not remounted', () => {
    renderHeader('classic')
    const title = screen.getByRole('heading', { name: 'E-commerce Orders' })
    const replay = screen.getByRole('button', { name: 'Replay' })
    const more = screen.getByRole('button', { name: 'More actions' })
    act(() => setLook!('b'))
    expect(screen.getByText(/2 coroutines are waiting for inventory-lock/)).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'E-commerce Orders' })).toBe(title)
    act(() => setLook!('classic'))
    expect(screen.queryByText(/coroutines are waiting/)).toBeNull()
    expect(screen.getByRole('heading', { name: 'E-commerce Orders' })).toBe(title)
    // The controls after the line are the same nodes too.
    expect(screen.getByRole('button', { name: 'Replay' })).toBe(replay)
    expect(screen.getByRole('button', { name: 'More actions' })).toBe(more)
  })
})
