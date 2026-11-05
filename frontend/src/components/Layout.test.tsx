import { describe, it, expect, vi } from 'vitest'
import { render, screen } from '@testing-library/react'

vi.mock('@tanstack/react-router', () => ({
  Link: ({ children, to }: { children: unknown; to?: string }) => (
    <a href={to}>
      {typeof children === 'function'
        ? (children as (s: { isActive: boolean }) => React.ReactNode)({ isActive: false })
        : (children as React.ReactNode)}
    </a>
  ),
}))

import { Layout } from './Layout'

const renderLayout = () => render(<Layout>content</Layout>)

describe('Layout nav', () => {
  it('links the two places: Sessions and the Gallery', () => {
    renderLayout()
    expect(screen.getByRole('link', { name: 'Sessions' })).toHaveAttribute('href', '/')
    expect(screen.getByRole('link', { name: 'Gallery' })).toHaveAttribute('href', '/gallery')
  })

  it('does not advertise the actions that stay on their pages', () => {
    renderLayout()
    for (const gone of ['Scenarios', 'Compare', 'Home']) {
      expect(screen.queryByRole('link', { name: gone })).toBeNull()
    }
  })

  it('keeps the brand link pointing at the sessions home', () => {
    renderLayout()
    expect(screen.getByRole('link', { name: /coroutine visualizer/i })).toHaveAttribute('href', '/')
  })

  it('has the theme switch', () => {
    renderLayout()
    expect(screen.getByRole('button', { name: /switch to (light|dark) theme/i })).toBeInTheDocument()
  })

  it('still renders its children', () => {
    renderLayout()
    expect(screen.getByText('content')).toBeInTheDocument()
  })
})
