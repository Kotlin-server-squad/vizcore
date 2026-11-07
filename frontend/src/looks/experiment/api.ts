import { getToken } from '@/lib/auth-store'
import type { LookId } from '../registry'
import type { EntryKind } from './assign'

/** What the server says about the experiment (`GET /api/experiments/looks/config`). */
export interface ExperimentConfig {
  enabled: boolean
  weights: Record<string, number>
  surveyUrl: string | null
}

/** One anonymous measurement, as the server takes it. */
export type ExperimentEvent =
  | { type: 'entry'; look: LookId; kind: EntryKind }
  | { type: 'seen'; look: LookId }
  | { type: 'time'; look: LookId; ms: number }
  | { type: 'switch'; from: LookId; to: LookId }

const BASE = '/api/experiments/looks'
const OFF: ExperimentConfig = { enabled: false, weights: {}, surveyUrl: null }

let config: Promise<ExperimentConfig> | null = null

/** Test hook: ask the server again. */
export function forgetExperimentConfig() {
  config = null
}

function authHeaders(): Record<string, string> {
  const token = getToken()
  return token ? { Authorization: `Bearer ${token}` } : {}
}

async function fetchConfig(): Promise<ExperimentConfig> {
  const response = await fetch(`${BASE}/config`, { headers: authHeaders() })
  if (!response.ok) throw new Error(`HTTP ${response.status}`)
  const body = (await response.json()) as Partial<ExperimentConfig>
  return {
    enabled: body.enabled === true,
    weights: body.weights && typeof body.weights === 'object' ? body.weights : {},
    surveyUrl: typeof body.surveyUrl === 'string' && body.surveyUrl ? body.surveyUrl : null,
  }
}

/**
 * The experiment's settings, asked once per page load, when the first session
 * page opens, and shared by the next ones. Any failure (an older server, not
 * signed in yet, offline) reads as off for this page, and is not kept: the next
 * session page asks again. It never sends the person to /login.
 */
export function loadExperimentConfig(): Promise<ExperimentConfig> {
  if (!config) {
    const asking = fetchConfig()
    config = asking
    asking.catch(() => {
      if (config === asking) config = null
    })
  }
  return config.catch(() => OFF)
}

/**
 * Sends one batch. Leaving the page without a token, by `sendBeacon` (it
 * survives the page closing); with a token, by a keepalive `fetch` instead,
 * since a beacon cannot carry the Authorization header and the token never goes
 * into a URL. Never throws: a lost measurement is not the person's problem.
 */
export function sendBatch(anonId: string, events: ExperimentEvent[], leaving: boolean) {
  const body = JSON.stringify({ anonId, events })
  const url = `${BASE}/events`
  const headers = authHeaders()
  if (leaving && !headers.Authorization && typeof navigator.sendBeacon === 'function') {
    try {
      if (navigator.sendBeacon(url, new Blob([body], { type: 'text/plain' }))) return
    } catch {
      // Fall through to fetch.
    }
  }
  try {
    void fetch(url, {
      method: 'POST',
      keepalive: true,
      headers: { 'Content-Type': 'application/json', ...headers },
      body,
    }).catch(() => undefined)
  } catch {
    // fetch itself refused (a keepalive quota): drop the batch.
  }
}

/** Saves this browser's rating of [look]; true when the server kept it. */
export async function sendVote(
  anonId: string,
  look: LookId,
  rating: number,
  comment: string
): Promise<boolean> {
  try {
    const response = await fetch(`${BASE}/votes`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', ...authHeaders() },
      body: JSON.stringify({ anonId, look, rating, comment }),
    })
    return response.ok
  } catch {
    return false
  }
}

/** The survey address with `look=<id>` added. */
export function surveyLink(surveyUrl: string, look: LookId): string {
  const url = new URL(surveyUrl)
  url.searchParams.set('look', look)
  return url.toString()
}
