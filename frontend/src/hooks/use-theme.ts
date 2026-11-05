import { useCallback, useEffect, useState } from 'react'

export type Theme = 'dark' | 'light'

/** Also read by the inline script in index.html; keep the two in step. */
export const THEME_STORAGE_KEY = 'vizcore-theme'

/** The saved theme; dark when nothing (or nothing valid) is saved, or storage is blocked. */
export function readStoredTheme(): Theme {
  try {
    return localStorage.getItem(THEME_STORAGE_KEY) === 'light' ? 'light' : 'dark'
  } catch {
    return 'dark'
  }
}

function applyTheme(theme: Theme) {
  const root = document.documentElement
  root.classList.remove('dark', 'light')
  root.classList.add(theme)
}

/** The current theme and a toggle; the choice is applied to <html> and saved. */
export function useTheme() {
  const [theme, setTheme] = useState<Theme>(readStoredTheme)

  useEffect(() => {
    applyTheme(theme)
    try {
      localStorage.setItem(THEME_STORAGE_KEY, theme)
    } catch {
      // Storage blocked (private mode, policy): the theme still applies for this visit.
    }
  }, [theme])

  const toggle = useCallback(() => setTheme((t) => (t === 'dark' ? 'light' : 'dark')), [])
  return { theme, toggle }
}
