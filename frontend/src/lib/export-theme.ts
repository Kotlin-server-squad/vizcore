/**
 * Exports keep the dark look in both themes (spec: "Exported PNG and SVG keep
 * today's dark look").
 */

/** Turns CSS transitions off (rule in index.css) while the theme is swapped for an export. */
export const NO_TRANSITIONS_CLASS = 'vz-no-transitions'

/** Forces a style recalculation, so the class changes before it take effect now. */
function flushStyles(): void {
  void document.body.offsetHeight
}

/**
 * Runs `read` with <html> switched to dark. The switch and the restore happen
 * in one synchronous task, so the browser never paints the dark state.
 *
 * Transitions are off for the whole swap: otherwise an element with a colour
 * transition (HeroUI buttons, for one) would still report its light colour when
 * read, and the restore would start a visible dark-to-light fade.
 */
export function withDarkTheme<T>(read: () => T): T {
  const root = document.documentElement
  if (!root.classList.contains('light')) return read()
  root.classList.add(NO_TRANSITIONS_CLASS)
  root.classList.replace('light', 'dark')
  flushStyles()
  try {
    return read()
  } finally {
    root.classList.replace('dark', 'light')
    flushStyles()
    root.classList.remove(NO_TRANSITIONS_CLASS)
  }
}

/** html2canvas `onclone` hook: renders the clone in the dark theme. */
export function asDarkClone(doc: Document): void {
  doc.documentElement.classList.remove('light')
  doc.documentElement.classList.add('dark')
}
