import { describe, it, expect } from 'vitest'
import { render, screen } from '@testing-library/react'
import { LivePill } from './LivePill'

describe('LivePill', () => {
  it('renders LIVE and the ~150ms poll sub-label when streamEnabled', () => {
    render(<LivePill streamEnabled />)
    expect(screen.getByText('LIVE')).toBeInTheDocument()
    expect(screen.getByText('~150ms poll')).toBeInTheDocument()
  })

  it('renders NOT LIVE and no poll sub-label when not streamEnabled', () => {
    render(<LivePill streamEnabled={false} />)
    expect(screen.getByText('NOT LIVE')).toBeInTheDocument()
    // Must not say DEMO: that word belongs to the fidelity rung, and a real
    // attached session is not a demo just because streaming is off.
    expect(screen.queryByText('DEMO')).toBeNull()
    expect(screen.queryByText('~150ms poll')).not.toBeInTheDocument()
  })
})
