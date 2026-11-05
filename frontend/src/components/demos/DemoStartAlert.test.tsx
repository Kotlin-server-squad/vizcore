import { describe, it, expect } from 'vitest'
import { render, screen } from '@testing-library/react'
import { DemoStartAlert } from './DemoStartAlert'
import { ApiError } from '@/lib/api-errors'

describe('DemoStartAlert', () => {
  it('renders nothing without an error', () => {
    const { container } = render(<DemoStartAlert startError={null} />)
    expect(container).toBeEmptyDOMElement()
  })

  it('names the demo and says the server is busy at the demo limit', () => {
    const error = new ApiError(503, 'Server busy: too many demos running, try again in a few seconds', 'demo-limit')
    render(<DemoStartAlert startError={{ scenario: 'Nested: Product Page', error }} />)
    expect(screen.getByRole('alert')).toHaveTextContent(
      'Could not start “Nested: Product Page”: Server busy: too many demos running, try again in a few seconds',
    )
  })
})
