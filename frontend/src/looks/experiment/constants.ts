/**
 * The look experiment in the browser (plan 2026-11-06-look-experiment). The
 * server decides whether it runs (LOOK_EXPERIMENT_ENABLED) and the split; these
 * are the browser's own timings and limits. The decision rule lives on the
 * server, in LookDecisionRule.
 *
 * To remove the experiment: delete this folder, its route
 * (routes/experiments/looks.tsx), the layer and entry hook on the session route,
 * the "Rate this look" item in SessionHeader and `assigned` in LookProvider.
 */

/** Where the experiment keeps its state in the browser (one JSON value). */
export const EXPERIMENT_STORAGE_KEY = 'vizcore-look-experiment'

/** The vote card offers itself after this much visible time in a look, summed over visits. */
export const VOTE_AFTER_MS = 3 * 60_000

/** On a later visit in a look, the card offers itself after this much visible time on the page. */
export const LATER_VISIT_DELAY_MS = 30_000

/** How often the vote card's timing is checked while the page is visible. */
export const VOTE_CHECK_EVERY_MS = 5_000

/** Measurements wait in the browser at most this long before they are sent (sooner when the page is hidden). */
export const FLUSH_EVERY_MS = 30_000

/** The server takes at most this many events per batch. */
export const MAX_EVENTS_PER_BATCH = 50

/** A comment's longest length (the server refuses longer). */
export const MAX_COMMENT_LENGTH = 500
