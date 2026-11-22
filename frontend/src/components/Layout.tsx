import { Link } from '@tanstack/react-router'
import { Navbar, NavbarBrand, NavbarContent, NavbarItem } from '@heroui/react'
import { FiActivity } from 'react-icons/fi'
import { ThemeSwitch } from './ThemeSwitch'

/** The brand link's accessible name: the product, then what it is. */
const BRAND_LABEL = 'vizcore — Coroutine Visualizer'

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
          {/* The brand is vizcore; "Coroutine Visualizer" says what it is. On narrow
              screens the subtitle goes first, then the name, leaving the icon, so the
              links and the theme switch fit. The looks style it through data-look-part. */}
          <Link
            to="/"
            aria-label={BRAND_LABEL}
            data-look-part="brand"
            className="flex min-w-0 items-center gap-2 text-inherit"
          >
            <FiActivity className="h-6 w-6 shrink-0" aria-hidden />
            <span className="hidden min-w-0 items-baseline gap-2 sm:flex">
              <span data-look-part="brand-name" className="font-bold">
                vizcore
              </span>
              <span
                data-look-part="brand-subtitle"
                className="hidden truncate text-xs font-normal text-default-500 md:inline"
              >
                Coroutine Visualizer
              </span>
            </span>
          </Link>
        </NavbarBrand>
        <NavbarContent className="gap-3 sm:gap-6" justify="start">
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
