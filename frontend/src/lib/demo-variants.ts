/**
 * A demo's second ending: the same story with one thing going wrong. The demo's
 * route starts it when `param` is `true` (ScenarioRunnerRoutes.kt); the pages
 * carry it as `?variant=<id>`.
 */
export interface DemoVariant {
  /** The value of `?variant=` on the session page. */
  id: string
  /** The demo route's query parameter that starts this ending. */
  param: string
  /** How the ending reads after "Play: " and "Ending: ". */
  outcome: string
}

/** The demos that have a second ending, by catalog id. */
const SECOND_ENDINGS: Record<string, DemoVariant> = {
  'order-processing': { id: 'card-declined', param: 'fail', outcome: 'card declined' },
  'user-registration': { id: 'email-retried', param: 'failEmail', outcome: 'email retried' },
  'report-generation': { id: 'slow-api-cancelled', param: 'timeout', outcome: 'slow API cancelled' },
}

/** How the usual ending reads next to a second one. */
export const USUAL_OUTCOME = 'it works'

/** The demo's second ending, if it has one. */
export function secondEnding(demoId: string | undefined): DemoVariant | undefined {
  return demoId !== undefined && Object.hasOwn(SECOND_ENDINGS, demoId) ? SECOND_ENDINGS[demoId] : undefined
}

/**
 * The variant an address names, only if this demo has it: anything else
 * (another demo's variant, a typo, not text) is the usual ending.
 */
export function validVariant(demoId: string | undefined, variant: unknown): DemoVariant | undefined {
  const known = secondEnding(demoId)
  return known !== undefined && variant === known.id ? known : undefined
}

/** The button that starts an ending: "Play: card declined". */
export const playLabel = (outcome: string) => `Play: ${outcome}`

/** What the demo route is sent for this ending. */
export const variantParams = (variant: DemoVariant): Record<string, string> => ({ [variant.param]: 'true' })
