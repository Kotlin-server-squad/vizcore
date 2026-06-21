import { StrictMode } from 'react'
import ReactDOM from 'react-dom/client'
import { HeroUIProvider, ToastProvider } from '@heroui/react'
import { QueryClientProvider } from '@tanstack/react-query'
import { RouterProvider, createRouter } from '@tanstack/react-router'
import { queryClient } from '@/lib/query-client'
import { registerNavigator } from '@/lib/navigation'
import { routeTree } from './routeTree.gen'
import './index.css'

// Create router instance
const router = createRouter({
  routeTree,
  context: {
    queryClient,
  },
  defaultPreload: 'intent',
  defaultPreloadStaleTime: 0,
})

// Wire the api-client's framework-agnostic navigate indirection to the real
// router so a 401 can redirect to /login (D-05) from outside React.
registerNavigator((path) => {
  void router.navigate({ to: path })
})

// Register router for type safety
declare module '@tanstack/react-router' {
  interface Register {
    router: typeof router
  }
}

const rootElement = document.getElementById('root')!

if (!rootElement.innerHTML) {
  const root = ReactDOM.createRoot(rootElement)
  root.render(
    <StrictMode>
      <QueryClientProvider client={queryClient}>
        <HeroUIProvider>
          {/* ToastProvider must mount above the router so it exists before any
              addToast() call fires (issue #5086 ordering footgun). */}
          <ToastProvider placement="bottom-right" />
          <RouterProvider router={router} />
        </HeroUIProvider>
      </QueryClientProvider>
    </StrictMode>
  )
}

