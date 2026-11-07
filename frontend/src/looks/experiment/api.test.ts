import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { clearToken, setToken } from '@/lib/auth-store'
import { forgetExperimentConfig, loadExperimentConfig, sendVote, surveyLink } from './api'
import { enqueueEvent, flushEvents, forgetQueue, pendingEvents } from './queue'

const ID = '0f8fad5b-d9cb-469f-a165-70867728950e'
let fetchMock: ReturnType<typeof vi.fn>
let beacon: ReturnType<typeof vi.fn>

beforeEach(() => {
  forgetExperimentConfig()
  forgetQueue()
  clearToken()
  fetchMock = vi.fn(async () => new Response(null, { status: 204 }))
  vi.stubGlobal('fetch', fetchMock)
  beacon = vi.fn(() => true)
  Object.defineProperty(navigator, 'sendBeacon', { value: beacon, configurable: true })
})

afterEach(() => {
  vi.unstubAllGlobals()
  clearToken()
})

const json = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })

describe('loadExperimentConfig', () => {
  it('asks once and shares the answer', async () => {
    fetchMock.mockResolvedValue(
      json({ enabled: true, weights: { b: 1 }, surveyUrl: 'https://x.org' })
    )
    const [first, second] = await Promise.all([loadExperimentConfig(), loadExperimentConfig()])
    expect(first).toEqual({ enabled: true, weights: { b: 1 }, surveyUrl: 'https://x.org' })
    expect(second).toBe(first)
    expect(fetchMock).toHaveBeenCalledTimes(1)
    expect(fetchMock.mock.calls[0]![0]).toBe('/api/experiments/looks/config')
  })

  it('reads a failure as off and asks again next time (say, after logging in)', async () => {
    fetchMock.mockResolvedValueOnce(json({ error: 'Unauthorized' }, 401))
    expect(await loadExperimentConfig()).toEqual({ enabled: false, weights: {}, surveyUrl: null })
    setToken('jwt-1')
    fetchMock.mockResolvedValueOnce(json({ enabled: true, weights: {}, surveyUrl: null }))
    expect((await loadExperimentConfig()).enabled).toBe(true)
    expect(fetchMock.mock.calls[1]![1]).toMatchObject({
      headers: { Authorization: 'Bearer jwt-1' },
    })
  })

  it('reads a network error as off', async () => {
    fetchMock.mockRejectedValueOnce(new TypeError('Failed to fetch'))
    expect((await loadExperimentConfig()).enabled).toBe(false)
  })
})

describe('the measurements queue', () => {
  it('adds up visible time per look and sends one batch', () => {
    enqueueEvent(ID, { type: 'seen', look: 'b' })
    enqueueEvent(ID, { type: 'time', look: 'b', ms: 1000 })
    enqueueEvent(ID, { type: 'time', look: 'b', ms: 500 })
    expect(pendingEvents()).toEqual([
      { type: 'seen', look: 'b' },
      { type: 'time', look: 'b', ms: 1500 },
    ])
    flushEvents({ leaving: false })
    expect(fetchMock).toHaveBeenCalledTimes(1)
    const [url, init] = fetchMock.mock.calls[0]!
    expect(url).toBe('/api/experiments/looks/events')
    expect(init).toMatchObject({ method: 'POST', keepalive: true })
    expect(JSON.parse(init.body)).toEqual({ anonId: ID, events: pendingSent() })
    expect(pendingEvents()).toEqual([])

    function pendingSent() {
      return [
        { type: 'seen', look: 'b' },
        { type: 'time', look: 'b', ms: 1500 },
      ]
    }
  })

  it('leaving the page, sends by beacon without a token', () => {
    enqueueEvent(ID, { type: 'seen', look: 'a' })
    flushEvents({ leaving: true })
    expect(beacon).toHaveBeenCalledTimes(1)
    expect(beacon.mock.calls[0]![0]).toBe('/api/experiments/looks/events')
    expect(fetchMock).not.toHaveBeenCalled()
  })

  it('leaving the page with a token, sends by keepalive fetch with the header (never the token in a URL)', () => {
    setToken('jwt-2')
    enqueueEvent(ID, { type: 'seen', look: 'a' })
    flushEvents({ leaving: true })
    expect(beacon).not.toHaveBeenCalled()
    expect(fetchMock.mock.calls[0]![0]).toBe('/api/experiments/looks/events')
    expect(fetchMock.mock.calls[0]![1]).toMatchObject({
      keepalive: true,
      headers: { Authorization: 'Bearer jwt-2' },
    })
  })

  it('falls back to fetch when the beacon is refused', () => {
    beacon.mockReturnValue(false)
    enqueueEvent(ID, { type: 'seen', look: 'a' })
    flushEvents({ leaving: true })
    expect(fetchMock).toHaveBeenCalledTimes(1)
  })

  it('splits more than 50 events into batches', () => {
    for (let i = 0; i < 60; i++) enqueueEvent(ID, { type: 'seen', look: 'a' })
    flushEvents({ leaving: false })
    expect(fetchMock).toHaveBeenCalledTimes(2)
    expect(JSON.parse(fetchMock.mock.calls[0]![1].body).events).toHaveLength(50)
  })

  it('sends nothing when nothing waits', () => {
    flushEvents({ leaving: true })
    expect(fetchMock).not.toHaveBeenCalled()
    expect(beacon).not.toHaveBeenCalled()
  })
})

describe('sendVote', () => {
  it('posts the vote and says whether it was saved', async () => {
    expect(await sendVote(ID, 'c', 4, 'nice')).toBe(true)
    expect(JSON.parse(fetchMock.mock.calls[0]![1].body)).toEqual({
      anonId: ID,
      look: 'c',
      rating: 4,
      comment: 'nice',
    })
    fetchMock.mockResolvedValueOnce(json({ error: 'x' }, 400))
    expect(await sendVote(ID, 'c', 4, '')).toBe(false)
    fetchMock.mockRejectedValueOnce(new TypeError('offline'))
    expect(await sendVote(ID, 'c', 4, '')).toBe(false)
  })
})

describe('surveyLink', () => {
  it('adds the look to the survey address, keeping its own query', () => {
    expect(surveyLink('https://x.org/s?src=viz', 'b')).toBe('https://x.org/s?src=viz&look=b')
    expect(surveyLink('https://x.org/s', 'classic')).toBe('https://x.org/s?look=classic')
  })
})
