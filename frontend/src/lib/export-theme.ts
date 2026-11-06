/**
 * Exports keep the dark look in both themes (spec: "Exported PNG and SVG keep
 * today's dark look"), and the Classic look whichever trial look the page wears
 * (PR L0): the look's `data-look` on <html> is set aside like the light class.
 */

/** Turns CSS transitions off (rule in index.css) while the theme is swapped for an export. */
export const NO_TRANSITIONS_CLASS = 'vz-no-transitions'

/** Forces a style recalculation, so the class changes before it take effect now. */
function flushStyles(): void {
  void document.body.offsetHeight
}

/**
 * Runs `read` with <html> switched to Classic dark. The switch and the restore
 * happen in one synchronous task, so the browser never paints the dark state.
 *
 * Transitions are off for the whole swap: otherwise an element with a colour
 * transition (HeroUI buttons, for one) would still report its light colour when
 * read, and the restore would start a visible dark-to-light fade.
 */
export function withDarkTheme<T>(read: () => T): T {
  const root = document.documentElement
  const light = root.classList.contains('light')
  const look = root.dataset.look
  if (!light && look === undefined) return read()
  root.classList.add(NO_TRANSITIONS_CLASS)
  if (light) root.classList.replace('light', 'dark')
  if (look !== undefined) delete root.dataset.look
  flushStyles()
  try {
    return read()
  } finally {
    if (light) root.classList.replace('dark', 'light')
    if (look !== undefined) root.dataset.look = look
    flushStyles()
    root.classList.remove(NO_TRANSITIONS_CLASS)
  }
}

/** html2canvas `onclone` hook: renders the clone in the Classic dark theme. */
export function asDarkClone(doc: Document): void {
  doc.documentElement.classList.remove('light')
  doc.documentElement.classList.add('dark')
  delete doc.documentElement.dataset.look
}
