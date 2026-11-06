import { useMemo } from 'react'
import { useSessionEvents } from '@/hooks/use-sessions'
import { inSeqOrder } from '@/lib/projections/in-seq-order'
import type { VizEvent } from '@/types/api'
import { CHANNEL_EVENT_KINDS } from '@/types/api'
import type {
  ChannelCreated,
  ChannelSendStarted,
  ChannelSendCompleted,
  ChannelSendSuspended,
  ChannelReceiveStarted,
  ChannelReceiveCompleted,
  ChannelReceiveSuspended,
  ChannelClosed,
  ChannelBufferStateChanged,
  ChannelEvent,
} from '@/types/api'

/** Tracked state for a single channel. */
export interface ChannelState {
  channelId: string
  name: string | null
  channelType: string
  capacity: number
  currentSize: number
  isClosed: boolean
  closeCause: string | null
  /** Coroutine IDs that have sent to this channel. */
  producers: Set<string>
  /** Coroutine IDs that have received from this channel. */
  consumers: Set<string>
}

export interface UseChannelEventsResult {
  /** All channel events from the session. */
  channelEvents: ChannelEvent[]
  /** Unique channels discovered. */
  channels: ChannelState[]
  /** Events grouped by channelId. */
  eventsByChannel: Map<string, ChannelEvent[]>
  /** Current buffer states keyed by channelId. */
  bufferStates: Map<string, { currentSize: number; capacity: number }>
}

/**
 * Filters session events to only channel events,
 * groups by channelId, and tracks per-channel state.
 *
 * Reads `given` when passed — the events the page shows, so the facts follow
 * the live stream and the replay cursor — else the stored history, which live
 * updates never invalidate. It subscribes to that history either way (#124).
 */
export function useChannelEvents(sessionId: string, given?: readonly VizEvent[]): UseChannelEventsResult {
  const { data: stored } = useSessionEvents(sessionId)
  const events = given ?? stored

  return useMemo(() => {
    const channelEvents: ChannelEvent[] = []
    const eventsByChannel = new Map<string, ChannelEvent[]>()
    const channelStates = new Map<string, ChannelState>()
    const bufferStates = new Map<string, { currentSize: number; capacity: number }>()

    if (!events || events.length === 0) {
      return {
        channelEvents: [],
        channels: [],
        eventsByChannel: new Map(),
        bufferStates: new Map(),
      }
    }

    // In seq order: the live stream can deliver an earlier event late (inSeqOrder).
    for (const event of inSeqOrder(events)) {
      if (!CHANNEL_EVENT_KINDS.has(event.kind)) continue

      const channelEvent = event as unknown as ChannelEvent
      channelEvents.push(channelEvent)

      // Get the channelId — every channel event has one
      const channelId = getChannelId(channelEvent)
      if (!channelId) continue

      // Group by channel
      if (!eventsByChannel.has(channelId)) {
        eventsByChannel.set(channelId, [])
      }
      eventsByChannel.get(channelId)!.push(channelEvent)

      // Ensure state entry exists
      if (!channelStates.has(channelId)) {
        channelStates.set(channelId, {
          channelId,
          name: null,
          channelType: 'BUFFERED',
          capacity: 0,
          currentSize: 0,
          isClosed: false,
          closeCause: null,
          producers: new Set(),
          consumers: new Set(),
        })
      }

      const state = channelStates.get(channelId)!

      switch (channelEvent.kind) {
        case 'ChannelCreated': {
          const e = channelEvent as ChannelCreated
          state.name = e.name
          state.channelType = e.channelType
          state.capacity = e.capacity
          bufferStates.set(channelId, { currentSize: 0, capacity: e.capacity })
          break
        }
        case 'ChannelSendStarted': {
          const e = channelEvent as ChannelSendStarted
          state.producers.add(e.coroutineId)
          break
        }
        case 'ChannelSendCompleted': {
          const e = channelEvent as ChannelSendCompleted
          state.producers.add(e.coroutineId)
          break
        }
        case 'ChannelSendSuspended': {
          const e = channelEvent as ChannelSendSuspended
          state.producers.add(e.coroutineId)
          state.currentSize = e.bufferSize
          bufferStates.set(channelId, { currentSize: e.bufferSize, capacity: e.capacity })
          break
        }
        case 'ChannelReceiveStarted': {
          const e = channelEvent as ChannelReceiveStarted
          state.consumers.add(e.coroutineId)
          break
        }
        case 'ChannelReceiveCompleted': {
          const e = channelEvent as ChannelReceiveCompleted
          state.consumers.add(e.coroutineId)
          break
        }
        case 'ChannelReceiveSuspended': {
          const e = channelEvent as ChannelReceiveSuspended
          state.consumers.add(e.coroutineId)
          break
        }
        case 'ChannelClosed': {
          const e = channelEvent as ChannelClosed
          state.isClosed = true
          state.closeCause = e.cause
          break
        }
        case 'ChannelBufferStateChanged': {
          const e = channelEvent as ChannelBufferStateChanged
          state.currentSize = e.currentSize
          bufferStates.set(channelId, { currentSize: e.currentSize, capacity: e.capacity })
          break
        }
      }
    }

    const channels = Array.from(channelStates.values())

    return { channelEvents, channels, eventsByChannel, bufferStates }
  }, [events])
}

function getChannelId(event: ChannelEvent): string | null {
  if ('channelId' in event) {
    return (event as { channelId: string }).channelId
  }
  return null
}
