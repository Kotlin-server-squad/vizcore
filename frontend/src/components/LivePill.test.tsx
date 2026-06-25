import { describe, it, expect } from 'vitest'
import { render, screen } from '@testing-library/react'
import { LivePill } from './LivePill'

describe('LivePill', () => {
  it('renders LIVE and the ~150ms poll sub-label when streamEnabled', () => {
    render(<LivePill streamEnabled />)
    expect(screen.getByText('LIVE')).toBeInTheDocument()
    expect(screen.getByText('~150ms poll')).toBeInTheDocument()
  })

  it('renders DEMO and no poll sub-label when not streamEnabled', () => {
    render(<LivePill streamEnabled={false} />)
    expect(screen.getByText('DEMO')).toBeInTheDocument()
    expect(screen.queryByText('~150ms poll')).not.toBeInTheDocument()
  })
})
