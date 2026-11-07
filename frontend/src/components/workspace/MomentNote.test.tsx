import { describe, it, expect } from 'vitest'
import { render, screen } from '@testing-library/react'
import { MomentNote } from './MomentNote'

describe('MomentNote', () => {
  it('names the replay cursor in seconds and events', () => {
    render(<MomentNote moment={{ kind: 'replay', atNanos: 4.21e9, index: 36, total: 120 }} />)
    const note = screen.getByTestId('moment-note')
    expect(note).toHaveTextContent('At 4.2 s · event 37 of 120')
    expect(note).toHaveAttribute('data-look-part', 'moment')
  })

  it('names the moment a shared link was loaded at', () => {
    render(<MomentNote moment={{ kind: 'loaded', seq: 812 }} />)
    expect(screen.getByTestId('moment-note')).toHaveTextContent('As loaded · event 812')
  })

  it('says nothing in the live view', () => {
    const { container } = render(<MomentNote moment={null} />)
    expect(container).toBeEmptyDOMElement()
  })
})
