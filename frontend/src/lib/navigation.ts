/**
 * Router-navigate indirection (D-05). The api-client lives outside React and
 * cannot call a router hook, yet it must redirect to `/login` on a 401. Rather
 * than importing the concrete TanStack router (which is created in `main.tsx`
 * and would create a circular/SSR-hostile import), the app registers a
 * navigate callback here at bootstrap. Tests can register a spy instead.
 *
 * If no navigator is registered (e.g. in a unit test that does not care about
 * routing), navigation is a no-op rather than a crash.
 */

type Navigator = (path: string) => void

let navigator: Navigator | null = null

/** Wire the real router navigate at app bootstrap (called from main.tsx). */
export function registerNavigator(fn: Navigator): void {
  navigator = fn
}

/** Redirect the app to the `/login` route (D-05, 401 interception). */
export function navigateToLogin(): void {
  navigator?.('/login')
}
