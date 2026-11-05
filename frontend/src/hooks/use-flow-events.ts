import { useMemo } from 'react'
import { useSessionEvents } from '@/hooks/use-sessions'
import { inSeqOrder } from '@/lib/projections/in-seq-order'
import type { VizEvent } from '@/types/api'
import { FLOW_EVENT_KINDS } from '@/types/api'
import type {
  FlowCreated,
  FlowValueEmitted,
  FlowOperatorApplied,
  FlowValueFiltered,
  FlowValueTransformed,
  FlowBackpressure,
  FlowBufferOverflow,
  SharedFlowEmission,
  SharedFlowSubscription,
  StateFlowValueChanged,
  FlowOperatorEvent,
} from '@/types/api'

/** Represents a single operator in a flow chain. */
export interface FlowOperator {
  operatorName: string
  operatorIndex: number
  flowId: string
  sourceFlowId: string
  label: string | null
}

/** Tracked state for a single flow. */
export interface FlowState {
  flowId: string
  flowType: string
  label: string | null
  coroutineId: string
  operators: FlowOperator[]
  emissions: FlowValueEmitted[]
  collections: { started: number; completed: number; cancelled: number }
  backpressureEvents: FlowBackpressure[]
  bufferOverflows: FlowBufferOverflow[]
  /** For SharedFlow: subscription tracking */
  subscriberCount: number
  sharedEmissions: SharedFlowEmission[]
  /** For StateFlow: value change history */
  stateFlowChanges: StateFlowValueChanged[]
  /** Value traces: input -> transformed -> filtered */
  valueTraces: Array<{
    sequenceNumber: number
    inputValue: string
    transformedValue: string | null
    filtered: boolean | null
    operatorName: string
  }>
}

export interface UseFlowEventsResult {
  flowEvents: FlowOperatorEvent[]
  flows: FlowState[]
  eventsByFlow: Map<string, FlowOperatorEvent[]>
  hasBackpressure: boolean
}

/**
 * Filters session events to flow events, groups by flowId,
 * and tracks operator chains, backpressure, and value traces.
 *
 * Reads `given` when passed — the events the page shows, so the facts follow
 * the live stream and the replay cursor — else the stored history, which live
 * updates never invalidate. It subscribes to that history either way (#124).
 */
export function useFlowEvents(sessionId: string, given?: readonly VizEvent[]): UseFlowEventsResult {
  const { data: stored } = useSessionEvents(sessionId)
  const events = given ?? stored

  return useMemo(() => {
    const flowEvents: FlowOperatorEvent[] = []
    const eventsByFlow = new Map<string, FlowOperatorEvent[]>()
    const flowStates = new Map<string, FlowState>()
    let hasBackpressure = false

    if (!events || events.length === 0) {
      return {
        flowEvents: [],
        flows: [],
        eventsByFlow: new Map(),
        hasBackpressure: false,
      }
    }

    // In seq order: the live stream can deliver an earlier event late (inSeqOrder).
    for (const event of inSeqOrder(events)) {
      if (!FLOW_EVENT_KINDS.has(event.kind)) continue

      const flowEvent = event as unknown as FlowOperatorEvent
      flowEvents.push(flowEvent)

      const flowId = getFlowId(flowEvent)
      if (!flowId) continue

      if (!eventsByFlow.has(flowId)) {
        eventsByFlow.set(flowId, [])
      }
      eventsByFlow.get(flowId)!.push(flowEvent)

      if (!flowStates.has(flowId)) {
        flowStates.set(flowId, {
          flowId,
          flowType: 'Cold',
          label: null,
          coroutineId: '',
          operators: [],
          emissions: [],
          collections: { started: 0, completed: 0, cancelled: 0 },
          backpressureEvents: [],
          bufferOverflows: [],
          subscriberCount: 0,
          sharedEmissions: [],
          stateFlowChanges: [],
          valueTraces: [],
        })
      }

      const state = flowStates.get(flowId)!

      switch (flowEvent.kind) {
        case 'FlowCreated': {
          const e = flowEvent as FlowCreated
          state.flowType = e.flowType
          state.label = e.label
          state.coroutineId = e.coroutineId
          break
        }
        case 'FlowValueEmitted': {
          const e = flowEvent as FlowValueEmitted
          state.emissions.push(e)
          break
        }
        case 'FlowCollectionStarted': {
          state.collections.started++
          break
        }
        case 'FlowCollectionCompleted': {
          state.collections.completed++
          break
        }
        case 'FlowCollectionCancelled': {
          state.collections.cancelled++
          break
        }
        case 'FlowOperatorApplied': {
          const e = flowEvent as FlowOperatorApplied
          state.operators.push({
            operatorName: e.operatorName,
            operatorIndex: e.operatorIndex,
            flowId: e.flowId,
            sourceFlowId: e.sourceFlowId,
            label: e.label,
          })
          break
        }
        case 'FlowValueFiltered': {
          const e = flowEvent as FlowValueFiltered
          state.valueTraces.push({
            sequenceNumber: e.sequenceNumber,
            inputValue: e.valuePreview,
            transformedValue: null,
            filtered: !e.passed,
            operatorName: e.operatorName,
          })
          break
        }
        case 'FlowValueTransformed': {
          const e = flowEvent as FlowValueTransformed
          state.valueTraces.push({
            sequenceNumber: e.sequenceNumber,
            inputValue: e.inputValuePreview,
            transformedValue: e.outputValuePreview,
            filtered: null,
            operatorName: e.operatorName,
          })
          break
        }
        case 'FlowBackpressure': {
          const e = flowEvent as FlowBackpressure
          state.backpressureEvents.push(e)
          hasBackpressure = true
          break
        }
        case 'FlowBufferOverflow': {
          const e = flowEvent as FlowBufferOverflow
          state.bufferOverflows.push(e)
          break
        }
        case 'SharedFlowEmission': {
          const e = flowEvent as SharedFlowEmission
          state.sharedEmissions.push(e)
          state.subscriberCount = e.subscriberCount
          break
        }
        case 'SharedFlowSubscription': {
          const e = flowEvent as SharedFlowSubscription
          state.subscriberCount = e.subscriberCount
          break
        }
        case 'StateFlowValueChanged': {
          const e = flowEvent as StateFlowValueChanged
          state.stateFlowChanges.push(e)
          state.subscriberCount = e.subscriberCount
          break
        }
      }
    }

    const flows = Array.from(flowStates.values())

    return { flowEvents, flows, eventsByFlow, hasBackpressure }
  }, [events])
}

function getFlowId(event: FlowOperatorEvent): string | null {
  if ('flowId' in event) {
    return (event as { flowId: string }).flowId
  }
  return null
}
