import { describe, it, expect, vi } from 'vitest'
import { render, screen } from '@testing-library/react'

vi.mock('@tanstack/react-router', () => ({
  Link: ({
    children,
    to,
    ...rest
  }: {
    children: unknown
    to?: string
    'aria-label'?: string
    'data-look-part'?: string
    className?: string
  }) => (
    <a href={to} aria-label={rest['aria-label']} data-look-part={rest['data-look-part']} className={rest.className}>
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

  it('shows vizcore as the brand, with Coroutine Visualizer as its subtitle', () => {
    renderLayout()
    const brand = screen.getByRole('link', { name: /vizcore/i })
    expect(brand).toHaveAttribute('data-look-part', 'brand')
    const name = brand.querySelector('[data-look-part="brand-name"]')
    const subtitle = brand.querySelector('[data-look-part="brand-subtitle"]')
    expect(name).toHaveTextContent(/^vizcore$/)
    expect(name).toHaveClass('font-bold')
    expect(subtitle).toHaveTextContent(/^Coroutine Visualizer$/)
    expect(subtitle).toHaveClass('text-xs', 'text-default-500')
  })

  it('names the brand link even where its text is hidden on narrow screens', () => {
    renderLayout()
    const brand = screen.getByRole('link', { name: 'vizcore — Coroutine Visualizer' })
    expect(brand).toHaveAttribute('aria-label', 'vizcore — Coroutine Visualizer')
    // The words show from sm up; the subtitle only from md up, the icon always.
    expect(brand.querySelector('[data-look-part="brand-name"]')!.parentElement).toHaveClass('hidden', 'sm:flex')
    expect(brand.querySelector('[data-look-part="brand-subtitle"]')).toHaveClass('hidden', 'md:inline')
    expect(brand.querySelector('svg')).not.toBeNull()
  })

  it('keeps the brand link pointing at the sessions home', () => {
    renderLayout()
    expect(screen.getByRole('link', { name: /vizcore/i })).toHaveAttribute('href', '/')
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
