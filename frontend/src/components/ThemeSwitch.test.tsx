import { describe, it, expect, beforeEach } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { ThemeSwitch } from './ThemeSwitch'

beforeEach(() => {
  localStorage.clear()
  document.documentElement.className = 'dark'
  delete document.documentElement.dataset.vizTheme
})

describe('ThemeSwitch', () => {
  it('offers the light theme while dark is on', () => {
    render(<ThemeSwitch />)
    expect(screen.getByRole('button', { name: 'Switch to light theme' })).toBeInTheDocument()
  })

  it('switches the page to light and then offers dark', async () => {
    render(<ThemeSwitch />)
    await userEvent.click(screen.getByRole('button', { name: 'Switch to light theme' }))
    expect(document.documentElement.classList.contains('light')).toBe(true)
    expect(screen.getByRole('button', { name: 'Switch to dark theme' })).toBeInTheDocument()
  })
})
