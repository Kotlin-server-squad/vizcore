import { Link } from '@tanstack/react-router'
import { Navbar, NavbarBrand, NavbarContent, NavbarItem } from '@heroui/react'
import { FiActivity } from 'react-icons/fi'
import { ThemeSwitch } from './ThemeSwitch'

interface LayoutProps {
  children: React.ReactNode
}

/**
 * The app shell.
 *
 * The navbar carries the two places: Sessions (the sessions home) and the
 * Gallery (G-1 of the 2026-11-05 Gallery spec, which supersedes D-2 for the
 * Gallery only). Scenarios and Compare stay actions on the pages that own them
 * (D-2/D-3). The theme switch sits on the right.
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
        <NavbarContent className="gap-6" justify="start">
          <NavbarItem>
            <Link
              to="/"
              activeOptions={{ exact: true }}
              activeProps={{ className: 'font-semibold text-foreground' }}
              inactiveProps={{ className: 'text-default-500' }}
            >
              Sessions
            </Link>
          </NavbarItem>
          <NavbarItem>
            <Link
              to="/gallery"
              activeProps={{ className: 'font-semibold text-foreground' }}
              inactiveProps={{ className: 'text-default-500' }}
            >
              Gallery
            </Link>
          </NavbarItem>
        </NavbarContent>
        <NavbarContent justify="end">
          <NavbarItem>
            <ThemeSwitch />
          </NavbarItem>
        </NavbarContent>
      </Navbar>
      <main>{children}</main>
    </div>
  )
}
