import { Link } from '@tanstack/react-router'
import { Navbar, NavbarBrand, NavbarContent, NavbarItem, Button } from '@heroui/react'
import { FiActivity } from 'react-icons/fi'

interface LayoutProps {
  children: React.ReactNode
}

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
        <NavbarContent justify="center">
          <NavbarItem>
            <Link to="/">
              {({ isActive }: { isActive: boolean }) => (
                <Button variant={isActive ? 'flat' : 'light'} color={isActive ? 'primary' : 'default'}>
                  Home
                </Button>
              )}
            </Link>
          </NavbarItem>
          <NavbarItem>
            <Link to="/sessions">
              {({ isActive }: { isActive: boolean }) => (
                <Button variant={isActive ? 'flat' : 'light'} color={isActive ? 'primary' : 'default'}>
                  Sessions
                </Button>
              )}
            </Link>
          </NavbarItem>
          <NavbarItem>
            <Link to="/scenarios">
              {({ isActive }: { isActive: boolean }) => (
                <Button variant={isActive ? 'flat' : 'light'} color={isActive ? 'primary' : 'default'}>
                  Scenarios
                </Button>
              )}
            </Link>
          </NavbarItem>
          <NavbarItem>
            <Link to="/gallery">
              {({ isActive }: { isActive: boolean }) => (
                <Button variant={isActive ? 'flat' : 'light'} color={isActive ? 'primary' : 'default'}>
                  Gallery
                </Button>
              )}
            </Link>
          </NavbarItem>
        </NavbarContent>
      </Navbar>
      <main>{children}</main>
    </div>
  )
}

