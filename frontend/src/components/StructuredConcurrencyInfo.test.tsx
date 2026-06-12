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

    // The full text around the FAILED chip should say "FAILED" not just "CANCELLED" for the parent
    // Look for the text that describes what happens to the parent
    const container = body.closest('div')
    expect(container).not.toBeNull()
    const text = container!.textContent ?? ''
    // Should mention parent becoming FAILED
    expect(text).toMatch(/FAILED/i)
    // Should NOT say "parent" gets "CANCELLED"
    // (siblings getting CANCELLED is correct — test is specific to parent semantics)
    expect(text).not.toMatch(/parent.*CANCELLED/i)
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
