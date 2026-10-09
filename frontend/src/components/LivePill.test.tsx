import { describe, it, expect } from 'vitest'
import { render, screen } from '@testing-library/react'
import { LivePill } from './LivePill'

describe('LivePill', () => {
  it('renders LIVE when the stream is on and connected, with no invented poll interval (#145)', () => {
    render(<LivePill streamEnabled connected />)
    expect(screen.getByText('LIVE')).toBeInTheDocument()
    expect(screen.queryByText(/150ms/)).not.toBeInTheDocument()
  })

  it('does not claim LIVE while the stream is on but not connected', () => {
    render(<LivePill streamEnabled connected={false} />)
    expect(screen.queryByText('LIVE')).not.toBeInTheDocument()
    expect(screen.getByText('CONNECTING')).toBeInTheDocument()
  })

  it('renders NOT LIVE when not streamEnabled', () => {
    render(<LivePill streamEnabled={false} />)
    expect(screen.getByText('NOT LIVE')).toBeInTheDocument()
    // Must not say DEMO: that word belongs to the fidelity rung, and a real
    // attached session is not a demo just because streaming is off.
    expect(screen.queryByText('DEMO')).toBeNull()
  })
})
