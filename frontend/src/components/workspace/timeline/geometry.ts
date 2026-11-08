/** Placing the timeline's pieces on its axis: percentages of the view's span, so the drawing stretches with its box. */

export function pct(nanos: number, spanNanos: number): number {
  return spanNanos > 0 ? (Math.min(Math.max(nanos, 0), spanNanos) / spanNanos) * 100 : 0
}

/** `left` and `width` styles for [from, to] nanos. */
export function place(range: [number, number], spanNanos: number): { left: string; width: string } {
  const left = pct(range[0], spanNanos)
  return { left: `${left}%`, width: `${Math.max(0, pct(range[1], spanNanos) - left)}%` }
}

/** Tick positions for the axis: at most about eight, on a 1-2-5 step. */
export function ticks(spanNanos: number): number[] {
  if (spanNanos <= 0) return [0]
  const steps = [1e6, 2e6, 5e6, 1e7, 2e7, 5e7, 1e8, 2e8, 5e8, 1e9, 2e9, 5e9, 1e10, 2e10, 5e10, 1e11, 3e11, 6e11, 1.2e12, 3e12]
  const step = steps.find(s => spanNanos / s <= 8) ?? spanNanos
  const out: number[] = []
  for (let t = 0; t <= spanNanos + 1; t += step) out.push(t)
  return out
}

/** A piece narrower than this share of the axis gets no text inside it (its title and the row label still say it). */
export const TEXT_MIN_PCT = 7
