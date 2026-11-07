/**
 * Upper bound on buffered live events (#137). Matches the backend's bounded
 * EventStore ring (10k): the backend replays at most this many on connect, so
 * older events would be gone after a reload anyway. Without a bound a long
 * stream grows browser memory without limit.
 */
export const LIVE_EVENTS_MAX = 10_000
