import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { createRef } from 'react'
import { SessionHeader } from './SessionHeader'
import { LOOK_STORAGE_KEY, LookProvider, forgetVisitLook } from '@/looks/look-context'
import type { SessionSnapshot } from '@/types/api'

vi.mock('@/hooks/use-capabilities', () => ({ useCapabilities: () => ({ data: { sharingEnabled: true } }) }))
vi.mock('../export/ExportMenu', () => ({ ExportMenu: () => <div data-testid="export-menu" /> }))

const session = { sessionId: 's-1', coroutineCount: 5, eventCount: 42, coroutines: [] } as unknown as SessionSnapshot

function renderHeader(props: Partial<Parameters<typeof SessionHeader>[0]> = {}, { withLook = false } = {}) {
  const handlers = {
    onToggleStream: vi.fn(),
    onToggleReplay: vi.fn(),
    onExitReplay: vi.fn(),
    onReconnect: vi.fn(),
    onRefetch: vi.fn(),
    onOpenShares: vi.fn(),
    onOpenChecks: vi.fn(),
  }
  const header = (
    <SessionHeader
      sessionId="s-1"
      session={session}
      title="Deadlock"
      rung="demo"
      readOnly={false}
      outcome={null}
      runSpanNanos={null}
      streamEnabled
      isConnected
      replayActive={false}
      replayAtNanos={null}
      newEventsCount={0}
      loadedAtMs={0}
      panelRef={createRef()}
      panelEvents={[]}
      viewMode="graph"
      {...handlers}
      {...props}
    />
  )
  render(withLook ? <LookProvider>{header}</LookProvider> : header)
  return handlers
}

