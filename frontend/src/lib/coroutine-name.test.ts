import { describe, it, expect } from 'vitest'
import { splitCoroutineName, wrapPieces } from './coroutine-name'

describe('splitCoroutineName', () => {
  it('splits "Class.method" into the class and the method', () => {
    expect(splitCoroutineName('OrderService.processOrder')).toEqual({ owner: 'OrderService', name: 'processOrder' })
  })

  it('splits at the last dot', () => {
    expect(splitCoroutineName('dashboard.notifications.unread')).toEqual({
      owner: 'dashboard.notifications',
      name: 'unread',
    })
  })

  it.each(['fetch-price', 'worker', 'Step 1. Load data', 'v1.2', '.hidden', 'trailing.', ''])(
    'keeps %j as one name',
    label => {
      expect(splitCoroutineName(label)).toEqual({ owner: null, name: label })
    },
  )
})

describe('wrapPieces', () => {
  it('breaks at camelCase boundaries, never inside a word', () => {
    expect(wrapPieces('processOrderWithRetry')).toEqual(['process', 'Order', 'With', 'Retry'])
    expect(wrapPieces('HTTPServer2Go')).toEqual(['HTTP', 'Server2', 'Go'])
  })

  it('breaks after separators, which stay with the piece before them', () => {
    expect(wrapPieces('fetch-user_profile/v2')).toEqual(['fetch-', 'user_', 'profile/', 'v2'])
    expect(wrapPieces('com.example.OrderService')).toEqual(['com.', 'example.', 'Order', 'Service'])
  })

  it.each(['processOrder', 'fetch-1', 'a', 'UPPER', 'Outer$inner.run'])('joins back to %j', name => {
    expect(wrapPieces(name).join('')).toBe(name)
  })

  it('leaves a plain word whole', () => {
    expect(wrapPieces('worker')).toEqual(['worker'])
  })
})
