import { useCallback, useEffect, useState } from 'react'

export type Theme = 'dark' | 'light'

/** Also read by the inline script in index.html; keep the two in step. */
export const THEME_STORAGE_KEY = 'vizcore-theme'

/**
 * The saved theme; dark when nothing (or nothing valid) is saved. When storage
 * is blocked, the theme already on <html> stands in for it, so a choice made
 * this visit survives navigation, which mounts a fresh switch on every page.
 */
export function readStoredTheme(): Theme {
  try {
    return localStorage.getItem(THEME_STORAGE_KEY) === 'light' ? 'light' : 'dark'
  } catch {
    return document.documentElement.classList.contains('light') ? 'light' : 'dark'
  }
}

/**
 * The theme chosen during this page visit, kept on <html> (`data-viz-theme`).
 * Every route renders its own switch, so this is what a remounted switch starts
 * from; storage, which may be blocked or full, only carries the choice across loads.
 */
function visitTheme(): Theme | null {
  const chosen = document.documentElement.dataset.vizTheme
  return chosen === 'light' || chosen === 'dark' ? chosen : null
}

function applyTheme(theme: Theme) {
  const root = document.documentElement
  root.classList.remove('dark', 'light')
  root.classList.add(theme)
  root.dataset.vizTheme = theme
}

/** The current theme and a toggle; the choice is applied to <html> and saved. */
export function useTheme() {
  const [theme, setTheme] = useState<Theme>(() => visitTheme() ?? readStoredTheme())

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
