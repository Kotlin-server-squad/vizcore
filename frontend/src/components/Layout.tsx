import { Link } from '@tanstack/react-router'
import { Navbar, NavbarBrand } from '@heroui/react'
import { FiActivity } from 'react-icons/fi'

interface LayoutProps {
  children: React.ReactNode
}

/**
 * The app shell.
 *
 * The navbar is brand-only (D-1/D-2/D-3). It previously advertised five
 * destinations — Home, Sessions, Scenarios, Gallery, Compare — of which Home
 * and Sessions were the same page, and Scenarios, Gallery and Compare were
 * actions wearing the costume of places. Per-page actions now live on the pages
 * that own them; the brand returns you to the sessions home.
 */
export function Layout({ children }: LayoutProps) {
  return (
    <div className="min-h-screen bg-background">
      <Navbar isBordered>
        <NavbarBrand>
          <Link to="/" className="flex items-center gap-2 font-bold text-inherit">
            <FiActivity className="h-6 w-6" />
            <span>Coroutine Visualizer</span>
          </Link>
        </NavbarBrand>
      </Navbar>
      <main>{children}</main>
    </div>
  )
}
