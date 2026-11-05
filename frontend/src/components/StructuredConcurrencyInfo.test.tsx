import { describe, it, expect, beforeEach } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { StructuredConcurrencyInfo, HOW_TO_READ_KEY } from './StructuredConcurrencyInfo'

const open = () => userEvent.click(screen.getByRole('button', { name: /how to read the tree/i }))

describe('StructuredConcurrencyInfo', () => {
  beforeEach(() => localStorage.clear())

  it('is one closed line by default, so the tree stays near the top', () => {
    render(<StructuredConcurrencyInfo />)
    const toggle = screen.getByRole('button', { name: /how to read the tree/i })
    expect(toggle).toHaveAttribute('aria-expanded', 'false')
    expect(screen.queryByText('Failure Propagation')).toBeNull()
  })

  it('opens to the explanation and the legend, and remembers that', async () => {
    const { unmount } = render(<StructuredConcurrencyInfo />)
    await open()
    expect(screen.getByText('Failure Propagation')).toBeInTheDocument()
    expect(screen.getByText('Suspended')).toBeInTheDocument()
    expect(localStorage.getItem(HOW_TO_READ_KEY)).toBe('open')

    unmount()
    render(<StructuredConcurrencyInfo />)
    expect(screen.getByRole('button', { name: /how to read the tree/i })).toHaveAttribute(
      'aria-expanded',
      'true',
    )
  })

  it('points aria-controls at the body only while the body exists', async () => {
    render(<StructuredConcurrencyInfo />)
    const toggle = screen.getByRole('button', { name: /how to read the tree/i })
    expect(toggle).not.toHaveAttribute('aria-controls')
    await open()
    const id = toggle.getAttribute('aria-controls')
    expect(id).toBeTruthy()
    expect(document.getElementById(id!)).toHaveTextContent('Failure Propagation')
  })

  it('opens from the keyboard', async () => {
    render(<StructuredConcurrencyInfo />)
    const toggle = screen.getByRole('button', { name: /how to read the tree/i })
    toggle.focus()
    await userEvent.keyboard('{Enter}')
    expect(toggle).toHaveAttribute('aria-expanded', 'true')
    await userEvent.keyboard(' ')
    expect(toggle).toHaveAttribute('aria-expanded', 'false')
  })

  it('remembers closing it again', async () => {
    localStorage.setItem(HOW_TO_READ_KEY, 'open')
    render(<StructuredConcurrencyInfo />)
    await open()
    expect(localStorage.getItem(HOW_TO_READ_KEY)).toBe('closed')
  })

  it('states the parent becomes FAILED, and the siblings are cancelled', async () => {
    render(<StructuredConcurrencyInfo />)
    await open()
    const text = screen.getByText(/when a child coroutine throws/i).closest('div')!.textContent ?? ''
    expect(text).toMatch(/parent.*which gets FAILED/i)
    expect(text).not.toMatch(/which gets CANCELLED/i)
    expect(text).toMatch(/sibling.*cancel/i)
  })
})
