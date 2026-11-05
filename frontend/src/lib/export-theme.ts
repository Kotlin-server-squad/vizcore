/**
 * Exports keep the dark look in both themes (spec: "Exported PNG and SVG keep
 * today's dark look").
 */

/**
 * Runs `read` with <html> switched to dark. The switch and the restore happen
 * in one synchronous task, so the browser never paints the dark state.
 */
export function withDarkTheme<T>(read: () => T): T {
  const root = document.documentElement
  if (!root.classList.contains('light')) return read()
  root.classList.replace('light', 'dark')
  try {
    return read()
  } finally {
    root.classList.replace('dark', 'light')
  }
}

/** html2canvas `onclone` hook: renders the clone in the dark theme. */
export function asDarkClone(doc: Document): void {
  doc.documentElement.classList.remove('light')
  doc.documentElement.classList.add('dark')
}
