/**
 * How many live events the page keeps (the newest); see `use-event-stream.ts`. In its own module
 * so pure code (story mode, Part 9) can read it without the stream hook.
 */
export const LIVE_EVENTS_MAX = 10_000
