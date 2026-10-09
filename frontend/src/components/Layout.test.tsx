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
  it('no longer advertises retired destinations', () => {
    renderLayout()
    for (const gone of ['Scenarios', 'Gallery', 'Compare', 'Home']) {
      expect(screen.queryByRole('link', { name: gone })).toBeNull()
    }
  })

  it('keeps the brand link pointing at the sessions home', () => {
    renderLayout()
    expect(screen.getByRole('link', { name: /coroutine visualizer/i })).toHaveAttribute('href', '/')
  })

  it('still renders its children', () => {
    renderLayout()
    expect(screen.getByText('content')).toBeInTheDocument()
  })
})
