import { createContext, useContext } from 'react'

/**
 * How the graph tells the session page how much canvas it has to spare for its first view: the
 * canvas height minus what the root and its first row of children need at the look's readable
 * zoom (negative when they do not fit). The session page turns it into the tallest dock that still
 * leaves that room (`startingDockHeight`). Null when the graph has nothing to draw (the dock goes back to
 * the look's share). The context is null outside a session page: nothing listens.
 */
export type ReportFirstView = (sparePx: number | null) => void

export const GraphFirstViewContext = createContext<ReportFirstView | null>(null)

export function useGraphFirstView(): ReportFirstView | null {
  return useContext(GraphFirstViewContext)
}

/**
 * The canvas height the first view needs: the toolbar's inset, then the root and one row of
 * children with the layout's padding above and below, at the zoom the fit will use. A look with a
 * fit floor (`fitMinZoom`) never zooms below it; Classic shrinks down to `minReadableScale`.
 * Always two rows, also while only the root exists, so the dock does not jump when children appear.
 */
export function firstViewCanvasPx(opts: {
  cardHeight: number
  vGap: number
  padding: number
  toolbarInset: number
  scale: number
}): number {
  const { cardHeight, vGap, padding, toolbarInset, scale } = opts
  return Math.ceil(toolbarInset + (2 * padding + 2 * cardHeight + vGap) * scale)
}
