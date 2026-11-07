/** `GET /api/experiments/looks/results` (backend LookResults). Never carries an anonymous id. */
export interface LookResultRow {
  look: string
  label: string
  weight: number
  inSplit: boolean
  assigned: number
  chose: number
  visitorsSeen: number
  sessionsSeen: number
  medianTimeMs: number | null
  /** 0–1. */
  switchAwayRate: number | null
  switchedTo: Record<string, number>
  votes: number
  averageRating: number | null
  /** Newest first. */
  comments: { rating: number; comment: string; at: number }[]
}

export interface LookResults {
  enabled: boolean
  storage: 'database' | 'memory' | string
  startedAt: number | null
  day: number | null
  rule: { minVotesPerLook: number; maxDays: number; clearlyWorseSwitchAwayPoints: number; tooCloseRating: number }
  decision: { status: string; text: string; winner: string | null; keep: string[] }
  tooLittleData: boolean
  warning: string | null
  looks: LookResultRow[]
}
