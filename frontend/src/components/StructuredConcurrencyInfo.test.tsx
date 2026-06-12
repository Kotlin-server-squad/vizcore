/**
 * TDD RED → GREEN: StructuredConcurrencyInfo Failure Propagation copy
 *
 * Asserts that the Failure Propagation block:
 *  - states the parent becomes FAILED (not CANCELLED)
 *  - retains the sibling-CANCELLED wording
 */
import { describe, it, expect } from 'vitest'
import { render, screen } from '@testing-library/react'
import { StructuredConcurrencyInfo } from './StructuredConcurrencyInfo'

// No external dependencies — pure presentational component
describe('StructuredConcurrencyInfo', () => {
  it('renders the Failure Propagation section', () => {
    render(<StructuredConcurrencyInfo />)
    expect(screen.getByText('Failure Propagation')).toBeInTheDocument()
  })

  it('states the parent becomes FAILED (not CANCELLED) in the Failure Propagation block', () => {
    render(<StructuredConcurrencyInfo />)

    // The failure propagation description should mention the parent becoming FAILED
    const body = screen.getByText(/when a child coroutine throws/i)
    expect(body).toBeInTheDocument()

    const container = body.closest('div')
    expect(container).not.toBeNull()
    const text = container!.textContent ?? ''

    // Should describe the parent becoming FAILED (completes exceptionally)
    // The phrase "which gets FAILED" or "parent, which gets FAILED" must appear
    expect(text).toMatch(/parent.*which gets FAILED/i)

    // The text must NOT say "parent, which gets CANCELLED"
    // (Note: siblings being cancelled is fine — the regex anchors on "which gets CANCELLED"
    // to avoid false-matching "sibling coroutines are also cancelled")
    expect(text).not.toMatch(/which gets CANCELLED/i)
  })

  it('retains sibling CANCELLED wording in the Failure Propagation block', () => {
    render(<StructuredConcurrencyInfo />)

    const body = screen.getByText(/when a child coroutine throws/i)
    const container = body.closest('div')
    const text = container!.textContent ?? ''
    // Siblings still get CANCELLED
    expect(text).toMatch(/sibling.*cancel/i)
  })
})
