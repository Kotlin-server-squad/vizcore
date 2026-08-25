import '@testing-library/jest-dom/vitest'

/**
 * jsdom has no IntersectionObserver, and framer-motion's `whileInView` calls it
 * on mount — so any component using a viewport-triggered animation throws on
 * render rather than failing an assertion. `EventsList` is the one that bites.
 *
 * The stub reports an immediate intersection: in a real browser the elements
 * under test are on screen, so "in view" is the truthful answer, and a no-op
 * observer would leave viewport-gated content permanently hidden.
 */
class IntersectionObserverStub implements IntersectionObserver {
  readonly root: Element | Document | null = null
  readonly rootMargin: string = ''
  readonly thresholds: ReadonlyArray<number> = []

  // Named via ConstructorParameters rather than IntersectionObserverCallback:
  // that is a type-only DOM name, and the lint config's browser globals only
  // carry runtime values, so spelling it out trips no-undef.
  constructor(
    private readonly callback: ConstructorParameters<typeof IntersectionObserver>[0],
  ) {}

  observe(target: Element): void {
    this.callback(
      [{ isIntersecting: true, target } as IntersectionObserverEntry],
      this,
    )
  }

  unobserve(): void {}
  disconnect(): void {}
  takeRecords(): IntersectionObserverEntry[] {
    return []
  }
}

globalThis.IntersectionObserver =
  IntersectionObserverStub as unknown as typeof IntersectionObserver

/**
 * jsdom implements no `window.matchMedia` either, and the reduced-motion and
 * forced-colors checks call it during render. Report "no preference": the
 * default a browser reports unless the user has asked for otherwise, so the
 * suite exercises the ordinary path rather than the accessibility fallback.
 */
if (typeof window !== 'undefined' && !window.matchMedia) {
  window.matchMedia = ((query: string) => ({
    matches: false,
    media: query,
    onchange: null,
    addListener: () => {},
    removeListener: () => {},
    addEventListener: () => {},
    removeEventListener: () => {},
    dispatchEvent: () => false,
  })) as unknown as typeof window.matchMedia
}