describe('SessionHeader', () => {
  it('names the session, its rung and its counts', () => {
    renderHeader()
    expect(screen.getByRole('heading', { name: 'Deadlock' })).toBeInTheDocument()
    expect(screen.getByTestId('rung-badge')).toHaveTextContent('DEMO')
    expect(screen.getByText('5 coroutines')).toBeInTheDocument()
    expect(screen.getByText('42 events')).toBeInTheDocument()
  })

  describe('the run outcome (demos only)', () => {
    it('is absent for an attached app', () => {
      renderHeader({ outcome: null })
      expect(screen.queryByTestId('run-outcome')).toBeNull()
    })

    it('reads Finished with how long the run took', () => {
      renderHeader({ outcome: { outcome: 'finished', failedCount: 0, ended: true }, runSpanNanos: 12e9 })
      expect(screen.getByTestId('run-outcome')).toHaveTextContent('Finished · 12.0 s')
    })

    it('reads Failed in red with how many failed — never green Completed (S-3)', () => {
      renderHeader({ outcome: { outcome: 'failed', failedCount: 2, ended: true }, runSpanNanos: 3e9 })
      const pill = screen.getByTestId('run-outcome')
      expect(pill).toHaveTextContent('Failed · 2 coroutines failed · 3.0 s')
      expect(pill.className).toContain('danger')
    })

    it('reads Running', () => {
      renderHeader({ outcome: { outcome: 'running', failedCount: 0, ended: false }, runSpanNanos: 4e9 })
      expect(screen.getByTestId('run-outcome')).toHaveTextContent(/^Running$/)
    })

    it('gives a failed run no duration while some of it still runs', () => {
      renderHeader({ outcome: { outcome: 'failed', failedCount: 1, ended: false }, runSpanNanos: 3e9 })
      expect(screen.getByTestId('run-outcome')).toHaveTextContent(/^Failed · 1 coroutine failed$/)
    })

    it('announces a run that fails while it is watched, in one live region (#290)', () => {
      const props = {
        sessionId: 's-1', session, title: 'Deadlock', rung: 'demo' as const, readOnly: false, runSpanNanos: null,
        streamEnabled: true, isConnected: true, replayActive: false, replayAtNanos: null, newEventsCount: 0,
        loadedAtMs: 0, panelRef: createRef<HTMLDivElement>(), panelEvents: [], viewMode: 'graph' as const,
        onToggleStream: vi.fn(), onToggleReplay: vi.fn(), onExitReplay: vi.fn(), onRefetch: vi.fn(),
        onOpenShares: vi.fn(), onOpenChecks: vi.fn(),
      }
      const { rerender } = render(<SessionHeader {...props} outcome={{ outcome: 'running', failedCount: 0, ended: false }} />)
      const status = screen.getByRole('status', { name: 'Run outcome' })
      expect(status).toHaveAttribute('aria-atomic', 'true')
      expect(status).toHaveTextContent('Running')
      rerender(<SessionHeader {...props} outcome={{ outcome: 'failed', failedCount: 1, ended: false }} />)
      // The same region, so a screen reader hears the change.
      expect(screen.getByRole('status', { name: 'Run outcome' })).toBe(status)
      expect(status).toHaveTextContent('Failed · 1 coroutine failed')
    })

    it('says "at least" when the backend dropped events', () => {
      renderHeader({ outcome: { outcome: 'finished', failedCount: 0, ended: true }, runSpanNanos: 12e9, droppedCount: 2 })
      expect(screen.getByTestId('run-outcome')).toHaveTextContent('Finished · at least 12.0 s')
    })
  })

  describe('the view (T-2)', () => {
    it('reads Updating while the stream is connected, and offers Pause view', async () => {
      const { onToggleStream } = renderHeader()
      expect(screen.getByTestId('view-status')).toHaveTextContent('Updating')
      await userEvent.click(screen.getByRole('button', { name: 'Pause view' }))
      expect(onToggleStream).toHaveBeenCalledTimes(1)
    })

    it('reads View paused with the stream off, and offers Resume view', () => {
      renderHeader({ streamEnabled: false, isConnected: false })
      expect(screen.getByTestId('view-status')).toHaveTextContent('View paused')
      expect(screen.getByRole('button', { name: 'Resume view' })).toBeInTheDocument()
    })

    it('says what is wrong with the stream and offers Reconnect (#137)', async () => {
      const { onReconnect } = renderHeader({ isConnected: false, streamError: 'Live updates stopped' })
      expect(screen.getByTestId('view-status')).toHaveTextContent('Live updates stopped')
      await userEvent.click(screen.getByRole('button', { name: 'Reconnect' }))
      expect(onReconnect).toHaveBeenCalledTimes(1)
    })

    it('counts the events the backend dropped', () => {
      renderHeader({ droppedCount: 3 })
      expect(screen.getByTestId('dropped-count')).toHaveTextContent('3 events dropped')
    })

    it('reads Replay at the cursor, with the new-events badge that exits replay', async () => {
      const { onExitReplay } = renderHeader({ replayActive: true, replayAtNanos: 4.2e9, newEventsCount: 2 })
      expect(screen.getByTestId('view-status')).toHaveTextContent('Replay at 4.2 s')
      expect(screen.getByRole('button', { name: 'Exit replay' })).toBeInTheDocument()
      await userEvent.click(screen.getByRole('button', { name: 'Exit replay and jump to live' }))
      expect(onExitReplay).toHaveBeenCalledTimes(1)
    })

    it('reads Loaded at in a shared view, which has no live controls', () => {
      renderHeader({ readOnly: true, loadedAtMs: new Date(2026, 10, 6, 14, 2, 31).getTime() })
      expect(screen.getByTestId('view-status')).toHaveTextContent(/^Loaded at /)
      expect(screen.queryByRole('button', { name: /pause view|resume view/i })).toBeNull()
    })
  })

  describe('actions', () => {
    it('offers Replay and Export', () => {
      renderHeader()
      expect(screen.getByRole('button', { name: 'Replay' })).toBeInTheDocument()
      expect(screen.getByTestId('export-menu')).toBeInTheDocument()
    })

    it('puts Share, Checks and Refresh in "⋯"', async () => {
      const { onOpenShares, onOpenChecks } = renderHeader()
      await userEvent.click(screen.getByRole('button', { name: 'More actions' }))
      const menu = await screen.findByRole('menu')
      expect(within(menu).getByRole('menuitem', { name: /share/i })).toBeInTheDocument()
      expect(within(menu).getByRole('menuitem', { name: /refresh/i })).toBeInTheDocument()
      await userEvent.click(within(menu).getByRole('menuitem', { name: /checks/i }))
      expect(onOpenChecks).toHaveBeenCalledTimes(1)
      expect(onOpenShares).not.toHaveBeenCalled()
    })

    it('adds Reset session and Clear events to "⋯" for a demo, and the Run button', async () => {
      const scenario = { run: vi.fn(), reset: vi.fn(), clear: vi.fn(), isRunPending: false, isResetPending: false, errorText: null }
      renderHeader({ scenario, outcome: { outcome: 'notStarted', failedCount: 0, ended: false } })
      expect(screen.getByRole('button', { name: 'Run Scenario' })).toBeEnabled()
      await userEvent.click(screen.getByRole('button', { name: 'More actions' }))
      await userEvent.click(await screen.findByRole('menuitem', { name: /clear events/i }))
      expect(scenario.clear).toHaveBeenCalledTimes(1)
    })

    it('shows why a scenario action failed', () => {
      const scenario = {
        run: vi.fn(), reset: vi.fn(), clear: vi.fn(), isRunPending: false, isResetPending: false,
        errorText: 'Could not run the scenario: Server busy',
      }
      renderHeader({ scenario, outcome: { outcome: 'notStarted', failedCount: 0, ended: false } })
      expect(screen.getByRole('alert')).toHaveTextContent('Could not run the scenario: Server busy')
    })

    it('offers no Share in a shared view (ADR-019)', async () => {
      renderHeader({ readOnly: true })
      await userEvent.click(screen.getByRole('button', { name: 'More actions' }))
      const menu = await screen.findByRole('menu')
      expect(within(menu).queryByRole('menuitem', { name: /share/i })).toBeNull()
      expect(within(menu).getByRole('menuitem', { name: /checks/i })).toBeInTheDocument()
    })
  })

  describe('the Look choice (trial)', () => {
    beforeEach(() => {
      localStorage.clear()
      forgetVisitLook()
    })

    async function openMenu() {
      await userEvent.click(screen.getByRole('button', { name: 'More actions' }))
      return screen.findByRole('menu')
    }

    it('offers the four looks in "⋯", marked as a trial, with the current one marked', async () => {
      localStorage.setItem(LOOK_STORAGE_KEY, 'b')
      renderHeader({}, { withLook: true })
      const menu = await openMenu()
      const section = within(menu).getByRole('group', { name: 'Look · trial' })
      const items = within(section).getAllByRole('menuitem')
      expect(items.map((i) => i.textContent)).toEqual(['Classic', 'A · Compact', 'B · Calm', 'C · Bold'])
      expect(items.map((i) => i.getAttribute('aria-current'))).toEqual([null, null, 'true', null])
    })

    it('switches the page to the chosen look', async () => {
      renderHeader({}, { withLook: true })
      expect(document.documentElement.dataset.look).toBe('classic')
      const menu = await openMenu()
      await userEvent.click(within(menu).getByRole('menuitem', { name: 'C · Bold' }))
      expect(document.documentElement.dataset.look).toBe('c')
      expect(localStorage.getItem(LOOK_STORAGE_KEY)).toBe('c')
    })

    it('is not offered where the page has no look switch (the shared view)', async () => {
      renderHeader({ readOnly: true })
      const menu = await openMenu()
      expect(within(menu).queryByRole('group', { name: 'Look · trial' })).toBeNull()
      expect(within(menu).queryByRole('menuitem', { name: 'A · Compact' })).toBeNull()
    })
  })
})
